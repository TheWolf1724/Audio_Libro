package com.lectorvoz.app.integration

import com.lectorvoz.core.data.pronunciation.PronunciationEntry
import com.lectorvoz.core.model.ChapterText
import com.lectorvoz.core.text.UtteranceBuilder

/** Utilidades de texto de `:core:text` para la UI (prueba en vivo del diccionario). */
object TextTools {
    /** Texto que se pronunciaria para [text] con las reglas [entries] (normalizado). */
    fun spokenPreview(entries: List<PronunciationEntry>, text: String): String {
        if (text.isBlank()) return ""
        val builder = UtteranceBuilder(dictionary = dictionaryOf(entries))
        return builder.build(ChapterText(0, text, null, "es"), 0, 0L).joinToString(" ") { it.spokenText }
    }

    /** Reglas RegEx que no compilan (para avisar al editar). */
    fun invalidPatterns(entries: List<PronunciationEntry>): Set<String> =
        dictionaryOf(entries).invalidRules.map { it.pattern }.toSet()

    private val ABBREVIATIONS = setOf(
        "sr", "sra", "srta", "dr", "dra", "prof", "ud", "uds", "vs", "cap", "pag", "pags", "av", "avda", "gral",
        "dpto", "apdo", "ej", "tel", "pp", "vol", "num", "fig", "mr", "mrs",
    )

    /** true si en [i] termina una frase: signo de cierre, salvo un punto de abreviatura, decimal o inicial. */
    private fun isTerminal(text: String, i: Int): Boolean {
        val c = text[i]
        if (c == '!' || c == '?' || c == '\u2026') return true
        if (c != '.') return false
        val next = text.getOrNull(i + 1)
        // 3.5, p.ej, www.web: sigue una minuscula o un decimal ("Fin.Siguiente" pegado si corta)
        if (next != null && (next.isLowerCase() || (next.isDigit() && i > 0 && text[i - 1].isDigit()))) return false
        var w = i
        while (w > 0 && text[w - 1].isLetter()) w--
        val word = text.substring(w, i)
        if (word.lowercase() in ABBREVIATIONS) return false
        // "p. ej.": la p suelta antes de "ej"
        if (word == "p" && text.startsWith("ej", (i + 1..text.length).firstOrNull { it >= text.length || text[it] != ' ' } ?: text.length)) return false
        // Inicial ("J. R. R. Tolkien"): una mayuscula suelta seguida de otra palabra con mayuscula.
        if (word.length == 1 && word[0].isUpperCase()) {
            var n = i + 1
            while (n < text.length && text[n] == ' ') n++
            if (n < text.length && text[n].isUpperCase()) return false
        }
        return true
    }

    /**
     * Frase de [text] que contiene [offset]: desde el final de la anterior (. ! ? o salto de linea) hasta su
     * propio punto final inclusive; los puntos de abreviaturas ("Sr.", "p. ej."), decimales e iniciales no la
     * cortan. Nula si ahi no hay texto.
     */
    fun sentenceBounds(text: String, offset: Int): IntRange? {
        if (text.isBlank()) return null
        val pos = offset.coerceIn(0, text.length - 1)
        var s = pos
        while (s > 0 && text[s - 1] != '\n' && !(isTerminal(text, s - 1) && s - 1 != pos)) s--
        var e = pos
        while (e < text.length && text[e] != '\n' && !isTerminal(text, e)) e++
        // incluye el signo final (y comillas o cierres que lo acompanan)
        while (e < text.length && (text[e] == '.' || text[e] == '!' || text[e] == '?' || text[e] == '\u2026' || text[e] in "\"')\u00BB\u201D")) e++
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        return if (e > s) s until e else null
    }

    /** Palabra de [text] que contiene la posicion [offset] (o la siguiente). */
    fun wordAt(text: String, offset: Int): String? {
        if (text.isEmpty()) return null
        var i = offset.coerceIn(0, text.length - 1)
        while (i < text.length && !text[i].isLetterOrDigit()) i++
        if (i >= text.length) return null
        var s = i
        var e = i
        while (s > 0 && (text[s - 1].isLetterOrDigit() || text[s - 1] == '\'')) s--
        while (e < text.length && (text[e].isLetterOrDigit() || text[e] == '\'')) e++
        return text.substring(s, e).takeIf { it.isNotBlank() }
    }
}
