package com.lectorvoz.tts.neural

import com.lectorvoz.tts.voice.VoiceRef

/** Parametros de una sintesis (todos por generacion: no exigen recargar el modelo). */
data class SynthesisParams(
    /** Velocidad del modelo ya multiplicada por la base de la voz y el ritmo adaptativo. */
    val speed: Float = 1f,
    /** Locutor. */
    val sid: Int = 0,
    /** Idioma de la frase para Supertonic (`extra["lang"]`): "es", "en"... */
    val lang: String? = "es",
    /** Pasos de generacion de Supertonic (`num_steps`, VoiceQuality.steps). */
    val numSteps: Int = 8,
)

/**
 * Sintetizador PCM float mono. No es seguro entre hilos: el motor lo usa solo desde su
 * hilo de sintesis. Las llamadas son bloqueantes.
 */
interface NeuralSynthesizer {
    /** Frecuencia de muestreo de la salida. */
    val sampleRate: Int

    /**
     * Sintetiza [text]. Devuelve nulo si [isCancelled] paso a true durante la sintesis
     * (se consulta en cada bloque que entrega el modelo).
     */
    fun synthesize(text: String, params: SynthesisParams, isCancelled: () -> Boolean): FloatArray?

    /** Libera el modelo nativo. */
    fun release()
}

/** Carga sintetizadores (bloqueante, puede tardar segundos). */
fun interface NeuralSynthesizerLoader {
    /** Carga el modelo de la voz [ref] con [numThreads] hilos de inferencia. */
    fun load(ref: VoiceRef, numThreads: Int): NeuralSynthesizer
}
