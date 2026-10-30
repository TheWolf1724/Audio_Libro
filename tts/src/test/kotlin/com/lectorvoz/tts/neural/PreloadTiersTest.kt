package com.lectorvoz.tts.neural

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Nivel de precarga segun el terminal. */
class PreloadTiersTest {
    private val gb = 1024L * 1024 * 1024

    @Test
    fun movilSobradoPrecargaMucho() {
        assertThat(PreloadTiers.choose(8 * gb, false, false, 0, 0.3f)).isEqualTo(PreloadTiers.HIGH)
        // Aun sin medir la velocidad, con memoria de sobra.
        assertThat(PreloadTiers.choose(8 * gb, false, false, -1, null)).isEqualTo(PreloadTiers.HIGH)
    }

    @Test
    fun casoNormalElParrafoSiguiente() {
        assertThat(PreloadTiers.choose(4 * gb, false, false, 0, 0.4f)).isEqualTo(PreloadTiers.MEDIUM)
        // Mucha memoria pero sintesis no tan rapida, o el movil templado: medio.
        assertThat(PreloadTiers.choose(8 * gb, false, false, 0, 0.7f)).isEqualTo(PreloadTiers.MEDIUM)
        assertThat(PreloadTiers.choose(8 * gb, false, false, 2, 0.3f)).isEqualTo(PreloadTiers.MEDIUM)
    }

    @Test
    fun movilJustoOLimitadoSoloLasFrasesSiguientes() {
        assertThat(PreloadTiers.choose(2 * gb, false, false, 0, 0.3f)).isEqualTo(PreloadTiers.LOW)
        assertThat(PreloadTiers.choose(8 * gb, true, false, 0, 0.3f)).isEqualTo(PreloadTiers.LOW)
        assertThat(PreloadTiers.choose(8 * gb, false, true, 0, 0.3f)).isEqualTo(PreloadTiers.LOW)
        assertThat(PreloadTiers.choose(8 * gb, false, false, 3, 0.3f)).isEqualTo(PreloadTiers.LOW)
        assertThat(PreloadTiers.choose(8 * gb, false, false, 0, 1.2f)).isEqualTo(PreloadTiers.LOW)
    }

    @Test
    fun nivelesOrdenados() {
        val l = PreloadTiers.LOW; val m = PreloadTiers.MEDIUM; val h = PreloadTiers.HIGH
        assertThat(l.maxAheadMs).isLessThan(m.maxAheadMs)
        assertThat(m.maxAheadMs).isLessThan(h.maxAheadMs)
        assertThat(l.paragraphsAhead).isLessThan(m.paragraphsAhead)
        assertThat(m.paragraphsAhead).isLessThan(h.paragraphsAhead)
        for (p in listOf(l, m, h)) assertThat(p.refillBelowMs).isLessThan(p.bufferAheadMs)
    }
}
