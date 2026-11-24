package com.lectorvoz.app.integration

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Reubicacion de subrayados sobre su cita cuando el texto del capitulo cambia. */
class AnnotationAnchorTest {
    private val text = "Era una noche oscura.\nLlovia   sin parar sobre la ciudad. Nadie salia."

    @Test
    fun siLaCitaSigueDondeEstaNoSeMueve() {
        val start = text.indexOf("Llovia")
        val end = text.indexOf(" Nadie")
        val r = AnchorText(text).locate("Llovia sin parar sobre la ciudad.", start, end, text)
        assertThat(r).isEqualTo(start until end)
    }

    @Test
    fun siElTextoSeDesplazaSeReubicaEnLaAparicionMasCercana() {
        val moved = "PROLOGO NUEVO. " + text
        val oldStart = text.indexOf("Nadie")
        val r = AnchorText(moved).locate("Nadie salia.", oldStart, oldStart + 12, moved)!!
        assertThat(moved.substring(r.first, r.last + 1)).isEqualTo("Nadie salia.")
    }

    @Test
    fun siLaCitaYaNoEstaEsHuerfano() {
        assertThat(AnchorText("Otro texto distinto.").locate("Nadie salia.", 5, 17, "Otro texto distinto.")).isNull()
    }

    @Test
    fun ignoraMarcasDeImagenYEspaciosRepetidos() {
        val t = "Antes ￼ la   luz."
        val r = AnchorText(t).locate("Antes la luz.", 99, 120, t)!!
        assertThat(t.substring(r.first, r.last + 1)).isEqualTo(t)
    }
}
