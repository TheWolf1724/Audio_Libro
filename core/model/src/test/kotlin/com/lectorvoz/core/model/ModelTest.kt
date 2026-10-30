package com.lectorvoz.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModelTest {

    private fun range(a: Int, b: Int) = LocatorRange(Locator(0, a), Locator(0, b))

    @Test
    fun utteranceEqualsComparaContenidoDelArray() {
        val u1 = Utterance(1, range(0, 4), "Hola", "hola", intArrayOf(0, 1, 2, 3))
        val u2 = Utterance(1, range(0, 4), "Hola", "hola", intArrayOf(0, 1, 2, 3))
        val u3 = u1.copy(spokenToDisplay = intArrayOf(0, 1, 2, 4))
        assertThat(u1).isEqualTo(u2)
        assertThat(u1.hashCode()).isEqualTo(u2.hashCode())
        assertThat(u1).isNotEqualTo(u3)
    }

    @Test
    fun locatorSeOrdenaPorCapituloYDesplazamiento() {
        assertThat(Locator(1, 0)).isGreaterThan(Locator(0, 999))
        assertThat(Locator(2, 5)).isLessThan(Locator(2, 6))
        assertThat(Locator(0, 2) in range(0, 4)).isTrue()
        assertThat(Locator(0, 4) in range(0, 4)).isFalse()
    }

    @Test
    fun ajustesPorDefectoSonValidos() {
        assertThat(ReaderSettings().readingMode).isEqualTo(ReadingMode.PAGED)
        assertThat(VoiceSettings().engine).isEqualTo(TtsEngineType.NEURAL)
        assertThat(VoiceSettings().readingLanguage).isEqualTo(ReadingLanguage.AUTO)
    }
}
