package com.lectorvoz.tts.playback

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Logica de agitar, doble toque, velocidad efectiva y bateria. */
@OptIn(ExperimentalCoroutinesApi::class)
class SensorsTest {
    private val g = 9.80665f

    @Test
    fun agitarExigeDosPicosSeguidosYLuegoDescansa() {
        val s = ShakeLogic()
        assertThat(s.onSample(0f, 0f, g, 0)).isFalse() // Reposo.
        assertThat(s.onSample(3 * g, 0f, 0f, 100)).isFalse() // Primer pico.
        assertThat(s.onSample(0f, 0f, g, 200)).isFalse()
        assertThat(s.onSample(-3 * g, 0f, 0f, 400)).isTrue() // Segundo pico: gesto.
        assertThat(s.onSample(0f, 0f, g, 500)).isFalse()
        // Durante el descanso no vuelve a disparar.
        assertThat(s.onSample(3 * g, 0f, 0f, 600)).isFalse()
        assertThat(s.onSample(0f, 0f, g, 700)).isFalse()
        assertThat(s.onSample(3 * g, 0f, 0f, 800)).isFalse()
        // Picos muy separados no cuentan.
        val t = ShakeLogic()
        assertThat(t.onSample(3 * g, 0f, 0f, 0)).isFalse()
        assertThat(t.onSample(0f, 0f, g, 100)).isFalse()
        assertThat(t.onSample(3 * g, 0f, 0f, 2_000)).isFalse()
        // Un pico mantenido (sin bajar) no es un segundo pico.
        assertThat(t.onSample(3 * g, 0f, 0f, 2_100)).isFalse()
    }

    @Test
    fun dobleToqueYToqueSimple() = runTest {
        var single = 0
        var double = 0
        val d = DoubleTapDetector(backgroundScope, { single++ }, { double++ }, windowMs = 400)
        d.onTap()
        advanceTimeBy(200)
        d.onTap()
        runCurrent()
        assertThat(double).isEqualTo(1)
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(single).isEqualTo(0)
        d.onTap()
        advanceTimeBy(401)
        runCurrent()
        assertThat(single).isEqualTo(1)
        assertThat(double).isEqualTo(1)
    }

    @Test
    fun velocidadEfectivaMediaMovil() {
        val r = RateMeter(window = 3)
        assertThat(r.charsPerSecond()).isNull()
        r.add(30, 2_000)
        assertThat(r.charsPerSecond()).isEqualTo(15f)
        r.add(10, 0) // Descartada.
        r.add(10, 500_000) // Descartada (pausa larga).
        assertThat(r.charsPerSecond()).isEqualTo(15f)
        r.add(30, 1_000)
        r.add(30, 1_000)
        r.add(30, 1_000) // Ventana de 3: la primera sale.
        assertThat(r.charsPerSecond()).isEqualTo(30f)
        r.reset()
        assertThat(r.charsPerSecond()).isNull()
    }

    @Test
    fun bateriaBaja() {
        assertThat(BatteryStatus(14, false).isLow).isTrue()
        assertThat(BatteryStatus(14, true).isLow).isFalse()
        assertThat(BatteryStatus(15, false).isLow).isFalse()
        assertThat(BatteryStatus.UNKNOWN.isLow).isFalse()
    }

    @Test
    fun bateriaBajaConHisteresis() {
        // Sin estar bajo antes: entra por debajo del 15 %.
        assertThat(BatteryStatus(15, false).isLowWithHysteresis(wasLow = false)).isFalse()
        assertThat(BatteryStatus(14, false).isLowWithHysteresis(wasLow = false)).isTrue()
        // Estando bajo: no sale hasta recuperar el 20 % (evita el parpadeo en torno al 15 %).
        assertThat(BatteryStatus(16, false).isLowWithHysteresis(wasLow = true)).isTrue()
        assertThat(BatteryStatus(19, false).isLowWithHysteresis(wasLow = true)).isTrue()
        assertThat(BatteryStatus(20, false).isLowWithHysteresis(wasLow = true)).isFalse()
        // Cargar siempre sale del regimen bajo.
        assertThat(BatteryStatus(5, true).isLowWithHysteresis(wasLow = true)).isFalse()
    }
}
