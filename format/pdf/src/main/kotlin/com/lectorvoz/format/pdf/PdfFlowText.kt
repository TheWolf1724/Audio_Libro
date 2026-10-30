package com.lectorvoz.format.pdf

/**
 * Texto de una pagina PDF preparado para la voz: [text] es texto fluido (lineas de un mismo
 * parrafo unidas, guiones de fin de linea quitados, cabeceras/pies fuera) y cada caracter
 * `i` procede del indice de pdfium `sourceIndex(i)`. El mapa es monotono no decreciente,
 * asi que los rangos se convierten en ambos sentidos con busqueda binaria.
 *
 * El indice de pdfium sigue siendo el `offset` de [com.lectorvoz.core.model.Locator]: quien
 * segmente [text] convierte sus rangos con [sourceStart]/[sourceEnd] antes de resaltar.
 */
class PdfFlowText(
    val text: String,
    private val map: IntArray,
    /** Longitud del texto de pdfium de la pagina. */
    val sourceLength: Int,
) {
    init {
        require(map.size == text.length) { "Mapa de longitud distinta al texto" }
        for (i in 1 until map.size) require(map[i] >= map[i - 1]) { "Mapa no monotono en $i" }
        require(map.isEmpty() || (map[0] >= 0 && map[map.size - 1] < sourceLength)) { "Mapa fuera del texto de origen" }
    }

    val length: Int get() = text.length

    /** Indice de pdfium del caracter [index] del texto fluido. */
    fun sourceIndex(index: Int): Int = map[index]

    /** Inicio en pdfium de un rango que empieza en [start] del texto fluido. */
    fun sourceStart(start: Int): Int = if (start >= map.size) sourceLength else map[start.coerceAtLeast(0)]

    /** Fin (exclusivo) en pdfium de un rango que acaba en [endExclusive] del texto fluido. */
    fun sourceEnd(endExclusive: Int): Int {
        if (endExclusive <= 0) return sourceStart(0)
        return map[(endExclusive - 1).coerceAtMost(map.size - 1)] + 1
    }

    /** Primer indice del texto fluido cuyo origen es >= [source] ([length] si no hay). */
    fun flowIndex(source: Int): Int {
        var lo = 0
        var hi = map.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (map[mid] < source) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Subcadena del texto fluido que corresponde al rango de pdfium [start, endExclusive). */
    fun textForSource(start: Int, endExclusive: Int): String {
        val a = flowIndex(start)
        val b = flowIndex(endExclusive).coerceAtLeast(a)
        return text.substring(a, b)
    }

    /**
     * Proyeccion a la longitud de pdfium (un caracter por indice), para quien necesite que
     * los desplazamientos del texto coincidan 1:1 con los de pdfium ([com.lectorvoz.core.model.ChapterText]).
     * Cada caracter fluido se coloca en su indice de origen; los huecos se rellenan con
     * espacio, o con guion blando (U+00AD, invisible para la voz) si estan dentro de una
     * palabra unida (guion de fin de linea).
     */
    fun alignedText(): String {
        val n = sourceLength
        val out = CharArray(n)
        val flowAt = IntArray(n) { -1 }
        for (i in map.indices) {
            val s = map[i]
            val prev = flowAt[s]
            if (prev < 0 || (out[s].isWhitespace() && !text[i].isWhitespace())) {
                out[s] = text[i]
                flowAt[s] = i
            }
        }
        var i = 0
        while (i < n) {
            if (flowAt[i] >= 0) { i++; continue }
            var j = i
            while (j < n && flowAt[j] < 0) j++
            val left = if (i > 0) flowAt[i - 1] else -1
            val right = if (j < n) flowAt[j] else -1
            val joined = left >= 0 && right == left + 1 &&
                !text[left].isWhitespace() && !text[right].isWhitespace()
            val fill = if (joined) SOFT_HYPHEN else ' '
            for (k in i until j) out[k] = fill
            i = j
        }
        return String(out)
    }

    companion object {
        const val SOFT_HYPHEN = '­'

        /** Texto identico al de pdfium (sin transformar). */
        fun identity(source: String): PdfFlowText = PdfFlowText(source, IntArray(source.length) { it }, source.length)
    }
}
