package com.lectorvoz.tts.engine

import com.lectorvoz.core.model.TtsEngineType
import com.lectorvoz.core.model.Utterance
import com.lectorvoz.core.model.VoiceSettings

/**
 * Motor de voz comun (sistema o neuronal).
 *
 * Contrato: [speak] es una funcion suspendida que lee desde [start] pidiendo las frases
 * siguientes a `next` y vuelve al terminar el libro (next devuelve nulo). Pausar o parar =
 * cancelar la corrutina que llama a [speak]; el motor corta el audio de inmediato. Quien
 * llama recuerda la ultima frase notificada en [SpeechListener.onUtteranceStart] y la vuelve
 * a pasar como `start` para reanudar.
 */
interface SpeechEngine {
    val type: TtsEngineType

    /**
     * Prepara el motor (inicializa TextToSpeech o carga el modelo) con [settings].
     * Lanza [SpeechEngineException] si la voz no esta disponible.
     */
    suspend fun prepare(settings: VoiceSettings)

    /** Lee desde [start]; ver contrato de la interfaz. */
    suspend fun speak(
        start: Utterance,
        next: suspend (Utterance) -> Utterance?,
        listener: SpeechListener,
    )

    /** Cambia velocidad, tono o pausas; se aplica desde la siguiente frase sintetizada. */
    fun updateSettings(settings: VoiceSettings)

    /** Volumen 0..1 de la salida (atenuacion por foco y fundido del temporizador). */
    fun setVolume(volume: Float)

    /** Libera recursos pesados (modelo, TextToSpeech). Se puede volver a [prepare]. */
    fun release()

    /** Libro abierto (clave de la cache de audio en disco); nulo si no hay ninguno. */
    fun setBook(bookId: String?) {}
}

/** Audio final de una frase: voz ya procesada + pausa calibrada. */
class RenderedAudio(val pcm: FloatArray, val silenceFrames: Int, val sampleRate: Int)

/** Motor que puede producir el audio de una frase sin reproducirlo (exportar). */
interface UtteranceRenderer {
    /** Audio final de [u] con [settings] (bloquea el hilo de sintesis, no el llamante). */
    suspend fun render(u: Utterance, settings: VoiceSettings): RenderedAudio
}

/** Motor que puede preparar audio en la cache de disco (mientras el movil carga). */
interface Pregenerator {
    /** Sintetiza y guarda [u] si no estaba; true si genero audio nuevo. */
    suspend fun pregenerate(u: Utterance, settings: VoiceSettings): Boolean

    /** true si hay cache de disco y queda sitio para seguir preparando. */
    val canPregenerate: Boolean

    /** Si [on], lo que se sintetiza al leer tambien se guarda en disco (no repetir trabajo). */
    fun setWriteThrough(on: Boolean)
}

/** Notificaciones del motor. Pueden llegar desde hilos de audio: no bloquear. */
interface SpeechListener {
    /** Empieza a sonar [utterance]. */
    fun onUtteranceStart(utterance: Utterance)

    /** Se dice la palabra [spokenStart, spokenEnd) de `utterance.spokenText`. */
    fun onWord(utterance: Utterance, spokenStart: Int, spokenEnd: Int)

    /** Termino de sonar [utterance]. */
    fun onUtteranceDone(utterance: Utterance)
}

/** Error del motor de voz con mensaje legible. */
class SpeechEngineException(message: String, cause: Throwable? = null) : Exception(message, cause)
