package com.lectorvoz.reader.render

import com.lectorvoz.core.model.HighlightKind
import com.lectorvoz.core.model.LocatorRange

/**
 * Resaltados activos (inmutable). Frase, palabra y seleccion tienen un rango cada una; la
 * busqueda puede tener varios con uno "actual".
 */
data class Highlights(
    val sentence: LocatorRange? = null,
    val word: LocatorRange? = null,
    val selection: LocatorRange? = null,
    val search: List<LocatorRange> = emptyList(),
    val searchCurrent: LocatorRange? = null,
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
    fun all(): List<LocatorRange> = listOfNotNull(sentence, word, selection) + search

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
