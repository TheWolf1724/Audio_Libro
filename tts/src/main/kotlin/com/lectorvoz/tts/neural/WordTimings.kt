package com.lectorvoz.tts.neural

import kotlin.math.abs

/** Palabra de `spokenText` [start, end) que empieza a sonar en la muestra [startFrame]. */
data class WordTiming(val start: Int, val end: Int, val startFrame: Int)

/**
 * Estima el instante de cada palabra (sherpa-onnx no da marcas de tiempo).
 *
 * Se reparte el tramo con voz (sin el silencio inicial y final del audio) en proporcion al
 * peso de cada palabra: sus letras/digitos mas un peso extra por la puntuacion que la
 * sigue (las pausas de coma o punto tambien consumen tiempo).
 */
object WordTimings {
    /** Umbral de amplitud para considerar que hay voz. */
    const val SILENCE_THRESHOLD = 0.01f

    /** Peso de la separacion entre palabras (en "letras"). */
    private const val GAP_WEIGHT = 0.6f

    /** Calcula los tiempos de [text] sobre [samples] (PCM float mono). */
    fun estimate(text: String, samples: FloatArray): List<WordTiming> {
        val first = firstVoiced(samples)
        val last = lastVoiced(samples)
        return if (first < 0) {
            estimate(text, 0, samples.size)
        } else {
            estimate(text, first, last + 1)
        }
    }

    /** Reparte [text] entre las muestras [voiceStart, voiceEnd). */
    fun estimate(text: String, voiceStart: Int, voiceEnd: Int): List<WordTiming> {
        val words = words(text)
        if (words.isEmpty()) return emptyList()
        val weights = FloatArray(words.size) { i ->
            val w = words[i]
            var letters = 0
            for (k in w.first until w.second) if (text[k].isLetterOrDigit()) letters++
            val nextStart = if (i + 1 < words.size) words[i + 1].first else text.length
            letters.coerceAtLeast(1) + GAP_WEIGHT + punctuationWeight(text, w.second, nextStart)
        }
        val total = weights.sum()
        val span = (voiceEnd - voiceStart).coerceAtLeast(0)
        val out = ArrayList<WordTiming>(words.size)
        var acc = 0f
        for (i in words.indices) {
            val frame = voiceStart + (span * (acc / total)).toInt()
            out += WordTiming(words[i].first, words[i].second, frame)
            acc += weights[i]
        }
        return out
    }

    /** Rangos [start, end) de las palabras (secuencias sin espacios) de [text]. */
    fun words(text: String): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && !isWordChar(text[i])) i++
            if (i >= text.length) break
            val s = i
            while (i < text.length && !text[i].isWhitespace()) i++
            // Recorta puntuacion final pegada a la palabra ("hola," -> "hola").
            var e = i
            while (e > s && !text[e - 1].isLetterOrDigit()) e--
            if (e > s) out += s to e
        }
        return out
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit()

    private fun punctuationWeight(text: String, from: Int, to: Int): Float {
        var w = 0f
        for (k in from until to.coerceAtMost(text.length)) {
            w += when (text[k]) {
                '.', '!', '?', '…' -> 3f
                ';', ':' -> 2f
                ',', '—', '–' -> 1.5f
                else -> 0f
            }
        }
        return w
    }

    /** Primera muestra con voz o -1. */
    fun firstVoiced(samples: FloatArray): Int {
        for (i in samples.indices) if (abs(samples[i]) >= SILENCE_THRESHOLD) return i
        return -1
    }

    /** Ultima muestra con voz o -1. */
    fun lastVoiced(samples: FloatArray): Int {
        for (i in samples.indices.reversed()) if (abs(samples[i]) >= SILENCE_THRESHOLD) return i
        return -1
    }
}
