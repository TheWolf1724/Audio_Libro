package com.lectorvoz.tts.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Compresion de tiempo: longitud exacta, tono y nivel conservados, sin clics. */
class TimeStretchTest {
    private val fs = 44_100

    private fun sine(freq: Double, amp: Float, seconds: Double): FloatArray =
        FloatArray((fs * seconds).toInt()) { i -> (amp * sin(2 * PI * freq * i / fs)).toFloat() }

    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i] * x[i]
        return sqrt(s / (to - from))
    }

    /** Frecuencia estimada por cruces por cero ascendentes. */
    private fun freq(x: FloatArray, from: Int, to: Int): Double {
        var crossings = 0
        for (i in from + 1 until to) if (x[i - 1] <= 0f && x[i] > 0f) crossings++
        return crossings.toDouble() * fs / (to - from)
    }

    @Test
    fun longitudExactaParaCualquierFactor() {
        val x = sine(200.0, 0.3f, 1.3)
        for (f in listOf(1.1f, 1.25f, 1.4375f, 1.5f, 2.0f, 0.8f)) {
            assertThat(TimeStretch.stretch(x, fs, f).size).isEqualTo((x.size / f).roundToInt())
        }
    }

    @Test
    fun factorUnoDevuelveLaEntrada() {
        val x = sine(200.0, 0.3f, 0.5)
        assertThat(TimeStretch.stretch(x, fs, 1f)).isSameInstanceAs(x)
        assertThat(TimeStretch.stretch(x, fs, 1.002f)).isSameInstanceAs(x)
    }

    @Test
    fun conservaTonoYNivelSinClics() {
        for (f0 in listOf(110.0, 220.0, 330.0)) {
            val x = sine(f0, 0.4f, 2.0)
            val y = TimeStretch.stretch(x, fs, 1.4375f)
            val a = y.size / 5
            val b = y.size * 4 / 5
            // Tono: la misma frecuencia (no el 1,44x que daria un remuestreo).
            assertThat(freq(y, a, b)).isWithin(f0 * 0.03).of(f0)
            // Nivel: igual RMS en el cuerpo.
            assertThat(rms(y, a, b)).isWithin(0.03).of(rms(x))
            // Sin clics: ningun salto entre muestras mayor que el doble de la pendiente natural.
            val natural = 0.4f * 2 * PI * f0 / fs
            var maxStep = 0f
            for (i in a + 1 until b) maxStep = maxOf(maxStep, abs(y[i] - y[i - 1]))
            assertThat(maxStep).isLessThan((natural * 2).toFloat())
        }
    }

    @Test
    fun silencioSigueSiendoSilencio() {
        val y = TimeStretch.stretch(FloatArray(fs), fs, 1.4375f)
        assertThat(y.size).isEqualTo((fs / 1.4375f).roundToInt())
        assertThat(y.all { it == 0f }).isTrue()
    }

    @Test
    fun entradasMinusculasSeRemuestrean() {
        // Mas corta que dos tramas: longitud exacta y valores acotados.
        val x = FloatArray(50) { 0.5f }
        val y = TimeStretch.stretch(x, 1000, 1.4375f)
        assertThat(y.size).isEqualTo(35)
        assertThat(y.all { abs(it - 0.5f) < 1e-6f }).isTrue()
        assertThat(TimeStretch.stretch(FloatArray(0), fs, 1.5f)).isEmpty()
    }

    @Test
    fun elApagadoFinalNoSeComprimeNiSeVuelveUnCorte() {
        // Voz que se apaga en 50 ms (exponencial hasta -60 dB, como un final real) y silencio
        // detras: tras acelerar x1,4375 el apagado conserva su forma (no se convierte en una
        // caida en seco) y el cuerpo se acelera.
        val voice = sine(180.0, 0.4f, 1.0)
        val decay = fs * 50 / 1000
        for (i in 0 until decay) voice[voice.size - decay + i] *= Math.pow(10.0, -3.0 * i / decay).toFloat()
        val x = voice + FloatArray(fs * 3 / 10)
        assertThat(TruncationDetector.isTruncated(x, fs)).isFalse()
        val y = TimeStretch.stretchKeepingEnd(x, fs, 1.4375f)
        // Cuerpo acelerado: dura ~1/1,4375 (+ el final sin comprimir).
        val last = y.indexOfLast { abs(it) > 0.004f }
        assertThat(last.toDouble() / fs).isWithin(0.03).of((1.0 - 0.05) / 1.4375 + 0.05)
        // El apagado: desde que baja del 20 % hasta el final tarda lo mismo que en el original.
        fun fadeMs(z: FloatArray, end: Int): Double {
            var a = end
            while (a > 0 && peakAround(z, a) < 0.2f) a--
            return (end - a) * 1000.0 / fs
        }
        assertThat(fadeMs(y, last)).isWithin(8.0).of(fadeMs(x, x.indexOfLast { abs(it) > 0.004f }))
        assertThat(TruncationDetector.isTruncated(y, fs)).isFalse()
        // Silencio del final intacto.
        assertThat(y.size - last).isAtLeast(fs * 29 / 100)
    }

    /** Maximo absoluto en el periodo (~6 ms) que acaba en [i]. */
    private fun peakAround(z: FloatArray, i: Int): Float {
        var m = 0f
        for (k in (i - fs * 6 / 1000).coerceAtLeast(0)..i) m = maxOf(m, abs(z[k]))
        return m
    }

    @Test
    fun elArranqueYElFinalSeConservan() {
        // Voz "real" simulada: silencio, tono, silencio. El tono debe empezar y acabar en la
        // misma proporcion de la salida (no se pierde el final al comprimir).
        val x = FloatArray(fs / 2) + sine(180.0, 0.4f, 1.0) + FloatArray(fs / 2)
        val y = TimeStretch.stretch(x, fs, 1.4375f)
        val first = y.indexOfFirst { abs(it) > 0.05f }
        val last = y.indexOfLast { abs(it) > 0.05f }
        assertThat(first.toDouble() / y.size).isWithin(0.03).of(0.25)
        assertThat(last.toDouble() / y.size).isWithin(0.03).of(0.75)
    }
}
