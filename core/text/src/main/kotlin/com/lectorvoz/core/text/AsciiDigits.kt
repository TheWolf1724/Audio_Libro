package com.lectorvoz.core.text

/**
 * Convierte los digitos Unicode que no son ASCII (ancho completo `３`, arabigo-indicos `٣`,
 * devanagari, matematicos...) a '0'..'9', con mapa al texto original. Los patrones `\d` de
 * Java solo reconocen ASCII: sin este paso un digito Unicode llegaba al normalizador como
 * "digito" pero ningun patron lo aceptaba.
 */
internal object AsciiDigits {
    /** Texto con los digitos en ASCII, o null si [text] ya no tenia ninguno distinto. */
    fun fold(text: String): MappedText? {
        var i = 0
        val n = text.length
        var found = false
        while (i < n) {
            val cp = text.codePointAt(i)
            if (cp > 0x7F && Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt()) {
                found = true
                break
            }
            i += Character.charCount(cp)
        }
        if (!found) return null
        val sb = StringBuilder(n)
        val map = IntArray(n)
        var k = 0
        i = 0
        while (i < n) {
            val cp = text.codePointAt(i)
            val len = Character.charCount(cp)
            if (cp > 0x7F && Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt()) {
                val d = Character.digit(cp, 10)
                sb.append(('0'.code + d).toChar())
                map[k++] = i
            } else {
                for (j in 0 until len) {
                    sb.append(text[i + j])
                    map[k++] = i + j
                }
            }
            i += len
        }
        return MappedText(sb.toString(), map.copyOf(k))
    }

    /** Aplica [normalize] sobre el texto con digitos ASCII y recompone el mapa al original. */
    inline fun normalizeFolded(text: String, normalize: (String) -> MappedText): MappedText {
        val folded = fold(text) ?: return normalize(text)
        return normalize(folded.text).composeWith(folded.toSource)
    }
}

/** true si [c] es un digito ASCII 0-9 (los patrones `\d` solo aceptan estos). */
internal fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'
