package com.lectorvoz.tts.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Corte en seco al final (modelo que se come la ultima silaba) frente a un final natural. */
class TruncationDetectorTest {
    private val fs = 44_100

    private fun tone(seconds: Double, amp: Float = 0.3f): FloatArray =
        FloatArray((fs * seconds).toInt()) { i -> (amp * sin(2 * PI * 200 * i / fs)).toFloat() }

    @Test
    fun finalCortadoEnSecoConColaDeSilencio() {
        // Voz a plena intensidad que para de golpe y deja 300 ms de silencio detras.
        val x = tone(1.0) + FloatArray(fs * 3 / 10)
        assertThat(TruncationDetector.isTruncated(x, fs)).isTrue()
    }

    @Test
    fun finalNaturalConDecaimientoNoEsCorte() {
        // Los ultimos 100 ms se apagan exponencialmente hasta -60 dB (como una voz real).
        val x = tone(1.0) + FloatArray(fs * 3 / 10)
        val fade = fs / 10
        for (i in 0 until fade) x[fs - fade + i] *= Math.pow(10.0, -3.0 * i / fade).toFloat()
        assertThat(TruncationDetector.isTruncated(x, fs)).isFalse()
        assertThat(TruncationDetector.finalFallMs(x, fs)!!).isAtLeast(40)
    }

    @Test
    fun corteQueCaeEnMitadDeUnaVentanaTambienSeDetecta() {
        // La voz para a mitad de una ventana: esa ventana mide menos (puede quedar por debajo
        // de "fuerte"), pero de fuerte a silencio pasan <= 15 ms igualmente.
        for (offsetMs in listOf(1, 2, 3, 4)) {
            val x = tone(1.0 + offsetMs / 1000.0) + FloatArray(fs * 3 / 10)
            assertThat(TruncationDetector.isTruncated(x, fs)).isTrue()
        }
    }

    @Test
    fun finalFlojoAunqueBruscoNoEsCorte() {
        // Un final 30 dB por debajo del pico que se corta: consonante floja, no una silaba comida.
        val x = tone(0.8) + tone(0.2, amp = 0.3f * 0.03f) + FloatArray(fs * 3 / 10)
        assertThat(TruncationDetector.isTruncated(x, fs)).isFalse()
    }

    @Test
    fun silencioYVozHastaElFinalNoSonCortes() {
        assertThat(TruncationDetector.isTruncated(FloatArray(fs), fs)).isFalse()
        assertThat(TruncationDetector.isTruncated(tone(1.0), fs)).isFalse()
        assertThat(TruncationDetector.isTruncated(FloatArray(5), fs)).isFalse()
    }
}
