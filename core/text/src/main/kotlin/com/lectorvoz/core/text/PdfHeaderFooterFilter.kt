package com.lectorvoz.core.text

/**
 * Firmas de linea para reconocer cabeceras, pies y numeros de pagina en texto extraido de
 * PDF (lo usa [PdfPageFlow]).
 */
internal object PdfHeaderFooterFilter {
    private val ROMAN_TOKEN = Regex("(?<![\\p{L}])[ivxlcdm]{1,7}(?![\\p{L}])")
    /** Palabras corrientes que tambien son romanos validos: no son numeros de pagina. */
    private val COMMON_WORDS = setOf("mi", "di", "vi", "li", "ci", "mix", "dim", "mid", "mil", "civil")
    private val DIGITS = Regex("\\d+")
    private val SPACES = Regex("\\s+")
    private val PAGE_NUMBER = Regex("^(?:p[aá]g(?:ina)?\\.?|page|p\\.)?\\s*[-–—\\[(]?\\s*#\\s*[-–—\\])]?(?:\\s*(?:de|of|/)\\s*#)?$")
    private val UPPER_ROMAN = Regex("^[\\[(]?\\s*[IVXLCDM]{1,7}\\s*[\\])]?\\.?$")

    /** Firma de una linea: minusculas, numeros y romanos como '#', espacios colapsados. */
    fun signature(line: String): String {
        var t = line.trim().lowercase()
        t = DIGITS.replace(t, "#")
        // Solo los romanos validos ("iv", "xii"): "mil" o "civil" son palabras, no numeros de pagina.
        t = ROMAN_TOKEN.replace(t) { m -> if (m.value !in COMMON_WORDS && SpanishNormalizer.isRoman(m.value.uppercase())) "#" else m.value }
        return SPACES.replace(t, " ")
    }

    /** true si la firma [sig] es la de un numero de pagina ("#", "- # -", "pagina # de #"). */
    fun isPageNumber(sig: String): Boolean = PAGE_NUMBER.matches(sig)

    /**
     * true si la linea original [line] es un numero de pagina. Un romano en mayusculas
     * ("II", "IV.") no lo es: en la cabeza de una pagina suele ser el titulo de un capitulo
     * (los libros numeran sus paginas preliminares en minusculas).
     */
    fun isPageNumberLine(line: String): Boolean {
        val t = line.trim()
        if (UPPER_ROMAN.matches(t)) return false
        return isPageNumber(signature(t))
    }
}
