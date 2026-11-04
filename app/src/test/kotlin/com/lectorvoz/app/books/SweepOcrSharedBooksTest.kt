package com.lectorvoz.app.books

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lectorvoz.app.fakes.book
import com.lectorvoz.app.integration.BookContent
import com.lectorvoz.app.integration.PdfCleanerAdapter
import com.lectorvoz.core.data.library.Book
import com.lectorvoz.core.model.BookFormat
import com.lectorvoz.format.pdf.PageSize
import com.lectorvoz.format.pdf.PdfBackend
import com.lectorvoz.format.pdf.PdfBook
import com.lectorvoz.format.pdf.PdfBookmark
import com.lectorvoz.format.pdf.PdfMeta
import com.lectorvoz.format.pdf.RawPageText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Regresion del sweep 1 (A1): "Leer este PDF con OCR" debe llegar a la instancia COMPARTIDA del
 * PDF (la que usan la voz, la busqueda y la exportacion), tambien cuando se activa con la voz
 * parada. [SharedBooks.acquire] reaplica la preferencia por libro al reutilizar un `Entry`.
 */
@RunWith(AndroidJUnit4::class)
class SweepOcrSharedBooksTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Pagina con texto propio (pdfium incompleto); el OCR daria otra cosa. */
    private class FakeBackend : PdfBackend {
        override val pageCount = 1
        override fun pageSizePoints(index: Int) = PageSize(200f, 300f)
        override fun meta() = PdfMeta(null, null, null)
        override fun bookmarks() = emptyList<PdfBookmark>()
        override fun render(index: Int, bitmap: Bitmap, startX: Int, startY: Int, drawWidth: Int, drawHeight: Int) = Unit
        override fun close() = Unit
        override fun pageText(index: Int): RawPageText {
            val t = "Texto propio del PDF."
            return RawPageText(t, FloatArray(t.length * 4))
        }
    }

    /** Opener de prueba: abre PDF con un backend falso (pdfium no carga en la JVM). */
    private class FakeOpener(context: Context) : BookOpener(context) {
        override suspend fun open(book: Book, skipPdfHeaders: Boolean): OpenedBook {
            val pdf = PdfBook.create(FakeBackend(), Dispatchers.Unconfined, "x")
            pdf.forceFallback = pdfForceOcr?.invoke(book) ?: false
            return OpenedBook(book, BookContent.Pdf(pdf, PdfCleanerAdapter(pdf, skipPdfHeaders)), emptyList())
        }
    }

    @Test
    fun activarOcrConLaVozParadaLlegaALaInstanciaCompartida() = runTest {
        var forceOcr = false
        val opener = FakeOpener(context).apply { pdfForceOcr = { forceOcr } }
        val shared = SharedBooks(opener)
        val b = book(7, format = BookFormat.PDF)

        // 1. La busqueda (o el OcrManager) abre la instancia compartida con el OCR desactivado.
        val search = shared.acquire(b, true)
        val pdf = (search.opened.content as BookContent.Pdf).book
        assertThat(pdf.forceFallback).isFalse()

        // 2. El usuario activa "Leer este PDF con OCR" con la voz PARADA: cambia la preferencia.
        forceOcr = true

        // 3. La voz pulsa Reproducir: SpeechBooks.providerFor -> acquire REUTILIZA el Entry.
        val voice = shared.acquire(b, true)
        assertThat(voice.opened).isSameInstanceAs(search.opened)

        // La instancia compartida acaba con forceFallback=true (antes se quedaba en false).
        assertThat(pdf.forceFallback).isTrue()

        // Y al desactivarlo, una nueva adquisicion lo vuelve a quitar.
        forceOcr = false
        val again = shared.acquire(b, true)
        assertThat(again.opened).isSameInstanceAs(search.opened)
        assertThat(pdf.forceFallback).isFalse()

        search.release(); voice.release(); again.release()
    }

    /**
     * S2-3: la preferencia "forzar OCR" (lectura suspendida de DataStore) se lee FUERA de
     * `openLock`. Si se leyera bajo el candado, una lectura lenta al reutilizar un libro
     * serializaria todas las demas adquisiciones; aqui se comprueba que, con esa lectura
     * bloqueada, otro libro distinto se puede adquirir sin esperar.
     */
    @Test
    fun laPreferenciaForzarOcrSeLeeFueraDelCandado() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val reusing = CompletableDeferred<Unit>()
        val blockBook1 = AtomicBoolean(false)
        val opener = FakeOpener(context).apply {
            pdfForceOcr = { b ->
                if (b.id == 1L && blockBook1.get()) { reusing.complete(Unit); gate.await() }
                false
            }
        }
        val shared = SharedBooks(opener)
        val b1 = book(1, format = BookFormat.PDF)
        val b2 = book(2, format = BookFormat.PDF)

        // El libro 1 queda abierto (la siguiente adquisicion sera un REUSO del mismo Entry).
        val hold1 = shared.acquire(b1, true)
        // A partir de ahora, leer la preferencia del libro 1 se queda esperando en el gate.
        blockBook1.set(true)
        val reuse = async(Dispatchers.Default) { shared.acquire(b1, true) }
        reusing.await() // el reuso esta bloqueado en la lectura de la preferencia

        // Con esa lectura bloqueada, adquirir OTRO libro no debe esperar: el candado esta libre.
        val lease2 = withTimeout(5_000) { shared.acquire(b2, true) }
        assertThat(lease2.opened.book.id).isEqualTo(2L)

        gate.complete(Unit)
        val reuseLease = reuse.await()
        hold1.release(); lease2.release(); reuseLease.release()
    }
}
