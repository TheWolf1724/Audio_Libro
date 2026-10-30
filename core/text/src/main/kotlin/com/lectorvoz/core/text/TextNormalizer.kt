package com.lectorvoz.core.text

/**
 * Normalizador de texto para voz: convierte el texto mostrado en texto pronunciable
 * (numeros, abreviaturas, simbolos...) y devuelve el mapa al texto mostrado.
 */
interface TextNormalizer {
    /** Idioma BCP-47 principal que trata (p. ej. "es"). */
    val language: String

    /** Normaliza [text]; `toSource` del resultado apunta a posiciones de [text]. */
    fun normalize(text: String): MappedText
}

/** Seleccion de normalizador por idioma. */
object TextNormalizers {
    /** Normalizador para el idioma BCP-47 [tag]; espanol por defecto. */
    fun forLanguage(tag: String?): TextNormalizer {
        val t = tag?.lowercase().orEmpty()
        return if (t == "en" || t.startsWith("en-") || t.startsWith("en_")) EnglishNormalizer() else SpanishNormalizer()
    }
}
