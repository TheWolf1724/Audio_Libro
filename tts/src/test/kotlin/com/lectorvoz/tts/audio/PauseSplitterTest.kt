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

    @Test
    fun repartoDesproporcionadoDevuelveNulo() {
        // Dos frases: la primera corta (10 de 50 caracteres) y la segunda larga. El silencio mas
        // profundo NO esta en la frontera entre frases, sino DENTRO de la primera (una pausa
        // dramatica a 0,1-0,25 s). El buscador lo elige y la primera parte sale "muerta de hambre"
        // (~0,24 s para 10 caracteres): la validacion de proporcion lo detecta y devuelve nulo para
        // que el motor sintetice cada frase por separado. Antes se devolvia este reparto erroneo.
        val pcm = tone(0.1) + silence(0.15) + tone(2.75)
        val parts = PauseSplitter.split(pcm, fs, intArrayOf(0, 10), 50)
        assertThat(parts).isNull()
    }

    @Test
    fun frasePropocionalmenteCortaSeMantiene() {
        // Tres frases de 18, 6 y 12 caracteres con audio proporcional (~30 ms/caracter): la del
        // medio es corta pero su audio esta por encima del suelo (60 ms) y en proporcion, asi que
        // el reparto es fiable y NO se descarta (la validacion no es excesivamente severa).
        val a = tone(18 * 0.03); val b = tone(6 * 0.03); val c = tone(12 * 0.03); val gap = silence(0.1)
        val pcm = a + gap + b + gap + c + silence(0.1)
        val parts = PauseSplitter.split(pcm, fs, intArrayOf(0, 18, 24), 36)!!
        assertThat(parts).hasSize(3)
        // La parte corta del medio esta claramente por encima del suelo de 60 ms.
        val middle = parts[1].last - parts[1].first + 1
        assertThat(middle).isGreaterThan(fs * 60 / 1000)
        assertThat(parts.first().first).isEqualTo(0)
        assertThat(parts.last().last).isEqualTo(pcm.size - 1)
    }
}
