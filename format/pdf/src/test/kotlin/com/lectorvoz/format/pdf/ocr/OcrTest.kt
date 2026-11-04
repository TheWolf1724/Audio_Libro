package com.lectorvoz.format.pdf.ocr

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lectorvoz.format.pdf.PageSize
import com.lectorvoz.format.pdf.PageTextFallback
import com.lectorvoz.format.pdf.PdfBackend
import com.lectorvoz.format.pdf.PdfBook
import com.lectorvoz.format.pdf.PdfBookmark
import com.lectorvoz.format.pdf.PdfMeta
import com.lectorvoz.format.pdf.RawPageText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Texto reconocido: guardado en disco y uso en las paginas sin texto propio. */
@RunWith(AndroidJUnit4::class)
class OcrTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Pagina 0 con texto propio; pagina 1 escaneada (sin texto). */
    private class Backend : PdfBackend {
        var textCalls = 0
        override val pageCount = 2
        override fun pageSizePoints(index: Int) = PageSize(400f, 600f)
        override fun meta() = PdfMeta(null, null, null)
        override fun bookmarks() = emptyList<PdfBookmark>()
        override fun render(index: Int, bitmap: Bitmap, startX: Int, startY: Int, drawWidth: Int, drawHeight: Int) = Unit
        override fun close() = Unit
        override fun pageText(index: Int): RawPageText {
            textCalls++
            val t = if (index == 0) "Esta pagina trae su propio texto." else " 12 "
            return RawPageText(t, FloatArray(t.length * 4))
        }
    }

    private fun recognized(): RawPageText {
        val t = "Hola mundo\n\nOtra linea"
        val boxes = FloatArray(t.length * 4)
        for (i in t.indices) if (t[i] != '\n' && t[i] != ' ') { boxes[i * 4] = 10f + i; boxes[i * 4 + 1] = 20f; boxes[i * 4 + 2] = 11f + i; boxes[i * 4 + 3] = 30.5f }
        return RawPageText(t, boxes)
    }

    @Test
    fun guardaYLeeLasPaginasConSusCajas() {
        val store = OcrStore(tmp.root)
        val page = recognized()
        store.write(7, 3, page)
        val back = store.read(7, 3)!!
        assertThat(back.text).isEqualTo(page.text)
        // Cajas con resolucion de 1/8 de punto.
        for (i in page.boxes.indices) assertThat(back.boxes[i]).isWithin(0.07f).of(page.boxes[i])
        assertThat(store.recognizedPages(7)).containsExactly(3)
        store.markNative(7, listOf(0, 1))
        store.markNative(7, listOf(5))
        assertThat(store.nativePages(7)).containsExactly(0, 1, 5)
        assertThat(store.books()).containsExactly(7L)
        assertThat(store.sizeBytes(7)).isGreaterThan(0L)
        store.deleteBook(7)
        assertThat(store.read(7, 3)).isNull()
        assertThat(store.books()).isEmpty()
    }

    @Test
    fun ficheroDanadoSeDescarta() {
        val store = OcrStore(tmp.root)
        store.write(1, 0, recognized())
        java.io.File(tmp.root, "1/p0.ocr").writeText("basura")
        assertThat(store.read(1, 0)).isNull()
        assertThat(store.has(1, 0)).isFalse()
    }

    @Test
    fun soloLasPaginasSinTextoUsanElOcr() = runTest {
        val backend = Backend()
        val book = PdfBook.create(backend, Dispatchers.Unconfined, "x")
        var asked = 0
        var ready = false
        book.textFallback = PageTextFallback { _, index, wait -> asked++; if (index == 1 && (wait || ready)) recognized() else null }
        assertThat(book.pageText(0).rawText).isEqualTo("Esta pagina trae su propio texto.")
        assertThat(asked).isEqualTo(0)
        // Pasando paginas no se espera al OCR, y lo provisional no se cachea.
        assertThat(book.pageText(1, wait = false).rawText).isEqualTo(" 12 ")
        ready = true
        assertThat(book.pageText(1, wait = false).rawText).isEqualTo("Hola mundo\n\nOtra linea")
        book.invalidateText(1)
        asked = 0
        ready = false
        val scanned = book.pageText(1)
        assertThat(scanned.rawText).isEqualTo("Hola mundo\n\nOtra linea")
        assertThat(scanned.pageSize).isEqualTo(PageSize(400f, 600f))
        // El flujo para la voz separa los dos parrafos.
        assertThat(scanned.flow.text).contains("Hola mundo")
        assertThat(scanned.rectsForRange(0, 4)).isNotEmpty()
        // Cacheado: no se vuelve a pedir.
        book.pageText(1)
        assertThat(asked).isEqualTo(1)
        // Sin cache (el OCR la acaba de reconocer): se vuelve a pedir.
        book.invalidateText(1)
        book.pageText(1)
        assertThat(asked).isEqualTo(2)
        assertThat(book.hasOwnText(0)).isTrue()
        assertThat(book.hasOwnText(1)).isFalse()
    }

    @Test
    fun forzarOcrUsaElOcrAunConTextoPropio() = runTest {
        val backend = Backend()
        val book = PdfBook.create(backend, Dispatchers.Unconfined, "x")
        var asked = 0
        book.textFallback = PageTextFallback { _, _, _ -> asked++; recognized() }
        // Sin forzar: la pagina 0 trae su texto y no se pide OCR.
        assertThat(book.pageText(0).rawText).isEqualTo("Esta pagina trae su propio texto.")
        assertThat(asked).isEqualTo(0)
        assertThat(book.hasOwnText(0)).isTrue()
        // Forzando el OCR: el texto de la pagina 0 pasa a venir del reconocimiento.
        book.forceFallback = true
        assertThat(book.pageText(0).rawText).isEqualTo("Hola mundo\n\nOtra linea")
        assertThat(asked).isEqualTo(1)
        // Con el OCR forzado ninguna pagina cuenta como "con texto propio".
        assertThat(book.hasOwnText(0)).isFalse()
        // Cambiar el flag olvida lo cacheado: al quitarlo vuelve el texto propio.
        book.forceFallback = false
        assertThat(book.pageText(0).rawText).isEqualTo("Esta pagina trae su propio texto.")
    }

    @Test
    fun forzarOcrEntreElCalculoYLaCacheNoDejaLaPaginaSinOcr() = runTest {
        // M1: el setter de forceFallback se intercala entre el calculo del texto (fuera del
        // candado) y su insercion. Sin la correccion, la pagina quedaba cacheada SIN OCR pese a
        // exigirse; con ella no se cachea una pagina incoherente y la siguiente peticion la rehace.
        val backend = Backend()
        val book = PdfBook.create(backend, Dispatchers.Unconfined, "x")
        book.textFallback = PageTextFallback { _, _, _ -> recognized() }
        // Simula el cambio de flag justo antes de insertar en la cache (la ventana de la carrera).
        book.onBeforeCacheForTest = { book.forceFallback = true; book.onBeforeCacheForTest = null }
        // Esta peticion decidio "sin OCR" (la pagina trae texto): devuelve provisional su texto...
        assertThat(book.pageText(0).rawText).isEqualTo("Esta pagina trae su propio texto.")
        // ...pero no lo cachea: la siguiente peticion recalcula con el flag nuevo y da el OCR.
        assertThat(book.pageText(0).rawText).isEqualTo("Hola mundo\n\nOtra linea")
        assertThat(book.hasOwnText(0)).isFalse()
    }

    @Test
    fun idiomaDelModelo() {
        assertThat(OcrEngine.languageFor("en-GB")).isEqualTo("eng")
        assertThat(OcrEngine.languageFor("es")).isEqualTo("spa")
        assertThat(OcrEngine.languageFor(null)).isEqualTo("spa")
        assertThat(PdfBook.hasReadableText(" 12 ")).isFalse()
        assertThat(PdfBook.hasReadableText("Capitulo primero del libro")).isTrue()
    }
}
