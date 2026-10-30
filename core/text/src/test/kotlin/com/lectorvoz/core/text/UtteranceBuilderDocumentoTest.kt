package com.lectorvoz.core.text

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.Block
import com.lectorvoz.core.model.BlockKind
import com.lectorvoz.core.model.ChapterDocument
import com.lectorvoz.core.model.FootnoteMode
import com.lectorvoz.core.model.InlineSpan
import com.lectorvoz.core.model.InlineStyle
import com.lectorvoz.core.model.PauseKind
import com.lectorvoz.core.model.Utterance
import com.lectorvoz.core.model.UtteranceKind
import com.lectorvoz.core.model.VoiceSettings
import org.junit.Test

/** Construccion a partir de un capitulo EPUB analizado (bloques y estilos). */
class UtteranceBuilderDocumentoTest {
    private val builder = UtteranceBuilder()

    /** Ayudante para montar un ChapterDocument como lo haria :format:epub. */
    private class Doc {
        val sb = StringBuilder()
        val blocks = ArrayList<Block>()
        val anchors = HashMap<String, Int>()

        fun text(s: String, kind: BlockKind = BlockKind.Paragraph, spans: (Int) -> List<InlineSpan> = { emptyList() }): Doc {
            if (sb.isNotEmpty()) sb.append('\n')
            val start = sb.length
            sb.append(s)
            blocks.add(Block.Text(start, sb.length, kind, spans(start)))
            return this
        }

        fun rule(): Doc {
            blocks.add(Block.Rule(sb.length))
            return this
        }

        fun anchor(id: String) = apply { anchors[id] = sb.length + 1 }

        fun build(title: String? = null, lang: String = "es") = ChapterDocument(4, title, lang, sb.toString(), blocks, anchors)
    }

    private fun span(base: Int, text: String, part: String, style: InlineStyle): InlineSpan {
        val i = text.indexOf(part)
        return InlineSpan(base + i, base + i + part.length, style)
    }

    private fun spoken(us: List<Utterance>) = us.map { it.spokenText }

    @Test
    fun encabezadoListaYSeparador() {
        val doc = Doc()
            .text("El comienzo", BlockKind.Heading(1))
            .text("Era de noche.")
            .text("Pan", BlockKind.ListItem("1."))
            .text("Leche", BlockKind.ListItem("•"))
            .rule()
            .text("Fin.")
            .build()
        val us = builder.build(doc, idBase = 0)
        assertThat(spoken(us)).containsExactly("El comienzo.", "Era de noche.", "uno. Pan.", "Leche.", "Fin.").inOrder()
        assertThat(us.map { it.kind }).containsExactly(
            UtteranceKind.HEADING, UtteranceKind.SENTENCE, UtteranceKind.LIST_ITEM, UtteranceKind.LIST_ITEM, UtteranceKind.SENTENCE,
        ).inOrder()
        assertThat(us.map { it.pauseAfter }).containsExactly(
            PauseKind.SECTION, PauseKind.PARAGRAPH, PauseKind.PARAGRAPH, PauseKind.SECTION, PauseKind.CHAPTER,
        ).inOrder()
        // El marcador hablado apunta al inicio del elemento.
        assertThat(us[2].spokenToDisplay[0]).isEqualTo(0)
    }

    @Test
    fun cursivaAisladaLlevaMicropausaYTitulosNo() {
        val t1 = "Lo llamaban siempre naíf aunque no lo era."
        val t2 = "Leyó Cien años de soledad en una tarde."
        val t3 = "Todo esto va en cursiva."
        val doc = Doc()
            .text(t1) { listOf(span(it, t1, "naíf", InlineStyle.Italic)) }
            .text(t2) { listOf(span(it, t2, "Cien años de soledad", InlineStyle.Italic)) }
            .text(t3) { listOf(span(it, t3, t3, InlineStyle.Italic)) }
            .build()
        val us = builder.build(doc, idBase = 0)
        assertThat(spoken(us)).containsExactly(
            "Lo llamaban siempre, naíf aunque no lo era.",
            "Leyó Cien años de soledad en una tarde.",
            "Todo esto va en cursiva.",
        ).inOrder()
        val u = us[0]
        val comma = u.spokenText.indexOf(',')
        assertThat(u.displayText[u.spokenToDisplay[comma]]).isEqualTo(' ')
        assertThat(u.displayText[u.spokenToDisplay[comma + 2]]).isEqualTo('n')
    }

    private fun notesDoc(): ChapterDocument {
        val p = "Era un hecho1 bien conocido. Y otro más."
        return Doc()
            .text(p) { base -> listOf(span(base, p, "1", InlineStyle.Link("OEBPS/c.xhtml#n1")), span(base, p, "1", InlineStyle.Superscript)) }
            .text("Siguiente párrafo.")
            .anchor("n1")
            .text("1. La fuente es un archivo privado.")
            .build()
    }

    @Test
    fun notasOmitidasPorDefecto() {
        val us = builder.build(notesDoc(), idBase = 0)
        assertThat(spoken(us)).containsExactly("Era un hecho bien conocido.", "Y otro más.", "Siguiente párrafo.").inOrder()
    }

    @Test
    fun notasAlFinalDelParrafo() {
        val us = builder.build(notesDoc(), idBase = 0, options = TextOptions(footnotes = FootnoteMode.END_OF_PARAGRAPH))
        assertThat(spoken(us)).containsExactly(
            "Era un hecho bien conocido.", "Y otro más.", "Nota. La fuente es un archivo privado.", "Siguiente párrafo.",
        ).inOrder()
        val note = us[2]
        assertThat(note.kind).isEqualTo(UtteranceKind.FOOTNOTE)
        assertThat(note.range.isEmpty).isTrue()
        assertThat(us[1].pauseAfter).isEqualTo(PauseKind.SENTENCE)
        assertThat(note.pauseAfter).isEqualTo(PauseKind.PARAGRAPH)
    }

    /** Revision 2, M8: el fragmento se busca en este capitulo solo si la ruta del enlace es la suya. */
    @Test
    fun notaLocalSoloSiElEnlaceApuntaAEsteCapitulo() {
        val p = "Ver la parte 2 más adelante."
        val doc = Doc()
            .text(p) { base -> listOf(span(base, p, "2", InlineStyle.Link("OEBPS/otro.xhtml#s2"))) }
            .anchor("s2")
            .text("Un párrafo normal del capítulo.")
            .build()
        for (path in listOf("OEBPS/c.xhtml", null)) {
            val us = builder.build(doc, options = TextOptions(footnotes = FootnoteMode.END_OF_PARAGRAPH, chapterPath = path))
            assertThat(us.none { it.kind == UtteranceKind.FOOTNOTE }).isTrue()
            assertThat(spoken(us).last()).isEqualTo("Un párrafo normal del capítulo.")
        }
        // Con la ruta correcta y un destino que es nota, si se resuelve.
        val local = builder.build(
            notesDoc(),
            options = TextOptions(footnotes = FootnoteMode.END_OF_PARAGRAPH, chapterPath = "OEBPS/c.xhtml"),
        )
        assertThat(spoken(local)).contains("Nota. La fuente es un archivo privado.")
    }

    /** Revision 2, M8: un titulo nunca se toma por el cuerpo de una nota. */
    @Test
    fun tituloComoDestinoSeLeeEnSuSitio() {
        val p = "Como se ve en el II del índice."
        val doc = Doc()
            .text(p) { base -> listOf(span(base, p, "II", InlineStyle.Link("OEBPS/c.xhtml#t2"))) }
            .anchor("t2")
            .text("II", BlockKind.Heading(2))
            .text("Texto de la segunda parte.")
            .build()
        val us = builder.build(doc, options = TextOptions(footnotes = FootnoteMode.END_OF_PARAGRAPH, chapterPath = "OEBPS/c.xhtml"))
        assertThat(us.none { it.kind == UtteranceKind.FOOTNOTE }).isTrue()
        assertThat(us.map { it.kind }).contains(UtteranceKind.HEADING)
    }

    @Test
    fun notaDeOtroCapituloPorFootnoteTexts() {
        val p = "Lo dijo Ana2 ayer."
        val doc = Doc().text(p) { base -> listOf(span(base, p, "2", InlineStyle.Link("OEBPS/notas.xhtml#n2"))) }.build()
        val opts = TextOptions(footnotes = FootnoteMode.END_OF_PARAGRAPH, footnoteTexts = mapOf("OEBPS/notas.xhtml#n2" to "[2] Ana Pérez."))
        val us = builder.build(doc, idBase = 0, options = opts)
        assertThat(spoken(us)).containsExactly("Lo dijo Ana ayer.", "Nota. Ana Pérez.").inOrder()
    }

    @Test
    fun tablaConCabecerasYOpcionDeOmitir() {
        val h = "Nombre | Edad"
        val doc = Doc()
            .text(h) { base -> listOf(span(base, h, "Nombre", InlineStyle.Bold), span(base, h, "Edad", InlineStyle.Bold)) }
            .text("Ana | 30")
            .text("Luis | 41")
            .text("Después.")
            .build()
        val us = builder.build(doc, idBase = 0)
        assertThat(spoken(us)).containsExactly("Nombre: Ana, Edad: treinta.", "Nombre: Luis, Edad: cuarenta y uno.", "Después.").inOrder()
        assertThat(us[0].kind).isEqualTo(UtteranceKind.TABLE)
        assertThat(us[1].pauseAfter).isEqualTo(PauseKind.PARAGRAPH)
        val sin = builder.build(doc, idBase = 0, options = TextOptions(readTables = false))
        assertThat(spoken(sin)).containsExactly("Después.")
    }

    @Test
    fun poesiaConSaltosDeLineaNoSeUne() {
        val doc = Doc()
            .text("Verde que te quiero verde.\nVerde viento. Verdes ramas.\nEl barco sobre la mar")
            .text("y el caballo en la montaña.")
            .build()
        val us = builder.build(doc, idBase = 0)
        assertThat(us.map { it.kind }.toSet()).containsExactly(UtteranceKind.VERSE_LINE, UtteranceKind.SENTENCE)
        assertThat(spoken(us).take(4)).containsExactly(
            "Verde que te quiero verde.", "Verde viento.", "Verdes ramas.", "El barco sobre la mar.",
        ).inOrder()
        assertThat(us[0].pauseAfter).isEqualTo(PauseKind.VERSE)
        assertThat(us[3].pauseAfter).isEqualTo(PauseKind.PARAGRAPH)
    }

    @Test
    fun anuncioDeCapituloSinRepetirElTitulo() {
        val doc = Doc().text("El viaje", BlockKind.Heading(1)).text("Era de noche.").build(title = "El viaje")
        val opts = TextOptions.from(VoiceSettings(), chapterNumber = 3)
        val us = builder.build(doc, idBase = 7, options = opts)
        assertThat(spoken(us)).containsExactly("Capítulo tres.", "El viaje.", "Era de noche.").inOrder()
        assertThat(us[0].kind).isEqualTo(UtteranceKind.CHAPTER_ANNOUNCE)
        assertThat(us[0].range.isEmpty).isTrue()
        assertThat(us[0].range.start.offset).isEqualTo(0)
        // Anuncio en la ranura 1 de la posicion 0; las frases, en la 0 de su inicio.
        assertThat(us.map { it.id }).containsExactly(8L, 7L, 7L + (9L shl 8)).inOrder()
        // Sin anuncio si no se empieza desde el principio.
        assertThat(builder.build(doc, fromOffset = 10, idBase = 0, options = opts).first().kind).isNotEqualTo(UtteranceKind.CHAPTER_ANNOUNCE)
    }

    @Test
    fun anuncioEnIngles() {
        val doc = Doc().text("It was night.").build(title = "The Journey", lang = "en")
        val us = builder.build(doc, idBase = 0, options = TextOptions(announceChapters = true, chapterNumber = 2))
        assertThat(spoken(us)).containsExactly("Chapter two. The Journey.", "It was night.").inOrder()
        assertThat(us.map { it.language }).containsExactly("en", "en").inOrder()
    }
}
