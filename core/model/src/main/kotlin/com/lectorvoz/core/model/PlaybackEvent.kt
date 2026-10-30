package com.lectorvoz.core.model

/** Eventos que emite el motor de voz (`:tts`) mientras lee. */
sealed interface PlaybackEvent {
    /** Empieza a sonar la frase [id], que ocupa [range] en el texto. */
    data class UtteranceStarted(val id: Long, val range: LocatorRange) : PlaybackEvent

    /** Se esta diciendo la palabra [displayStart, displayEnd) de `displayText` de la frase [id]. */
    data class WordRange(val id: Long, val displayStart: Int, val displayEnd: Int) : PlaybackEvent

    /** Termino la frase [id]. */
    data class UtteranceDone(val id: Long) : PlaybackEvent

    /** Termino el capitulo (o pagina en PDF) [chapter]. */
    data class ChapterFinished(val chapter: Int) : PlaybackEvent

    /** Error del motor; [utteranceId] es nulo si no afecta a una frase concreta. */
    data class Error(
        val message: String,
        val utteranceId: Long? = null,
        val cause: Throwable? = null,
    ) : PlaybackEvent
}
