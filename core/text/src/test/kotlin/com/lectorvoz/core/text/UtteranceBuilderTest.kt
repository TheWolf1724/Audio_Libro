package com.lectorvoz.core.text

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.ChapterText
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.Utterance
import org.junit.Test

class UtteranceBuilderTest {
    private val builder = UtteranceBuilder(SpanishNormalizer())

    private val colon = "«Capítulo tres. El 12 de octubre de 1492, a las 2:30 de la madrugada, Rodrigo de " +
        "Triana gritó: ¡tierra a la vista! Nadie, ni siquiera el almirante, sabía que aquel 1,5 por " +
        "ciento de esperanza iba a cambiar el siglo XV. —¿Qué hora es? —preguntó Martín.»"

    private fun chapter(text: String, index: Int = 2) = ChapterText(index, text, "T", "es")

    /** Revision 2, M5: el id de una frase no depende de desde donde se construye el capitulo. */
    @Test
    fun idsEstablesSinDependerDelPuntoDeInicio() {
        val full = builder.build(chapter(colon))
        assertThat(full.map { it.id }.distinct()).hasSize(full.size)
        for (from in listOf(1, 5, 40, 120, colon.length - 3)) {
            val part = builder.build(chapter(colon), fromOffset = from)
            assertThat(part).isNotEmpty()
            for (u in part) {
                val same = full.single { it.range.endExclusive == u.range.endExclusive }
                assertThat(u.id).isEqualTo(same.id)
            }
        }
        // Capitulos distintos (base por defecto) no comparten ids.
        val other = builder.build(chapter(colon, index = 3))
        assertThat(other.map { it.id }.intersect(full.map { it.id }.toSet())).isEmpty()
        // Con anuncio: el anuncio y la primera frase (ambos en 0) tienen ids distintos.
        val withAnnounce = builder.build(chapter(colon), options = TextOptions(announceChapters = true, chapterNumber = 3))
        assertThat(withAnnounce.map { it.id }.distinct()).hasSize(withAnnounce.size)
    }

    @Test
    fun textoDeReferencia() {
        val us = builder.build(chapter(colon), idBase = 10)
        assertThat(us.map { it.displayText }).containsExactly(
            "«Capítulo tres.",
            "El 12 de octubre de 1492, a las 2:30 de la madrugada, Rodrigo de Triana gritó: ¡tierra a la vista!",
            "Nadie, ni siquiera el almirante, sabía que aquel 1,5 por ciento de esperanza iba a cambiar el siglo XV.",
            "—¿Qué hora es? —preguntó Martín.»",
        ).inOrder()
        assertThat(us.map { it.spokenText }).containsExactly(
            "Capítulo tres.",
            "El doce de octubre de mil cuatrocientos noventa y dos, a las dos y media de la madrugada, " +
                "Rodrigo de Triana gritó: ¡tierra a la vista!",
            "Nadie, ni siquiera el almirante, sabía que aquel uno coma cinco por ciento de esperanza iba " +
                "a cambiar el siglo quince.",
            "¿Qué hora es? preguntó Martín.",
        ).inOrder()
        assertThat(us.map { it.id }).containsExactlyElementsIn(us.map { 10L + (it.range.start.offset.toLong() shl 8) }).inOrder()
        assertThat(us[0].range.start).isEqualTo(Locator(2, 0))
        us.forEach { assertThat(colon.substring(it.range.start.offset, it.range.endExclusive.offset)).isEqualTo(it.displayText) }
        checkProperties(colon, us)
    }

    @Test
    fun desdeUnDesplazamientoEmpiezaEnLaPalabra() {
        val off = colon.indexOf("Triana") + 2
        val us = builder.build(chapter(colon), fromOffset = off, idBase = 0)
        assertThat(us.first().displayText).startsWith("Triana gritó")
        assertThat(us.first().range.start.offset).isEqualTo(colon.indexOf("Triana"))
        assertThat(us).hasSize(3)
    }

    @Test
    fun desdeElFinalNoHayFrases() {
        assertThat(builder.build(chapter(colon), fromOffset = colon.length, idBase = 0)).isEmpty()
    }

    @Test
    fun parrafosImagenesYLineasVacias() {
        val text = "Título\n\n￼\nPrimer párrafo. Segundo.\n—\n"
        val us = builder.build(chapter(text), idBase = 0)
        assertThat(us.map { it.displayText }).containsExactly("Título", "Primer párrafo.", "Segundo.").inOrder()
        checkProperties(text, us)
    }

    @Test
    fun maxCharsLimitaLaLongitud() {
        val long = (1..40).joinToString(", ") { "palabra número $it del texto" } + "."
        val us = UtteranceBuilder(SpanishNormalizer(), maxChars = 100).build(chapter(long), idBase = 0)
        assertThat(us.size).isGreaterThan(5)
        us.forEach { assertThat(it.displayText.length).isAtMost(100) }
        checkProperties(long, us)
    }

    @Test
    fun propiedadesConTextoVariado() {
        val text = buildLongText(5_000)
        val us = builder.build(chapter(text), idBase = 0)
        checkProperties(text, us)
    }

    @Test
    fun rendimientoCapituloDeCienMilCaracteres() {
        val text = buildLongText(100_000)
        val ch = chapter(text)
        val opts = TextOptions(announceChapters = true, chapterNumber = 1)
        repeat(5) { builder.build(ch, idBase = 0, options = opts) } // calentamiento JIT
        var best = Long.MAX_VALUE
        var us: List<Utterance> = emptyList()
        repeat(3) {
            val t0 = System.nanoTime()
            us = builder.build(ch, idBase = 0, options = opts)
            best = minOf(best, (System.nanoTime() - t0) / 1_000_000)
        }
        println("UtteranceBuilder: ${text.length} caracteres, ${us.size} frases en $best ms")
        // Objetivo: menos de 200 ms por capitulo de 100 000 caracteres en la JVM.
        assertThat(best).isLessThan(200L)
        checkProperties(text, us)
    }

    private fun buildLongText(minLength: Int): String {
        val parts = listOf(
            "El Sr. García llegó el 12/10/1492 a las 14:05 h con 21 personas y 3,5 kg de pan. ",
            "—¿Cuánto cuesta? —preguntó la Dra. López—. Unos 1.250 € o 1.300 $, etc. ",
            "En el siglo XV reinaba Isabel I; la ONU y la RTVE no existían (claro). ",
            "Visita https://www.ejemplo.es/ruta o escribe a info@ejemplo.es para saber más… ",
            "Felipe II subió un 15 % la renta del 2.º año en EE. UU. y aprox. 3.000.000 de personas.\n",
            "￼\n«Capítulo IV» — 1914-1918: la 1.ª guerra; 2 + 2 = 4 & más.\n\n",
        )
        val sb = StringBuilder(minLength + 200)
        var k = 0
        while (sb.length < minLength) sb.append(parts[k++ % parts.size])
        return sb.toString()
    }

    /** Rangos ordenados, sin solape, cubren todo lo legible y mapas monotonos y completos. */
    private fun checkProperties(text: String, us: List<Utterance>) {
        var prevEnd = 0
        val covered = BooleanArray(text.length)
        for (u in us) {
            if (u.range.isEmpty) continue // unidades sinteticas (anuncio, notas)
            val s = u.range.start.offset
            val e = u.range.endExclusive.offset
            assertThat(s).isAtLeast(prevEnd)
            assertThat(e).isGreaterThan(s)
            prevEnd = e
            for (k in s until e) covered[k] = true
            assertThat(u.displayText).isEqualTo(text.substring(s, e))
            assertThat(u.spokenText).isNotEmpty()
            SpanishNormalizerTest.assertMapValid(MappedText(u.spokenText, u.spokenToDisplay), u.displayText.length)
        }
        for (k in text.indices) {
            if (text[k].isLetterOrDigit()) {
                assertWithMessage(k, text, covered[k])
            }
        }
    }

    private fun assertWithMessage(k: Int, text: String, covered: Boolean) {
        if (!covered) {
            throw AssertionError("Caracter legible sin cubrir en $k: '${text.substring(maxOf(0, k - 20), minOf(text.length, k + 20))}'")
        }
    }

    @Test
    fun textoAleatorioNoRompeNada() {
        val pieces = listOf(
            "—", "«", "»", "“", "”", "\"", "¿", "?", "¡", "!", ".", "...", "…", ",", ";", ":", " ", " ", "\n", "\n\n",
            "1", "12", "３", "٣", "²", "¹", "[3]", "(4)", "a", "Sr.", "Mr.", "the", "de", "XV", "II", "https://x.es/a",
            "a@b.com", "€", "$", "%", "-", "*", "\uD83D\uDE00", "\uD83D", "￼", "|", " | ", "\u00AD", "\u200B", "palabra",
            "Capítulo", "Chapter", "dijo", "said", "é", "ñ", "\t", "\r\n", "1.", "•",
        )
        val rnd = java.util.Random(42)
        val b = UtteranceBuilder(maxChars = 40)
        repeat(400) {
            val sb = StringBuilder()
            repeat(rnd.nextInt(60)) { sb.append(pieces[rnd.nextInt(pieces.size)]) }
            val text = sb.toString()
            val opts = TextOptions(
                footnotes = if (rnd.nextBoolean()) com.lectorvoz.core.model.FootnoteMode.END_OF_PARAGRAPH else com.lectorvoz.core.model.FootnoteMode.SKIP,
                announceChapters = rnd.nextBoolean(),
                chapterNumber = 2,
            )
            val from = if (text.isEmpty()) 0 else rnd.nextInt(text.length)
            for (u in b.build(chapter(text), fromOffset = from, idBase = 0, options = opts)) {
                assertThat(u.spokenText).isNotEmpty()
                SpanishNormalizerTest.assertMapValid(MappedText(u.spokenText, u.spokenToDisplay), maxOf(1, u.displayText.length))
                if (!u.range.isEmpty) assertThat(u.displayText).isEqualTo(text.substring(u.range.start.offset, u.range.endExclusive.offset))
            }
            EnglishNormalizer().normalize(text)
            SpanishNormalizer().normalize(text)
            PdfPageFlow.flow(text, text, null)
        }
    }
}
