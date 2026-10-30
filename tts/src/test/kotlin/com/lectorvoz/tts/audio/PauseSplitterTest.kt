package com.lectorvoz.tts.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Partir el audio de varias frases por sus pausas naturales. */
class PauseSplitterTest {
    private val fs = 44_100

    private fun tone(seconds: Double): FloatArray =
        FloatArray((fs * seconds).toInt()) { i -> (0.3 * sin(2 * PI * 180 * i / fs)).toFloat() }

    private fun silence(seconds: Double) = FloatArray((fs * seconds).toInt())

    @Test
    fun cortaDentroDeCadaPausaAunqueLaProporcionNoSeaExacta() {
        // Tres frases de 1,0 s, 2,0 s y 0,6 s con pausas de 120 ms; los caracteres no son
        // exactamente proporcionales a la duracion (la estimacion cae cerca, no encima).
        val a = tone(1.0); val b = tone(2.0); val c = tone(0.6); val gap = silence(0.12)
        val pcm = silence(0.05) + a + gap + b + gap + c + silence(0.3)
        val parts = PauseSplitter.split(pcm, fs, intArrayOf(0, 12, 40), 50)!!
        assertThat(parts).hasSize(3)
        val gap1 = (0.05 + 1.0) * fs..(0.05 + 1.0 + 0.12) * fs
        val gap2 = (0.05 + 1.0 + 0.12 + 2.0) * fs..(0.05 + 1.0 + 0.12 + 2.0 + 0.12) * fs
        assertThat(parts[1].first.toDouble()).isIn(com.google.common.collect.Range.closed(gap1.start, gap1.endInclusive))
        assertThat(parts[2].first.toDouble()).isIn(com.google.common.collect.Range.closed(gap2.start, gap2.endInclusive))
        // Contiguas y cubren todo.
        assertThat(parts.first().first).isEqualTo(0)
        assertThat(parts.last().last).isEqualTo(pcm.size - 1)
        assertThat(parts[0].last + 1).isEqualTo(parts[1].first)
    }

    @Test
    fun sinPausaClaraDevuelveNulo() {
        // Voz continua (ninguna pausa 30 dB por debajo): no se arriesga a cortar una palabra.
        val pcm = silence(0.05) + tone(3.0) + silence(0.3)
        assertThat(PauseSplitter.split(pcm, fs, intArrayOf(0, 20), 40)).isNull()
    }

    @Test
    fun unaSolaParteEsTodoElAudio() {
        val pcm = tone(1.0)
        assertThat(PauseSplitter.split(pcm, fs, intArrayOf(0), 10)).containsExactly(pcm.indices)
    }
}
