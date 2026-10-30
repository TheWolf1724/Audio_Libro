package com.lectorvoz.tts.audio

import com.lectorvoz.core.model.VoiceSettings
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

/** Que hace la cadena de audio con cada frase. */
data class ProcessingOptions(
    /** Igualar volumen entre frases (RMS objetivo + limitador suave). */
    val normalize: Boolean = true,
    /** Paso alto ~100 Hz y realce suave en 2-4 kHz (altavoz del movil). */
    val enhance: Boolean = false,
    /** Compresor suave y techo de volumen bajo. */
    val night: Boolean = false,
) {
    companion object {
        /** Opciones a partir de los ajustes del usuario. */
        fun from(s: VoiceSettings) = ProcessingOptions(
            normalize = s.loudnessNormalize,
            enhance = s.voiceEnhance,
            night = s.nightMode,
        )
    }
}

/**
 * Cadena de audio por frase, en float y en el hilo de sintesis:
 * recorte de silencios del modelo -> realce (EQ) -> compresor nocturno -> normalizacion de
 * sonoridad -> limitador suave -> fundidos de entrada y salida (sin clics en los empalmes).
 *
 * Una asignacion por frase (la copia recortada) y otra solo si hay picos que limitar; el
 * resto es en el sitio y sin objetos por muestra. Las muestras no finitas (NaN, infinito)
 * del modelo se cambian por silencio. No es seguro entre hilos (los filtros guardan
 * estado): una instancia por hilo de sintesis.
 */
class VoiceProcessor(
    /** Silencio que se conserva antes de la primera muestra con voz. */
    private val leadMs: Int = 30,
    /**
     * Silencio y decaimiento que se conserva tras la ultima muestra con voz. Amplio a proposito
     * (100 ms): Supertonic deja que la ultima palabra se apague poco a poco y, si se recorta
     * corto, suena como si la frase "terminara de golpe". Mejor conservar la cola natural; la
     * pausa entre frases va aparte. (Las silabas finales que faltaban de verdad las descartaba
     * el propio modelo a velocidad alta; eso se evita en `NeuralTtsEngine.modelSpeed`.)
     */
    private val tailMs: Int = 100,
    /** Fundido de entrada y salida (suficiente para que el arranque no suene brusco). */
    private val fadeMs: Int = 16,
    /** RMS objetivo de la voz (-20 dBFS). */
    private val targetRms: Float = 0.1f,
    /** Umbral del limitador suave (por encima se curva hacia 1). */
    private val limitThreshold: Float = 0.8f,
    /** Techo de pico en modo nocturno (-6 dBFS). */
    private val nightCeiling: Float = 0.5f,
) {
    private var filtersRate = 0
    private var highPass: Biquad? = null
    private var presence: Biquad? = null

    /**
     * Procesa [input] (PCM float mono a [sampleRate]) y devuelve una copia nueva ya
     * recortada y tratada. [input] no se modifica (puede venir de una cache).
     */
    fun process(input: FloatArray, sampleRate: Int, options: ProcessingOptions): FloatArray {
        val range = trimRange(input, sampleRate)
        if (range.isEmpty()) return FloatArray(0)
        val out = input.copyOfRange(range.first, range.last + 1)
        for (i in out.indices) if (!out[i].isFinite()) out[i] = 0f
        if (options.enhance) enhance(out, sampleRate)
        if (options.night) compress(out, sampleRate)
        if (options.normalize) normalize(out, sampleRate)
        limit(out, if (options.night) nightCeiling else 1f, sampleRate)
        fade(out, sampleRate)
        return out
    }

    /**
     * Rango a conservar: de la primera a la ultima muestra con voz, mas [leadMs] y
     * [tailMs]. El umbral es relativo al pico (1,2 %) con un minimo absoluto, para no
     * confundir el ruido de fondo del modelo con voz pero sin comerse consonantes finales
     * flojas (fricativas) ni el arranque suave de la primera palabra. Vacio si todo es silencio.
     */
    fun trimRange(pcm: FloatArray, sampleRate: Int): IntRange {
        var peak = 0f
        for (x in pcm) {
            val a = level(x)
            if (a > peak) peak = a
        }
        val threshold = max(MIN_VOICE_LEVEL, peak * 0.012f)
        if (peak < threshold) return IntRange.EMPTY
        var first = 0
        while (first < pcm.size && level(pcm[first]) < threshold) first++
        var last = pcm.size - 1
        while (last > first && level(pcm[last]) < threshold) last--
        val start = max(0, first - ms(leadMs, sampleRate))
        val end = min(pcm.size - 1, last + ms(tailMs, sampleRate))
        return start..end
    }

    /** Paso alto a 100 Hz y campana de +3 dB en 3 kHz. */
    fun enhance(buf: FloatArray, sampleRate: Int) {
        if (filtersRate != sampleRate) {
            highPass = Biquad.highPass(sampleRate, 100.0)
            presence = Biquad.peaking(sampleRate, 3000.0, 0.9, 3.0)
            filtersRate = sampleRate
        }
        val hp = highPass!!
        val pk = presence!!
        hp.reset(); pk.reset()
        hp.process(buf)
        pk.process(buf)
    }

    /**
     * Compresor suave (umbral -26 dBFS, relacion 3:1, ataque 5 ms, relajacion 150 ms) con
     * seguidor de envolvente de picos.
     */
    fun compress(buf: FloatArray, sampleRate: Int) {
        val attack = coef(5.0, sampleRate)
        val release = coef(150.0, sampleRate)
        val threshold = 0.05f
        val slope = 1f / 3f - 1f
        var env = 0f
        for (i in buf.indices) {
            val a = abs(buf[i])
            env = if (a > env) attack * env + (1 - attack) * a else release * env + (1 - release) * a
            if (env > threshold) {
                val gain = (env / threshold).toDouble().pow(slope.toDouble()).toFloat()
                buf[i] *= gain
            }
        }
    }

    /**
     * Lleva el RMS de la voz a [targetRms]. El RMS se mide por bloques de 10 ms y solo
     * cuentan los bloques con voz (por encima de -45 dBFS), para que las pausas internas
     * no inflen la ganancia. Ganancia limitada a 0,3..4 (+-12 dB).
     */
    fun normalize(buf: FloatArray, sampleRate: Int) {
        val rms = gatedRms(buf, sampleRate)
        if (rms <= 0f) return
        val gain = (targetRms / rms).coerceIn(0.3f, 4f)
        for (i in buf.indices) buf[i] *= gain
    }

    /** RMS de los bloques de 10 ms con voz; 0 si no hay ninguno. */
    fun gatedRms(buf: FloatArray, sampleRate: Int): Float {
        val block = ms(10, sampleRate).coerceAtLeast(1)
        val gate = GATE_LEVEL * GATE_LEVEL
        var sum = 0.0
        var count = 0L
        var i = 0
        while (i < buf.size) {
            val end = min(buf.size, i + block)
            var s = 0.0
            for (k in i until end) s += buf[k] * buf[k]
            if (s / (end - i) >= gate) {
                sum += s
                count += end - i
            }
            i = end
        }
        return if (count == 0L) 0f else sqrt(sum / count).toFloat()
    }

    /**
     * Limitador por ganancia con envolvente: la ganancia que cada muestra necesita para no
     * pasar de la curva suave (por encima de [limitThreshold] x [ceiling] se acerca al techo
     * con tanh) se suaviza con relajacion de ~50 ms hacia delante y ataque de ~1 ms hacia
     * atras (la frase entera esta en memoria: sin retardo). Asi la ganancia cambia despacio
     * y la forma de onda no se deforma (sin la distorsion armonica de recortar muestra a
     * muestra), y ninguna muestra pasa del techo.
     */
    fun limit(buf: FloatArray, ceiling: Float, sampleRate: Int = DEFAULT_RATE) {
        val t = limitThreshold * ceiling
        val room = ceiling - t
        var gain: FloatArray? = null
        for (i in buf.indices) {
            val a = abs(buf[i])
            if (a > t) {
                val g = gain ?: FloatArray(buf.size) { 1f }.also { gain = it }
                g[i] = (t + room * tanh((a - t) / room)) / a
            }
        }
        val g = gain ?: return
        val release = coef(LIMIT_RELEASE_MS, sampleRate)
        val attack = coef(LIMIT_ATTACK_MS, sampleRate)
        var env = 1f
        for (i in g.indices) {
            env = min(g[i], 1f - (1f - env) * release)
            g[i] = env
        }
        env = 1f
        for (i in g.indices.reversed()) {
            env = min(g[i], 1f - (1f - env) * attack)
            g[i] = env
        }
        for (i in buf.indices) buf[i] *= g[i]
    }

    /** Fundido de coseno alzado de [fadeMs] al principio y al final. */
    fun fade(buf: FloatArray, sampleRate: Int) {
        val n = min(ms(fadeMs, sampleRate), buf.size / 2)
        if (n <= 0) return
        for (i in 0 until n) {
            val g = (0.5 - 0.5 * kotlin.math.cos(Math.PI * i / n)).toFloat()
            buf[i] *= g
            buf[buf.size - 1 - i] *= g
        }
    }

    private fun coef(ms: Double, sampleRate: Int): Float = exp(-1.0 / (ms * 0.001 * sampleRate)).toFloat()

    /** Nivel absoluto de una muestra; 0 si no es finita. */
    private fun level(x: Float): Float = if (x.isFinite()) abs(x) else 0f

    private companion object {
        const val DEFAULT_RATE = 44_100
        const val LIMIT_ATTACK_MS = 1.0
        const val LIMIT_RELEASE_MS = 50.0

        /** Nivel minimo para considerar voz (-50 dBFS). */
        const val MIN_VOICE_LEVEL = 0.0032f

        /** Puerta de bloques con voz para el RMS (-45 dBFS). */
        const val GATE_LEVEL = 0.0056f

        fun ms(ms: Int, sampleRate: Int): Int = (ms.toLong() * sampleRate / 1000).toInt()
    }
}
