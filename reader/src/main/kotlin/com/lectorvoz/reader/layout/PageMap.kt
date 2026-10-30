package com.lectorvoz.reader.layout

/**
 * Corte de un capitulo en paginas: pagina -> rango de lineas y de desplazamientos del texto
 * plano del capitulo. Pagina `p` cubre [startOffset(p), endOffset(p)).
 */
class PageMap(
    /** Primera linea de cada pagina; tamano = numero de paginas + 1 (centinela = lineCount). */
    private val firstLines: IntArray,
    /** Desplazamiento inicial de cada pagina; tamano = paginas + 1 (centinela = longitud). */
    private val startOffsets: IntArray,
) {
    init {
        require(firstLines.size == startOffsets.size && firstLines.isNotEmpty())
    }

    val pageCount: Int get() = firstLines.size - 1

    fun firstLine(page: Int): Int = firstLines[page]

    /** Ultima linea (exclusiva) de [page]. */
    fun endLine(page: Int): Int = firstLines[page + 1]

    fun startOffset(page: Int): Int = startOffsets[page]

    fun endOffset(page: Int): Int = startOffsets[page + 1]

    /**
     * Pagina que contiene [offset]. Desplazamientos fuera del capitulo se ajustan a la
     * primera o ultima pagina.
     */
    fun pageForOffset(offset: Int): Int {
        if (pageCount <= 1) return 0
        // Busqueda binaria de la ultima pagina con startOffset <= offset.
        var lo = 0
        var hi = pageCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (startOffsets[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    override fun equals(other: Any?): Boolean =
        other is PageMap && firstLines.contentEquals(other.firstLines) && startOffsets.contentEquals(other.startOffsets)

    override fun hashCode(): Int = 31 * firstLines.contentHashCode() + startOffsets.contentHashCode()

    override fun toString(): String = "PageMap(pages=$pageCount, starts=${startOffsets.contentToString()})"
}
