package com.lectorvoz.tts.engine

import com.lectorvoz.core.model.PauseKind
import com.lectorvoz.core.model.ReadingLanguage
import com.lectorvoz.core.model.Utterance
import com.lectorvoz.core.model.UtteranceKind
import com.lectorvoz.core.model.VoiceSettings

/**
 * Pausa calibrada tras cada frase segun `Utterance.pauseAfter` y las pausas en ms de
 * [VoiceSettings], multiplicadas por `punctuationPause`. Se anade despues de recortar el
 * silencio propio del modelo, asi que es la pausa real que se oye.
 */
object Pauses {
    /** Pausa en milisegundos tras una unidad con pausa [kind]. */
    fun afterMs(kind: PauseKind, s: VoiceSettings): Int {
        val base = when (kind) {
            PauseKind.NONE -> 0
            PauseKind.CLAUSE -> s.pauseClauseMs
            PauseKind.SENTENCE -> s.pauseSentenceMs
            PauseKind.VERSE -> s.pauseVerseMs
            PauseKind.PARAGRAPH -> s.pauseParagraphMs
            PauseKind.SECTION -> s.pauseSectionMs
            PauseKind.CHAPTER -> s.pauseChapterMs
        }
        return (base * s.punctuationPause).toInt().coerceAtLeast(0)
    }

    /** Pausa tras [u] con los ajustes [s]. */
    fun afterMs(u: Utterance, s: VoiceSettings): Int = afterMs(u.pauseAfter, s)

    /** Muestras de silencio para [ms] milisegundos a [sampleRate]. */
    fun samples(ms: Int, sampleRate: Int): Int = (ms.toLong() * sampleRate / 1000L).toInt()
}

/**
 * Ritmo adaptativo: factor 0,9..1,0 sobre la velocidad. Algo mas lento en titulos, en el
 * anuncio de capitulo y en frases con muchas cifras (se mira el texto visible, porque el
 * hablado ya trae las cifras en letra).
 */
object AdaptivePace {
    /** Factor en titulos y anuncios de capitulo. */
    const val HEADING = 0.92f

    /** Factor minimo (cifras muy densas). */
    const val MIN = 0.9f

    /** Factor de velocidad para [u] (1 si `adaptivePace` esta desactivado). */
    fun factor(u: Utterance, s: VoiceSettings): Float {
        if (!s.adaptivePace) return 1f
        var f = when (u.kind) {
            UtteranceKind.HEADING, UtteranceKind.CHAPTER_ANNOUNCE -> HEADING
            else -> 1f
        }
        f = minOf(f, digitFactor(u.displayText))
        return f.coerceIn(MIN, 1f)
    }

    /** 1 sin cifras; baja hasta [MIN] cuando las cifras son muchas y densas. */
    fun digitFactor(text: String): Float {
        var digits = 0
        var alnum = 0
        for (c in text) {
            if (c.isDigit()) digits++
            if (c.isLetterOrDigit()) alnum++
        }
        if (digits < 4 || alnum == 0) return 1f
        val ratio = digits.toFloat() / alnum
        return when {
            ratio >= 0.3f -> MIN
            ratio >= 0.12f -> 0.95f
            else -> 1f
        }
    }
}

/** Idioma de sintesis por frase ("es" o "en"). */
object SpeechLanguage {
    /** Idioma efectivo de [u]: el forzado por el usuario o el detectado en la frase. */
    fun of(u: Utterance, s: VoiceSettings): String = when (s.readingLanguage) {
        ReadingLanguage.SPANISH -> "es"
        ReadingLanguage.ENGLISH -> "en"
        ReadingLanguage.AUTO -> normalize(u.language)
    }

    /** "en-GB" -> "en"; cualquier otra cosa desconocida -> "es". */
    fun normalize(tag: String?): String {
        val primary = tag?.substringBefore('-')?.substringBefore('_')?.lowercase().orEmpty()
        return if (primary == "en") "en" else "es"
    }
}
