package com.lectorvoz.tts.audio

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Compresion de tiempo sin cambiar el tono (WSOLA: solapamiento y suma con busqueda de
 * similitud), para acelerar una frase ya sintetizada.
 *
 * Por que existe: Supertonic, cuando se le pide una velocidad por encima de ~1,1, habla algo
 * menos deprisa de lo pedido y al llegar al final que habia planificado DESCARTA lo que falta
 * (deja silencio detras). Se nota en las frases cortas, donde el deficit (fijo, ~100-200 ms)
 * no cabe: se come la ultima silaba. Asi que esas frases se sintetizan a una velocidad que el
 * modelo reproduce entera y se aceleran aqui hasta la pedida (ver `NeuralTtsEngine.modelSpeed`).
 *
 * Mono, en float. Tramas de [frameMs] con ventana de Hann y solapamiento del 50 %; para cada
 * trama de salida se busca, en +-[searchMs] alrededor de la posicion nominal de entrada, el
 * tramo que mejor continua la trama anterior (correlacion normalizada), de modo que los
 * periodos del tono se alineen y no haya clics ni "eco metalico". Coste: unos milisegundos
 * por segundo de audio, despreciable frente a la sintesis.
 */
object TimeStretch {
    /** Duracion de la trama (ms): mas de un periodo de tono incluso en voces graves. */
    const val FRAME_MS = 30

    /** Tolerancia de busqueda (ms) a cada lado: cubre un periodo de tono hasta ~65 Hz. */
    const val SEARCH_MS = 15

    /** Factores tan cerca de 1 que no merece la pena tocar el audio. */
    private const val IDENTITY_TOLERANCE = 0.005f

    /**
     * Como [stretch], pero sin comprimir el apagado final de la voz: se acelera hasta
     * [keepTailMs] antes de la ultima muestra fuerte (> 10 % del pico, -20 dB) y el resto
     * (toda la caida y el silencio) se empalma tal cual en el punto que mejor encaja.
     * Comprimir esa caida la acortaria (y la busqueda de WSOLA puede incluso saltarla hasta el
     * silencio): sonaria como un corte. Asi el final conserva su forma natural. La duracion es
     * la de [stretch] mas unos milisegundos (la caida no se acorta).
     */
    fun stretchKeepingEnd(input: FloatArray, sampleRate: Int, factor: Float, keepTailMs: Int = KEEP_TAIL_MS): FloatArray {
        require(factor > 0f && factor.isFinite()) { "factor invalido: $factor" }
        if (abs(factor - 1f) < IDENTITY_TOLERANCE || input.isEmpty()) return input
        var peak = 0f
        for (v in input) if (abs(v) > peak) peak = abs(v)
        var lastLoud = input.size - 1
        while (lastLoud > 0 && abs(input[lastLoud]) < peak * 0.1f) lastLoud--
        val cut = lastLoud - sampleRate * keepTailMs / 1000
        val xf = sampleRate * JOIN_MS / 1000
        val search = sampleRate * SEARCH_MS / 1000
        // Sin cuerpo suficiente que acelerar antes del apagado: compresion normal.
        if (cut < 2 * (sampleRate * FRAME_MS / 1000) + search + xf) return stretch(input, sampleRate, factor)
        // Solo el cuerpo (hasta cut + xf), leido en el sitio, sin copiarlo.
        val body = stretchPrefix(input, cut + xf, sampleRate, factor)
        if (body.size <= xf) return stretch(input, sampleRate, factor)
        // Empalme: donde el original continua mejor el final del cuerpo acelerado.
        val ref = body.copyOfRange(body.size - xf, body.size)
        var best = cut
        var bestScore = Double.NEGATIVE_INFINITY
        for (p in (cut - search).coerceAtLeast(0)..(cut + search).coerceAtMost(input.size - xf)) {
            var dot = 0.0
            var e = 0.0
            for (i in 0 until xf) { dot += ref[i] * input[p + i]; e += input[p + i] * input[p + i] }
            val sc = if (e < 1e-12) -1.0 else dot / sqrt(e)
            if (sc > bestScore) { bestScore = sc; best = p }
        }
        val out = FloatArray(body.size - xf + (input.size - best))
        System.arraycopy(body, 0, out, 0, body.size - xf)
        for (i in 0 until xf) {
            val g = (0.5 - 0.5 * cos(Math.PI * i / xf)).toFloat()
            out[body.size - xf + i] = ref[i] * (1f - g) + input[best + i] * g
        }
        System.arraycopy(input, best + xf, out, body.size, input.size - best - xf)
        return out
    }

    /** Margen antes de la ultima muestra fuerte que [stretchKeepingEnd] deja sin comprimir. */
    const val KEEP_TAIL_MS = 20

    /** Fundido del empalme con el final sin comprimir. */
    private const val JOIN_MS = 10

    /**
     * Devuelve [input] acelerado por [factor] (> 1 = mas corto; < 1 = mas largo). La longitud
     * de salida es exactamente `round(input.size / factor)`. [input] no se modifica. Con
     * [factor] ~ 1 devuelve el propio [input].
     */
    fun stretch(input: FloatArray, sampleRate: Int, factor: Float): FloatArray {
        require(factor > 0f && factor.isFinite()) { "factor invalido: $factor" }
        if (abs(factor - 1f) < IDENTITY_TOLERANCE || input.isEmpty()) return input
        return stretchPrefix(input, input.size, sampleRate, factor)
    }

    /** [stretch] de las primeras [n] muestras de [input] (sin copiarlas); siempre devuelve uno nuevo. */
    private fun stretchPrefix(input: FloatArray, n: Int, sampleRate: Int, factor: Float): FloatArray {
        val outLen = (n / factor).roundToInt().coerceAtLeast(1)
        val frame = (sampleRate * FRAME_MS / 1000).coerceAtLeast(4) and 1.inv() // par
        val hop = frame / 2
        // Entradas mas cortas que dos tramas: no hay periodicidad que preservar (son clics o
        // silencio); se remuestrea linealmente a la longitud exacta.
        if (n < 2 * frame || outLen < frame) return resampleLinear(input, n, outLen)

        val window = hann(frame)
        val search = sampleRate * SEARCH_MS / 1000
        // Exacta: la ultima trama se recorta al sumarla (lo que sobresale se descartaba igual).
        val out = FloatArray(outLen)
        // Trama 0: tal cual desde el principio.
        var prev = 0
        overlapAdd(out, 0, input, n, 0, window)
        var k = 1
        while (k * hop < outLen) {
            val nominal = (k * hop * factor).roundToInt()
            // Continuacion natural de la trama anterior: lo que seguiria si no saltaramos.
            val ref = prev + hop
            val lo = (nominal - search).coerceAtLeast(0)
            val hi = (nominal + search).coerceAtMost(n - hop)
            val pos = if (hi <= lo) lo.coerceAtMost(n - 1) else bestMatch(input, n, ref, lo, hi, nominal, hop)
            overlapAdd(out, k * hop, input, n, pos, window)
            prev = pos
            k++
        }
        return out
    }

    // Ventana de Hann de la ultima longitud usada (siempre la misma con una frecuencia de
    // muestreo fija): se calcula una vez. Solo se lee, asi que compartirla entre hilos es seguro.
    @Volatile private var hannCache: FloatArray = FloatArray(0)

    private fun hann(frame: Int): FloatArray {
        hannCache.let { if (it.size == frame) return it }
        return FloatArray(frame) { (0.5 - 0.5 * cos(2.0 * Math.PI * it / frame)).toFloat() }.also { hannCache = it }
    }

    /**
     * Posicion en [lo, hi] cuyo tramo de [len] muestras se parece mas a `input[ref, ref+len)`
     * (correlacion normalizada), buscando primero a saltos de 4 y afinando despues. En empate
     * (silencio) gana la mas cercana a [nominal].
     */
    private fun bestMatch(input: FloatArray, n: Int, ref: Int, lo: Int, hi: Int, nominal: Int, len: Int): Int {
        var refEnergy = 0.0
        val refEnd = minOf(ref + len, n)
        for (i in ref until refEnd) refEnergy += input[i] * input[i]
        if (refEnergy < 1e-9) return nominal.coerceIn(lo, hi)

        fun score(pos: Int): Double {
            var dot = 0.0
            var energy = 0.0
            val end = minOf(len, n - pos, refEnd - ref)
            for (i in 0 until end) {
                val a = input[pos + i]
                dot += a * input[ref + i]
                energy += a * a
            }
            if (energy < 1e-9) return -1.0
            return dot / sqrt(energy * refEnergy)
        }

        var best = nominal.coerceIn(lo, hi)
        var bestScore = score(best)
        var pos = lo
        while (pos <= hi) {
            val sc = score(pos)
            if (sc > bestScore || (sc == bestScore && abs(pos - nominal) < abs(best - nominal))) {
                bestScore = sc
                best = pos
            }
            pos += 4
        }
        for (p in (best - 3).coerceAtLeast(lo)..(best + 3).coerceAtMost(hi)) {
            val sc = score(p)
            if (sc > bestScore) {
                bestScore = sc
                best = p
            }
        }
        return best
    }

    private fun overlapAdd(out: FloatArray, outPos: Int, input: FloatArray, n: Int, inPos: Int, window: FloatArray) {
        val len = minOf(window.size, n - inPos, out.size - outPos)
        for (i in 0 until len) out[outPos + i] += window[i] * input[inPos + i]
    }

    /** Remuestreo lineal a [outLen] muestras (cambia el tono; solo para tramos minusculos). */
    private fun resampleLinear(input: FloatArray, n: Int, outLen: Int): FloatArray {
        if (n == 1) return FloatArray(outLen) { input[0] }
        val out = FloatArray(outLen)
        val step = (n - 1).toDouble() / maxOf(1, outLen - 1)
        for (i in 0 until outLen) {
            val x = i * step
            val j = x.toInt().coerceAtMost(n - 2)
            val t = (x - j).toFloat()
            out[i] = input[j] * (1f - t) + input[j + 1] * t
        }
        return out
    }
}
