package com.lectorvoz.app.integration

/**
 * Texto de un capitulo en forma comparable (sin marcas de imagen ni uniones invisibles, con los espacios y saltos
 * colapsados) y el mapa a las posiciones originales: para comprobar que un subrayado sigue sobre su cita y, si el
 * texto cambio (OCR, otra version del analizador), reubicarlo.
 */
class AnchorText(text: String) {
    val norm: String
    private val map: IntArray // posicion normalizada -> original

    init {
        val sb = StringBuilder(text.length)
        val idx = IntArray(text.length)
        var lastSpace = true
        for (i in text.indices) {
            val c = text[i]
            if (c == '\uFFFC' || c == '\u200B') continue
            if (c.isWhitespace()) {
                if (lastSpace) continue
                idx[sb.length] = i
                sb.append(' ')
                lastSpace = true
            } else {
                idx[sb.length] = i
                sb.append(c)
                lastSpace = false
            }
        }
        norm = sb.toString().trimEnd()
        map = idx
    }

    /** Cita en la misma forma que el texto. */
    fun normalize(quote: String): String = AnchorText(quote).norm

    /**
     * Rango actual de la cita guardada en [start, end): el mismo si sigue coincidiendo; si no, la aparicion mas
     * cercana a [start]; nulo si el texto ya no la contiene (subrayado huerfano).
     */
    fun locate(quote: String, start: Int, end: Int, original: String): IntRange? {
        val q = normalize(quote)
        if (q.isEmpty()) return null
        if (start in 0 until end && end <= original.length && AnchorText(original.substring(start, end)).norm == q) return start until end
        var best = -1
        var bestDist = Int.MAX_VALUE
        var from = 0
        while (true) {
            val i = norm.indexOf(q, from)
            if (i < 0) break
            val dist = kotlin.math.abs(map[i] - start)
            if (dist < bestDist) { bestDist = dist; best = i }
            from = i + 1
        }
        if (best < 0) return null
        val last = best + q.length - 1
        return map[best] until (map[last] + 1)
    }
}
