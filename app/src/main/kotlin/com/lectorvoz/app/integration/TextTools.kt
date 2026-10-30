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
