package com.lectorvoz.app.ocr

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lectorvoz.app.fakes.FakeLibraryRepository
import com.lectorvoz.app.fakes.book
import com.lectorvoz.core.model.BookFormat
import com.lectorvoz.core.model.Locator
import com.lectorvoz.format.pdf.PageSize
import com.lectorvoz.format.pdf.PdfBackend
import com.lectorvoz.format.pdf.PdfBook
import com.lectorvoz.format.pdf.PdfBookmark
import com.lectorvoz.format.pdf.PdfMeta
import com.lectorvoz.format.pdf.RawPageText
import com.lectorvoz.format.pdf.ocr.OcrStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.util.Collections

/** OCR de fondo y al momento, con un reconocedor falso. */
@RunWith(AndroidJUnit4::class)
class OcrManagerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    /** [count] paginas; las de [withText] traen texto propio, el resto son escaneadas. */
    private class Backend(override val pageCount: Int, val withText: Set<Int>) : PdfBackend {
        override fun pageSizePoints(index: Int) = PageSize(400f, 600f)
        override fun meta() = PdfMeta(null, null, null)
        override fun bookmarks() = emptyList<PdfBookmark>()
        override fun render(index: Int, bitmap: Bitmap, startX: Int, startY: Int, drawWidth: Int, drawHeight: Int) = Unit
        override fun close() = Unit
        override fun pageText(index: Int): RawPageText {
            val t = if (index in withText) "Texto propio de la pagina $index del libro" else ""
            return RawPageText(t, FloatArray(t.length * 4))
        }
    }

    private class Conditions(var ok: Boolean = true) : DeviceConditions {
        override fun charging() = true
        override fun good() = ok
    }

    private val recognized: MutableList<Pair<Int, Boolean>> = Collections.synchronizedList(ArrayList())
    private val audioRemoved: MutableList<Long> = Collections.synchronizedList(ArrayList())

    private fun manager(library: FakeLibraryRepository, pdf: PdfBook, store: OcrStore, conditions: DeviceConditions = Conditions()) =
        OcrManager(
            context, scope, library,
            openPdf = { pdf to {} },
            store = store,
            recognizer = PageRecognizer { _, i, _, urgent ->
                recognized += i to urgent
                val t = "Pagina reconocida $i"
                RawPageText(t, FloatArray(t.length * 4))
            },
            removeAudio = { audioRemoved += it },
            conditions = conditions,
            conditionsPollMs = 50,
        )

    private fun pdfBook(b: com.lectorvoz.core.data.library.Book) = b.copy(format = BookFormat.PDF)

    private fun waitUntil(what: () -> Boolean) = runBlocking { withTimeout(5_000) { while (!what()) delay(10) } }

    @Test
    fun deFondoReconocePorDelanteDeLaPosicionYSaltaLasPaginasConTexto() {
        val b = pdfBook(book(1, opened = 5)).copy(locator = Locator(2, 0))
        val library = FakeLibraryRepository(listOf(b))
        val store = OcrStore(tmp.root)
        // Pagina 0 con texto (la muestra no es todo texto: hay escaneadas).
        val pdf = PdfBook.create(Backend(6, withText = setOf(0, 4)), Dispatchers.Unconfined, "x")
        val m = manager(library, pdf, store)
        m.start()
        m.setAppVisible(true)
        m.setReaderBook(1)
        waitUntil { store.recognizedPages(1) == setOf(2, 3, 5) }
        // Desde la pagina 2 (posicion) en adelante, de fondo; la 4 trae texto: no se reconoce.
        assertThat(recognized.map { it.first }).containsExactly(2, 3, 5).inOrder()
        assertThat(recognized.all { !it.second }).isTrue()
        assertThat(store.nativePages(1)).contains(4)
    }

    @Test
    fun pdfConTextoNoGastaNada() {
        val b = pdfBook(book(1, opened = 5))
        val library = FakeLibraryRepository(listOf(b))
        val store = OcrStore(tmp.root)
        val pdf = PdfBook.create(Backend(20, withText = (0 until 20).toSet()), Dispatchers.Unconfined, "x")
        val m = manager(library, pdf, store)
        m.start()
        m.setAppVisible(true)
        m.setReaderBook(1)
        waitUntil { store.isTextual(1) }
        assertThat(recognized).isEmpty()
    }

    @Test
    fun sinAppNiVozNoTrabajaYConMalasCondicionesEspera() {
        val b = pdfBook(book(1, opened = 5))
        val library = FakeLibraryRepository(listOf(b))
        val store = OcrStore(tmp.root)
        val pdf = PdfBook.create(Backend(4, withText = emptySet()), Dispatchers.Unconfined, "x")
        val cond = Conditions(ok = false)
        val m = manager(library, pdf, store, cond)
        m.start()
        m.setReaderBook(1)
        runBlocking { delay(200) }
        assertThat(recognized).isEmpty() // app no visible
        m.setAppVisible(true)
        runBlocking { delay(200) }
        assertThat(recognized).isEmpty() // bateria baja / caliente
        cond.ok = true
        waitUntil { recognized.size == 4 }
    }

    @Test
    fun alMomentoReconoceUnaVezYLuegoLeeDelDisco() = runBlocking {
        val b = pdfBook(book(1))
        val library = FakeLibraryRepository(listOf(b))
        val store = OcrStore(tmp.root)
        val pdf = PdfBook.create(Backend(3, withText = emptySet()), Dispatchers.Unconfined, "x")
        val m = manager(library, pdf, store)
        pdf.textFallback = m.fallbackFor(b)
        assertThat(pdf.pageText(1).rawText).isEqualTo("Pagina reconocida 1")
        assertThat(recognized).containsExactly(1 to true)
        // Otra instancia del libro (reabierto): sale del disco, sin reconocer de nuevo.
        val again = PdfBook.create(Backend(3, withText = emptySet()), Dispatchers.Unconfined, "x")
        again.textFallback = m.fallbackFor(b)
        assertThat(again.pageText(1).rawText).isEqualTo("Pagina reconocida 1")
        assertThat(recognized).hasSize(1)
        // Desactivado: lo guardado se sigue usando, pero no se reconoce nada nuevo.
        m.setEnabled(false)
        assertThat(again.pageText(2).rawText).isEmpty()
        assertThat(recognized).hasSize(1)
    }

    @Test
    fun alTerminarOQuitarElLibroSeLiberaTextoYAudio() = runBlocking {
        val b = pdfBook(book(1))
        val library = FakeLibraryRepository(listOf(b, pdfBook(book(2))))
        val store = OcrStore(tmp.root)
        store.write(1, 0, RawPageText("a", FloatArray(4)))
        store.write(2, 0, RawPageText("b", FloatArray(4)))
        val m = manager(library, PdfBook.create(Backend(1, emptySet()), Dispatchers.Unconfined, "x"), store)
        m.finished(b, finished = true)
        assertThat(store.books()).containsExactly(2L)
        m.finished(pdfBook(book(2)), finished = false)
        assertThat(store.books()).containsExactly(2L)
        m.deleted(pdfBook(book(2)))
        assertThat(store.books()).isEmpty()
        assertThat(audioRemoved).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun alArrancarBorraLoDeLibrosQueYaNoEstan() {
        val library = FakeLibraryRepository(listOf(pdfBook(book(1))))
        val store = OcrStore(tmp.root)
        store.write(1, 0, RawPageText("a", FloatArray(4)))
        store.write(9, 0, RawPageText("b", FloatArray(4)))
        val m = manager(library, PdfBook.create(Backend(1, emptySet()), Dispatchers.Unconfined, "x"), store)
        m.start()
        waitUntil { store.books() == listOf(1L) }
    }
}
