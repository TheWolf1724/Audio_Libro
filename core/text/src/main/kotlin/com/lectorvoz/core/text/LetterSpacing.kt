package com.lectorvoz.core.text

/**
 * Repara el texto "interletrado" de los PDF: titulos y epigrafes con espacio extra entre letras
 * (tracking) salen de pdfium como `P a r a  l o s  q u e` (un espacio entre CADA letra), y la voz los
 * deletrea. Lo que los distingue de los huecos entre palabras es la GEOMETRIA: el hueco entre letras de
 * una misma palabra es mas estrecho que el que separa palabras.
 *
 * Funcion pura. Devuelve un texto de la MISMA longitud (las cajas por caracter siguen valiendo, y con
 * ellas el resaltado): los espacios entre letras de una misma palabra pasan a U+200B (ancho cero,
 * invisible para el normalizador y que no corta la palabra); los de entre palabras se dejan.
 */
object LetterSpacing {
    /** Espacio de ancho cero: une las letras sin cambiar la longitud del texto. */
    const val JOINER = '​'

    private const val MIN_TOKENS = 5
    private const val MAX_TOKEN_LEN = 2
    private const val MAX_SINGLE_WORD_TOKENS = 14

    /**
     * [text] con las letras de cada palabra interletrada unidas, o el mismo [text] si no hay nada
     * que reparar o no hay cajas fiables. [boxes] = 4 floats por caracter (left, top, right, bottom).
     */
    fun fix(text: String, boxes: FloatArray): String {
        val n = text.length
        if (n < 9 || boxes.size != n * 4) return text
        var out: CharArray? = null
        var i = 0
        while (i < n) {
            val c = text[i]
            if (c == ' ' || c == '\n') { i++; continue }
            val e0 = tokenEnd(text, i)
            if (e0 - i > MAX_TOKEN_LEN) { i = e0; continue }
            // Candidato: reunir los tokens cortos consecutivos de esta linea.
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            var s = i
            var e = e0
            while (true) {
                if (e - s > MAX_TOKEN_LEN || !allLetters(text, s, e)) break
                starts.add(s); ends.add(e)
                var k = e
                while (k < n && text[k] == ' ') k++
                if (k >= n || text[k] == '\n' || k == e) break
                s = k
                e = tokenEnd(text, k)
            }
            val last = if (ends.isEmpty()) e0 else ends.last()
            if (starts.size >= MIN_TOKENS) {
                val fixed = repair(text, boxes, starts, ends)
                if (fixed != null) {
                    if (out == null) out = text.toCharArray()
                    for (idx in fixed) out[idx] = JOINER
                }
            }
            i = maxOf(last, i + 1)
        }
        return if (out == null) text else String(out)
    }

    /** Indices de los espacios a convertir en [JOINER], o null si el tramo no es interletrado/fiable. */
    private fun repair(text: String, boxes: FloatArray, starts: List<Int>, ends: List<Int>): List<Int>? {
        val singles = starts.indices.count { ends[it] - starts[it] == 1 }
        if (singles * 10 < starts.size * 6) return null
        val gaps = FloatArray(starts.size - 1)
        for (t in 0 until starts.size - 1) {
            val a = ends[t] - 1 // ultima letra del token
            val b = starts[t + 1] // primera letra del siguiente
            val ra = boxes[a * 4 + 2]
            val lb = boxes[b * 4]
            if (boxes[a * 4 + 2] <= boxes[a * 4] || boxes[b * 4 + 2] <= boxes[b * 4]) return null // sin geometria
            gaps[t] = lb - ra
        }
        val sorted = gaps.sortedArray()
        val median = sorted[sorted.size / 2]
        val threshold = maxOf(median * 1.6f, median + 1f)
        val isWord = BooleanArray(gaps.size) { gaps[it] > threshold }
        // Sin ningun hueco de palabra: o es UNA palabra interletrada, o una frase sin forma de separarla.
        if (isWord.none { it } && starts.size > MAX_SINGLE_WORD_TOKENS) return null
        val joins = ArrayList<Int>()
        for (t in gaps.indices) {
            if (isWord[t]) continue
            for (idx in ends[t] until starts[t + 1]) joins.add(idx)
        }
        return joins
    }

    private fun tokenEnd(text: String, from: Int): Int {
        var e = from
        while (e < text.length && text[e] != ' ' && text[e] != '\n') e++
        return e
    }

    private fun allLetters(text: String, s: Int, e: Int): Boolean {
        for (k in s until e) if (!text[k].isLetter()) return false
        return true
    }
}
