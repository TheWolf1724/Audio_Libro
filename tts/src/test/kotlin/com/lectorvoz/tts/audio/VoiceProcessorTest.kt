package com.lectorvoz.tts.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Cadena de audio con senales sinteticas. */
class VoiceProcessorTest {
    private val fs = 44_100
    private val off = ProcessingOptions(normalize = false, enhance = false, night = false)

    private fun sine(freq: Double, amp: Float, seconds: Double): FloatArray =
        FloatArray((fs * seconds).toInt()) { i -> (amp * sin(2 * PI * freq * i / fs)).toFloat() }

    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i] * x[i]
        return sqrt(s / (to - from))
    }

    private fun db(ratio: Double) = 20 * log10(ratio)

    @Test
    fun recortaSilenciosLargosYConservaMargenes() {
        val voice = sine(220.0, 0.3f, 1.0)
        val input = FloatArray(fs / 2) + voice + FloatArray(fs * 8 / 10)
        val out = VoiceProcessor().process(input, fs, off)
        // 1 s de voz + 30 ms antes + 100 ms despues (con tolerancia por los pasos por cero).
        val expected = voice.size + fs * 30 / 1000 + fs * 100 / 1000
        assertThat(out.size).isIn(expected - 200..expected + 10)
    }

    @Test
    fun todoSilencioDaVacio() {
        assertThat(VoiceProcessor().process(FloatArray(fs), fs, ProcessingOptions())).isEmpty()
        assertThat(VoiceProcessor().process(FloatArray(fs) { 0.001f }, fs, ProcessingOptions())).isEmpty()
    }

    @Test
    fun fundidosSinClicsEnLosExtremos() {
        // Senal que empieza y acaba en su maximo (el peor caso para un clic).
        val input = FloatArray(fs / 2) { 0.5f }
        val out = VoiceProcessor().process(input, fs, off)
        assertThat(out.first()).isEqualTo(0f)
        assertThat(out.last()).isEqualTo(0f)
        // El salto maximo entre muestras en el fundido es pequeno (16 ms de rampa).
        var maxStep = 0f
        for (i in 1 until 400) maxStep = maxOf(maxStep, abs(out[i] - out[i - 1]))
        assertThat(maxStep).isLessThan(0.01f)
        // En medio, la senal queda intacta.
        assertThat(out[out.size / 2]).isEqualTo(0.5f)
    }

    @Test
    fun normalizaFrasesFlojasYFuertesAlMismoNivel() {
        val p = VoiceProcessor()
        val opts = ProcessingOptions(normalize = true)
        val quiet = p.process(sine(300.0, 0.05f, 1.0), fs, opts)
        val loud = p.process(sine(300.0, 0.4f, 1.0), fs, opts)
        val mid = fs / 2
        val rq = rms(quiet, mid - 2000, mid + 2000)
        val rl = rms(loud, mid - 2000, mid + 2000)
        assertThat(abs(db(rq / rl))).isLessThan(1.0)
        assertThat(rq).isWithin(0.01).of(0.1)
        assertThat(loud.maxOf { abs(it) }).isAtMost(1f)
    }

    @Test
    fun limitadorSuaveNoPasaDelTecho() {
        val p = VoiceProcessor()
        val clipped = FloatArray(1000) { if (it % 2 == 0) 1.8f else -1.8f }
        p.limit(clipped, 1f)
        assertThat(clipped.maxOf { abs(it) }).isAtMost(1f)
        assertThat(clipped.maxOf { abs(it) }).isGreaterThan(0.9f)
        val small = floatArrayOf(0.3f, -0.5f)
        p.limit(small, 1f)
        assertThat(small).isEqualTo(floatArrayOf(0.3f, -0.5f))
    }

    /** Revision 2, B4: una muestra NaN o infinita del modelo no contagia la frase. */
    @Test
    fun muestrasNoFinitasSeSilencian() {
        val p = VoiceProcessor()
        val x = sine(300.0, 0.3f, 0.3)
        x[100] = Float.NaN
        x[2_000] = Float.POSITIVE_INFINITY
        x[5_000] = Float.NEGATIVE_INFINITY
        for (opts in listOf(off, ProcessingOptions(), ProcessingOptions(normalize = true, enhance = true, night = true))) {
            val out = p.process(x, fs, opts)
            assertThat(out).isNotEmpty()
            assertThat(out.all { it.isFinite() }).isTrue()
            assertThat(out.maxOf { abs(it) }).isGreaterThan(0.05f)
        }
    }

    /**
     * Revision 2, B4: el limitador nocturno baja la ganancia con una envolvente; no deforma
     * cada ciclo (antes: tanh muestra a muestra, distorsion armonica).
     */
    @Test
    fun limitadorNocturnoSinDistorsion() {
        val p = VoiceProcessor()
        val input = sine(200.0, 0.56f, 0.5)
        val out = input.copyOf()
        p.limit(out, 0.5f, fs)
        assertThat(out.maxOf { abs(it) }).isAtMost(0.5f)
        // En regimen (sin los primeros 100 ms) la ganancia apenas varia dentro de cada ciclo.
        var lo = Float.MAX_VALUE
        var hi = 0f
        for (i in fs / 10 until input.size) {
            if (abs(input[i]) < 0.1f) continue
            val g = out[i] / input[i]
            lo = minOf(lo, g)
            hi = maxOf(hi, g)
        }
        assertThat((hi - lo) / hi).isLessThan(0.03f)
        assertThat(hi).isLessThan(0.95f) // si limita
    }

    @Test
    fun realceQuitaGravesYSubePresencia() {
        val p = VoiceProcessor()
        fun gainDb(freq: Double): Double {
            val x = sine(freq, 0.2f, 1.0)
            val before = rms(x, fs / 2, fs)
            p.enhance(x, fs)
            return db(rms(x, fs / 2, fs) / before)
        }
        assertThat(gainDb(40.0)).isLessThan(-12.0)
        assertThat(gainDb(1000.0)).isWithin(1.0).of(0.0)
        assertThat(gainDb(3000.0)).isWithin(0.7).of(3.0)
        assertThat(Biquad.highPass(fs, 100.0).magnitudeAt(100.0, fs)).isWithin(0.02).of(0.7071)
    }

    @Test
    fun nocturnoComprimeYPoneTecho() {
        val p = VoiceProcessor()
        // Tramo flojo seguido de uno fuerte: el compresor acerca sus niveles.
        val input = sine(250.0, 0.05f, 0.5) + sine(250.0, 0.8f, 0.5)
        val plain = p.process(input, fs, ProcessingOptions(normalize = false))
        val night = p.process(input, fs, ProcessingOptions(normalize = false, night = true))
        fun spread(x: FloatArray): Double {
            val half = x.size / 2
            return db(rms(x, half + 4000, x.size - 4000) / rms(x, 4000, half - 4000))
        }
        assertThat(spread(night)).isLessThan(spread(plain) - 6)
        assertThat(night.maxOf { abs(it) }).isAtMost(0.5f)
    }
}
