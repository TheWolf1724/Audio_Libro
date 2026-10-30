package com.lectorvoz.core.model

/**
 * Posicion en un libro.
 * EPUB: indice de recurso del spine + desplazamiento de caracter en el texto plano de
 * ese capitulo. PDF: indice de pagina + indice de caracter de pdfium.
 */
data class Locator(val chapter: Int, val offset: Int) : Comparable<Locator> {
    init {
        require(chapter >= 0) { "chapter negativo: $chapter" }
        require(offset >= 0) { "offset negativo: $offset" }
    }

    override fun compareTo(other: Locator): Int =
        compareValuesBy(this, other, Locator::chapter, Locator::offset)

    companion object {
        /** Principio del libro. */
        val START = Locator(0, 0)
    }
}

/** Rango semiabierto [start, endExclusive) entre dos posiciones. */
data class LocatorRange(val start: Locator, val endExclusive: Locator) {
    init {
        require(start <= endExclusive) { "Rango invertido: $start > $endExclusive" }
    }

    /** true si el rango no contiene ningun caracter. */
    val isEmpty: Boolean get() = start == endExclusive

    /** true si [locator] cae dentro del rango. */
    operator fun contains(locator: Locator): Boolean =
        locator >= start && locator < endExclusive
}
