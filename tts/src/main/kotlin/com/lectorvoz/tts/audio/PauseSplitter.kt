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
 *
 * Validacion de plausibilidad del reparto: encontrar una pausa clara no basta. El modelo puede
 * dejar un silencio profundo DENTRO de una frase (pausa dramatica, coma marcada) mas hondo que
 * la frontera real, y entonces el corte roba audio a la frase vecina y la deja casi muda (en el
 * movil el usuario la percibe como saltada). Por eso, antes de aceptar el reparto, se comprueba
 * que la duracion de cada trozo sea aproximadamente proporcional a su numero de caracteres: se
 * mide muestras por caracter de cada trozo y se compara con la media del grupo. Si algun trozo
 * se desvia fuera de [[MIN_PER_CHAR_RATIO], [MAX_PER_CHAR_RATIO]] de la media, o cae por debajo
 * del suelo absoluto de [MIN_PART_MS] ms teniendo mas de [FLOOR_MIN_CHARS] caracteres, el reparto
 * se considera NO fiable y se devuelve nulo (el motor cae al fallback de sintetizar por frase,
 * que siempre suena bien). Asi el fallo "se salta una frase" se convierte en "un poco mas lento
 * pero correcto"; los casos limpios siguen aprovechando la agrupacion.
 */
object PauseSplitter {
    /** Ventana de la envolvente. */
    const val WINDOW_MS = 5

    /** Duracion del tramo silencioso que se busca en cada frontera. */
    const val GAP_MS = 20

    /** Profundidad minima de la pausa respecto al pico de la voz. */
    const val MIN_GAP_DEPTH_DB = 30.0

    /**
     * Cota inferior de la relacion muestras/caracter de un trozo respecto a la media del grupo.
     * Por debajo, el trozo tiene muy poco audio para su texto (frase comida) y el reparto se
     * descarta. 0,55 deja pasar la variacion normal (frases mas rapidas o lentas, pausas
     * internas) pero atrapa un trozo claramente famelico.
     */
    const val MIN_PER_CHAR_RATIO = 0.55

    /**
     * Cota superior de la relacion muestras/caracter respecto a la media: por encima, un trozo
     * absorbe audio de su vecino (el reverso del trozo famelico). 1,8 es holgado para no rechazar
     * repartos buenos con frases naturalmente mas lentas.
     */
    const val MAX_PER_CHAR_RATIO = 1.8

    /** Suelo absoluto de duracion de un trozo (ms): por debajo, con texto real, es inverosimil. */
    const val MIN_PART_MS = 60

    /** A partir de cuantos caracteres se aplica el suelo [MIN_PART_MS] (frases minimas aparte). */
    const val FLOOR_MIN_CHARS = 3

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
        return if (plausible(out, starts, totalChars, sampleRate)) out else null
    }

    /**
     * Cierto si la duracion de cada trozo es verosimil para su numero de caracteres (ver la
     * documentacion de la clase). [parts] tiene un rango por frase (mismo orden que [starts]).
     */
    private fun plausible(parts: List<IntRange>, starts: IntArray, totalChars: Int, sampleRate: Int): Boolean {
        var totalSamples = 0L
        for (p in parts) totalSamples += (p.last - p.first + 1).toLong()
        if (totalSamples <= 0L || totalChars <= 0) return false
        val floor = sampleRate.toLong() * MIN_PART_MS / 1000
        val meanPerChar = totalSamples.toDouble() / totalChars
        for (i in parts.indices) {
            val chars = (if (i + 1 < starts.size) starts[i + 1] else totalChars) - starts[i]
            if (chars <= 0) return false
            val samples = (parts[i].last - parts[i].first + 1).toLong()
            if (chars > FLOOR_MIN_CHARS && samples < floor) return false
            val ratio = (samples.toDouble() / chars) / meanPerChar
            if (ratio < MIN_PER_CHAR_RATIO || ratio > MAX_PER_CHAR_RATIO) return false
        }
        return true
    }

    /** Radio minimo de busqueda alrededor de la estimacion de cada frontera. */
    private const val MIN_RADIUS_MS = 150
}
