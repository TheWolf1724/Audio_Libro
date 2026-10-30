package com.lectorvoz.app

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lectorvoz.app.books.BookOpener
import com.lectorvoz.app.books.ExportFiles
import com.lectorvoz.app.books.ExportManager
import com.lectorvoz.app.books.GroupedProvider
import com.lectorvoz.app.books.ImportCoordinator
import com.lectorvoz.app.books.SharedBooks
import com.lectorvoz.app.fakes.FakeBookmarks
import com.lectorvoz.app.fakes.FakeCollectionRepository
import com.lectorvoz.app.fakes.FakeLibraryRepository
import com.lectorvoz.app.fakes.FakePlaybackPort
import com.lectorvoz.app.fakes.FakePronunciation
import com.lectorvoz.app.fakes.FakeSettingsRepository
import com.lectorvoz.app.fakes.FakeStats
import com.lectorvoz.app.fakes.FakeVoices
import com.lectorvoz.app.fakes.book
import com.lectorvoz.app.integration.BookContent
import com.lectorvoz.app.integration.PreviewFocus
import com.lectorvoz.app.integration.UtteranceProviderImpl
import com.lectorvoz.app.integration.VoicePreviewer
import com.lectorvoz.app.integration.VoiceUiState
import com.lectorvoz.app.playback.ListeningTracker
import com.lectorvoz.app.playback.VoiceSettingsSync
import com.lectorvoz.app.ui.reader.SCREEN_IDLE_MS
import com.lectorvoz.app.ui.reader.locatorForFraction
import com.lectorvoz.app.ui.reader.shouldKeepScreenOn
import com.lectorvoz.core.data.library.Book
import com.lectorvoz.core.data.pronunciation.PronunciationEntry
import com.lectorvoz.core.data.settings.BookOverrides
import com.lectorvoz.core.data.stats.SessionKind
import com.lectorvoz.core.model.BookFormat
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.LocatorRange
import com.lectorvoz.core.model.TocEntry
import com.lectorvoz.core.model.TtsEngineType
import com.lectorvoz.core.model.Utterance
import com.lectorvoz.core.model.VoiceSettings
import com.lectorvoz.format.epub.EpubBook
import com.lectorvoz.tts.UtteranceProvider
import com.lectorvoz.tts.engine.RenderedAudio
import com.lectorvoz.tts.engine.UtteranceRenderer
import com.lectorvoz.tts.neural.PcmSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Regresiones de la revision 3 en `:app` (A1-A6, M3-M13, bajos). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class Revision3AppTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() = Dispatchers.resetMain()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun epubFile(): File {
        val f = File(tmp.root, "r3.epub")
        fun xhtml(body: String) = """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xml:lang="es"><head><title>t</title></head><body>$body</body></html>"""
        val entries = listOf(
            "META-INF/container.xml" to """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "OEBPS/content.opf" to """<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">x</dc:identifier><dc:title>Prueba</dc:title><dc:language>es</dc:language></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a"/><itemref idref="b"/></spine></package>""",
            "OEBPS/nav.xhtml" to """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>i</title></head><body><nav epub:type="toc"><ol><li><a href="a.xhtml">Primero</a></li><li><a href="b.xhtml">Segundo</a></li></ol></nav></body></html>""",
            "OEBPS/a.xhtml" to xhtml("<h1>Primero</h1><p>Era una noche oscura. Llovia sin parar.</p><p>El tren llego tarde.</p>"),
            "OEBPS/b.xhtml" to xhtml("<p>Al dia siguiente salio el sol. Todo cambio.</p>"),
        )
        ZipOutputStream(f.outputStream()).use { z ->
            val mime = "application/epub+zip".toByteArray()
            z.putNextEntry(ZipEntry("mimetype").apply { method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = size; crc = CRC32().apply { update(mime) }.value })
            z.write(mime); z.closeEntry()
            for ((n, t) in entries) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() }
        }
        return f
    }

    private fun epubBook(id: Long = 7): Book {
        val f = epubFile()
        return book(id, "Libro EPUB").copy(localPath = f.absolutePath, sizeBytes = f.length())
    }

    /** Espera (tiempo real) a que se cumpla [cond]: el libro se abre en Dispatchers.IO. */
    private suspend fun waitUntil(cond: () -> Boolean) = kotlinx.coroutines.withContext(Dispatchers.Default) {
        kotlinx.coroutines.withTimeout(10_000) { while (!cond()) kotlinx.coroutines.delay(10) }
    }

    // ------------------------------------------------------------------ A1 y B6

    @Test
    fun posicionYEstadisticasDeEscuchaSinLector() = runTest(UnconfinedTestDispatcher()) {
        val playback = FakePlaybackPort()
        val library = FakeLibraryRepository(listOf(book(7)))
        val stats = FakeStats()
        var now = 0L
        val tracker = ListeningTracker(this, playback, library, stats, progressOf = { _, loc -> loc.offset / 1000f }, wallClock = { 1_000_000L + now }, monotonic = { now })
        fun state(off: Int, playing: Boolean = true) = VoiceUiState(bookId = 7, position = Locator(0, off), playWhenReady = playing, isPlaying = playing)

        tracker.onState(state(10))
        assertThat(library.list.value.first().locator).isEqualTo(Locator(0, 10))
        now = 1_000; tracker.onState(state(50))
        assertThat(library.list.value.first().locator).isEqualTo(Locator(0, 10)) // limitado a cada 30 s
        now = 29_000; tracker.onState(state(70))
        assertThat(library.list.value.first().locator).isEqualTo(Locator(0, 10))
        now = 31_000; tracker.onState(state(90))
        assertThat(library.list.value.first().locator).isEqualTo(Locator(0, 90))
        assertThat(library.list.value.first().progress).isWithin(0.001f).of(0.09f)
        now = 32_000; tracker.onState(state(95, playing = false))
        assertThat(library.list.value.first().locator).isEqualTo(Locator(0, 95)) // al pausar, siempre
        // Un tramo de escucha de 32 s con 85 caracteres avanzados.
        assertThat(stats.records).hasSize(1)
        val r = stats.records.single()
        assertThat(r.kind).isEqualTo(SessionKind.LISTEN)
        assertThat(r.durationMs).isEqualTo(32_000)
        assertThat(r.chars).isEqualTo(85)
        // Terminar el libro lo marca como terminado.
        now = 33_000; tracker.onState(state(95, playing = false).copy(ended = true))
        assertThat(library.list.value.first().finished).isTrue()
        assertThat(library.list.value.first().progress).isEqualTo(1f)
    }

    @Test
    fun estadisticaCuentaCaracteresAlCruzarDeCapitulo() = runTest(UnconfinedTestDispatcher()) {
        val playback = FakePlaybackPort()
        val library = FakeLibraryRepository(listOf(book(7)))
        val stats = FakeStats()
        var now = 0L
        val tracker = ListeningTracker(this, playback, library, stats, progressOf = { _, _ -> null }, wallClock = { 1_000_000L + now }, monotonic = { now })
        fun st(ch: Int, off: Int, len: Int?, playing: Boolean = true) =
            VoiceUiState(bookId = 7, position = Locator(ch, off), chapterLength = len, playWhenReady = playing, isPlaying = playing)

        tracker.onState(st(0, 20, 100))             // abre tramo en (0,20); el cap 0 mide 100
        now = 1_000; tracker.onState(st(0, 80, 100)) // +60 dentro del capitulo
        now = 2_000; tracker.onState(st(1, 30, 200)) // resto del cap 0 (100-80=20) + 30 = 50
        now = 3_000; tracker.onState(st(1, 150, 200)) // +120
        now = 4_000; tracker.onState(st(1, 150, 200, playing = false)) // cierra el tramo
        // 60 + (20 + 30) + 120 = 230 (antes contaba 30 por olvidar el resto del cap previo).
        assertThat(stats.records.single().chars).isEqualTo(230)
    }

    @Test
    fun estadisticaCuentaAlSaltarVariosCapitulos() = runTest(UnconfinedTestDispatcher()) {
        val playback = FakePlaybackPort()
        val library = FakeLibraryRepository(listOf(book(7)))
        val stats = FakeStats()
        var now = 0L
        val tracker = ListeningTracker(this, playback, library, stats, progressOf = { _, _ -> null }, wallClock = { 1_000_000L + now }, monotonic = { now })
        fun st(ch: Int, off: Int, len: Int?, playing: Boolean = true) =
            VoiceUiState(bookId = 7, position = Locator(ch, off), chapterLength = len, playWhenReady = playing, isPlaying = playing)

        tracker.onState(st(0, 80, 100))             // abre tramo en (0,80); el cap 0 mide 100
        now = 1_000; tracker.onState(st(3, 10, 400)) // salto de +3 capitulos: (100-80) + 10 = 30
        now = 2_000; tracker.onState(st(3, 10, 400, playing = false))
        // Antes sumaba 0 al saltar mas de un capitulo; ahora al menos el resto del previo + el offset.
        assertThat(stats.records.single().chars).isEqualTo(30)
    }

    @Test
    fun guardarPosicionDeLibroBorradoNoTumba() = runTest(UnconfinedTestDispatcher()) {
        val playback = FakePlaybackPort()
        val library = object : com.lectorvoz.core.data.library.LibraryRepository by FakeLibraryRepository() {
            override suspend fun updateProgress(id: Long, locator: Locator, progress: Float, finished: Boolean?) = error("FOREIGN KEY")
        }
        val tracker = ListeningTracker(this, playback, library, FakeStats(), progressOf = { _, _ -> 0.5f }, monotonic = { 0L })
        tracker.onState(VoiceUiState(bookId = 3, position = Locator(1, 1), playWhenReady = true, isPlaying = true))
        tracker.onState(VoiceUiState(bookId = 3, position = Locator(1, 2), playWhenReady = false))
    }

    // ------------------------------------------------------------------ A2

    private fun graph(scope: kotlinx.coroutines.CoroutineScope, playback: FakePlaybackPort, library: FakeLibraryRepository, bookmarks: FakeBookmarks, settings: FakeSettingsRepository = FakeSettingsRepository()) =
        AppGraph(
            context = context,
            appScope = scope,
            settings = settings,
            library = library,
            bookmarks = bookmarks,
            pronunciation = FakePronunciation(),
            stats = FakeStats(),
            collections = FakeCollectionRepository(),
            playback = playback,
            voices = FakeVoices(),
            exportFiles = RecordingFiles(),
        )

    @Test
    fun marcarDesdeLaNotificacionConLibroBorradoNoTumbaNiParaElRecolector() = runTest(UnconfinedTestDispatcher()) {
        val playback = FakePlaybackPort()
        val library = FakeLibraryRepository(listOf(book(7), book(8)))
        val bookmarks = FakeBookmarks()
        val g = graph(backgroundScope, playback, library, bookmarks)
        g.start()
        playback.mutable.value = VoiceUiState(bookId = 7, position = Locator(0, 5), playWhenReady = true, isPlaying = true)
        // Insercion que falla (clave ajena): se registra y el recolector sigue vivo.
        bookmarks.missing += 7
        playback.bookmarkFlow.emit(Locator(0, 5))
        bookmarks.missing.clear()
        playback.bookmarkFlow.emit(Locator(0, 6))
        assertThat(bookmarks.added.map { it.second }).containsExactly(Locator(0, 6))
        // Libro que ya no esta en la biblioteca: no se intenta insertar.
        library.list.value = listOf(book(8))
        playback.bookmarkFlow.emit(Locator(0, 7))
        assertThat(bookmarks.added).hasSize(1)
    }

    @Test
    fun borrarElLibroQueSuenaParaLaVoz() = runTest(UnconfinedTestDispatcher()) {
        val playback = FakePlaybackPort()
        val library = FakeLibraryRepository(listOf(book(7), book(8)))
        val settings = FakeSettingsRepository()
        settings.updateOverrides(7) { BookOverrides(speed = 1.4f) }
        val g = graph(backgroundScope, playback, library, FakeBookmarks(), settings)
        playback.mutable.value = VoiceUiState(bookId = 7, playWhenReady = true, isPlaying = true)
        g.deleteBook(book(7))
        // Para la voz (M1): olvida el libro y limpia el estado (bookId a null).
        assertThat(playback.calls).contains("stop")
        assertThat(playback.state.value.bookId).isNull()
        assertThat(library.list.value.map { it.id }).containsExactly(8L)
        assertThat(settings.overrides(7).first()).isEqualTo(BookOverrides.NONE)
        // Otro libro: no se toca la voz.
        playback.calls.clear()
        g.deleteBook(book(8))
        assertThat(playback.calls).doesNotContain("stop")
    }

    // El libro se abre con IO real (Dispatchers.IO), incompatible con el planificador de
    // pruebas; este caso corre con hilos reales (runBlocking + ambito de app real).
    @Test
    fun borrarElLibroQueSuenaSueltaLaLicenciaYLimpiaElEstado() = kotlinx.coroutines.runBlocking {
        val playback = FakePlaybackPort()
        val b = epubBook(7)
        val library = FakeLibraryRepository(listOf(b))
        val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        try {
            val g = graph(appScope, playback, library, FakeBookmarks())
            g.start()
            // La voz abre el libro (una referencia de SharedBooks).
            g.speech.providerFor(b)
            assertThat(g.books.openCount).isEqualTo(1)
            playback.mutable.value = VoiceUiState(bookId = 7, playWhenReady = true, isPlaying = true)
            g.deleteBook(b)
            // Al quedar bookId null, el colector llama a releaseAll y se cierra el fichero.
            waitUntil { g.books.openCount == 0 }
            assertThat(playback.state.value.bookId).isNull()
            assertThat(g.books.openCount).isEqualTo(0)
        } finally {
            appScope.cancel()
        }
    }

    // ------------------------------------------------------------------ A3, M12

    @Test
    fun pantallaEncendidaSoloSiSePideYConVozOToques() {
        assertThat(shouldKeepScreenOn(setting = false, playing = true, sinceTouchMs = 0)).isFalse()
        assertThat(shouldKeepScreenOn(true, playing = false, sinceTouchMs = 1_000)).isTrue()
        assertThat(shouldKeepScreenOn(true, playing = false, sinceTouchMs = SCREEN_IDLE_MS)).isFalse()
        // Con la voz sonando la pantalla se mantiene aunque haga rato del ultimo toque.
        assertThat(shouldKeepScreenOn(true, playing = true, sinceTouchMs = SCREEN_IDLE_MS * 10)).isTrue()
    }

    @Test
    fun deslizadorPorCaracteresYNoPorCapitulos() {
        // Capitulos de 100, 900 y 0 caracteres: el 50 % cae en el capitulo 1, offset 400.
        val lens = mapOf(0 to 100, 1 to 900, 2 to 0)
        assertThat(locatorForFraction(0.5f, 3) { lens[it] }).isEqualTo(Locator(1, 400))
        assertThat(locatorForFraction(0.05f, 3) { lens[it] }).isEqualTo(Locator(0, 50))
        assertThat(locatorForFraction(1f, 3) { lens[it] }.chapter).isEqualTo(2)
        // Sin longitudes (PDF): pagina proporcional.
        assertThat(locatorForFraction(0.5f, 10) { null }).isEqualTo(Locator(5, 0))
        assertThat(locatorForFraction(0.5f, 0) { null }).isEqualTo(Locator.START)
    }

    // ------------------------------------------------------------------ A4

    private class FakeSink : PcmSink {
        var opened = 0
        var written = 0L
        var released = false
        override fun open(sampleRate: Int) { opened = sampleRate }
        override fun write(data: FloatArray, offset: Int, length: Int): Int { written += length; return length }
        override fun playedFrames(): Long = written
        override fun setVolume(volume: Float) = Unit
        override fun setPitch(pitch: Float) = true
        override fun release() { released = true }
    }

    private class FakeFocus(val grant: Boolean = true) : PreviewFocus {
        val log = ArrayList<String>()
        override fun request(): Boolean { log += "request"; return grant }
        override fun abandon() { log += "abandon" }
    }

    @Test
    fun probarVozNoTocaElReproductorDelLibro() = runTest {
        val rendered = ArrayList<String>()
        val renderer = object : UtteranceRenderer {
            override suspend fun render(u: Utterance, settings: VoiceSettings): RenderedAudio {
                rendered += u.spokenText
                return RenderedAudio(FloatArray(100), 10, 22_050)
            }
        }
        val sink = FakeSink()
        val focus = FakeFocus()
        var system = 0
        val previewer = VoicePreviewer({ renderer }, focus, { _, _ -> system++ }, { sink }, io = Dispatchers.Unconfined, pollMs = 1)
        assertThat(previewer.preview(VoiceSettings(), "Hola. Esta es la voz.")).isNull()
        assertThat(rendered).hasSize(2)
        assertThat(sink.opened).isEqualTo(22_050)
        assertThat(sink.written).isEqualTo(220)
        assertThat(sink.released).isTrue()
        // Foco transitorio pedido y devuelto: el libro se pausa y sigue solo.
        assertThat(focus.log).containsExactly("request", "abandon").inOrder()
        assertThat(system).isEqualTo(0)
        // Voz del sistema: motor efimero, sin el renderizador.
        assertThat(previewer.preview(VoiceSettings(engine = TtsEngineType.SYSTEM), "Hola.")).isNull()
        assertThat(system).isEqualTo(1)
        assertThat(rendered).hasSize(2)
        // Sin foco no suena nada.
        val denied = VoicePreviewer({ renderer }, FakeFocus(grant = false), { _, _ -> system++ }, { sink }, io = Dispatchers.Unconfined)
        assertThat(denied.preview(VoiceSettings(), "Hola.")).isNotNull()
        assertThat(rendered).hasSize(2)
    }

    // ------------------------------------------------------------------ A6

    @Test
    fun importarDesdeFueraSobreviveALaActividadYSiempreCopia() = runTest(UnconfinedTestDispatcher()) {
        val library = FakeLibraryRepository()
        val imports = ImportCoordinator(backgroundScope, library)
        imports.importExternal(listOf(Uri.parse("content://x/uno.pdf")))
        // Nadie recogia el resultado (actividad recreandose): espera en el canal.
        val r = imports.results.first()
        assertThat(r.openBookId).isEqualTo(1000L)
        assertThat(r.failed).isEqualTo(0)
        assertThat(library.imported.single().second).isTrue()
        library.results["content://x/mal.pdf"] = FakeLibraryRepository.failed("mal.pdf")
        imports.importExternal(listOf(Uri.parse("content://x/mal.pdf"), Uri.parse("content://x/dos.pdf")))
        val r2 = imports.results.first()
        assertThat(r2.failed).isEqualTo(1)
        assertThat(r2.openBookId).isNull()
        assertThat(imports.running.value).isEqualTo(0)
    }

    // ------------------------------------------------------------------ M5, B1, M6

    @Test
    fun cambiarVelocidadNoRehaceElTroceo() = runTest {
        val epub = EpubBook.open(epubFile())
        try {
            val p = UtteranceProviderImpl(BookContent.Epub(epub), VoiceSettings(announceChapters = false))
            val first = p.at(Locator.START)!!
            assertThat(p.update(VoiceSettings(announceChapters = false, speed = 1.5f, pauseSentenceMs = 900), emptyList())).isFalse()
            assertThat(p.at(Locator.START)).isSameInstanceAs(first)
            assertThat(p.update(VoiceSettings(announceChapters = false), listOf(PronunciationEntry(pattern = "noche", replacement = "nocturno")))).isTrue()
            assertThat(p.at(Locator.START)).isNotSameInstanceAs(first)
            // Ids estables de :core:text (base por capitulo): siguiente y anterior cruzan capitulos.
            var u = p.at(Locator.START)
            val all = ArrayList<Utterance>()
            while (u != null) { all += u; u = p.next(u) }
            assertThat(all.map { it.id }.toSet()).hasSize(all.size)
            assertThat(p.previous(all.first { it.range.start.chapter == 1 })!!.range.start.chapter).isEqualTo(0)
            assertThat(p.rangeAt(Locator(0, 20))).isNotNull()
            assertThat(p.progressAt(Locator(1, 0))).isGreaterThan(0f)
        } finally {
            epub.close()
        }
    }

    @Test
    fun librosCompartidosPorRecuento() = runTest {
        val shared = SharedBooks(BookOpener(context))
        val b = epubBook()
        val l1 = shared.acquire(b, true)
        val l2 = shared.acquire(b, true)
        assertThat(l2.opened).isSameInstanceAs(l1.opened)
        assertThat(shared.openCount).isEqualTo(1)
        l1.release()
        l1.release() // segunda vez: no hace nada
        assertThat(shared.openCount).isEqualTo(1)
        l2.release()
        assertThat(shared.openCount).isEqualTo(0)
    }

    // ------------------------------------------------------------------ M8 y exportar libro

    private class RecordingFiles : ExportFiles {
        val created = ArrayList<String>()
        val deleted = ArrayList<Uri>()
        override fun create(tree: Uri, name: String): Uri { created += name; return Uri.parse("content://destino/${created.size}") }
        override fun delete(uri: Uri) { deleted += uri }
    }

    @Test
    fun exportarSeCancelaBorrandoElFicheroYSoltandoElLibro() = runTest(UnconfinedTestDispatcher()) {
        val playback = FakePlaybackPort().apply { exportGate = CompletableDeferred() }
        val files = RecordingFiles()
        val shared = SharedBooks(BookOpener(context))
        val m = ExportManager(backgroundScope, playback, shared, FakeSettingsRepository(), FakePronunciation(), files)
        val b = epubBook()
        val dest = Uri.parse("content://destino/capitulo")
        m.exportPart(b, 0, dest)
        assertThat(m.state.value!!.running).isTrue()
        waitUntil { playback.exported.isNotEmpty() }
        assertThat(shared.openCount).isEqualTo(1)
        m.cancel()
        waitUntil { m.state.value?.running == false }
        assertThat(m.state.value!!.result).contains("cancelada")
        assertThat(files.deleted).containsExactly(dest)
        assertThat(shared.openCount).isEqualTo(0)
    }

    @Test
    fun exportarLibroCompletoUnFicheroPorParte() = runTest(UnconfinedTestDispatcher()) {
        val playback = FakePlaybackPort()
        val files = RecordingFiles()
        val m = ExportManager(backgroundScope, playback, SharedBooks(BookOpener(context)), FakeSettingsRepository(), FakePronunciation(), files)
        m.exportBook(epubBook(), Uri.parse("content://carpeta"))
        waitUntil { m.state.value?.running == false }
        assertThat(files.created).containsExactly("01 - Primero.wav", "02 - Segundo.wav").inOrder()
        assertThat(playback.exported.map { it.second }).containsExactly(0, 1).inOrder()
        assertThat(m.state.value!!.result).contains("2 ficheros")
    }

    @Test
    fun partesDePdfPorIndiceOPorTramos() {
        val sinIndice = ExportManager.pdfParts(45, emptyList())
        assertThat(sinIndice.map { it.chapters }).containsExactly(0..19, 20..39, 40..44).inOrder()
        val toc = listOf(TocEntry("Uno", Locator(2, 0)), TocEntry("Dos", Locator(10, 0)))
        val porIndice = ExportManager.pdfParts(30, toc)
        assertThat(porIndice.map { it.title }).containsExactly("Inicio", "Uno", "Dos").inOrder()
        assertThat(porIndice.map { it.chapters }).containsExactly(0..1, 2..9, 10..29).inOrder()
    }

    @Test
    fun partesDePdfCasosLimite() {
        // Primer marcador en la pagina 0 (muy comun): sin tramo "Inicio".
        val tocDesde0 = listOf(TocEntry("Uno", Locator(0, 0)), TocEntry("Dos", Locator(8, 0)))
        val desde0 = ExportManager.pdfParts(20, tocDesde0)
        assertThat(desde0.map { it.title }).containsExactly("Uno", "Dos").inOrder()
        assertThat(desde0.map { it.chapters }).containsExactly(0..7, 8..19).inOrder()
        // Un solo marcador util (< 2): cae a tramos por paginas.
        val unoSolo = ExportManager.pdfParts(25, listOf(TocEntry("Cap", Locator(3, 0))))
        assertThat(unoSolo.map { it.chapters }).containsExactly(0..19, 20..24).inOrder()
        // Marcadores fuera de rango o duplicados: se ignoran / deduplican.
        val sucio = ExportManager.pdfParts(
            10,
            listOf(TocEntry("A", Locator(2, 0)), TocEntry("A2", Locator(2, 0)), TocEntry("Fuera", Locator(99, 0)), TocEntry("B", Locator(5, 0))),
        )
        assertThat(sucio.map { it.title }).containsExactly("Inicio", "A", "B").inOrder()
        assertThat(sucio.map { it.chapters }).containsExactly(0..1, 2..4, 5..9).inOrder()
    }

    @Test
    fun proveedorAgrupadoRetrocedeEntrePartes() = runTest {
        // previous() desde la primera frase de una parte vuelve a la ultima de la anterior.
        fun utt(ch: Int) = Utterance(ch.toLong() shl 40, LocatorRange(Locator(ch, 0), Locator(ch, 5)), "p$ch", "p$ch", IntArray(2) { it })
        val inner = object : UtteranceProvider {
            override val chapterCount = 3
            override suspend fun at(locator: Locator) = utt(locator.chapter)
            override suspend fun next(current: Utterance) = ((current.id ushr 40).toInt() + 1).takeIf { it < 3 }?.let(::utt)
            override suspend fun previous(current: Utterance) = ((current.id ushr 40).toInt() - 1).takeIf { it >= 0 }?.let(::utt)
        }
        val g = GroupedProvider(inner, listOf(0..1, 2..2))
        val parte2 = g.at(Locator(1, 0))!! // "p2", capitulo real 2
        assertThat(parte2.displayText).isEqualTo("p2")
        val anterior = g.previous(parte2)!!
        assertThat(anterior.displayText).isEqualTo("p1")
        assertThat(anterior.range.start.chapter).isEqualTo(0) // mapeado a la parte 0
    }

    @Test
    fun proveedorAgrupadoParaElExportador() = runTest {
        // Tres "paginas" con una frase cada una, agrupadas en [0..1] y [2..2].
        fun utt(ch: Int) = Utterance(ch.toLong() shl 40, LocatorRange(Locator(ch, 0), Locator(ch, 5)), "p$ch", "p$ch", IntArray(2) { it })
        val inner = object : UtteranceProvider {
            override val chapterCount = 3
            override suspend fun at(locator: Locator) = utt(locator.chapter)
            override suspend fun next(current: Utterance) = ((current.id ushr 40).toInt() + 1).takeIf { it < 3 }?.let(::utt)
            override suspend fun previous(current: Utterance) = ((current.id ushr 40).toInt() - 1).takeIf { it >= 0 }?.let(::utt)
        }
        val g = GroupedProvider(inner, listOf(0..1, 2..2))
        val a = g.at(Locator(0, 0))!!
        assertThat(a.range.start.chapter).isEqualTo(0)
        val b = g.next(a)!!
        assertThat(b.displayText).isEqualTo("p1")
        assertThat(b.range.start.chapter).isEqualTo(0)
        val c = g.next(b)!!
        assertThat(c.range.start.chapter).isEqualTo(1) // el exportador para aqui
        assertThat(g.at(Locator(1, 0))!!.displayText).isEqualTo("p2")
    }

    // ------------------------------------------------------------------ velocidad desde Media3

    @Test
    fun velocidadCambiadaDesdeFueraSeGuardaSinBucles() = runTest(UnconfinedTestDispatcher()) {
        val playback = FakePlaybackPort()
        val settings = FakeSettingsRepository()
        val applied = ArrayList<VoiceSettings>()
        val sync = VoiceSettingsSync(backgroundScope, playback, settings, FakePronunciation()) { v, _ -> applied += v }
        sync.start()
        val pushes = playback.calls.count { it == "settings" }
        assertThat(pushes).isEqualTo(1)
        // El mando Media3 cambia la velocidad: se guarda en los ajustes globales.
        playback.active.value = playback.active.value.copy(speed = 1.6f)
        assertThat(settings.voiceState.value.speed).isEqualTo(1.6f)
        // Se reenvia una vez el valor guardado y no hay mas escrituras (sin bucle).
        assertThat(playback.calls.count { it == "settings" }).isEqualTo(pushes + 1)
        // Con un libro con velocidad propia, se guarda en el libro.
        settings.updateOverrides(7) { BookOverrides(speed = 1.2f) }
        playback.mutable.value = VoiceUiState(bookId = 7, playWhenReady = true)
        playback.active.value = playback.active.value.copy(speed = 0.9f)
        assertThat(settings.overrides(7).first().speed).isEqualTo(0.9f)
        assertThat(settings.voiceState.value.speed).isEqualTo(1.6f)
        assertThat(applied.last().speed).isEqualTo(0.9f)
    }
}
