package com.lectorvoz.format.pdf

import android.graphics.RectF

/**
 * Texto de una pagina PDF con geometria por caracter.
 *
 * [rawText] tiene un caracter por indice de pdfium (es el `offset` de [com.lectorvoz.core.model.Locator]).
 * [flow] es el texto fluido para la voz (lineas de un parrafo unidas, guiones de corte
 * quitados, rangos excluidos fuera) con su mapa a indices de pdfium, segun [cleaner].
 * [cleanText] es [flow] proyectado a la MISMA longitud que [rawText] (huecos con espacio o
 * guion blando), de modo que sus desplazamientos coinciden 1:1 con los de pdfium.
 */
class PdfPageText(
    val pageIndex: Int,
    val rawText: String,
    /** 4 floats por caracter (left, top, right, bottom) en puntos, origen arriba a la izquierda. */
    private val boxes: FloatArray,
    val pageSize: PageSize,
    private val cleaner: PageTextCleaner = PageTextCleaner.Identity,
) {
    init {
        require(boxes.size == rawText.length * 4) { "boxes debe tener 4 floats por caracter" }
    }

    val length: Int get() = rawText.length

    /** Rangos excluidos (cabeceras/pies), ordenados y dentro del texto. */
    val excludedRanges: List<IntRange> by lazy {
        cleaner.excludedRanges(this)
            .map { it.first.coerceAtLeast(0)..it.last.coerceAtMost(length - 1) }
            .filter { !it.isEmpty() }
            .sortedBy { it.first }
    }

    /** Texto fluido para la voz con mapa a indices de pdfium. */
    val flow: PdfFlowText by lazy {
        try {
            cleaner.flow(this, excludedRanges).takeIf { it.sourceLength == length }
                ?: BasicPdfTextFlow.flow(this, excludedRanges)
        } catch (e: IllegalArgumentException) {
            BasicPdfTextFlow.flow(this, excludedRanges)
        }
    }

    /** [flow] con un caracter por indice de pdfium (ver [PdfFlowText.alignedText]). */
    val cleanText: String by lazy { flow.alignedText() }

    /** true si se limpio con [other]. */
    fun usesCleaner(other: PageTextCleaner): Boolean = other === cleaner

    /** Copia con otro limpiador (comparte texto y cajas). */
    fun withCleaner(other: PageTextCleaner): PdfPageText =
        if (other === cleaner) this else PdfPageText(pageIndex, rawText, boxes, pageSize, other)

    /** Copia la caja del caracter [i] en [out] (left, top, right, bottom); false si no tiene. */
    fun boxInto(i: Int, out: FloatArray): Boolean {
        if (!hasBox(i)) return false
        System.arraycopy(boxes, i * 4, out, 0, 4)
        return true
    }

    /** true si el caracter [i] tiene caja (no es un salto generado ni espacio sin geometria). */
    fun hasBox(i: Int): Boolean =
        i in 0 until length && (boxes[i * 4 + 2] > boxes[i * 4] || boxes[i * 4 + 3] > boxes[i * 4 + 1])

    /** Copia la caja del caracter [i] en [out]; false si no tiene. */
    fun charBox(i: Int, out: RectF): Boolean {
        if (!hasBox(i)) return false
        out.set(boxes[i * 4], boxes[i * 4 + 1], boxes[i * 4 + 2], boxes[i * 4 + 3])
        return true
    }

    /**
     * Rectangulos (en puntos de pagina, origen arriba a la izquierda) que cubren los
     * caracteres [start, end). Une caracteres consecutivos de la misma linea en un unico
     * rectangulo.
     */
    fun rectsForRange(start: Int, end: Int): List<RectF> {
        val s = start.coerceIn(0, length)
        val e = end.coerceIn(s, length)
        val out = ArrayList<RectF>()
        var cur: RectF? = null
        for (i in s until e) {
            if (!hasBox(i)) continue
            val l = boxes[i * 4]; val t = boxes[i * 4 + 1]; val r = boxes[i * 4 + 2]; val b = boxes[i * 4 + 3]
            val c = cur
            if (c != null && sameLine(c, t, b) && l >= c.left - (b - t)) {
                c.union(l, t, r, b)
            } else {
                cur = RectF(l, t, r, b).also { out += it }
            }
        }
        return out
    }

    private fun sameLine(c: RectF, t: Float, b: Float): Boolean {
        val overlap = minOf(c.bottom, b) - maxOf(c.top, t)
        val minH = minOf(c.height(), b - t)
        return overlap > minH * 0.5f
    }

    /**
     * Indice del caracter bajo el punto ([x], [y]) en puntos de pagina, o el mas cercano
     * dentro de [tolerance] puntos. -1 si no hay ninguno.
     */
    fun charIndexAt(x: Float, y: Float, tolerance: Float = 12f): Int {
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (i in 0 until length) {
            if (!hasBox(i)) continue
            val l = boxes[i * 4]; val t = boxes[i * 4 + 1]; val r = boxes[i * 4 + 2]; val b = boxes[i * 4 + 3]
            val dx = if (x < l) l - x else if (x > r) x - r else 0f
            val dy = if (y < t) t - y else if (y > b) y - b else 0f
            if (dx == 0f && dy == 0f) return i
            // La distancia vertical pesa mas: preferir la misma linea.
            val dist = dx + dy * 2f
            if (dist < bestDist) { bestDist = dist; best = i }
        }
        return if (best >= 0 && bestDist <= tolerance * 2f) best else -1
    }
}
