package com.lectorvoz.format.pdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.Locator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/** Texto fluido para la voz (limpiador basico y punto de extension) con datos falsos. */
@RunWith(AndroidJUnit4::class)
class PdfFlowTextTest {

    private fun flow(raw: String, excluded: List<IntRange> = emptyList()) = BasicPdfTextFlow.flow(raw, excluded)

    /** Comprueba que cada caracter fluido procede de un caracter igual (salvo saltos/espacios). */
    private fun assertMapConsistent(raw: String, f: PdfFlowText) {
        for (i in 0 until f.length) {
            val c = f.text[i]
            val src = raw[f.sourceIndex(i)]
            if (c == ' ' || c == '\n') continue
            if (c == '-' && src == '\u0002') continue
            assertThat(c).isEqualTo(src)
        }
    }

    @Test
    fun une_lineas_de_un_mismo_parrafo() {
        val raw = "En un lugar de la Mancha, de cuyo\r\nnombre no quiero acordarme, no ha\r\nmucho tiempo que vivia un hidalgo."
        val f = flow(raw)
        assertThat(f.text).isEqualTo(
            "En un lugar de la Mancha, de cuyo nombre no quiero acordarme, no ha mucho tiempo que vivia un hidalgo.",
        )
        assertThat(f.text).doesNotContain("\n")
        assertMapConsistent(raw, f)
    }

    @Test
    fun quita_guion_de_fin_de_linea() {
        val raw = "Una pala\u0002\r\nbra partida y otra divi-\r\ndida tambien."
        val f = flow(raw)
        assertThat(f.text).isEqualTo("Una palabra partida y otra dividida tambien.")
        assertMapConsistent(raw, f)
        // La proyeccion conserva la longitud y no parte la palabra con espacios.
        val aligned = f.alignedText()
        assertThat(aligned).hasLength(raw.length)
        assertThat(aligned.replace("­", "")).contains("palabra")
        assertThat(aligned.replace("­", "")).contains("dividida")
    }

    @Test
    fun guion_seguido_de_mayuscula_se_conserva() {
        val f = flow("Ver anexo-\r\nB del contrato y algo mas de texto aqui.")
        assertThat(f.text).startsWith("Ver anexo- B")
    }

    @Test
    fun parrafos_por_linea_en_blanco_raya_y_linea_corta() {
        val raw = "Primera linea larga de un parrafo que sigue\r\n" +
            "y termina aqui.\r\n" +
            "Segundo parrafo con una linea bastante larga\r\n" +
            "que acaba.\r\n" +
            "\r\n" +
            "Tercero tras linea en blanco con texto largo\r\n" +
            "— Dialogo que empieza con raya y sigue mas.\r\n"
        val f = flow(raw)
        val paras = f.text.split('\n')
        assertThat(paras).containsExactly(
            "Primera linea larga de un parrafo que sigue y termina aqui.",
            "Segundo parrafo con una linea bastante larga que acaba.",
            "Tercero tras linea en blanco con texto largo",
            "— Dialogo que empieza con raya y sigue mas.",
        ).inOrder()
        assertMapConsistent(raw, f)
    }

    @Test
    fun sangria_y_salto_vertical_con_geometria() {
        // Cuatro lineas de 30 caracteres; la tercera sangrada tras punto, la cuarta lejos.
        val lines = listOf(
            "Texto del primer parrafo que s", // x=10, y=10
            "igue en esta linea y acaba ya.", // x=10, y=22
            "Sangrado: nuevo parrafo que si", // x=30, y=34
            "Titulo separado por un hueco g", // x=10, y=80
        )
        val raw = lines.joinToString("\r\n")
        val xs = floatArrayOf(10f, 10f, 30f, 10f)
        val ys = floatArrayOf(10f, 22f, 34f, 80f)
        val starts = IntArray(lines.size)
        var p = 0
        for (k in lines.indices) { starts[k] = p; p += lines[k].length + 2 }
        val box: (Int, FloatArray) -> Boolean = { i, out ->
            val k = starts.indexOfLast { it <= i }
            val col = i - starts[k]
            if (col >= lines[k].length) false else {
                out[0] = xs[k] + col * 5f; out[1] = ys[k]; out[2] = out[0] + 5f; out[3] = ys[k] + 10f; true
            }
        }
        val f = BasicPdfTextFlow.flow(raw, emptyList(), box)
        val paras = f.text.split('\n')
        assertThat(paras).hasSize(3)
        assertThat(paras[0]).isEqualTo("Texto del primer parrafo que s igue en esta linea y acaba ya.")
        assertThat(paras[2]).startsWith("Titulo")
    }

    @Test
    fun rangos_excluidos_fuera_del_texto() {
        val raw = "CABECERA DEL LIBRO\r\nTexto real de la pagina.\r\n23"
        val head = 0..17
        val foot = raw.length - 2 until raw.length
        val f = flow(raw, listOf(head, foot))
        assertThat(f.text).isEqualTo("Texto real de la pagina.")
        val aligned = f.alignedText()
        assertThat(aligned).hasLength(raw.length)
        assertThat(aligned.trim()).isEqualTo("Texto real de la pagina.")
    }

    @Test
    fun conversion_de_indices() {
        val raw = "ab\r\ncd"
        val f = flow(raw) // "ab cd"
        assertThat(f.text).isEqualTo("ab cd")
        assertThat(f.sourceIndex(3)).isEqualTo(4)
        assertThat(f.sourceStart(3)).isEqualTo(4)
        assertThat(f.sourceEnd(5)).isEqualTo(6)
        assertThat(f.sourceEnd(2)).isEqualTo(2)
        assertThat(f.flowIndex(4)).isEqualTo(3)
        assertThat(f.flowIndex(3)).isEqualTo(3) // el '\n' no esta: siguiente
        assertThat(f.flowIndex(99)).isEqualTo(5)
        assertThat(f.textForSource(0, 6)).isEqualTo("ab cd")
        assertThat(f.textForSource(4, 6)).isEqualTo("cd")
    }

    @Test
    fun mapa_no_monotono_se_rechaza() {
        val error = runCatching { PdfFlowText("ab", intArrayOf(1, 0), 2) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun texto_vacio_y_controles() {
        assertThat(flow("").text).isEmpty()
        val f = flow("a\u0001b  ￾c")
        assertThat(f.text).isEqualTo("a b c")
    }

    // ------------------------------------------------------------ extension (fakes)

    private class OnePage(val text: String) : PdfBackend {
        override val pageCount = 1
        override fun pageSizePoints(index: Int) = PageSize(100f, 100f)
        override fun meta() = PdfMeta(null, null, null)
        override fun bookmarks() = emptyList<PdfBookmark>()
        override fun render(index: Int, bitmap: android.graphics.Bitmap, startX: Int, startY: Int, drawWidth: Int, drawHeight: Int) = Unit
        override fun pageText(index: Int) = RawPageText(text, FloatArray(text.length * 4))
        override fun close() = Unit
    }

    @Test
    fun limpiador_externo_con_mapa_y_locator_de_pdfium() = runTest {
        val raw = "Pie 1\r\nHola que tal\r\nestas hoy."
        // Falso de :core:text: quita la primera linea y une el resto, con mapa al texto recibido.
        val externo = PageTextCleaner.of(
            excluded = { listOf(0..4) },
            flow = { page, _ ->
                val src = page.rawText
                val out = StringBuilder()
                val map = ArrayList<Int>()
                for (i in 7 until src.length) {
                    val c = src[i]
                    if (c == '\r') { out.append(' '); map += i; continue }
                    if (c == '\n') continue
                    out.append(c); map += i
                }
                PdfFlowText(out.toString(), map.toIntArray(), src.length)
            },
        )
        val book = PdfBook.create(OnePage(raw), Dispatchers.Unconfined, "x")
        val src = book.textSource(externo)
        val f = src.flowText(0)
        assertThat(f.text).isEqualTo("Hola que tal estas hoy.")
        // "estas" en el texto fluido -> indices de pdfium para resaltar.
        val i = f.text.indexOf("estas")
        val range = src.rangeFor(0, f, i, i + 5)
        assertThat(raw.substring(range.start.offset, range.endExclusive.offset)).isEqualTo("estas")
        assertThat(src.locatorFor(0, f, 0)).isEqualTo(Locator(0, 7))
        // El texto para TextSource conserva la longitud de pdfium.
        val chapter = src.chapterText(0)
        assertThat(chapter.text).hasLength(raw.length)
        assertThat(chapter.text.substring(range.start.offset, range.endExclusive.offset)).isEqualTo("estas")
        assertThat(book.pageText(0, externo).excludedRanges).containsExactly(0..4)
        // Se cachea: misma instancia para el mismo limpiador.
        assertThat(book.pageText(0, externo)).isSameInstanceAs(book.pageText(0, externo))
    }

    @Test
    fun limpiador_externo_roto_cae_al_basico() = runTest {
        val roto = PageTextCleaner.of(
            excluded = { emptyList() },
            flow = { page, _ -> PdfFlowText("xy", intArrayOf(5, 1), page.length) },
        )
        val book = PdfBook.create(OnePage("uno\r\ndos"), Dispatchers.Unconfined, "x")
        assertThat(book.textSource(roto).flowText(0).text).isEqualTo("uno dos")
    }
}
