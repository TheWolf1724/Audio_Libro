package com.lectorvoz.format.pdf

/**
 * Limpiador basico de texto PDF para la voz (logica pura): une las lineas de un mismo
 * parrafo con un espacio, quita el guion de fin de linea cuando la palabra sigue en la
 * linea siguiente, salta los rangos excluidos (cabeceras/pies) y colapsa espacios.
 *
 * Fin de parrafo (se emite '\n') si entre dos lineas hay una linea en blanco, la siguiente
 * empieza por raya/vineta, hay un salto vertical claro (> 1,5 interlineas), la anterior
 * acaba en puntuacion de fin de frase y es corta o la siguiente esta sangrada, o la
 * anterior es muy corta (titulo) y la siguiente empieza en mayuscula. La geometria es
 * opcional: sin cajas solo se usan las reglas de texto.
 *
 * `:app` puede sustituirlo por el filtro de `:core:text` a traves de [PageTextCleaner.flow].
 */
object BasicPdfTextFlow {

    /** Flujo de [page] saltando [excluded] (indices de pdfium). */
    fun flow(page: PdfPageText, excluded: List<IntRange>): PdfFlowText =
        flow(page.rawText, excluded) { i, out -> page.boxInto(i, out) }

    private class Line(val start: Int, val end: Int, val breakAt: Int) {
        /** Primer y ultimo (incluido) caracter con contenido, o -1. */
        var first = -1
        var last = -1
        var removed = false
        val blank: Boolean get() = first < 0 && !removed
        var left = Float.NaN
        var top = Float.NaN
        var bottom = Float.NaN
        val length: Int get() = if (first < 0) 0 else last - first + 1
    }

    /**
     * [box] escribe la caja (left, top, right, bottom) del caracter `i` y devuelve false si
     * no tiene geometria; nulo = sin geometria.
     */
    fun flow(raw: String, excluded: List<IntRange>, box: ((Int, FloatArray) -> Boolean)? = null): PdfFlowText {
        val n = raw.length
        if (n == 0) return PdfFlowText("", IntArray(0), 0)
        val skip = BooleanArray(n)
        for (r in excluded) {
            val a = r.first.coerceAtLeast(0)
            val b = r.last.coerceAtMost(n - 1)
            for (i in a..b) skip[i] = true
        }
        val lines = splitLines(raw)
        val tmp = FloatArray(4)
        for (l in lines) {
            var anyExcluded = false
            for (i in l.start until l.end) {
                if (skip[i]) { anyExcluded = true; continue }
                if (isSpace(raw[i])) continue
                if (l.first < 0) l.first = i
                l.last = i
                if (box != null && box(i, tmp)) {
                    if (l.left.isNaN()) l.left = tmp[0]
                    l.top = if (l.top.isNaN()) tmp[1] else minOf(l.top, tmp[1])
                    l.bottom = if (l.bottom.isNaN()) tmp[3] else maxOf(l.bottom, tmp[3])
                }
            }
            if (l.first < 0 && anyExcluded) l.removed = true
        }
        val content = lines.filter { it.first >= 0 }
        val typicalLen = percentile(content.map { it.length.toFloat() }, 0.75f)
        val lefts = content.mapNotNull { l -> l.left.takeUnless { it.isNaN() } }
        val typicalLeft = if (lefts.isEmpty()) Float.NaN else percentile(lefts, 0.3f)
        val em = percentile(content.mapNotNull { l -> (l.bottom - l.top).takeIf { it.isFinite() && it > 0f } }, 0.5f)
        val pitches = ArrayList<Float>()
        for (k in 1 until content.size) {
            val d = content[k].top - content[k - 1].top
            if (d.isFinite() && d > 0f) pitches += d
        }
        val pitch = percentile(pitches, 0.5f)

        val sb = StringBuilder(n)
        val map = IntArray(n + 1)
        fun push(c: Char, src: Int) {
            map[sb.length] = src
            sb.append(c)
        }

        // Siguiente linea con contenido de cada linea y si hay una en blanco entre ambas.
        val nextIdx = IntArray(lines.size) { -1 }
        val blankAfter = BooleanArray(lines.size)
        var following = -1
        var sawBlank = false
        for (k in lines.indices.reversed()) {
            nextIdx[k] = following
            blankAfter[k] = sawBlank
            val l = lines[k]
            if (l.first >= 0) { following = k; sawBlank = false } else if (l.blank) sawBlank = true
        }

        var prev = -1
        for (k in lines.indices) {
            val l = lines[k]
            if (l.first < 0) continue
            if (prev >= 0) {
                val a = lines[prev]
                when (decide(raw, a, l, blankAfter[prev], typicalLen, typicalLeft, em, pitch)) {
                    Join.PARAGRAPH -> push('\n', a.breakAt)
                    Join.SPACE -> push(' ', a.breakAt)
                    Join.NONE -> Unit
                }
            }
            // Guion de fin de linea: si la palabra sigue en la linea siguiente, no se emite.
            val nx = nextIdx[k]
            val stop = if (nx >= 0 && joins(raw, l, lines[nx], blankAfter[k])) l.last - 1 else l.last
            emitRange(raw, l.first, stop, skip, sb, ::push)
            prev = k
        }
        // Quitar espacios finales.
        var len = sb.length
        while (len > 0 && sb[len - 1].isWhitespace()) len--
        return PdfFlowText(sb.substring(0, len), map.copyOf(len), n)
    }

    private enum class Join { PARAGRAPH, SPACE, NONE }

    private fun decide(
        raw: String, a: Line, b: Line, blankBetween: Boolean,
        typicalLen: Float, typicalLeft: Float, em: Float, pitch: Float,
    ): Join {
        if (joins(raw, a, b, blankBetween)) return Join.NONE
        if (blankBetween) return Join.PARAGRAPH
        val bFirst = raw[b.first]
        if (bFirst in DIALOG_STARTS) return Join.PARAGRAPH
        if (bFirst == '-' && b.first + 1 < raw.length && raw[b.first + 1] == ' ') return Join.PARAGRAPH
        if (pitch > 0f && a.top.isFinite() && b.top.isFinite() && b.top - a.top > pitch * 1.5f) return Join.PARAGRAPH
        val ends = endsSentence(raw, a)
        val indented = em > 0f && typicalLeft.isFinite() && b.left.isFinite() && b.left - typicalLeft > em * 0.8f
        if (ends && (a.length < typicalLen * 0.8f || indented)) return Join.PARAGRAPH
        if (a.length < typicalLen * 0.6f && bFirst.isUpperCase()) return Join.PARAGRAPH
        return Join.SPACE
    }

    private fun emitRange(
        raw: String, from: Int, to: Int, skip: BooleanArray, sb: StringBuilder, push: (Char, Int) -> Unit,
    ) {
        var gapAt = -1
        for (i in from..to) {
            if (skip[i]) { if (gapAt < 0) gapAt = i; continue }
            val c = raw[i]
            if (c == '￾' || c == '￿') continue
            if (isSpace(c)) {
                if (gapAt < 0) gapAt = i
                continue
            }
            if (gapAt >= 0) {
                if (sb.isNotEmpty() && !sb[sb.length - 1].isWhitespace()) push(' ', gapAt)
                gapAt = -1
            }
            push(if (c == PDFIUM_HYPHEN) '-' else c, i)
        }
    }

    /** true si [a] acaba en guion de corte y la palabra sigue en [b]. */
    private fun joins(raw: String, a: Line, b: Line, blankBetween: Boolean): Boolean =
        !blankBetween && isJoinHyphen(raw, a) && raw[b.first].isLowerCase()

    private fun isJoinHyphen(raw: String, l: Line): Boolean {
        if (l.length < 2) return false
        val h = raw[l.last]
        return (h == PDFIUM_HYPHEN || h == '-' || h == PdfFlowText.SOFT_HYPHEN || h == '‐') && raw[l.last - 1].isLetter()
    }

    private fun endsSentence(raw: String, l: Line): Boolean = raw[l.last] in SENTENCE_END

    private fun isSpace(c: Char): Boolean = c != PDFIUM_HYPHEN && (c.isWhitespace() || c < ' ')

    private fun splitLines(raw: String): List<Line> {
        val out = ArrayList<Line>()
        var s = 0
        var i = 0
        val n = raw.length
        while (i < n) {
            val c = raw[i]
            if (c == '\r' || c == '\n') {
                val breakAt = i
                if (c == '\r' && i + 1 < n && raw[i + 1] == '\n') i++
                out += Line(s, breakAt, breakAt)
                s = i + 1
            }
            i++
        }
        out += Line(s, n, -1)
        return out
    }

    private fun percentile(values: List<Float>, p: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)]
    }

    /** Guion blando de fin de linea que pdfium marca con U+0002. */
    private const val PDFIUM_HYPHEN = '\u0002'
    private const val SENTENCE_END = ".!?:;»\"”…)"
    private const val DIALOG_STARTS = "—–•·▪●"
}
