package com.lectorvoz.tts.neural

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WordTimingsTest {

    @Test
    fun separaPalabrasSinPuntuacion() {
        val t = "¿Que tal, Ana? Bien."
        val words = WordTimings.words(t).map { t.substring(it.first, it.second) }
        assertThat(words).containsExactly("Que", "tal", "Ana", "Bien").inOrder()
    }

    @Test
    fun repartoProporcionalALaLongitud() {
        val t = "a palabralarguisima b"
        val timings = WordTimings.estimate(t, 0, 1000)
        assertThat(timings.map { t.substring(it.start, it.end) })
            .containsExactly("a", "palabralarguisima", "b").inOrder()
        assertThat(timings[0].startFrame).isEqualTo(0)
        // La palabra larga ocupa mucho mas que la corta.
        val corta = timings[1].startFrame - timings[0].startFrame
        val larga = timings[2].startFrame - timings[1].startFrame
        assertThat(larga).isGreaterThan(corta * 5)
        // Monotono y dentro del tramo.
        assertThat(timings.map { it.startFrame }).isInStrictOrder()
        assertThat(timings.last().startFrame).isLessThan(1000)
    }

    @Test
    fun laPuntuacionAlargaLaPalabraAnterior() {
        val sin = WordTimings.estimate("uno dos tres", 0, 1200)
        val con = WordTimings.estimate("uno. dos tres", 0, 1200)
        // "dos" empieza mas tarde si "uno" va seguido de punto.
        assertThat(con[1].startFrame).isGreaterThan(sin[1].startFrame)
    }

    @Test
    fun ignoraElSilencioInicialYFinal() {
        val samples = FloatArray(1000)
        for (i in 200 until 800) samples[i] = 0.3f
        val timings = WordTimings.estimate("hola mundo", samples)
        assertThat(timings.first().startFrame).isEqualTo(200)
        assertThat(timings.last().startFrame).isLessThan(800)
        assertThat(timings.last().startFrame).isGreaterThan(450)
    }

    @Test
    fun textoSinPalabrasNoDaTiempos() {
        assertThat(WordTimings.estimate("...", FloatArray(10))).isEmpty()
    }
}
