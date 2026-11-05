package com.lectorvoz.core.text

/**
 * Clasificacion de caracteres para el modo de lectura MIXTA (texto nativo + OCR). Distingue los
 * glifos que son TEXTO REAL de los que pdfium emite cuando NO sabe decodificar un glifo.
 *
 * pdfium del movil, ante fuentes sin tabla ToUnicode (versalitas, fuentes decorativas), devuelve
 * el glifo mapeado al area de USO PRIVADO (U+E000..U+F8FF) o a simbolos sueltos, cada uno con su
 * CAJA. Esas cajas tapan tinta de verdad, pero el caracter NO es legible (no es texto). Hay que
 * tratarlos como "sin decodificar": ni cuentan como cobertura para el detector, ni sirven de ancla
 * para ordenar la lectura (pdfium suele emitirlos fuera del orden de lectura).
 */
object GlyphClass {
    /**
     * true si [ch] es texto legible cuyo glifo cuenta como "ya extraido": letra, cifra, puntuacion
     * o simbolo matematico/moneda habituales. Los espacios, los no imprimibles y los glifos de uso
     * privado / simbolos raros (lo que pdfium no decodifico) devuelven false.
     */
    fun decodesToText(ch: Char): Boolean {
        if (ch.isLetterOrDigit()) return true
        return when (Character.getType(ch)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            Character.MATH_SYMBOL.toInt(),
            Character.CURRENCY_SYMBOL.toInt(),
            -> true
            else -> false
        }
    }
}
