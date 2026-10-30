package com.lectorvoz.tts.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Parte el audio de varias frases sintetizadas de una vez (ver `NeuralTtsEngine`, frases
 * agrupadas) en el audio de cada frase, cortando por la pausa natural que el modelo deja entre
 * ellas.
 *
 * Para cada frontera se estima donde cae en proporcion a los caracteres (dentro del tramo con
 * voz) y se busca alrededor el tramo mas silencioso de [GAP_MS]; tiene que ser una pausa de
 * verdad (al menos [MIN_GAP_DEPTH_DB] por debajo del pico) o se devuelve nulo y el motor
 * sintetiza las frases por separado: nunca se corta a mitad de palabra.
 */
object PauseSplitter {
    /** Ventana de la envolvente. */
    const val WINDOW_MS = 5

    /** Duracion del tramo silencioso que se busca en cada frontera. */
    const val GAP_MS = 20

    /** Profundidad minima de la pausa respecto al pico de la voz. */
    const val MIN_GAP_DEPTH_DB = 30.0

    /**
     * Rangos de [pcm] de cada parte. [starts] son los caracteres donde empieza cada parte en el
     * texto sintetizado (el primero 0) y [totalChars] su longitud. Nulo si alguna frontera no
     * cae en una pausa clara.
     */
    fun split(pcm: FloatArray, sampleRate: Int, starts: IntArray, totalChars: Int): List<IntRange>? {
        val parts = starts.size
        if (parts <= 1) return listOf(pcm.indices)
        val w = sampleRate * WINDOW_MS / 1000
        if (w < 1 || totalChars <= 0) return null
        val n = pcm.size / w
        if (n < 4 * parts) return null
        val env = DoubleArray(n) { k ->
            var s = 0.0
            for (i in k * w until (k + 1) * w) {
                val v = pcm[i]
                if (v.isFinite()) s += v * v
            }
            20 * log10(sqrt(s / w) + 1e-9)
        }
        val peak = env.max()
        val voiced = peak - 40
        var first = 0
        while (first < n && env[first] < voiced) first++
        var last = n - 1
        while (last > first && env[last] < voiced) last--
        if (last - first < 4 * parts) return null
        val span = (last - first).toDouble()
        val gapW = (GAP_MS / WINDOW_MS).coerceAtLeast(1)
        // Media movil de la energia (dB) en tramos de gapW ventanas que empiezan en k.
        fun gapLevel(k: Int): Double {
            var s = 0.0
            for (i in k until k + gapW) s += env[i]
            return s / gapW
        }
        val cuts = IntArray(parts - 1)
        var prev = first
        for (b in 1 until parts) {
            val est = first + span * starts[b] / totalChars
            val partWindows = span * ((if (b + 1 < parts) starts[b + 1] else totalChars) - starts[b - 1]) / totalChars / 2
            val radius = maxOf(MIN_RADIUS_MS / WINDOW_MS.toDouble(), partWindows * 0.35)
            val lo = maxOf(prev + gapW, (est - radius).toInt())
            val hi = minOf(last - gapW, (est + radius).toInt())
            if (hi <= lo) return null
            var best = lo
            var bestLevel = Double.MAX_VALUE
            for (k in lo..hi) {
                val g = gapLevel(k)
                // En empate, la mas cercana a la estimacion.
                if (g < bestLevel - 1e-9 || (g <= bestLevel + 1e-9 && kotlin.math.abs(k - est) < kotlin.math.abs(best - est))) {
                    bestLevel = g
                    best = k
                }
            }
            if (bestLevel > peak - MIN_GAP_DEPTH_DB) return null
            cuts[b - 1] = best + gapW / 2
            prev = cuts[b - 1]
        }
        val out = ArrayList<IntRange>(parts)
        var from = 0
        for (c in cuts) {
            val at = c * w
            out += from until at
            from = at
        }
        out += from until pcm.size
        return out
    }

    /** Radio minimo de busqueda alrededor de la estimacion de cada frontera. */
    private const val MIN_RADIUS_MS = 150
}
