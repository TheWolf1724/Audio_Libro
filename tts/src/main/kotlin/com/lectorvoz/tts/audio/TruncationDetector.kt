package com.lectorvoz.tts.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Detecta si una frase sintetizada acaba cortada en seco (el modelo se comio el final).
 *
 * Supertonic, al pedirle mas velocidad de la que reproduce (y a veces por azar), descarta la
 * ultima silaba: la voz suena fuerte y pasa a silencio de golpe (<= 10 ms), y detras queda la
 * cola de silencio que habia planificado. Un final natural tarda 30-100 ms en apagarse. Se mide, con
 * la envolvente RMS en ventanas de [WINDOW_MS], cuanto tarda la voz en pasar de fuerte (a menos
 * de [LOUD_DB] del pico) a silencio (mas de [FLOOR_DB] por debajo): hay corte si tarda
 * [MAX_FALL_MS] o menos. Medir el tiempo de caida (y no la caida entre dos ventanas) hace el
 * criterio inmune a donde caigan las ventanas respecto al corte. Una pasada: trivial.
 */
object TruncationDetector {
    const val WINDOW_MS = 5

    /** Por debajo del pico menos esto es silencio. */
    const val FLOOR_DB = 45.0

    /** A menos de esto del pico la voz es "fuerte". */
    const val LOUD_DB = 20.0

    /** Caida de fuerte a silencio en este tiempo o menos: corte. */
    const val MAX_FALL_MS = 15

    /**
     * Si [pcm] acaba cortado en seco. [maxFallMs] es la caida mas rapida que aun se considera
     * natural: el audio que luego se va a acelerar se mira con un margen mayor, porque
     * comprimir el tiempo acorta la caida en la misma proporcion.
     */
    fun isTruncated(pcm: FloatArray, sampleRate: Int, maxFallMs: Int = MAX_FALL_MS): Boolean {
        val fall = finalFallMs(pcm, sampleRate) ?: return false
        return fall <= maxFallMs
    }

    /**
     * Milisegundos desde la ultima ventana fuerte hasta la primera ventana de silencio que la
     * sigue; nulo si no hay voz fuerte, si suena hasta el final o si todo es silencio.
     */
    fun finalFallMs(pcm: FloatArray, sampleRate: Int): Int? {
        val w = sampleRate * WINDOW_MS / 1000
        if (w < 1 || pcm.size < 3 * w) return null
        val n = pcm.size / w
        val env = DoubleArray(n) { k ->
            var s = 0.0
            for (i in k * w until (k + 1) * w) {
                val v = pcm[i]
                if (v.isFinite()) s += v * v
            }
            20 * log10(sqrt(s / w) + 1e-9)
        }
        val peak = env.max()
        if (peak < SILENCE_DBFS) return null
        var loud = n - 1
        while (loud >= 0 && env[loud] < peak - LOUD_DB) loud--
        if (loud < 0) return null
        var silence = loud + 1
        while (silence < n && env[silence] >= peak - FLOOR_DB) silence++
        // Si suena hasta el final no hay cola que mirar (el modelo siempre deja una).
        if (silence >= n) return null
        return (silence - loud) * WINDOW_MS
    }

    /** Pico por debajo de esto (dBFS): todo silencio. */
    private const val SILENCE_DBFS = -60.0
}
