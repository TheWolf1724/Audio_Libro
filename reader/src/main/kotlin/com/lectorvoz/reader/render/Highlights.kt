package com.lectorvoz.reader.render

import com.lectorvoz.core.model.HighlightKind
import com.lectorvoz.core.model.LocatorRange

/**
 * Resaltados activos (inmutable). Frase, palabra y seleccion tienen un rango cada una; la
 * busqueda puede tener varios con uno "actual".
 */
/** Subrayado de color del usuario: [color] es el indice 0..3 (ver [MarkColors]). */
data class MarkRange(val range: LocatorRange, val color: Int)

/** Colores de los subrayados (translucidos: sirven sobre fondo claro y oscuro). */
object MarkColors {
    private val ARGB = intArrayOf(0x99FFD54F.toInt(), 0x9981C784.toInt(), 0x9964B5F6.toInt(), 0x99F48FB1.toInt())
    fun argb(index: Int): Int = ARGB[index.coerceIn(0, ARGB.lastIndex)]
}

data class Highlights(
    val sentence: LocatorRange? = null,
    val word: LocatorRange? = null,
    val selection: LocatorRange? = null,
    val search: List<LocatorRange> = emptyList(),
    val searchCurrent: LocatorRange? = null,
    val marks: List<MarkRange> = emptyList(),
) {
    fun with(kind: HighlightKind, range: LocatorRange?): Highlights = when (kind) {
        HighlightKind.SENTENCE -> copy(sentence = range)
        HighlightKind.WORD -> copy(word = range)
        HighlightKind.SELECTION -> copy(selection = range)
        HighlightKind.SEARCH -> copy(search = listOfNotNull(range), searchCurrent = range)
    }

    fun rangeOf(kind: HighlightKind): List<LocatorRange> = when (kind) {
        HighlightKind.SENTENCE -> listOfNotNull(sentence)
        HighlightKind.WORD -> listOfNotNull(word)
        HighlightKind.SELECTION -> listOfNotNull(selection)
        HighlightKind.SEARCH -> search
    }

    /** Todos los rangos (para saber que paginas hay que redibujar). */
    fun all(): List<LocatorRange> = listOfNotNull(sentence, word, selection) + search + marks.map { it.range }

    companion object {
        val NONE = Highlights()

        /** Parte [s, e) de [range] dentro del capitulo [chapter] de longitud [length]; null si no toca. */
        fun clip(range: LocatorRange?, chapter: Int, length: Int): IntRange? {
            if (range == null || range.isEmpty) return null
            if (range.endExclusive.chapter < chapter || range.start.chapter > chapter) return null
            val s = if (range.start.chapter < chapter) 0 else range.start.offset
            val e = if (range.endExclusive.chapter > chapter) length else range.endExclusive.offset
            val cs = s.coerceIn(0, length)
            val ce = e.coerceIn(cs, length)
            return if (ce > cs) cs until ce else null
        }
    }
}
