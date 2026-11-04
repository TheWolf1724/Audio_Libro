package com.lectorvoz.core.text

/**
 * Convierte a caracteres corrientes, con mapa al texto original:
 * - los digitos Unicode que no son ASCII (ancho completo `３`, arabigo-indicos `٣`,
 *   devanagari, matematicos...) a '0'..'9': los patrones `\d` de Java solo reconocen ASCII;
 * - las letras y cifras "estilizadas" que algunos libros usan para la cursiva o la negrita
 *   (bloque matematico `𝘕𝘪𝘷𝘦𝘭`, ancho completo), los numeros romanos de un caracter (`Ⅲ`),
 *   los numeros en circulo (`③`), las ligaduras (`ﬁ`) y las cifras de estilo antiguo que
 *   algunos PDF dejan en la zona de uso privado: la voz no los conoce y los callaba.
 * No toca superindices ni ordinales (`º`, `ª`, `²`): tienen su propio tratamiento.
 */
internal object AsciiDigits {
    /** Texto plegado, o null si [text] no tenia nada que plegar. */
    fun fold(text: String): MappedText? {
        var i = 0
        val n = text.length
        var found = false
        while (i < n) {
            val cp = text.codePointAt(i)
            if (cp > 0x7F && compat(cp) != null) {
                found = true
                break
            }
            i += Character.charCount(cp)
        }
        if (!found) return null
        val sb = StringBuilder(n)
        var map = IntArray(n + 8)
        var k = 0
        i = 0
        while (i < n) {
            val cp = text.codePointAt(i)
            val len = Character.charCount(cp)
            val rep = if (cp > 0x7F) compat(cp) else null
            if (rep != null) {
                for (c in rep) {
                    if (k == map.size) map = map.copyOf(map.size * 2)
                    sb.append(c)
                    map[k++] = i
                }
            } else {
                for (j in 0 until len) {
                    if (k == map.size) map = map.copyOf(map.size * 2)
                    sb.append(text[i + j])
                    map[k++] = i + j
                }
            }
            i += len
        }
        return MappedText(sb.toString(), map.copyOf(k))
    }

    /** Equivalente corriente de [cp], o null si se deja como esta. */
    private fun compat(cp: Int): String? {
        if (Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt()) return ('0'.code + Character.digit(cp, 10)).toChar().toString()
        if (cp in 0xF730..0xF739) return ('0'.code + (cp - 0xF730)).toChar().toString()
        val styled = cp in 0x1D400..0x1D7FF || cp in 0xFF01..0xFF5E || cp in 0x2160..0x2188 ||
            cp in 0x2460..0x24FF || cp in 0xFB00..0xFB06
        if (!styled) return null
        val src = String(Character.toChars(cp))
        return java.text.Normalizer.normalize(src, java.text.Normalizer.Form.NFKC).takeIf { it.isNotEmpty() && it != src }
    }

    /** Aplica [normalize] sobre el texto con digitos ASCII y recompone el mapa al original. */
    inline fun normalizeFolded(text: String, normalize: (String) -> MappedText): MappedText {
        val folded = fold(text) ?: return normalize(text)
        return normalize(folded.text).composeWith(folded.toSource)
    }
}

/** true si [c] es un digito ASCII 0-9 (los patrones `\d` solo aceptan estos). */
internal fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'
