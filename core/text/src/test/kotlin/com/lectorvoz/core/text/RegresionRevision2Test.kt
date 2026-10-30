package com.lectorvoz.core.text

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.ChapterText
import com.lectorvoz.core.model.FootnoteMode
import org.junit.Test
import kotlin.random.Random

/**
 * Regresiones de la revision 2 del codigo en `:core:text`:
 * fuzz del constructor de frases (incorporado de la reproduccion del revisor) y coste lineal
 * con comillas sin cerrar (B9).
 */
class RegresionRevision2Test {
    private val piezas = listOf(
        "Hola", "mundo", " ", " ", " ", ".", ",", "!", "?", "¿", "¡", "…", "...", "—", "–", "-", "«", "»", "“", "”", "\"",
        "'", "\n", "\n\n", "\r\n", "123", "1.000", "3,5", "12:30", "1/2", "5 %", "$", "€", "º", "ª", "1.º", "XIV", "Sr.",
        "EE. UU.", "https://www.ejemplo.com/a?b=1", "a@b.es", "😀", "👨‍👩‍👧", "𝟎", "٣", "３", "שלום", "مرحبا",
        "​", "­", "́", "é", "¹", "[3]", "(4)", "|", " | ", "the", "said", "dijo", "I", "don't", "￼",
        " ", "\uD800", "\uDC00", "ñ", "Ω", "x".repeat(50), "9".repeat(30), "—¿Vienes? —Sí —dijo—.",
    )

    private fun textoAleatorio(r: Random, n: Int): String {
        val sb = StringBuilder()
        while (sb.length < n) sb.append(piezas[r.nextInt(piezas.size)])
        return sb.toString()
    }

    /** 3000 textos aleatorios: sin excepciones, mapas en rango y monotonos, rangos coherentes e ids unicos. */
    @Test
    fun fuzzDelConstructorDeFrases() {
        val b = UtteranceBuilder(
            dictionary = PronunciationDictionary(
                listOf(PronunciationRule("Hola", "Ola"), PronunciationRule("(\\d)", "<$1>", isRegex = true)),
            ),
        )
        val r = Random(1234)
        val fallos = ArrayList<String>()
        repeat(3000) { iter ->
            val text = textoAleatorio(r, r.nextInt(1, 400))
            val from = r.nextInt(0, text.length + 1)
            try {
                val opts = TextOptions(announceChapters = r.nextBoolean(), chapterNumber = 1, footnotes = FootnoteMode.END_OF_PARAGRAPH)
                val us = b.build(ChapterText(0, text, "Titulo", null), from, 0L, opts)
                check(us.map { it.id }.distinct().size == us.size) { "ids repetidos" }
                for (u in us) {
                    check(u.spokenText.length == u.spokenToDisplay.size) { "longitud del mapa" }
                    for (i in u.spokenToDisplay.indices) {
                        val d = u.spokenToDisplay[i]
                        check(d >= 0 && (u.displayText.isEmpty() || d < u.displayText.length)) { "mapa fuera de rango $d" }
                        if (i > 0) check(d >= u.spokenToDisplay[i - 1]) { "mapa no monotono en $i" }
                    }
                    check(u.range.start.offset in 0..text.length && u.range.endExclusive.offset in u.range.start.offset..text.length) { "rango" }
                    if (u.range.endExclusive.offset > u.range.start.offset) {
                        check(text.substring(u.range.start.offset, u.range.endExclusive.offset) == u.displayText) { "texto visible" }
                    }
                }
            } catch (e: Throwable) {
                if (fallos.size < 8) fallos += "iter=$iter from=$from: $e"
            }
        }
        assertThat(fallos).isEmpty()
    }

    /** B9: un parrafo enorme con comillas sin cerrar se separa en tiempo lineal. */
    @Test
    fun comillasSinCerrarEnTiempoLineal() {
        val text = "«a “b ".repeat(60_000) // 360 000 caracteres, ninguna comilla cerrada
        Dialogs.split(text, 0, text.length) // calentamiento
        val t0 = System.nanoTime()
        val spans = Dialogs.split(text, 0, text.length)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertThat(spans).isNotEmpty()
        // Antes de la correccion: cuadratico (decenas de segundos con este tamano).
        assertThat(ms).isLessThan(2_000L)
    }

    /** B9: el emparejado en una pasada da los mismos tramos que antes (anidamiento incluido). */
    @Test
    fun comillasAnidadasSiguenEmparejandose() {
        val t = "Entonces dijo: «Ella me contó «algo» ayer.» Y se fue."
        val dialog = Dialogs.split(t, 0, t.length).filter { it.role == com.lectorvoz.core.model.VoiceRole.DIALOG }
        assertThat(dialog.map { t.substring(it.start, it.end) }).containsExactly("«Ella me contó «algo» ayer.»")
    }
}
