package com.lectorvoz.tts.audio

import com.google.common.truth.Truth.assertThat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Test

class QuestionIntonationTest {

    @Test
    fun preguntasDeSiNoSubenElTono() {
        assertThat(QuestionIntonation.isYesNoQuestion("¿Vienes?")).isTrue()
        assertThat(QuestionIntonation.isYesNoQuestion("¿Te gusta?", "es")).isTrue()
        assertThat(QuestionIntonation.isYesNoQuestion("Dijo, ¿vienes?")).isTrue()
        assertThat(QuestionIntonation.isYesNoQuestion("¿A ti te gusta?")).isTrue()
        assertThat(QuestionIntonation.isYesNoQuestion("Do you like it?", "en")).isTrue()
    }

    @Test
    fun preguntasConInterrogativoNoSuben() {
        assertThat(QuestionIntonation.isYesNoQuestion("¿Qué hora es?")).isFalse()
        assertThat(QuestionIntonation.isYesNoQuestion("¿Por qué lloras?")).isFalse()
        assertThat(QuestionIntonation.isYesNoQuestion("¿A dónde vas?")).isFalse()
        assertThat(QuestionIntonation.isYesNoQuestion("¿Tú qué piensas?")).isFalse()
        assertThat(QuestionIntonation.isYesNoQuestion("¿QUE HORA ES?")).isFalse()
        assertThat(QuestionIntonation.isYesNoQuestion("What time is it?", "en")).isFalse()
    }

    @Test
    fun noPreguntasNoSeTocan() {
        assertThat(QuestionIntonation.isYesNoQuestion("Vienes.")).isFalse()
        assertThat(QuestionIntonation.isYesNoQuestion("¡Vienes!")).isFalse()
        assertThat(QuestionIntonation.isYesNoQuestion("")).isFalse()
    }

    private fun tone(freq: Double, ms: Int, rate: Int = 44100) =
        FloatArray(ms * rate / 1000) { (0.5 * sin(2 * PI * freq * it / rate)).toFloat() }

    private fun zeroCrossings(a: FloatArray, from: Int, to: Int): Int {
        var n = 0
        for (i in from + 1 until to) if ((a[i - 1] < 0f) != (a[i] < 0f)) n++
        return n
    }

    @Test
    fun raiseTail_subeLaFrecuenciaAlFinalYAcortaLaCola() {
        val rate = 44100
        val pcm = tone(200.0, 2000, rate)
        val out = QuestionIntonation.raiseTail(pcm, rate)
        // Algo mas corta (la cola se lee mas deprisa) pero nunca mas larga ni vacia.
        assertThat(out.size).isLessThan(pcm.size)
        assertThat(out.size).isGreaterThan(pcm.size * 9 / 10)
        // El cuerpo no cambia.
        assertThat(out.copyOfRange(0, 1000).toList()).isEqualTo(pcm.copyOfRange(0, 1000).toList())
        // La frecuencia de los ultimos ms es mayor que la del principio.
        val win = rate / 10
        val early = zeroCrossings(out, 0, win)
        val late = zeroCrossings(out, out.size - win, out.size)
        assertThat(late).isGreaterThan(early)
        // Sin saltos ni valores fuera de rango.
        assertThat(out.all { abs(it) <= 1f && it.isFinite() }).isTrue()
    }

    @Test
    fun raiseTail_audioMuyCortoSeDevuelveIgual() {
        val pcm = tone(200.0, 300)
        assertThat(QuestionIntonation.raiseTail(pcm, 44100)).isSameInstanceAs(pcm)
    }
}
