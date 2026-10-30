package com.lectorvoz.core.model

/**
 * Texto de una llamada de nota ("1", "[2]", "(3)", "*", "†", "a", "iv"): lo comparten el
 * analisis EPUB (`EpubBook.footnoteTexts`) y la preparacion del texto para la voz.
 */
object NoteCallLabel {
    private val PATTERN = Regex("""^[\[(]?\s*(?:\d{1,4}|[*†‡§¶]{1,3}|[a-zA-Z]|[ivxlcIVXLC]{1,5})\s*[\])]?\.?$""")

    /** Longitud maxima de una llamada (sin espacios de los extremos). */
    const val MAX_LENGTH = 8

    /** true si [label] parece una llamada de nota. */
    fun matches(label: String): Boolean {
        val t = label.trim()
        return t.isNotEmpty() && t.length <= MAX_LENGTH && PATTERN.matches(t)
    }
}
