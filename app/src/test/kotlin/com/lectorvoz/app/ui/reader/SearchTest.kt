package com.lectorvoz.app.ui.reader

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchTest {
    @Test
    fun encuentraTextoPartidoPorSaltosDeLineaYEspaciosDobles() {
        val t = "El hidalgo de la\nMancha  vivia   en un lugar."
        val m = findMatches(t, "de la mancha").single()
        // El rango es del texto ORIGINAL y cubre desde "de" hasta "Mancha".
        assertThat(t.substring(m.start, m.end)).isEqualTo("de la\nMancha")
    }

    @Test
    fun uneLasPalabrasPartidasConGuionAlFinalDeLinea() {
        val t = "Un lugar de la Man-\ncha de cuyo nombre"
        val m = findMatches(t, "mancha").single()
        assertThat(t.substring(m.start, m.end)).isEqualTo("Man-\ncha")
        // Un guion normal (no al final de linea) no se toca.
        assertThat(findMatches("pre-historia", "prehistoria")).isEmpty()
    }

    @Test
    fun ignoraTildesYMayusculasComoAntes() {
        assertThat(findAll("Él dijo: el ÉL es él.", "el")).containsExactly(0, 9, 12, 18).inOrder()
        assertThat(findAll("abc", " ")).isEmpty()
        assertThat(findMatches("Canción", "cancion")).hasSize(1)
    }

    @Test
    fun respetaElMaximoYNoSeSolapa() {
        assertThat(findMatches("aaaa", "aa")).hasSize(2)
        assertThat(findMatches("a a a a a", "a", max = 3)).hasSize(3)
    }

    @Test
    fun ignoraEspaciosDeAnchoCero() {
        val j = "​"
        assertThat(findMatches("P${j}a${j}r${j}a los", "para los")).hasSize(1)
    }
}
