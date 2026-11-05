package com.lectorvoz.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pruebas de [GlyphClass]: que glifos cuentan como texto real y cuales son "sin decodificar". */
class GlyphClassTest {

    @Test
    fun letrasCifrasYPuntuacion_sonTextoReal() {
        for (c in "aZ9ñÁ.,;:¿?¡!()[]«»\"'-—…%€+=".toCharArray()) {
            assertThat(GlyphClass.decodesToText(c)).isTrue()
        }
    }

    @Test
    fun usoPrivado_noEsTextoReal() {
        // Rango de uso privado (U+E000..U+F8FF): ahi cae la versalita que pdfium no decodifica.
        for (code in intArrayOf(0xE000, 0xF761, 0xF762, 0xF730, 0xF8FF)) {
            assertThat(GlyphClass.decodesToText(code.toChar())).isFalse()
        }
    }

    @Test
    fun espaciosYNoImprimibles_noCuentanComoCobertura() {
        for (c in charArrayOf(' ', '\n', '\t', '\u0000', '�', ' ')) {
            assertThat(GlyphClass.decodesToText(c)).isFalse()
        }
    }
}
