package com.lectorvoz.format.pdf

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.BookFormat
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.LocatorRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pruebas de la logica alrededor de pdfium con un [PdfBackend] falso: pdfium es una
 * libreria nativa de Android (bionic) y no se puede cargar en la JVM de Robolectric.
 * La apertura real, el render y los errores de contrasena/corrupcion solo se pueden
 * comprobar en un dispositivo.
 */
@RunWith(AndroidJUnit4::class)
class PdfTextTest {

    /** Pagina de 200x300 pt con dos lineas: "Hola mundo" (y 10..20) y "Adios" (y 30..40). */
    private class FakeBackend(override val pageCount: Int = 2) : PdfBackend {
        var closed = false
        val text = "Hola mundo\r\nAdios"

        override fun pageSizePoints(index: Int) = PageSize(200f, 300f)
        override fun meta() = PdfMeta(title = null, author = "Ana; Luis", subject = null)
        override fun bookmarks() = listOf(PdfBookmark("Uno", 0, listOf(PdfBookmark("", 1, emptyList()))))
        override fun render(index: Int, bitmap: Bitmap, startX: Int, startY: Int, drawWidth: Int, drawHeight: Int) = Unit
        override fun close() { closed = true }

        override fun pageText(index: Int): RawPageText {
            val boxes = FloatArray(text.length * 4)
            var x = 10f
            for (i in text.indices) {
                val ch = text[i]
                if (ch == '\r' || ch == '\n') { x = 10f; continue }
                val line = if (i < 10) 0 else 1
                val top = 10f + line * 20f
                boxes[i * 4] = x; boxes[i * 4 + 1] = top; boxes[i * 4 + 2] = x + 8f; boxes[i * 4 + 3] = top + 10f
                x += 8f
            }
            return RawPageText(text, boxes)
        }
    }

    private fun book(backend: PdfBackend = FakeBackend()) =
        PdfBook.create(backend, Dispatchers.Unconfined, "fichero")

    @Test
    fun metadatos_e_indice() {
        val b = book()
        assertThat(b.metadata.title).isEqualTo("fichero")
        assertThat(b.metadata.authors).containsExactly("Ana", "Luis").inOrder()
        assertThat(b.toc).hasSize(1)
        assertThat(b.toc[0].children[0].title).isEqualTo("Pagina 2")
        assertThat(b.toc[0].children[0].target).isEqualTo(Locator(1, 0))
    }

    @Test
    fun texto_limpio_conserva_longitud() = runTest {
        val src = book().textSource()
        assertThat(src.format).isEqualTo(BookFormat.PDF)
        assertThat(src.chapterCount).isEqualTo(2)
        val t = src.chapterText(0)
        assertThat(t.text).hasLength(FakeBackend().text.length)
        // Las dos lineas son un mismo parrafo: se unen con espacio (no hay corte de frase).
        assertThat(t.text).isEqualTo("Hola mundo  Adios")
        assertThat(src.flowText(0).text).isEqualTo("Hola mundo Adios")
    }

    @Test
    fun limpiador_rellena_con_espacios() = runTest {
        val src = book().textSource { listOf(12..16) }
        assertThat(src.chapterText(0).text).isEqualTo("Hola mundo       ")
        assertThat(src.flowText(0).text).isEqualTo("Hola mundo")
    }

    @Test
    fun rectangulos_por_linea() = runTest {
        val src = book().textSource()
        // "mundo\r\nAd" cruza dos lineas -> dos rectangulos.
        val rects = src.rectsFor(LocatorRange(Locator(0, 5), Locator(0, 14)))
        assertThat(rects.keys).containsExactly(0)
        val r = rects.getValue(0)
        assertThat(r).hasSize(2)
        assertThat(r[0].left).isEqualTo(10f + 5 * 8f)
        assertThat(r[0].right).isEqualTo(10f + 10 * 8f)
        assertThat(r[1].top).isEqualTo(30f)
        assertThat(r[1].right).isEqualTo(10f + 2 * 8f)
    }

    @Test
    fun rango_entre_paginas() = runTest {
        val src = book().textSource()
        val rects = src.rectsFor(LocatorRange(Locator(0, 12), Locator(1, 4)))
        assertThat(rects.keys).containsExactly(0, 1).inOrder()
    }

    @Test
    fun toque_a_locator() = runTest {
        val src = book().textSource()
        assertThat(src.locatorAt(0, 10f + 8f * 2 + 1f, 15f)).isEqualTo(Locator(0, 2))
        // Un poco a la derecha del final de la segunda linea: el caracter mas cercano.
        assertThat(src.locatorAt(0, 10f + 8f * 5 + 3f, 35f)).isEqualTo(Locator(0, 16))
        // Muy lejos: nada.
        assertThat(src.locatorAt(0, 190f, 290f)).isNull()
    }

    @Test
    fun cerrar_cierra_el_backend_y_bloquea_uso() = runTest {
        val backend = FakeBackend()
        val b = book(backend)
        b.close()
        assertThat(backend.closed).isTrue()
        val error = runCatching { b.pageText(0) }.exceptionOrNull()
        assertThat(error).isInstanceOf(PdfException.Closed::class.java)
    }

    @Test
    fun backend_roto_da_error_claro() {
        val roto = object : PdfBackend by FakeBackend() {
            override val pageCount: Int get() = throw IllegalStateException("xref roto")
        }
        val error = runCatching { book(roto) }.exceptionOrNull()
        assertThat(error).isInstanceOf(PdfException.Corrupt::class.java)
        assertThat(error!!.message).contains("danado")
    }
}
