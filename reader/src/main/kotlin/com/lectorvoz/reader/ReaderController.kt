package com.lectorvoz.reader

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lectorvoz.core.model.DocumentSource
import com.lectorvoz.core.model.HighlightKind
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.LocatorRange
import com.lectorvoz.core.model.ReaderSettings
import com.lectorvoz.core.model.ReadingMode
import com.lectorvoz.format.pdf.PageTextCleaner
import com.lectorvoz.format.pdf.PdfBook
import com.lectorvoz.reader.cache.BitmapPool
import com.lectorvoz.reader.cache.DeferredReleaser
import com.lectorvoz.reader.cache.PageBitmapCache
import com.lectorvoz.reader.content.EpubContent
import com.lectorvoz.reader.content.PagedContent
import com.lectorvoz.reader.content.PdfContent
import com.lectorvoz.reader.content.RenderConfig
import com.lectorvoz.reader.layout.LayoutSettings
import com.lectorvoz.reader.layout.PageChrome
import com.lectorvoz.reader.layout.Viewport
import com.lectorvoz.reader.render.HighlightLayer
import com.lectorvoz.reader.render.Highlights
import com.lectorvoz.reader.view.PageFlipView
import com.lectorvoz.reader.view.PageHost
import com.lectorvoz.reader.view.PageKey
import com.lectorvoz.reader.view.PageProvider
import com.lectorvoz.reader.view.PageViewListener
import com.lectorvoz.reader.view.ScrollReaderView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Estado de la pagina mostrada y del progreso (barra de progreso, modo solo escuchar). */
data class PageInfo(
    val chapter: Int = 0,
    /** Pagina dentro del capitulo (0..). */
    val pageInChapter: Int = 0,
    val chapterPages: Int = 1,
    /** Pagina global (0..) si ya se conoce. */
    val globalPage: Int? = null,
    /** Total de paginas (aproximado si [totalExact] es false). */
    val totalPages: Int = 1,
    val totalExact: Boolean = false,
    val chapterTitle: String? = null,
    /** Numero de capitulos (paginas fisicas en PDF). */
    val chapterCount: Int = 1,
    /**
     * Caracteres por leer desde la posicion de lectura (inicio de la frase resaltada o, sin
     * voz, la posicion actual) hasta el final del capitulo. Con la velocidad de la voz en
     * caracteres/s, :app calcula el tiempo restante.
     */
    val chapterCharsRemaining: Int = 0,
    /** Igual que [chapterCharsRemaining] hasta el final del libro. */
    val bookCharsRemaining: Long = 0,
    /** false si alguna longitud de capitulo es estimada (aun no cargado o contado). */
    val charsExact: Boolean = false,
    /** Fraccion leida del capitulo por caracteres (0..1). */
    val chapterProgress: Float = 0f,
    /** Fraccion leida del libro por caracteres (0..1). */
    val bookProgress: Float = 0f,
) {
    /** Progreso 0..1 aproximado por paginas. */
    val progress: Float
        get() = if (totalPages <= 1) 0f else ((globalPage ?: 0).toFloat() / (totalPages - 1)).coerceIn(0f, 1f)
}

/**
 * Controlador del lector, independiente de la vista concreta ([PageFlipView] en modo
 * paginas o [ScrollReaderView] en modo scroll). Mantiene la posicion ([currentLocator]),
 * los resaltados, la cache de bitmaps y el render en fondo.
 *
 * Uso desde el hilo principal. :app crea uno por libro abierto, llama a [openEpub] o
 * [openPdf], aplica [setSettings] y lo muestra con `ReaderView(controller)`.
 *
 * Para el modo solo escuchar (que vive en :app): [bookTitle], [currentSentence] (texto de
 * la frase resaltada) y [pageInfo] (capitulo, titulo, progreso y caracteres restantes).
 *
 * Ciclo de vida de los trabajos: todo lo que toca el contenido abierto es hijo de un Job
 * por libro; al cerrar el libro se cancela y el contenido (ZipFile, pdfium) solo se cierra
 * cuando todos esos trabajos han terminado de verdad.
 */
class ReaderController(
    context: Context,
    private val fonts: FontProvider = FontProvider.Default,
    private val chrome: PageChrome = PageChrome(),
    cacheBytes: Long = PageBitmapCache.defaultMaxBytes(),
) : PageProvider, PageViewListener {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Hilo unico de render: tambien serializa el uso de los spans y del StaticLayout. */
    private val renderDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    private val pool = BitmapPool(cacheBytes / 2)
    private val releaser = DeferredReleaser(sink = { pool.put(it) })
    private var releasePosted = false
    private val releaseRunnable = Runnable {
        releasePosted = false
        releaser.releaseDrawn()
    }
    private var closed = false

    /** Cache LRU de paginas (por bytes) y pool de reutilizacion. */
    val cache = PageBitmapCache<PageKey>(cacheBytes, pool, onEvict = ::deferRelease)

    private val _locator = MutableStateFlow(Locator.START)

    /** Posicion de lectura actual (inicio de la pagina o el destino de [goTo]/[ensureVisible]). */
    val currentLocator: StateFlow<Locator> = _locator.asStateFlow()

    private val _settings = MutableStateFlow(ReaderSettings())
    val settings: StateFlow<ReaderSettings> = _settings.asStateFlow()

    private val _pageInfo = MutableStateFlow(PageInfo())
    val pageInfo: StateFlow<PageInfo> = _pageInfo.asStateFlow()

    private val _sentence = MutableStateFlow<String?>(null)

    /** Texto de la frase resaltada con [HighlightKind.SENTENCE] (null si no hay). */
    val currentSentence: StateFlow<String?> = _sentence.asStateFlow()

    /** Pulsacion larga sobre el texto: "leer desde aqui". */
    var onTextTapped: ((Locator) -> Unit)? = null

    /** Toque en el centro: mostrar/ocultar menu. */
    var onCenterTapped: (() -> Unit)? = null

    private var content: PagedContent? = null
    private var contentJob: CompletableJob = SupervisorJob(scope.coroutineContext.job)
    private var contentScope = CoroutineScope(scope.coroutineContext + contentJob)
    private var colors: ReaderColors = ReaderColors.LIGHT
    private var colorsOverride: ReaderColors? = null
    private var systemDark = false
    private var viewport: Viewport? = null
    private var host: PageHost? = null

    /** Pagina actual segun el controlador (el destino, aunque la vista aun este animando). */
    private var currentKey: PageKey? = null
    private var anchor: Locator = Locator.START
    private var highlights = Highlights.NONE

    // Capa del resaltado de la voz por pagina visible (solo hilo principal; ver HighlightLayer).
    private var voiceLayers: Map<PageKey, HighlightLayer> = emptyMap()
    private var layerJob: Job? = null
    private var renderGen = 0
    private val dirty = HashSet<PageKey>()
    private val jobs = HashMap<PageKey, Job>()
    private val preparing = HashSet<Int>()

    /** Bitmap de la pagina actual de antes de repaginar: se muestra hasta tener el nuevo. */
    private var transitional: Bitmap? = null

    /** Pagina para la que vale [transitional] (la de la misma posicion de lectura). */
    private var transitionalKey: PageKey? = null

    /** Repaginando: las claves aun no valen; no se piden renders hasta saber la pagina nueva. */
    private var repaginating = false
    private var repaginateToken = 0
    private var countJob: Job? = null
    private var followJob: Job? = null
    private var navJob: Job? = null
    private var sentenceJob: Job? = null

    /** Titulo del libro abierto. */
    val bookTitle: String? get() = content?.title

    /** Numero de capitulos (paginas en PDF) del libro abierto; 0 si no hay. */
    val chapterCount: Int get() = content?.chapterCount ?: 0

    // ------------------------------------------------------------------ apertura

    /** Abre un EPUB y se coloca en [start]. */
    fun openEpub(source: DocumentSource, start: Locator = Locator.START) {
        open(EpubContent(source, fonts), start)
    }

    /** Abre un PDF ya abierto con [PdfBook.open] y se coloca en [start]. */
    fun openPdf(book: PdfBook, start: Locator = Locator.START, cleaner: PageTextCleaner = PageTextCleaner.Identity) {
        open(PdfContent(book, cleaner), start)
    }

    /** Abre un contenido cualquiera (pruebas o formatos futuros). */
    fun open(newContent: PagedContent, start: Locator = Locator.START) {
        check(!closed) { "ReaderController cerrado" }
        closeContent()
        content = newContent
        contentJob = SupervisorJob(scope.coroutineContext.job)
        contentScope = CoroutineScope(scope.coroutineContext + contentJob)
        anchor = start
        _locator.value = start
        currentKey = null
        configureContent(forceRelayout = true)
    }

    /**
     * Cancela todo el trabajo del libro y cierra el contenido cuando ese trabajo ha
     * terminado (la cancelacion es cooperativa: una maquetacion o un render en marcha siguen
     * hasta su siguiente punto de suspension y no deben encontrarse el ZipFile cerrado).
     */
    private fun closeContent() {
        val c = content
        val job = contentJob
        job.cancel()
        countJob = null; followJob = null; navJob = null; sentenceJob = null; layerJob = null
        voiceLayers = emptyMap()
        jobs.clear()
        dirty.clear()
        preparing.clear()
        renderGen++
        dropTransitional()
        for (k in cache.keys()) cache.remove(k)?.let { deferRelease(it) }
        host?.invalidatePages()
        content = null
        currentKey = null
        repaginating = false
        highlights = Highlights.NONE
        _sentence.value = null
        if (c != null) {
            CLOSER.launch {
                job.join()
                try {
                    c.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error al cerrar el libro", e)
                }
            }
        }
    }

    // ------------------------------------------------------------------ vista

    /** Conecta la vista que muestra las paginas (la hace [ReaderView]). */
    fun attach(view: PageHost) {
        host = view
        when (view) {
            is PageFlipView -> { view.provider = this; view.listener = this }
            is ScrollReaderView -> { view.provider = this; view.listener = this }
        }
        applyViewSettings()
        currentKey?.let { view.resetPages(it) }
    }

    /** Desconecta [view] si es la actual. */
    fun detach(view: PageHost) {
        if (host === view) {
            host = null
            // Ya no hay vista que los dibuje: al GC (nunca al pool, por si queda un fotograma).
            releaser.dropAll()
        }
    }

    /** Tamano de la vista; repagina si cambia. */
    fun setViewport(widthPx: Int, heightPx: Int, density: Float, fontScale: Float = 1f) {
        val v = Viewport(widthPx, heightPx, density, fontScale)
        if (v.isEmpty || v == viewport) return
        viewport = v
        configureContent(forceRelayout = false)
    }

    /**
     * Cambia los ajustes en caliente. Si cambia la maquetacion se repagina y se vuelve al
     * mismo [currentLocator]; si solo cambian colores, se redibuja.
     */
    fun setSettings(newSettings: ReaderSettings) {
        _settings.value = newSettings
        updateColors()
        applyViewSettings()
        configureContent(forceRelayout = false)
    }

    /** Si el sistema esta en modo oscuro (para el tema "segun sistema"). */
    fun setSystemDark(dark: Boolean) {
        if (dark == systemDark) return
        systemDark = dark
        updateColors()
        configureContent(forceRelayout = false)
    }

    /** Fuerza unos colores concretos (null = los del tema de los ajustes). */
    fun setColors(override: ReaderColors?) {
        colorsOverride = override
        updateColors()
        configureContent(forceRelayout = false)
    }

    private fun updateColors() {
        val s = _settings.value
        colors = colorsOverride ?: ReaderColors.forTheme(s.theme, systemDark, s.highContrast)
    }

    private fun applyViewSettings() {
        val s = _settings.value
        (host as? PageFlipView)?.apply {
            animation = s.pageAnimation
            animationDurationMs = s.pageAnimationMillis
            tapZonesTurnPages = s.tapZonesTurnPages
            volumeKeysTurnPages = s.volumeKeysTurnPages
            setColors(colors.readerPageBack, colors.pageEdgeShadow)
        }
        // Mantener la pantalla encendida NO se pone aqui: lo decide la pantalla del lector
        // (`shouldKeepScreenOn`: solo con voz sonando o toques recientes). Fijarlo en la vista
        // con el ajuste la dejaba encendida siempre mientras el lector estaba abierto (calor).
    }

    private fun configureContent(forceRelayout: Boolean) {
        val c = content ?: return
        val v = viewport ?: return
        val s = _settings.value
        val config = RenderConfig(
            viewport = v,
            layout = LayoutSettings.from(s, chrome),
            colors = colors,
            pdfRecolor = s.pdfRecolor,
            scrollMode = s.readingMode == ReadingMode.SCROLL,
        )
        val relayout = c.configure(config) || forceRelayout
        (host as? PageFlipView)?.setColors(colors.readerPageBack, colors.pageEdgeShadow)
        if (relayout) {
            relayout(c)
        } else {
            // Solo colores: redibujar lo que hay sin perder la imagen actual.
            cancelRenders()
            dirty.addAll(cache.keys())
            currentKey?.let { request(it) }
            host?.onPagesChanged()
            refreshVoiceLayers()
        }
    }

    private fun cancelRenders() {
        renderGen++
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    private fun relayout(c: PagedContent) {
        cancelRenders()
        dirty.clear()
        // Las claves cambian de significado: fuera todo, salvo la imagen actual como transicion.
        val old = currentKey
        val cur = old?.let { cache.remove(it) }
        if (cur != null) {
            dropTransitional()
            transitional = cur
            transitionalKey = old
        }
        for (k in cache.keys()) cache.remove(k)?.let { deferRelease(it) }
        countJob?.cancel()
        navJob?.cancel()
        val target = anchor
        val token = ++repaginateToken
        repaginating = true
        navJob = contentScope.launch {
            try {
                val key = try {
                    c.keyFor(target)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo paginar", e)
                    return@launch
                }
                repaginating = false
                currentKey = key
                if (transitional != null) transitionalKey = key
                host?.resetPages(key)
                pageShown(key, keepAnchor = true)
                host?.onPagesChanged()
                scheduleCount(c)
            } finally {
                // Cancelado por goTo u otra causa: no dejar el render bloqueado.
                if (token == repaginateToken) repaginating = false
            }
        }
    }

    /**
     * Cuenta el total de paginas EPUB en fondo: aplazado [COUNT_DELAY_MS] (si el usuario
     * sigue moviendo un ajuste, se cancela y se vuelve a aplazar), en un hilo de prioridad
     * baja y sin bloquear la maquetacion visible. Mientras tanto el total es una estimacion
     * por caracteres ([PageInfo.totalExact] = false).
     */
    private fun scheduleCount(c: PagedContent) {
        if (c !is EpubContent) return
        countJob?.cancel()
        countJob = contentScope.launch {
            delay(COUNT_DELAY_MS)
            try {
                c.computeAllPageCounts(ReaderDispatchers.background)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo contar las paginas", e)
            }
            updatePageInfo()
            // Los pies ya dibujados muestran "~N": se repintan al volver a verse.
            dirty.addAll(cache.keys())
            host?.onPagesChanged()
        }
    }

    // ------------------------------------------------------------------ navegacion

    /** Muestra [key] como pagina actual (la vista anima si [animated]). */
    private fun show(key: PageKey, animated: Boolean) {
        repaginating = false // la clave ya es de la maquetacion nueva
        currentKey = key
        host?.showPage(key, animated)
        pageShown(key, keepAnchor = true)
    }

    /** Va a la pagina que contiene [locator]; con [animated], anima si es contigua. */
    fun goTo(locator: Locator, animated: Boolean = false) {
        anchor = locator
        _locator.value = locator
        val c = content ?: return
        if (viewport == null) return
        navJob?.cancel()
        navJob = contentScope.launch {
            val key = try {
                c.keyFor(locator)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo ir a $locator", e)
                return@launch
            }
            if (key != currentKey) show(key, animated && isNeighbor(key)) else updatePageInfo()
        }
    }

    private fun isNeighbor(key: PageKey): Boolean {
        val cur = currentKey ?: return false
        return neighbor(cur, 1) == key || neighbor(cur, -1) == key
    }

    /** Pagina siguiente (animada segun ajustes). */
    fun nextPage(): Boolean = host?.nextPage(true) ?: false

    /** Pagina anterior (animada segun ajustes). */
    fun prevPage(): Boolean = host?.prevPage(true) ?: false

    /**
     * Pasa de pagina solo si [locator] no esta en la pagina actual (seguir la voz), sin
     * oscilar con frases partidas entre dos paginas ([FollowPolicy]). La pagina actual es el
     * destino ya pedido aunque la vista siga animando: varias peticiones seguidas durante una
     * animacion no la reinician ni dan un paso doble.
     */
    fun ensureVisible(locator: Locator) {
        val c = content ?: return
        if (viewport == null) return
        followJob?.cancel()
        followJob = contentScope.launch {
            val key = try {
                c.keyFor(locator)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo seguir $locator", e)
                return@launch
            }
            val cur = currentKey
            if (key == cur) return@launch
            // Tambien entre capitulos: en PDF cada pagina es un capitulo y una frase partida
            // entre dos paginas haria ir y volver.
            if (cur != null) {
                val offs = c.offsetsOf(cur)
                val start = Locator(cur.chapter, offs.first)
                val end = Locator(cur.chapter, offs.last + 1)
                if (!FollowPolicy.shouldMove(start, end, locator, highlights.sentence)) return@launch
            }
            anchor = locator
            _locator.value = locator
            show(key, isNeighbor(key))
        }
    }

    // ------------------------------------------------------------------ resaltados

    /** Resalta [range] con el estilo de [kind] (sustituye al anterior de ese tipo). */
    fun highlight(range: LocatorRange, kind: HighlightKind) {
        updateHighlights(highlights.with(kind, range))
        if (kind == HighlightKind.SENTENCE) sentenceChanged(range)
    }

    /** Quita el resaltado de [kind]. */
    fun clearHighlight(kind: HighlightKind) {
        updateHighlights(highlights.with(kind, null))
        if (kind == HighlightKind.SENTENCE) sentenceChanged(null)
    }

    /** Resultados de busqueda; [current] se marca con contorno grueso. */
    fun setSearchResults(results: List<LocatorRange>, current: LocatorRange?) {
        updateHighlights(highlights.copy(search = results, searchCurrent = current))
    }

    private fun sentenceChanged(range: LocatorRange?) {
        sentenceJob?.cancel()
        sentenceJob = null
        val c = content
        if (range == null || c == null) {
            _sentence.value = null
        } else {
            sentenceJob = contentScope.launch {
                _sentence.value = try {
                    c.textOf(range)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        }
        updatePageInfo()
    }

    /** Texto legible de [range] (para :app: frase actual, compartir...). */
    suspend fun textOf(range: LocatorRange): String? = content?.textOf(range)

    /**
     * Aplica [new]. La seleccion y la busqueda van dentro del bitmap de la pagina: se marcan como
     * sucias las paginas afectadas (no se renderiza aqui: la vista, al redibujar, pide la pagina;
     * asi con la pantalla apagada no se rehace nada). La frase y la palabra de la voz van en una
     * CAPA encima ([refreshVoiceLayers]): avanzar la voz no redibuja ninguna pagina.
     */
    private fun updateHighlights(new: Highlights) {
        if (new == highlights) return
        val old = highlights
        highlights = new
        if (old.sentence != new.sentence || old.word != new.word) refreshVoiceLayers()
        val oldBaked = old.baked()
        val newBaked = new.baked()
        if (oldBaked == newBaked) return
        val chapters = HashSet<Int>()
        for (r in oldBaked.all() + newBaked.all()) for (ch in r.start.chapter..r.endExclusive.chapter) chapters += ch
        if (chapters.isEmpty()) return
        for (k in cache.keys()) if (k.chapter in chapters) dirty += k
        for (k in jobs.keys.toList()) if (k.chapter in chapters) { jobs.remove(k)?.cancel(); dirty += k }
        currentKey?.let { if (it in dirty) host?.onPageReady(it) }
    }

    /** Lo que va dentro del bitmap: todo menos la frase y la palabra de la voz. */
    private fun Highlights.baked(): Highlights = if (sentence == null && word == null) this else copy(sentence = null, word = null)

    /**
     * Recalcula la capa de la voz de las paginas visibles (unos pocos rectangulos, en el hilo de
     * render porque usa la maquetacion) y pide repintar. Sin frase ni palabra, la quita.
     */
    private fun refreshVoiceLayers() {
        layerJob?.cancel()
        layerJob = null
        val c = content
        val sentence = highlights.sentence
        val word = highlights.word
        if (c == null || (sentence == null && word == null)) {
            if (voiceLayers.isNotEmpty()) {
                voiceLayers = emptyMap()
                host?.onOverlayChanged()
            }
            return
        }
        val keys = LinkedHashSet<PageKey>()
        currentKey?.let { keys += it }
        (host as? ScrollReaderView)?.visibleKeys()?.let { keys += it }
        if (keys.isEmpty()) return
        layerJob = contentScope.launch {
            val layers = HashMap<PageKey, HighlightLayer>()
            try {
                withContext(renderDispatcher) {
                    for (k in keys) c.voiceLayer(k, sentence, word)?.let { layers[k] = it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo calcular el resaltado", e)
            }
            voiceLayers = layers
            host?.onOverlayChanged()
        }
    }

    // ------------------------------------------------------------------ PageProvider

    override fun overlay(key: PageKey): HighlightLayer? = voiceLayers[key]

    override fun bitmap(key: PageKey): Bitmap? {
        if (repaginating) return if (key == transitionalKey) transitional else null
        val b = cache[key]
        if (b == null || key in dirty) request(key)
        if (b == null && key == transitionalKey) return transitional
        return b
    }

    override fun neighbor(key: PageKey, delta: Int): PageKey? {
        val c = content ?: return null
        if (delta == 0) return key
        val count = c.knownPageCount(key.chapter)
        if (delta > 0) {
            if (count != null && key.page + 1 < count) return PageKey(key.chapter, key.page + 1)
            if (count == null) { prepareChapter(key.chapter); return null }
            return if (key.chapter + 1 < c.chapterCount) PageKey(key.chapter + 1, 0) else null
        }
        if (key.page > 0) return PageKey(key.chapter, key.page - 1)
        val prev = key.chapter - 1
        if (prev < 0) return null
        val prevCount = c.knownPageCount(prev)
        if (prevCount == null) { prepareChapter(prev); return null }
        return PageKey(prev, (prevCount - 1).coerceAtLeast(0))
    }

    /** Pagina un capitulo en fondo y avisa a la vista cuando ya se conocen sus paginas. */
    private fun prepareChapter(chapter: Int) {
        val c = content ?: return
        if (viewport == null || !preparing.add(chapter)) return
        contentScope.launch {
            try {
                c.pageCount(chapter)
                host?.onPagesChanged()
                currentKey?.let { prefetchNeighbors(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo paginar el capitulo $chapter", e)
            } finally {
                preparing.remove(chapter)
            }
        }
    }

    override fun pageHeight(key: PageKey, width: Int, viewHeight: Int): Int {
        val c = content as? PdfContent ?: return viewHeight
        return c.knownScrollHeight(key.chapter, width) ?: viewHeight
    }

    override val placeholderColor: Int get() = colors.readerBackground

    /** Pide el render de [key] en fondo (sin reservar memoria en el hilo principal). */
    private fun request(key: PageKey) {
        val c = content ?: return
        if (viewport == null || repaginating) return
        if (jobs[key]?.isActive == true) return
        val gen = renderGen
        val hl = highlights.baked()
        val footer = footerFor(c, key)
        jobs[key] = contentScope.launch {
            val self = coroutineContext.job
            var bmp: Bitmap? = null
            val ok = try {
                withContext(renderDispatcher) {
                    val (w, h) = c.bitmapSize(key)
                    val b = pool.get(w, h)
                    bmp = b
                    c.render(key, b, hl, footer)
                }
                true
            } catch (e: CancellationException) {
                bmp?.let { pool.put(it) } // nunca mostrado
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Error al dibujar $key", e)
                false
            }
            if (jobs[key] === self) jobs.remove(key)
            val b = bmp ?: return@launch
            if (!ok || gen != renderGen) { pool.put(b); return@launch }
            if (hl == highlights) dirty.remove(key)
            cache.put(key, b)
            if (key == transitionalKey) dropTransitional()
            // PDF en scroll: el alto real solo se conoce tras leer la pagina.
            if (c.bitmapSize(key) != (b.width to b.height)) dirty += key
            host?.onPageReady(key)
            if (key in dirty && key == currentKey) request(key)
        }
    }

    private fun footerFor(c: PagedContent, key: PageKey): String? {
        if (c is PdfContent) {
            return if (chrome.pdfFooter) "${key.chapter + 1} / ${c.chapterCount}" else null
        }
        val (total, exact) = c.totalPages()
        val global = c.globalIndex(key)
        return if (global != null) {
            val pct = if (total > 1) (global * 100 / (total - 1)).coerceIn(0, 100) else 0
            if (exact) "${global + 1} / $total  ·  $pct %" else "${global + 1} / ~$total"
        } else {
            val count = c.knownPageCount(key.chapter) ?: return null
            "${key.page + 1} / $count"
        }
    }

    // ------------------------------------------------------------------ liberacion segura

    private fun dropTransitional() {
        val t = transitional ?: return
        transitional = null
        transitionalKey = null
        deferRelease(t)
    }

    /**
     * [bmp] ha salido de la cache. Se devuelve al pool solo despues de que la vista grabe un
     * fotograma nuevo (ya no esta en su lista de dibujo); sin vista o tras cerrar, al GC.
     */
    private fun deferRelease(bmp: Bitmap) {
        if (closed) return
        val h = host
        if (h == null) return // sin vista: lo recoge el GC
        releaser.defer(bmp)
        h.invalidatePages()
    }

    override fun onFrameDrawn() {
        releaser.onFrameDrawn()
        if (releaser.pendingCount > 0 && !releasePosted) {
            releasePosted = true
            // En un mensaje posterior: el fotograma ya se ha entregado al hilo de render.
            main.post(releaseRunnable)
        }
    }

    // ------------------------------------------------------------------ PageViewListener

    override fun onPageShown(key: PageKey) {
        if (key == currentKey) return // ya lo pidio el controlador
        pageShown(key, keepAnchor = false)
    }

    private fun pageShown(key: PageKey, keepAnchor: Boolean) {
        currentKey = key
        refreshVoiceLayers()
        // La imagen de transicion solo vale para la pagina de la misma posicion.
        if (transitional != null && key != transitionalKey) dropTransitional()
        val c = content ?: return
        prefetchNeighbors(key)
        contentScope.launch {
            val offs = try {
                c.offsetsOf(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val a = anchor
            val inside = offs != null && a.chapter == key.chapter && a.offset in offs
            if (!(keepAnchor || inside)) {
                anchor = Locator(key.chapter, offs?.first ?: 0)
                _locator.value = anchor
            }
            updatePageInfo()
            val text = try {
                c.pageText(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ""
            }
            val info = _pageInfo.value
            val label = "Pagina ${(info.globalPage ?: key.page) + 1} de ${info.totalPages}"
            host?.setPageDescription("$label. $text", announce = false)
            if (!keepAnchor) {
                @Suppress("DEPRECATION")
                (host as? android.view.View)?.announceForAccessibility(label)
            }
        }
    }

    private fun prefetchNeighbors(key: PageKey) {
        val next = neighbor(key, 1)
        val prev = neighbor(key, -1)
        val pins = ArrayList<PageKey>(8)
        pins += key
        next?.let { pins += it }
        prev?.let { pins += it }
        (host as? ScrollReaderView)?.visibleKeys()?.let { pins += it }
        cache.pin(pins)
        // Solo si falta o cambio (resaltado nuevo = sucia): antes se volvia a dibujar entera cada
        // pagina mostrada (tambien cada paso de pagina de la voz) aunque ya estuviera lista.
        if (key !in cache || key in dirty) request(key)
        next?.let { if (it !in cache || it in dirty) request(it) }
        prev?.let { if (it !in cache || it in dirty) request(it) }
    }

    private fun updatePageInfo() {
        val c = content ?: return
        val key = currentKey ?: return
        val (total, exact) = c.totalPages()
        val position = highlights.sentence?.start ?: anchor
        val chars = TextProgress.compute(position, c.chapterCount) { c.knownCharCount(it) }
        _pageInfo.value = PageInfo(
            chapter = key.chapter,
            pageInChapter = key.page,
            chapterPages = c.knownPageCount(key.chapter) ?: 1,
            globalPage = c.globalIndex(key),
            totalPages = total,
            totalExact = exact,
            chapterTitle = c.chapterTitle(key.chapter),
            chapterCount = c.chapterCount,
            chapterCharsRemaining = chars.chapterRemaining,
            bookCharsRemaining = chars.bookRemaining,
            charsExact = chars.exact,
            chapterProgress = chars.chapterFraction,
            bookProgress = chars.bookFraction,
        )
    }

    override fun onCenterTap() {
        onCenterTapped?.invoke()
    }

    override fun onLongPress(key: PageKey, x: Float, y: Float) {
        val c = content ?: return
        contentScope.launch {
            val loc = try {
                withContext(renderDispatcher) { c.hitTest(key, x, y) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return@launch
            onTextTapped?.invoke(loc)
        }
    }

    /** Toque programatico en ([x], [y]) de la pagina actual -> Locator (o null). */
    suspend fun locatorAt(x: Float, y: Float): Locator? {
        val c = content ?: return null
        val key = currentKey ?: return null
        // En el hilo de render: el StaticLayout comparte spans con el dibujo.
        return withContext(renderDispatcher) { c.hitTest(key, x, y) }
    }

    // ------------------------------------------------------------------ memoria

    /**
     * Llamar desde `ComponentCallbacks2.onTrimMemory`. Con la interfaz oculta (no hay
     * fotogramas) lo retenido se suelta para el GC; luego se recortan cache, pool,
     * imagenes y maquetaciones segun el nivel.
     */
    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        val hidden = level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        cache.onTrimMemory(level)
        if (hidden) releaser.dropAll()
        content?.onTrimMemory(level)
    }

    /** Cierra el libro y libera memoria. Despues no se puede volver a usar. */
    fun close() {
        if (closed) return
        closeContent()
        closed = true
        releaser.close()
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        cache.clear()
        transitional = null
        transitionalKey = null
        pool.clear()
        host = null
    }

    companion object {
        private const val TAG = "ReaderController"

        /** Espera tras un cambio de ajustes antes de contar las paginas del libro. */
        const val COUNT_DELAY_MS = 1000L

        /** Cierra contenidos cuando terminan sus trabajos (no depende de ningun controlador). */
        private val CLOSER = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
