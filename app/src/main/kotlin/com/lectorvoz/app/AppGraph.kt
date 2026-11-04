package com.lectorvoz.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.util.UnstableApi
import com.lectorvoz.app.books.AppFontProvider
import com.lectorvoz.app.books.BookOpener
import com.lectorvoz.app.books.CoverExtractorImpl
import com.lectorvoz.app.books.CoverLoader
import com.lectorvoz.app.books.OpenedBook
import com.lectorvoz.app.integration.BookForVoice
import com.lectorvoz.app.integration.PlaybackFacade
import com.lectorvoz.app.integration.TimerChoice
import com.lectorvoz.app.books.ObservedLibrary
import com.lectorvoz.app.history.HistoryRecorder
import com.lectorvoz.app.ocr.OcrManager
import com.lectorvoz.app.update.AppRelease
import com.lectorvoz.core.data.history.InMemoryReadingHistory
import com.lectorvoz.core.data.history.ReadingHistoryRepository
import com.lectorvoz.core.data.history.RoomReadingHistoryRepository
import com.lectorvoz.tts.playback.TtsConfig
import java.io.File
import com.lectorvoz.app.update.UpdateChecker
import com.lectorvoz.app.update.UpdateInstaller
import com.lectorvoz.app.update.UpdateNotifier
import com.lectorvoz.app.update.InstallState
import com.lectorvoz.app.widget.ListenWidgetUpdater
import com.lectorvoz.app.integration.PlaybackPort
import com.lectorvoz.app.integration.UtteranceProviderImpl
import com.lectorvoz.app.integration.VoicesFacade
import com.lectorvoz.app.integration.VoicesPort
import com.lectorvoz.core.data.bookmarks.BookmarkRepository
import com.lectorvoz.core.data.bookmarks.RoomBookmarkRepository
import com.lectorvoz.core.data.collections.CollectionRepository
import com.lectorvoz.core.data.collections.RoomCollectionRepository
import com.lectorvoz.core.data.db.LectorVozDatabase
import com.lectorvoz.core.data.library.Book
import com.lectorvoz.core.data.library.LibraryRepository
import com.lectorvoz.core.data.library.RoomLibraryRepository
import com.lectorvoz.core.data.pronunciation.PronunciationRepository
import com.lectorvoz.core.data.pronunciation.RoomPronunciationRepository
import com.lectorvoz.core.data.settings.DataStoreSettingsRepository
import com.lectorvoz.core.data.settings.SettingsRepository
import com.lectorvoz.core.data.stats.ReadingStatsRepository
import com.lectorvoz.core.data.stats.RoomReadingStatsRepository
import com.lectorvoz.reader.FontProvider
import com.lectorvoz.app.books.ExportFiles
import com.lectorvoz.app.books.ExportManager
import com.lectorvoz.app.books.ImportCoordinator
import com.lectorvoz.app.books.SafExportFiles
import com.lectorvoz.app.books.SharedBooks
import com.lectorvoz.app.integration.BookContent
import com.lectorvoz.app.playback.ListeningTracker
import com.lectorvoz.app.playback.VoiceSettingsSync
import com.lectorvoz.core.data.pronunciation.PronunciationEntry
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.VoiceSettings
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.ajustes: DataStore<Preferences> by preferencesDataStore(
    name = "ajustes",
    // Fichero corrupto (corte de corriente, disco lleno): se sustituye por uno vacio.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Inyeccion de dependencias manual: un unico grafo por proceso, creado en [LectorVozApp].
 * Las pruebas construyen el suyo con repositorios falsos.
 */
class AppGraph(
    val context: Context,
    val appScope: CoroutineScope,
    val settings: SettingsRepository,
    library: LibraryRepository,
    val bookmarks: BookmarkRepository,
    val pronunciation: PronunciationRepository,
    val stats: ReadingStatsRepository,
    val collections: CollectionRepository,
    val playback: PlaybackPort,
    val voices: VoicesPort,
    val covers: CoverLoader = CoverLoader(),
    val fonts: FontProvider = AppFontProvider(context),
    val opener: BookOpener = BookOpener(context),
    exportFiles: ExportFiles = SafExportFiles(context),
    /** Consulta de la ultima version (sin red por defecto: las pruebas no salen a Internet). */
    updateFetch: suspend () -> AppRelease? = { null },
    /** Historial de lecturas (en memoria por defecto: pruebas). */
    val history: ReadingHistoryRepository = InMemoryReadingHistory(),
    historyCovers: File? = null,
) {
    /** Biblioteca sin avisos (para quien solo lee: OCR e historial). */
    private val rawLibrary: LibraryRepository = library

    /** Historial de lecturas: se anota al abrir, terminar y quitar libros. */
    val historyRecorder = HistoryRecorder(appScope, history, rawLibrary, historyCovers)

    /** OCR de los PDF escaneados y limpieza de lo guardado al terminar o quitar un libro. */
    val ocr: OcrManager = OcrManager(
        context, appScope, rawLibrary,
        openPdf = { b ->
            val lease = books.acquire(b, skipPdfHeaders = true)
            (lease.opened.content as? BookContent.Pdf)?.book to { lease.release() }
        },
        removeAudio = { id -> runCatching { TtsConfig.audioCache(context).removeBook(id.toString()) } },
    )

    /** Biblioteca de la app: avisa al historial y al OCR de los momentos clave. */
    val library: LibraryRepository = ObservedLibrary(rawLibrary, listOf(historyRecorder, ocr))

    /** Libros abiertos compartidos (voz, busqueda del lector, exportar). */
    val books = SharedBooks(opener).also { opener.pdfTextFallback = { b -> ocr.fallbackFor(b) } }

    /** Libro abierto para la voz (independiente del lector: sigue sonando al salir). */
    val speech = SpeechBooks(this)

    /** Importacion de "Abrir con" y "Compartir" (sobrevive a la actividad). */
    val imports = ImportCoordinator(appScope, this.library)

    /** Exportar a audio (sobrevive a la pantalla). */
    val exports = ExportManager(appScope, playback, books, settings, pronunciation, exportFiles)

    /** Posicion y estadisticas de escucha aunque el lector este cerrado. */
    val listening = ListeningTracker(appScope, playback, this.library, stats, progressOf = { id, loc -> speech.progressOf(id, loc) })

    /** Aviso de nueva version publicada en GitHub. */
    val updates: UpdateChecker by lazy { UpdateChecker(context, appScope, BuildConfig.VERSION_NAME, updateFetch) }

    /** Descarga e instalacion de la version nueva. */
    val installer: UpdateInstaller by lazy { UpdateInstaller(context, appScope) }

    /** Notificacion del estado de la actualizacion. */
    val updateNotifier: UpdateNotifier by lazy { UpdateNotifier(context, installer.state) { installer.version } }

    /**
     * Solo la version publicada se instala a si misma (la de depuracion tiene otro
     * identificador y firma: su aviso abre la pagina).
     */
    val canSelfUpdate: Boolean get() = !BuildConfig.DEBUG

    /** Widget de escritorio "seguir escuchando". */
    val widget = ListenWidgetUpdater(context, appScope, this.library, playback) { listenTo(it) }

    private val voiceSync = VoiceSettingsSync(appScope, playback, settings, pronunciation) { v, d -> speech.update(v, d) }

    /** Receptores de `onTrimMemory` (lectores vivos). */
    private val trimListeners = CopyOnWriteArraySet<(Int) -> Unit>()

    private var started = false

    /** Conexiones de ambito de aplicacion: marcar desde la notificacion, ajustes y escucha. */
    fun start() {
        if (started) return
        started = true
        appScope.launch {
            playback.bookmarkRequests.collect { loc ->
                // Un fallo (libro borrado mientras sonaba) no debe matar este recolector.
                try {
                    addBookmarkFromNotification(loc)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo guardar el marcador", e)
                }
            }
        }
        // Sin libro en el servicio (servicio destruido): soltar el libro de la voz.
        appScope.launch {
            playback.state.map { it.bookId }.distinctUntilChanged().collect { id -> if (id == null) speech.releaseAll() }
        }
        voiceSync.start()
        listening.start()
        widget.start()
        // OCR e historial segun los ajustes; el OCR de fondo sigue al libro que suena.
        appScope.launch {
            settings.app.map { Triple(it.ocrEnabled, it.ocrSaveBattery, it.historyEnabled) }.distinctUntilChanged().collect { (ocrOn, saveBattery, historyOn) ->
                ocr.setEnabled(ocrOn)
                ocr.setSaveBattery(saveBattery)
                historyRecorder.enabled = historyOn
            }
        }
        appScope.launch {
            playback.state.map { it.bookId to it.playWhenReady }.distinctUntilChanged().collect { (id, play) -> ocr.setVoice(id, play) }
        }
        ocr.start()
        historyRecorder.start()
        // Sin nada en marcha quita la de una actualizacion anterior (ya instalada).
        updateNotifier.start(appScope)
        // Aviso de nueva version: al abrir la app (y al activarlo), como mucho una vez al dia.
        appScope.launch {
            // La comprobacion la lanza la pantalla al abrirse ([checkUpdatesNow]); aqui solo se
            // atiende el cambio del ajuste (desactivar quita el aviso y para la descarga).
            settings.app.map { it.checkUpdates }.distinctUntilChanged().drop(1).collect { on ->
                if (on) {
                    updates.checkIfDue(true)
                } else {
                    updates.disable()
                    if (installer.state.value is InstallState.Downloading) installer.cancel()
                }
            }
        }
    }

    /** Al abrir la app: aviso de version nueva si toca (como mucho una consulta al dia). */
    fun checkUpdatesNow() {
        appScope.launch { if (settings.app.first().checkUpdates) updates.checkIfDue(true) }
    }

    /**
     * "Buscar actualizaciones ahora" (desde ajustes): comprobacion manual que ignora el limite
     * diario. Solo si el aviso de versiones esta activado (si no, la UI oculta el boton).
     */
    fun checkUpdatesNowManual() {
        appScope.launch { if (settings.app.first().checkUpdates) updates.checkNow() }
    }

    /**
     * Empieza a escuchar [book] desde su posicion guardada con sus ajustes de voz (desde el
     * widget). Si ya es el libro de la voz, solo reanuda. Solo tras un toque del usuario.
     */
    suspend fun listenTo(book: Book) {
        if (playback.state.value.bookId == book.id) {
            if (!playback.state.value.playWhenReady) playback.play()
            return
        }
        val voice = settings.voiceFor(book.id).first()
        val (opened, provider) = speech.providerFor(book)
        playback.updateSettings(voice)
        playback.start(BookForVoice(book.id, book.title, book.author, book.coverPath, opened.chapterTitles), provider, book.locator, autoPlay = true)
        voice.sleepTimerMinutes?.let { playback.setSleepTimer(TimerChoice.Minutes(it)) }
    }

    /**
     * Ultimo libro para reanudar la voz tras morir el proceso ("play" desde el auricular, la
     * notificacion o el coche): el que se estaba leyendo, en su posicion y con sus ajustes.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    suspend fun resumeRequest(): com.lectorvoz.tts.playback.BookRequest? {
        val book = library.continueReading.first() ?: return null
        val voice = settings.voiceFor(book.id).first()
        val (opened, provider) = speech.providerFor(book)
        playback.updateSettings(voice)
        return (playback as? PlaybackFacade)?.resumeRequest(
            BookForVoice(book.id, book.title, book.author, book.coverPath, opened.chapterTitles), provider, book.locator,
        )
    }

    /** La app esta en pantalla (MainActivity): el modelo de voz se conserva al pausar. */
    @Volatile var appVisible = false

    private suspend fun addBookmarkFromNotification(loc: Locator) {
        val id = playback.state.value.bookId ?: return
        if (library.get(id) == null) return
        val text = try {
            playback.currentSource?.textAt(loc)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        bookmarks.add(id, loc, text ?: "Marcado desde la notificacion")
    }

    /**
     * Quita [book] de la biblioteca: si la voz lo esta leyendo, la para (olvida el libro y
     * limpia la notificacion: revision final, M1) y suelta la licencia; al quedar `bookId` a
     * null, el colector de [start] llama a `speech.releaseAll()` y cierra el fichero abierto.
     * Despues borra datos y ajustes propios.
     */
    suspend fun deleteBook(book: Book) {
        if (playback.state.value.bookId == book.id) {
            speech.forget(book.id)
            playback.stop()
        }
        library.delete(book.id)
        settings.clearOverrides(book.id)
    }

    /** Registra un receptor de `onTrimMemory`; devuelve como quitarlo. */
    fun onTrimMemory(listener: (Int) -> Unit): () -> Unit {
        trimListeners += listener
        return { trimListeners -= listener }
    }

    /** Reparte `onTrimMemory` de la aplicacion. */
    fun trimMemory(level: Int) {
        covers.trim(level)
        trimListeners.forEach { it(level) }
    }

    companion object {
        private const val TAG = "AppGraph"

        @androidx.annotation.OptIn(UnstableApi::class)
        fun create(context: Context, scope: CoroutineScope): AppGraph {
            val app = context.applicationContext
            val db = LectorVozDatabase.build(app)
            return AppGraph(
                context = app,
                appScope = scope,
                settings = DataStoreSettingsRepository(app.ajustes),
                library = RoomLibraryRepository(app, db.bookDao(), db.pronunciationRuleDao(), CoverExtractorImpl(app), database = db),
                bookmarks = RoomBookmarkRepository(db.bookmarkDao()),
                pronunciation = RoomPronunciationRepository(db.pronunciationRuleDao()),
                stats = RoomReadingStatsRepository(db.readingSessionDao(), db.bookDao()),
                collections = RoomCollectionRepository(db),
                playback = PlaybackFacade(app, scope),
                voices = VoicesFacade(app),
                updateFetch = UpdateChecker::fetchLatest,
                history = RoomReadingHistoryRepository(db.readingHistoryDao(), File(app.filesDir, "historial")),
                historyCovers = File(app.filesDir, "historial"),
            )
        }
    }
}

/**
 * Mantiene abierto el libro que lee la voz (una referencia de [SharedBooks], distinta de la
 * instancia del lector, para que la lectura siga al cerrar la pantalla).
 */
class SpeechBooks(private val graph: AppGraph) {
    private val mutex = Mutex()
    private var lease: SharedBooks.Lease? = null
    @Volatile private var provider: UtteranceProviderImpl? = null
    @Volatile private var providerBook: Long? = null

    /** El libro anterior se suelta en el siguiente cambio: el servicio aun puede pedirle frases. */
    private var retired: SharedBooks.Lease? = null

    /** Proveedor de frases de [book], abriendolo si hace falta (suelta el anterior). */
    suspend fun providerFor(book: Book): Pair<OpenedBook, UtteranceProviderImpl> = mutex.withLock {
        val cur = lease
        val p = provider
        if (cur != null && p != null && cur.opened.book.id == book.id) return cur.opened to p
        val voice = graph.settings.voiceFor(book.id).first()
        val dict = graph.pronunciation.effective(book.id).first()
        val next = graph.books.acquire(book, voice.skipPdfHeadersFooters)
        retired?.release()
        retired = cur
        val np = UtteranceProviderImpl(next.opened.content, voice, dict, next.opened.toc)
        lease = next
        provider = np
        providerBook = book.id
        next.opened to np
    }

    /** Aplica ajustes y diccionario al libro que suena. */
    suspend fun update(voice: VoiceSettings, dict: List<PronunciationEntry>) {
        val (p, content) = mutex.withLock { provider to lease?.opened?.content }
        p ?: return
        (content as? BookContent.Pdf)?.cleaner?.enabled = voice.skipPdfHeadersFooters
        p.update(voice, dict)
    }

    /** Fraccion estimada del libro [bookId] en [loc] (nula si la voz no lo tiene abierto). */
    fun progressOf(bookId: Long, loc: Locator): Float? =
        if (providerBook == bookId) provider?.progressAt(loc) else null

    /** Suelta todo (el servicio ya no tiene libro abierto). */
    suspend fun releaseAll() = mutex.withLock {
        retired?.release()
        retired = null
        lease?.release()
        lease = null
        provider = null
        providerBook = null
    }

    /** Suelta el libro [bookId] si es el de la voz (al quitarlo de la biblioteca). */
    suspend fun forget(bookId: Long) = mutex.withLock {
        if (lease?.opened?.book?.id != bookId) return@withLock
        retired?.release()
        retired = lease
        lease = null
        provider = null
        providerBook = null
    }
}
