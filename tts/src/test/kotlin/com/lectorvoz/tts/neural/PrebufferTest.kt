package com.lectorvoz.tts.neural

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Colchon de audio previo a sonar segun la velocidad de sintesis medida. */
class PrebufferTest {
    @Test
    fun sinMedirOEnEquiposRapidos_noHayColchon() {
        assertThat(NeuralTtsEngine.prebufferTargetMs(null, 30_000)).isEqualTo(0)
        assertThat(NeuralTtsEngine.prebufferTargetMs(0.3f, 30_000)).isEqualTo(0)
    }

    @Test
    fun equipoJusto_colchonModerado() {
        assertThat(NeuralTtsEngine.prebufferTargetMs(0.7f, 30_000)).isEqualTo(3_000)
    }

    @Test
    fun equipoLento_colchonGrandePeroAcotadoPorElMargenDePrecarga() {
        assertThat(NeuralTtsEngine.prebufferTargetMs(1.2f, 30_000)).isEqualTo(6_000)
        // La espera total esta acotada: el usuario nunca espera mucho.
        assertThat(NeuralTtsEngine.PREBUFFER_MAX_WAIT_MS).isAtMost(8_000)
        // El colchon nunca supera el 80 % del margen de precarga (si no, el productor descansaria antes).
        assertThat(NeuralTtsEngine.prebufferTargetMs(1.2f, 5_000)).isEqualTo(4_000)
    }

    @Test
    fun pasosEnDirecto_soloBajanCuandoElMovilVaJusto() {
        assertThat(NeuralTtsEngine.stepsCapFor(null)).isEqualTo(Int.MAX_VALUE)
        assertThat(NeuralTtsEngine.stepsCapFor(0.4f)).isEqualTo(Int.MAX_VALUE)
        assertThat(NeuralTtsEngine.stepsCapFor(0.9f)).isEqualTo(6)
        assertThat(NeuralTtsEngine.stepsCapFor(1.5f)).isEqualTo(5)
        // Nunca por debajo del minimo audible.
        assertThat(NeuralTtsEngine.MIN_LIVE_STEPS).isAtLeast(4)
    }

    @Test
    fun hilosSegunLaGamaDelMovil() {
        val gb = 1_000_000_000L
        assertThat(NeuralThreads.choose(8, 4 * gb)).isEqualTo(2) // gama baja (Redmi 10)
        assertThat(NeuralThreads.choose(4, 8 * gb)).isEqualTo(2) // pocos nucleos
        assertThat(NeuralThreads.choose(8, 6 * gb)).isEqualTo(3)
        assertThat(NeuralThreads.choose(8, 12 * gb)).isEqualTo(4)
    }
}
