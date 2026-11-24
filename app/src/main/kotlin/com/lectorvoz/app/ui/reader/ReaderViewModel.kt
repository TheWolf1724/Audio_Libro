package com.lectorvoz.app.ui.reader

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lectorvoz.app.AppGraph
import com.lectorvoz.app.books.OpenedBook
import com.lectorvoz.app.books.SharedBooks
import com.lectorvoz.app.integration.AnchorText
import com.lectorvoz.app.integration.BookContent
import com.lectorvoz.app.integration.BookForVoice
import com.lectorvoz.app.integration.TextTools
import com.lectorvoz.app.integration.TimerChoice
import com.lectorvoz.app.integration.VoiceUiState
import com.lectorvoz.core.data.annotations.Annotation
import com.lectorvoz.core.data.annotations.AnnotationExport
import com.lectorvoz.core.data.bookmarks.Bookmark
import com.lectorvoz.core.data.library.Book
import com.lectorvoz.core.data.pronunciation.PronunciationEntry
import com.lectorvoz.core.data.settings.BookOverrides
import com.lectorvoz.core.data.settings.SettingsRanges
import com.lectorvoz.core.data.stats.SessionKind
import com.lectorvoz.core.model.BookFormat
import com.lectorvoz.core.model.HighlightKind
import com.lectorvoz.core.model.HighlightMode
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.LocatorRange
import com.lectorvoz.core.model.PlaybackEvent
import com.lectorvoz.core.model.ReaderSettings
import com.lectorvoz.core.model.TtsEngineType
import com.lectorvoz.core.model.VoiceSettings
import com.lectorvoz.reader.PageInfo
import com.lectorvoz.reader.render.MarkRange
import com.lectorvoz.reader.ReaderController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Accion pendiente tras una pulsacion larga sobre el texto. */
@Immutable
data class TextAction(val locator: Locator, val word: String?, val annotation: Annotation? = null)

/** Estado de la busqueda en el libro. */
@Immutable
data class SearchState(
    val query: String = "",
    val running: Boolean = false,
    val results: List<SearchHit> = emptyList(),
    val current: Int = -1,
    val done: Boolean = false,
)

/** Estado del lector (inmutable). */
@Immutable
/** El PDF esta protegido: se pide la contrasena ([wrong] = la anterior no valio). */
data class PasswordPrompt(val wrong: Boolean)

data class ReaderUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val passwordPrompt: PasswordPrompt? = null,
    /** Sube con cada salto explicito (deslizador, indice, busqueda, marcador): la pantalla ofrece "Volver". */
    val jumpOffer: Int = 0,
    /** Ritmo real de escucha del usuario (car/s) segun sus estadisticas, para estimar cuando la voz no suena. */
    val learnedCps: Float? = null,
    val book: Book? = null,
    val settings: ReaderSettings = ReaderSettings(),
    val voice: VoiceSettings = VoiceSettings(),
    val overrides: BookOverrides = BookOverrides.NONE,
    val pageInfo: PageInfo = PageInfo(),
    val locator: Locator = Locator.START,
    val toc: List<TocItem> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val annotations: List<Annotation> = emptyList(),
    /** Subrayados cuya cita ya no esta en el texto (cambio de OCR o de analizador): no se pintan. */
    val orphanAnnotations: Set<Long> = emptySet(),
    /** Subrayado huerfano que se esta reasignando: la proxima frase subrayada lo sustituye. */
    val reassigning: Long? = null,
    val playback: VoiceUiState = VoiceUiState(),
    val sentence: String? = null,
    val search: SearchState = SearchState(),
    val textAction: TextAction? = null,
    val nextInSeries: Book? = null,
    val message: String? = null,
    /** Lo que se prepara mientras se espera (paginas, salto, pagina actual). */
    val busy: com.lectorvoz.reader.ReaderBusy? = null,
    /** La pagina actual (PDF escaneado) se esta reconociendo. */
    val recognizingPage: Boolean = false,
) {
    /** La voz esta leyendo (o preparada para leer) este libro. */
    val voiceOnThisBook: Boolean get() = book != null && playback.bookId == book.id
    val isPlaying: Boolean get() = voiceOnThisBook && playback.playWhenReady
    val remaining: Remaining
        get() = remainingTime(pageInfo.chapterCharsRemaining, pageInfo.bookCharsRemaining, playback.charsPerSecond.takeIf { voiceOnThisBook }, voice.speed, learnedCps)
}

/**
 * Lector: conecta [ReaderController] (vista real), la voz ([com.lectorvoz.app.integration.PlaybackPort])
 * y los repositorios. El controlador vive aqui para sobrevivir a los giros de pantalla.
 *
 * Con la pantalla oculta ([setVisible]) no se resalta ni se pasa de pagina con la voz (no se
 * maqueta ni se dibuja para nadie); al volver se
 * sincroniza con la posicion de la voz. La posicion y las estadisticas de escucha las guarda
 * `ListeningTracker` (ambito de app); aqui solo la lectura visual.
 */
class ReaderViewModel(private val bookId: Long, private val graph: AppGraph) : ViewModel() {
    val controller = ReaderController(graph.context, graph.fonts)

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    /** Contenido que muestra el controlador (lo cierra el propio controlador). */
    private var readerContent: BookContent? = null
    private var aux: SharedBooks.Lease? = null
    private val auxMutex = Mutex()
    private var searchJob: Job? = null
    private val ranges = object : LinkedHashMap<Long, LocatorRange>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, LocatorRange>) = size > 64
    }

    // Visibilidad (pantalla en primer plano).
    private var visible = false
    private val renderActive: Boolean get() = visible

    // Estadisticas: tramo de lectura visual (la escucha la cuenta ListeningTracker).
    private var segmentStart = 0L
    private var segmentWall = 0L
    private var segmentChars = 0L
    private var segmentOpen = false

    /** Longitudes de capitulo para el deslizador de progreso (EPUB; se calculan al usarlo). */
    private val chapterLengths = HashMap<Int, Int>()
    private var lengthsJob: Job? = null

    /** Ajustes en vista previa (deslizadores): se aplican con retardo y sin guardar. */
    private val previewSettings = MutableStateFlow<ReaderSettings?>(null)

    private val untrim: () -> Unit

    init {
        controller.onCenterTapped = { toggleBars() }
        controller.onTextTapped = { loc -> onLongPress(loc) }
        untrim = graph.onTrimMemory { level -> controller.onTrimMemory(level) }
        open()
        observe()
    }

    private val _barsVisible = MutableStateFlow(true)
    val barsVisible: StateFlow<Boolean> = _barsVisible.asStateFlow()

    fun toggleBars() = _barsVisible.update { !it }
    fun showBars(show: Boolean) { _barsVisible.value = show }

    /** La voz esta leyendo este libro ahora mismo. */
    private fun voiceHere(): Boolean = graph.playback.state.value.bookId == bookId

    private fun open() = viewModelScope.launch {
        val book = graph.library.get(bookId)
        if (book == null) {
            _state.update { it.copy(loading = false, error = "El libro ya no esta en la biblioteca.") }
            return@launch
        }
        _state.update { it.copy(book = book) }
        graph.library.markOpened(bookId)
        // Si la voz lee este libro, se abre donde va la voz, no en la ultima pagina guardada (M1).
        val voicePos = graph.playback.state.value.takeIf { it.bookId == bookId }?.position
        val start = voicePos ?: book.locator
        try {
            val voice = graph.settings.voiceFor(bookId).first()
            val opened = graph.opener.open(book, voice.skipPdfHeadersFooters)
            readerContent = opened.content
            (opened.content as? BookContent.Pdf)?.cleaner?.prefetch(start.chapter)
            when (val c = opened.content) {
                is BookContent.Epub -> controller.openEpub(c.book, start)
                is BookContent.Pdf -> controller.openPdf(c.book, start, c.cleaner)
            }
            _state.update { it.copy(loading = false, toc = flattenToc(opened.toc)) }
            if (voicePos != null) highlightVoicePosition()
        } catch (e: CancellationException) {
            throw e
        } catch (e: com.lectorvoz.format.pdf.PdfException.PasswordRequired) {
            _state.update { it.copy(loading = false, passwordPrompt = PasswordPrompt(wrong = false)) }
        } catch (e: com.lectorvoz.format.pdf.PdfException.WrongPassword) {
            graph.passwords.forget(bookId)
            _state.update { it.copy(loading = false, passwordPrompt = PasswordPrompt(wrong = true)) }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = "No se puede abrir el libro: ${e.message ?: "error desconocido"}") }
        }
        _state.update { it.copy(nextInSeries = graph.collections.nextInSeries(bookId)) }
    }

    /** Prueba [password] en el PDF protegido y vuelve a abrirlo (la clave solo vive en memoria). */
    fun submitPassword(password: String) {
        graph.passwords.set(bookId, password)
        _state.update { it.copy(loading = true, passwordPrompt = null) }
        open()
    }

    /** Se descarta el dialogo de contrasena: el libro no se abre. */
    fun cancelPassword() {
        _state.update { it.copy(passwordPrompt = null, error = "Este PDF esta protegido con contrasena.") }
    }

    /** Resalta la frase que esta leyendo la voz y va a su pagina. */
    private suspend fun highlightVoicePosition() {
        val p = graph.playback.state.value
        if (p.bookId != bookId) return
        val pos = p.position ?: return
        val range = try {
            graph.playback.currentSource?.rangeAt(pos)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (range != null && _state.value.voice.highlightMode != HighlightMode.NONE) controller.highlight(range, HighlightKind.SENTENCE)
        controller.goTo(range?.start ?: pos)
    }

    @OptIn(FlowPreview::class)
    private fun observe() {
        viewModelScope.launch {
            graph.settings.readerFor(bookId).collect { s ->
                previewSettings.value = null
                controller.setSettings(s)
                _state.update { it.copy(settings = s) }
            }
        }
        viewModelScope.launch {
            previewSettings.debounce(PREVIEW_DEBOUNCE_MS).collect { s -> if (s != null) controller.setSettings(s) }
        }
        viewModelScope.launch { graph.settings.voiceFor(bookId).collect { v -> _state.update { it.copy(voice = v) } } }
        viewModelScope.launch {
            graph.stats.summary.map { s -> s.listenCharsPerMinute?.let { it / 60f } }.distinctUntilChanged()
                .collect { cps -> _state.update { it.copy(learnedCps = cps) } }
        }
        viewModelScope.launch { graph.settings.overrides(bookId).collect { o -> _state.update { it.copy(overrides = o) } } }
        viewModelScope.launch { graph.settings.forceOcr(bookId).collect { applyForceOcr(it) } }
        viewModelScope.launch { graph.bookmarks.observe(bookId).collect { b -> _state.update { it.copy(bookmarks = b) } } }
        viewModelScope.launch {
            graph.annotations.observe(bookId).collectLatest { list ->
                // Lo ya comprobado se aplica al instante (sin pintar la posicion cruda); solo se comprueba lo nuevo.
                resolved.keys.retainAll(list.mapTo(HashSet()) { it.id })
                applyResolved(list)
                val pending = list.filter { a -> resolved[a.id]?.let { sameAnchor(it.raw, a) } != true }
                if (pending.isNotEmpty()) {
                    val judged = resolveAnchors(pending)
                    resolved.putAll(judged)
                    applyResolved(list)
                    // La reubicacion se guarda: no se repite en cada apertura.
                    withContext(NonCancellable) {
                        for (r in judged.values) {
                            // Una cita truncada (MAX_QUOTE) daria un tramo acortado: no se persiste.
                            if (r.orphan || r.raw.quote.length >= 1000) continue
                            if (r.fixed.start == r.raw.start && r.fixed.end == r.raw.end) continue
                            // Solo si la fila sigue como se comprobo (el usuario pudo editarla entretanto).
                            val cur = graph.annotations.observe(bookId).first().firstOrNull { it.id == r.raw.id } ?: continue
                            if (sameAnchor(cur, r.raw)) graph.annotations.update(cur, cur.color, cur.note, r.fixed.start, r.fixed.end, cur.quote)
                        }
                    }
                }
            }
        }
        viewModelScope.launch { controller.pageInfo.collect { p -> _state.update { it.copy(pageInfo = p) } } }
        viewModelScope.launch { controller.busy.collect { b -> _state.update { it.copy(busy = b) } } }
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(graph.ocr.recognizing, controller.currentLocator) { set, l -> graph.ocr.isRecognizing(set, bookId, l.chapter) }
                .collect { r -> _state.update { it.copy(recognizingPage = r && it.book?.format == com.lectorvoz.core.model.BookFormat.PDF) } }
        }
        viewModelScope.launch { controller.currentSentence.collect { s -> if (renderActive || !voiceHere()) _state.update { it.copy(sentence = s) } } }
        viewModelScope.launch {
            controller.currentLocator.collect { l -> _state.update { it.copy(locator = l) } }
        }
        viewModelScope.launch {
            // Aparte y cancelable: pasar paginas deprisa no encola una carga por pagina.
            controller.currentLocator.collectLatest { l -> (readerContent as? BookContent.Pdf)?.cleaner?.prefetch(l.chapter) }
        }
        viewModelScope.launch { controller.currentLocator.debounce(1500).collect { saveProgress() } }
        viewModelScope.launch {
            graph.playback.state.collect { p ->
                val was = _state.value.isPlaying
                _state.update { it.copy(playback = p) }
                val now = _state.value.isPlaying
                if (was != now) switchSegment()
                if (p.bookId == bookId && p.ended) _state.update { it.copy(nextInSeries = graph.collections.nextInSeries(bookId)) }
            }
        }
        viewModelScope.launch { graph.playback.events.collect { onPlaybackEvent(it) } }
    }

    private fun onPlaybackEvent(e: PlaybackEvent) {
        if (!voiceHere()) return
        val mode = _state.value.voice.highlightMode
        when (e) {
            is PlaybackEvent.UtteranceStarted -> {
                ranges[e.id] = e.range
                // Sin pintar (pantalla oculta) no se hace nada: al volver se sincroniza con la voz.
                if (!renderActive) return
                controller.clearHighlight(HighlightKind.WORD)
                if (e.range.isEmpty) return
                if (mode != HighlightMode.NONE) controller.highlight(e.range, HighlightKind.SENTENCE) else controller.clearHighlight(HighlightKind.SENTENCE)
                if (_state.value.voice.followText) controller.ensureVisible(e.range.start)
            }
            is PlaybackEvent.WordRange -> {
                if (mode != HighlightMode.WORD || !renderActive) return
                val r = ranges[e.id] ?: return
                if (r.isEmpty || r.start.chapter != r.endExclusive.chapter) return
                val s = (r.start.offset + e.displayStart).coerceAtMost(r.endExclusive.offset)
                val end = (r.start.offset + e.displayEnd).coerceIn(s, r.endExclusive.offset)
                if (end > s) controller.highlight(LocatorRange(Locator(r.start.chapter, s), Locator(r.start.chapter, end)), HighlightKind.WORD)
            }
            is PlaybackEvent.Error -> _state.update { it.copy(message = e.message) }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ voz

    /** Empieza a escuchar desde [from] (o la pagina actual). Solo desde un toque del usuario. */
    fun listen(from: Locator? = null) = viewModelScope.launch {
        val book = _state.value.book ?: return@launch
        val p = graph.playback
        if (from == null && p.state.value.bookId == bookId) {
            p.togglePlayPause()
            return@launch
        }
        val start = from ?: controller.currentLocator.value
        if (p.state.value.bookId == bookId) {
            p.seekTo(start)
            if (!p.state.value.playWhenReady) p.play()
            return@launch
        }
        try {
            val (opened, provider) = graph.speech.providerFor(book)
            p.updateSettings(_state.value.voice)
            p.start(BookForVoice(book.id, book.title, book.author, book.coverPath, opened.chapterTitles), provider, start, autoPlay = true)
            _state.value.voice.sleepTimerMinutes?.let { p.setSleepTimer(TimerChoice.Minutes(it)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(message = "No se pudo iniciar la voz: ${e.message}") }
        }
    }

    fun togglePlay() {
        if (_state.value.voiceOnThisBook) graph.playback.togglePlayPause() else listen()
    }
    fun previousSentence() = graph.playback.previousSentence()
    fun nextSentence() = graph.playback.nextSentence()
    fun previousChapter() = graph.playback.previousChapter()
    fun nextChapter() = graph.playback.nextChapter()
    fun setTimer(choice: TimerChoice?) = graph.playback.setSleepTimer(choice)

    /** Velocidad a un toque: si el libro tiene la suya, se cambia la del libro. */
    fun setSpeed(speed: Float) = viewModelScope.launch {
        if (_state.value.overrides.speed != null) graph.settings.updateOverrides(bookId) { it.copy(speed = speed) }
        else graph.settings.updateVoice { it.copy(speed = speed) }
    }

    // ------------------------------------------------------------------ navegacion

    fun goTo(locator: Locator) = navigateTo(locator)

    /**
     * Mueve el lector a [loc] y, si la voz lee este libro, reposiciona tambien el audio. Sin
     * esto el deslizador, el indice, la busqueda y los marcadores solo moverian la vista y,
     * con "seguir el texto" activo, la voz la arrastraria de vuelta a la frase en curso en
     * cuestion de segundos (el deslizador parecia roto durante la escucha).
     */
    private fun navigateTo(loc: Locator) {
        // Salto explicito: se recuerda de donde se venia para poder volver (snackbar "Volver").
        val from = controller.currentLocator.value
        if (isFarJump(from, loc)) {
            synchronized(jumpStack) {
                if (jumpStack.lastOrNull() != from) jumpStack.addLast(from)
                while (jumpStack.size > MAX_JUMP_STACK) jumpStack.removeFirst()
            }
            _state.update { it.copy(jumpOffer = it.jumpOffer + 1) }
        }
        controller.goTo(loc)
        if (_state.value.voiceOnThisBook) graph.playback.seekTo(loc)
    }

    /** Posiciones de las que se salto (la ultima arriba): "volver a donde estaba". */
    private val jumpStack = ArrayDeque<Locator>()

    /** Vuelve a la posicion de la que se salto por ultima vez (sin registrar un salto nuevo). */
    fun jumpBack() {
        val to = synchronized(jumpStack) { jumpStack.removeLastOrNull() } ?: return
        controller.goTo(to)
        if (_state.value.voiceOnThisBook) graph.playback.seekTo(to)
    }

    /** Empieza a medir los capitulos (al tocar el deslizador de progreso). */
    fun prepareSeek() {
        if (lengthsJob != null || _state.value.book?.format != BookFormat.EPUB) return
        lengthsJob = viewModelScope.launch {
            val b = auxBook() ?: return@launch
            for (ch in 0 until b.content.chapterCount) {
                if (synchronized(chapterLengths) { ch in chapterLengths }) continue
                val len = chapterText(ch)?.length ?: continue
                synchronized(chapterLengths) { chapterLengths[ch] = len }
            }
        }
    }

    /** Salta a la fraccion [f] del libro (deslizador), por caracteres en EPUB (M12). */
    fun seekFraction(f: Float) {
        val count = controller.chapterCount.takeIf { it > 0 } ?: return
        val known = synchronized(chapterLengths) { HashMap(chapterLengths) }
        navigateTo(locatorForFraction(f, count) { known[it] })
    }

    /** Teclas de volumen; true si se consumio. Con la voz sonando cambian el volumen (B8). */
    fun volumeKey(up: Boolean): Boolean {
        if (!_state.value.settings.volumeKeysTurnPages) return false
        if (_state.value.isPlaying) return false
        if (up) controller.prevPage() else controller.nextPage()
        return true
    }

    // ------------------------------------------------------------------ marcadores

    fun addBookmark() = viewModelScope.launch {
        val loc = if (_state.value.voiceOnThisBook) graph.playback.state.value.position ?: controller.currentLocator.value else controller.currentLocator.value
        val text = _state.value.sentence?.takeIf { _state.value.voiceOnThisBook } ?: excerptAt(loc)
        saveBookmark(loc, text)
    }

    /** Marcador en la posicion pulsada (pulsacion larga sobre texto): cae donde toco el usuario,
     *  no en la posicion de lectura o de la voz. */
    fun addBookmarkAt(loc: Locator) = viewModelScope.launch { saveBookmark(loc, excerptAt(loc)) }

    private suspend fun saveBookmark(loc: Locator, text: String?) {
        graph.bookmarks.add(bookId, loc, text ?: "Pagina ${_state.value.pageInfo.pageInChapter + 1}")
        _state.update { it.copy(message = "Marcador anadido") }
    }

    // ------------------------------------------------------------------ subrayados y notas

    /** Resultado de comprobar un subrayado ([raw] tal como esta guardado). */
    private class Resolved(val raw: Annotation, val fixed: Annotation, val orphan: Boolean)

    private val resolved = HashMap<Long, Resolved>()

    private fun sameAnchor(a: Annotation, b: Annotation) =
        a.chapter == b.chapter && a.start == b.start && a.end == b.end && a.quote == b.quote

    private fun applyResolved(list: List<Annotation>) {
        val orphans = HashSet<Long>()
        val view = list.map { a ->
            val r = resolved[a.id]?.takeIf { sameAnchor(it.raw, a) }
            if (r?.orphan == true) orphans += a.id
            if (r == null) a else r.fixed.copy(color = a.color, note = a.note)
        }.sortedWith(compareBy({ it.chapter }, { it.start }))
        _state.update { it.copy(annotations = view, orphanAnnotations = orphans) }
        pushMarks(view, orphans)
    }

    private fun pushMarks(list: List<Annotation>, orphans: Set<Long>) {
        controller.setMarks(
            list.filter { it.id !in orphans }
                .map { MarkRange(LocatorRange(Locator(it.chapter, it.start), Locator(it.chapter, it.end)), it.color) },
        )
    }

    /** Reubica cada subrayado sobre su cita en el texto actual; los que ya no aparecen quedan como huerfanos. */
    private suspend fun resolveAnchors(list: List<Annotation>): Map<Long, Resolved> {
        val out = HashMap<Long, Resolved>()
        for ((chapter, items) in list.groupBy { it.chapter }) {
            val text = chapterText(chapter)
            // Sin texto, o provisional (OCR sin terminar): no se juzga, se deja como esta.
            if (text == null || auxBook()?.content?.provisional(chapter) == true) continue
            val anchors by lazy(LazyThreadSafetyMode.NONE) { AnchorText(text) }
            for (a in items) {
                val r = locateFast(text, a) ?: anchors.locate(a.quote, a.start, a.end, text)
                out[a.id] = when {
                    r == null -> Resolved(a, a, true)
                    r.first == a.start && r.last + 1 == a.end -> Resolved(a, a, false)
                    else -> Resolved(a, a.copy(start = r.first, end = r.last + 1), false)
                }
            }
        }
        return out
    }

    /** Ruta rapida: el tramo guardado sigue sobre su cita (sin construir el texto normalizado del capitulo entero). */
    private fun locateFast(text: String, a: Annotation): IntRange? {
        if (a.start !in 0 until a.end || a.end > text.length) return null
        return if (a.quote.isNotEmpty() && AnchorText.matches(text.substring(a.start, a.end), a.quote)) a.start until a.end else null
    }

    /** Amplia el subrayado [a] con la frase anterior o la siguiente (mas control que un subrayado de frase fija). */
    fun extendAnnotation(a: Annotation, forward: Boolean) = viewModelScope.launch {
        dismissTextAction()
        val live = _state.value.annotations.firstOrNull { it.id == a.id } ?: return@launch
        val text = chapterText(live.chapter) ?: return@launch
        var s = live.start.coerceIn(0, text.length)
        var e = live.end.coerceIn(s, text.length)
        if (forward) {
            var p = e
            while (p < text.length && text[p].isWhitespace()) p++
            val r = if (p < text.length) TextTools.sentenceBounds(text, p) else null
            if (r != null) e = r.last + 1
        } else {
            var p = s - 1
            while (p >= 0 && (text[p].isWhitespace() || text[p] in "\"')\u00BB\u201D")) p--
            val r = if (p >= 0) TextTools.sentenceBounds(text, p) else null
            if (r != null) s = r.first
        }
        if (s == live.start && e == live.end) {
            _state.update { it.copy(message = "No hay mas texto que anadir") }
            return@launch
        }
        val quote = AnchorText(text.substring(s, e)).norm
        graph.annotations.update(live, live.color, live.note, s, e, quote)
    }

    /** Guarda los subrayados y notas en el fichero [uri] (Markdown). */
    fun saveAnnotationsTo(uri: android.net.Uri, items: List<Annotation> = _state.value.annotations) = viewModelScope.launch {
        val md = annotationsMarkdown(items)
        val ok = withContext(Dispatchers.IO) {
            try {
                graph.context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(md.toByteArray(Charsets.UTF_8)) } != null
            } catch (e: Exception) {
                false
            }
        }
        _state.update { it.copy(message = if (ok) "Notas guardadas" else "No se pudo guardar el fichero") }
    }

    /** Subraya en [color] la frase que contiene [loc] (pulsacion larga). */
    fun highlightSentenceAt(loc: Locator, color: Int) = viewModelScope.launch {
        dismissTextAction()
        val text = chapterText(loc.chapter)
        val r = text?.let { TextTools.sentenceBounds(it, loc.offset) }
        if (text == null || r == null) {
            _state.update { it.copy(message = "No se pudo subrayar aqui") }
            return@launch
        }
        if (auxBook()?.content?.provisional(loc.chapter) == true) {
            // El texto de esta pagina aun cambiara cuando acabe el reconocimiento: el subrayado quedaria desplazado.
            _state.update { it.copy(message = "Espera a que termine de reconocerse el texto de esta pagina para subrayar") }
            return@launch
        }
        val target = _state.value.reassigning?.let { id -> _state.value.annotations.firstOrNull { it.id == id } }
        if (target != null) {
            val q = AnchorText(text.substring(r.first, r.last + 1)).norm
            graph.annotations.update(target, color, target.note, r.first, r.last + 1, q, loc.chapter)
            _state.update { it.copy(reassigning = null, message = "Subrayado reasignado") }
            return@launch
        }
        // Si ya hay un subrayado casi igual (mismo tramo con un caracter de mas o de menos) se recolorea.
        val dup = _state.value.annotations.firstOrNull {
            it.chapter == loc.chapter && minOf(it.end, r.last + 1) - maxOf(it.start, r.first) > (r.last + 1 - r.first) / 2
        }
        if (dup != null) {
            graph.annotations.update(dup, color, dup.note)
        } else {
            val quote = AnchorText(text.substring(r.first, r.last + 1)).norm
            graph.annotations.add(bookId, loc.chapter, r.first, r.last + 1, color, quote)
        }
        _state.update { it.copy(message = "Subrayado") }
    }

    fun updateAnnotation(a: Annotation, color: Int, note: String?) = viewModelScope.launch {
        dismissTextAction()
        // La copia del dialogo puede estar desfasada: se actua sobre la version actual (si sigue existiendo).
        val live = _state.value.annotations.firstOrNull { it.id == a.id } ?: return@launch
        graph.annotations.update(live, color, note)
    }

    /** Empieza (o cancela, si [a] es nulo) la reasignacion de un subrayado huerfano. */
    fun reassignAnnotation(a: Annotation?) {
        _state.update {
            it.copy(
                reassigning = a?.id,
                message = if (a != null) "Manten pulsada la frase donde va este subrayado (en el capitulo ${a.chapter + 1} o en otro)" else it.message,
            )
        }
    }

    fun deleteAnnotation(a: Annotation) = viewModelScope.launch {
        dismissTextAction()
        if (_state.value.reassigning == a.id) _state.update { it.copy(reassigning = null) }
        graph.annotations.delete(a.id)
    }

    /** Subrayados y notas del libro en Markdown (para compartir). */
    fun annotationsMarkdown(items: List<Annotation> = _state.value.annotations): String {
        val st = _state.value
        return AnnotationExport.markdown(st.book?.title ?: "Libro", items) { ch -> chapterTitleOf(st.toc, ch) }
    }

    private fun chapterTitleOf(toc: List<TocItem>, chapter: Int): String? =
        (toc.lastOrNull { it.locator.chapter == chapter } ?: toc.lastOrNull { it.locator.chapter <= chapter })?.title

    fun deleteBookmark(b: Bookmark) = viewModelScope.launch { graph.bookmarks.delete(b.id) }

    // ------------------------------------------------------------------ texto auxiliar (busqueda, palabras)

    /** Instancia compartida del libro (la misma que usa la voz si lee este libro, M6). */
    private suspend fun auxBook(): OpenedBook? = auxMutex.withLock {
        aux?.let { return it.opened }
        val book = _state.value.book ?: return null
        try {
            graph.books.acquire(book, _state.value.voice.skipPdfHeadersFooters).also { aux = it }.opened
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun chapterText(ch: Int): String? {
        val b = auxBook() ?: return null
        return try {
            b.content.plainText(ch)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun excerptAt(loc: Locator): String? {
        val t = chapterText(loc.chapter) ?: return null
        var s = loc.offset.coerceIn(0, t.length)
        // Empieza en una palabra entera (el desplazamiento puede caer a mitad de una).
        while (s > 0 && s < t.length && t[s - 1].isLetterOrDigit() && t[s].isLetterOrDigit()) s--
        return t.substring(s, (s + 160).coerceAtMost(t.length)).replace('\uFFFC', ' ').replace(Regex("\\s+"), " ").trim().ifEmpty { null }
    }

    // ------------------------------------------------------------------ busqueda

    fun search(query: String) {
        searchJob?.cancel()
        controller.setSearchResults(emptyList(), null)
        if (query.isBlank()) {
            _state.update { it.copy(search = SearchState()) }
            return
        }
        _state.update { it.copy(search = SearchState(query = query, running = true)) }
        searchJob = viewModelScope.launch {
            val b = auxBook() ?: run {
                _state.update { it.copy(search = it.search.copy(running = false, done = true)) }
                return@launch
            }
            val hits = ArrayList<SearchHit>()
            for (ch in 0 until b.content.chapterCount) {
                val text = chapterText(ch) ?: continue
                if (b.content.format == BookFormat.EPUB) synchronized(chapterLengths) { chapterLengths[ch] = text.length }
                val found = withContext(Dispatchers.Default) {
                    findMatches(text, query, MAX_RESULTS - hits.size).map { m ->
                        SearchHit(LocatorRange(Locator(ch, m.start), Locator(ch, m.end)), excerpt(text, m.start, m.end))
                    }
                }
                hits += found
                if (found.isNotEmpty()) _state.update { it.copy(search = it.search.copy(results = hits.toList())) }
                if (hits.size >= MAX_RESULTS) break
            }
            _state.update { it.copy(search = it.search.copy(running = false, done = true, results = hits.toList())) }
            controller.setSearchResults(hits.map { it.range }, null)
        }
    }

    fun openSearchResult(index: Int) {
        val s = _state.value.search
        val hit = s.results.getOrNull(index) ?: return
        _state.update { it.copy(search = s.copy(current = index)) }
        controller.setSearchResults(s.results.map { it.range }, hit.range)
        navigateTo(hit.range.start)
    }

    fun clearSearch() {
        searchJob?.cancel()
        controller.setSearchResults(emptyList(), null)
        _state.update { it.copy(search = SearchState()) }
    }

    // ------------------------------------------------------------------ pulsacion larga

    private fun onLongPress(loc: Locator) = viewModelScope.launch {
        val text = chapterText(loc.chapter)
        val word = text?.let { TextTools.wordAt(it, loc.offset) }
        val existing = _state.value.annotations.firstOrNull { it.chapter == loc.chapter && loc.offset >= it.start && loc.offset < it.end }
        _state.update { it.copy(textAction = TextAction(loc, word, existing)) }
    }

    fun dismissTextAction() = _state.update { it.copy(textAction = null) }

    fun readFromHere(loc: Locator) {
        dismissTextAction()
        listen(loc)
    }

    /** Guarda una correccion de pronunciacion (del libro o global). */
    fun savePronunciation(word: String, replacement: String, forBook: Boolean) = viewModelScope.launch {
        if (word.isBlank() || replacement.isBlank()) return@launch
        graph.pronunciation.save(PronunciationEntry(pattern = word.trim(), replacement = replacement.trim(), bookId = if (forBook) bookId else null))
        _state.update { it.copy(textAction = null, message = "Pronunciacion guardada") }
    }

    /** Prueba como sonaria [replacement] (desde un toque; no interrumpe el libro, A4). */
    fun previewPronunciation(replacement: String) = viewModelScope.launch {
        if (replacement.isBlank()) return@launch
        graph.playback.preview(_state.value.voice, replacement)?.let { err -> _state.update { it.copy(message = err) } }
    }

    // ------------------------------------------------------------------ apariencia

    fun updateReader(transform: (ReaderSettings) -> ReaderSettings) = viewModelScope.launch { graph.settings.updateReader(transform) }

    /**
     * Vista previa mientras se arrastra un deslizador del panel: se aplica al lector con un
     * pequeno retardo (no repagina a cada paso) y no se escribe en disco hasta soltar.
     */
    fun previewReader(transform: (ReaderSettings) -> ReaderSettings) {
        previewSettings.value = SettingsRanges.sanitize(transform(previewSettings.value ?: _state.value.settings))
    }

    fun updateOverrides(transform: (BookOverrides) -> BookOverrides) = viewModelScope.launch { graph.settings.updateOverrides(bookId, transform) }

    /** Valor de "forzar OCR" ya aplicado a las instancias abiertas (null = aun ninguno). */
    private var forceOcrApplied: Boolean? = null

    /**
     * Aplica "forzar OCR" a las instancias abiertas del PDF: la del lector y la compartida
     * (voz, busqueda, exportacion). La apertura ya fija el valor, asi que la primera llamada
     * solo lo recuerda; un cambio posterior limpia las caches de texto y, si la voz suena,
     * re-lee desde el inicio de la pagina actual (los offsets no son estables entre pdfium y OCR).
     *
     * El flag debe llegar SIEMPRE a la instancia compartida (A1): si la voz lee este libro se
     * adquiere (abriendola si hace falta); si no, se actualiza solo la que ya exista (p. ej. de
     * una busqueda) sin forzar su apertura. El reuso en `SharedBooks.acquire` reaplica la
     * preferencia, asi que la voz al empezar y las busquedas posteriores quedan coherentes.
     */
    private suspend fun applyForceOcr(force: Boolean) {
        val first = forceOcrApplied == null
        if (forceOcrApplied == force) return
        forceOcrApplied = force
        (readerContent as? BookContent.Pdf)?.let { it.book.forceFallback = force; it.cleaner.reset() }
        val shared = if (voiceHere()) auxBook() else auxMutex.withLock { aux?.opened }
        (shared?.content as? BookContent.Pdf)?.let { it.book.forceFallback = force; it.cleaner.reset() }
        if (first) return
        // El texto de las paginas ha cambiado: repaginar lo visible y re-leer la voz.
        (readerContent as? BookContent.Pdf)?.cleaner?.prefetch(controller.currentLocator.value.chapter)
        // La voz trocea en su propio proveedor (cache LRU por pagina): hay que vaciarla o
        // seguiria leyendo el texto anterior de las paginas ya cacheadas.
        graph.speech.invalidateText(bookId)
        if (voiceHere()) {
            // Reanclar al INICIO de la pagina actual: el offset de la posicion es un indice de
            // caracter del texto pdfium anterior y no corresponde al texto OCR nuevo (de otra
            // longitud); reanclar al offset crudo podria caer en otra frase de la pagina y
            // repetir o saltar una (S2-4).
            val page = (graph.playback.state.value.position ?: controller.currentLocator.value).chapter
            graph.playback.seekTo(Locator(page, 0))
        }
        _state.update { it.copy(message = if (force) "Este PDF se leera con OCR" else "Este PDF se leera con su texto") }
    }

    fun updateVoice(transform: (VoiceSettings) -> VoiceSettings) = viewModelScope.launch { graph.settings.updateVoice(transform) }

    /** Activa o quita la voz y velocidad propias de este libro (parten de las actuales). */
    fun toggleBookVoice() = viewModelScope.launch {
        val o = _state.value.overrides
        val v = _state.value.voice
        if (o.voiceId != null || o.speed != null) {
            graph.settings.updateOverrides(bookId) { it.copy(voiceId = null, speed = null) }
            _state.update { it.copy(message = "Este libro usa la voz general") }
        } else {
            // Con la voz del sistema y sin voz elegida no se guarda la neuronal por defecto (B5).
            val id = v.voiceId ?: if (v.engine == TtsEngineType.NEURAL) graph.voices.defaultVoiceId else null
            graph.settings.updateOverrides(bookId) { it.copy(voiceId = id, speed = v.speed) }
            _state.update { it.copy(message = "Voz y velocidad guardadas para este libro") }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    // ------------------------------------------------------------------ progreso y estadisticas

    /**
     * Guarda la pagina del lector. Mientras la voz lee este libro manda la posicion de la
     * voz (la guarda `ListeningTracker`): no se pisa con el inicio de la pagina visible.
     */
    private suspend fun saveProgress() {
        val book = _state.value.book ?: return
        if (_state.value.loading || _state.value.error != null) return
        if (voiceHere() && graph.playback.state.value.playWhenReady) return
        val info = controller.pageInfo.value
        val loc = controller.currentLocator.value
        val progress = info.bookProgress.takeIf { it > 0f } ?: info.progress
        val finished = if (progress >= 0.995f && info.chapter >= info.chapterCount - 1) true else null
        graph.library.updateProgress(book.id, loc, progress, finished)
    }

    /** La pantalla esta visible (lectura visual) o no. */
    fun setVisible(v: Boolean) {
        if (visible == v) return
        val wasActive = renderActive
        visible = v
        graph.playback.setReaderVisible(v)
        graph.ocr.setReaderBook(if (v) bookId else null)
        switchSegment()
        if (!v) graph.appScope.launch { saveProgress() }
        if (!wasActive && renderActive) viewModelScope.launch { highlightVoicePosition() }
    }

    private fun switchSegment() {
        val reading = renderActive && !_state.value.isPlaying
        if (reading == segmentOpen) return
        if (!reading) {
            closeSegment()
        } else {
            segmentOpen = true
            segmentStart = SystemClock.elapsedRealtime()
            segmentWall = System.currentTimeMillis()
            segmentChars = controller.pageInfo.value.bookCharsRemaining
        }
    }

    private fun closeSegment() {
        if (!segmentOpen) return
        segmentOpen = false
        val dur = SystemClock.elapsedRealtime() - segmentStart
        val chars = (segmentChars - controller.pageInfo.value.bookCharsRemaining).coerceAtLeast(0)
        val started = segmentWall
        graph.appScope.launch { graph.stats.record(bookId, SessionKind.READ, started, dur, chars) }
    }

    override fun onCleared() {
        graph.playback.setReaderVisible(false)
        graph.ocr.setReaderBook(null)
        closeSegment()
        untrim()
        val info = controller.pageInfo.value
        val loc = controller.currentLocator.value
        val book = _state.value.book
        val voicePlaying = voiceHere() && graph.playback.state.value.playWhenReady
        if (book != null && !_state.value.loading && _state.value.error == null && !voicePlaying) {
            val progress = info.bookProgress.takeIf { it > 0f } ?: info.progress
            graph.appScope.launch { graph.library.updateProgress(book.id, loc, progress) }
        }
        controller.close()
        aux?.release()
        aux = null
    }

    companion object {
        /** Saltos que se recuerdan para "Volver". */
        private const val MAX_JUMP_STACK = 8

        const val MAX_RESULTS = 300

        /** Retardo de la vista previa de los deslizadores de apariencia. */
        const val PREVIEW_DEBOUNCE_MS = 250L

        /** Formato legible. */
        fun formatOf(b: Book?): String = if (b?.format == BookFormat.PDF) "PDF" else "EPUB"
    }
}
