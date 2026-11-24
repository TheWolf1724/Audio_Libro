package com.lectorvoz.tts.audio

import kotlin.math.pow

/**
 * Entonacion ascendente al final de las preguntas de si/no para la voz natural.
 *
 * Supertonic no admite SSML ni control de tono: lo unico que recibe es el texto, asi que el contorno
 * lo decide el modelo. Aqui se corrige a posteriori: sobre la COLA del audio de la frase se aplica
 * una subida de tono progresiva remuestreando con una velocidad de lectura creciente (la cola se
 * acorta algo, lo que ademas suena a pregunta). Solo las preguntas de si/no ("¿Vienes?"): en
 * espanol e ingles las preguntas con pronombre interrogativo ("¿Que hora es?") terminan bajando.
 *
 * Puro y sin estado; entrada no modificada.
 */
object QuestionIntonation {
    /** Subida total al final de la frase, en semitonos (suave: se nota sin sonar a robot). */
    const val DEFAULT_SEMITONES = 2.5f

    /** Duracion de la cola afectada (ms): la ultima palabra o dos, incluido el apagado final. */
    const val DEFAULT_TAIL_MS = 450

    /** Interrogativos con tilde (inequivocos en cualquier posicion). */
    private val WH_ES_ACCENT = setOf(
        "qué", "quién", "quiénes", "cuál", "cuáles", "cuándo", "dónde", "adónde", "cómo",
        "cuánto", "cuánta", "cuántos", "cuántas",
    )

    /** Igual sin tilde (texto en mayusculas o sin acentos): solo fiable como primera palabra. */
    private val WH_ES_PLAIN = setOf(
        "que", "quien", "quienes", "cual", "cuales", "cuando", "donde", "adonde", "como",
        "cuanto", "cuanta", "cuantos", "cuantas",
    )

    /** Preposiciones que preceden a un interrogativo ("¿Por que...?", "¿A donde...?"). */
    private val PREPOSITIONS = setOf("por", "para", "a", "de", "en", "con", "hasta", "desde", "sobre", "hacia")

    private val WH_EN = setOf("what", "who", "whom", "whose", "which", "when", "where", "why", "how")

    /**
     * true si [spoken] es una pregunta de si/no: termina en '?' y la ultima pregunta (desde su '¿' en
     * espanol) no empieza por un interrogativo. Con preposicion inicial ("¿Por que...?", "¿De quien...?")
     * se trata como interrogativa (baja), igual que con "que", "quien"...
     */
    fun isYesNoQuestion(spoken: String, language: String = "es"): Boolean {
        var end = spoken.length
        while (end > 0 && (spoken[end - 1].isWhitespace() || spoken[end - 1] in CLOSERS)) end--
        if (end == 0 || spoken[end - 1] != '?') return false
        val body = spoken.substring(0, end - 1)
        // Ultima pregunta: tras el ultimo '¿' (espanol) o la ultima frase terminada (ingles).
        val from = maxOf(body.lastIndexOf('¿'), body.lastIndexOfAny(charArrayOf('.', '!', '?', ':', ';')))
        val q = body.substring(from + 1).trimStart(*CLOSERS_AND_SPACE)
        val words = q.split(' ', ',').filter { it.isNotEmpty() }.map { w -> w.takeWhile { it.isLetter() }.lowercase() }
        val first = words.firstOrNull().orEmpty()
        if (first.isEmpty()) return false
        if (language.startsWith("en")) return first !in WH_EN
        if (first in WH_ES_ACCENT || first in WH_ES_PLAIN) return false
        val second = words.getOrNull(1).orEmpty()
        // "¿Por que...?", "¿A donde...?": preposicion + interrogativo (con o sin tilde).
        if (first in PREPOSITIONS && (second in WH_ES_ACCENT || second in WH_ES_PLAIN)) return false
        // "¿Tu que piensas?": interrogativo con tilde en segunda posicion.
        if (second in WH_ES_ACCENT) return false
        return true
    }

    /**
     * Sube el tono de la cola de [pcm] hasta [semitones] en los ultimos [tailMs] ms. Devuelve una copia
     * nueva (algo mas corta en la cola); si el audio es demasiado corto, devuelve [pcm] tal cual.
     */
    fun raiseTail(
        pcm: FloatArray,
        sampleRate: Int,
        semitones: Float = DEFAULT_SEMITONES,
        tailMs: Int = DEFAULT_TAIL_MS,
    ): FloatArray {
        val tail = (tailMs.toLong() * sampleRate / 1000L).toInt()
        // Cola y cuerpo minimos: no tocar frases ridiculamente cortas.
        if (tail < 16 || pcm.size < tail * 2) return pcm
        val rateMax = 2.0.pow(semitones / 12.0)
        val start = pcm.size - tail
        // Capacidad maxima: cuerpo + hasta 2x la cola (sin boxing: una frase tiene cientos de miles de muestras).
        val out = FloatArray(start + tail * 2)
        System.arraycopy(pcm, 0, out, 0, start)
        var n = start
        // Remuestreo con paso creciente de 1 a rateMax a lo largo de la cola (interpolacion lineal).
        var pos = start.toDouble()
        val last = (pcm.size - 1).toDouble()
        while (pos < last) {
            val t = (pos - start) / tail // 0..1 sobre la cola de entrada
            val step = 1.0 + (rateMax - 1.0) * t
            val i0 = pos.toInt()
            val frac = (pos - i0).toFloat()
            val a = pcm[i0]
            val b = pcm[minOf(i0 + 1, pcm.size - 1)]
            if (n >= out.size) break // salvaguarda: nunca mas de 2x la cola
            out[n++] = a + (b - a) * frac
            pos += step
        }
        return out.copyOf(n)
    }

    private val CLOSERS = charArrayOf('"', '”', '»', ')', ']', '\'', '’')
    private val CLOSERS_AND_SPACE = charArrayOf(' ', '¿', '"', '“', '«', '(', '[', '\'', '‘', '—', '-')
}
