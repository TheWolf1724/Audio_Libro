package com.lectorvoz.core.text

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.ChapterText
import org.junit.Test

class PdfPageFlowTest {
    private fun page(n: Int, body: String) = "Historia de la ciudad\r\n$body\r\n$n\r\n"

    private val body = "Era una mañana fría cuando el viejo pa-\r\n" +
        "nadero abrió la puerta de su tienda y miró\r\n" +
        "la calle vacía con cierta tristeza en los ojos.\r\n" +
        "\r\n" +
        "Nadie vino aquel día.\r\n" +
        "—¿Dónde estarán todos? —se preguntó en voz alta\r\n" +
        "mientras barría el suelo."

    @Test
    fun uneLineasDeParrafoQuitaGuionesYCabeceras() {
        val p = page(12, body)
        val f = PdfPageFlow.flow(p, previous = page(11, "Otra cosa."), next = page(13, "Más cosas."))
        assertThat(f.text).isEqualTo(
            "Era una mañana fría cuando el viejo panadero abrió la puerta de su tienda y miró " +
                "la calle vacía con cierta tristeza en los ojos.\n" +
                "Nadie vino aquel día.\n" +
                "—¿Dónde estarán todos? —se preguntó en voz alta mientras barría el suelo.",
        )
        // Cabecera repetida y numero de pagina excluidos.
        val excluded = f.excluded.map { p.substring(it.start, it.end) }
        assertThat(excluded).containsExactly("Historia de la ciudad", "12").inOrder()
        // Mapa al original monotono y correcto.
        for (i in f.text.indices) {
            if (i > 0) assertThat(f.toOriginal[i]).isAtLeast(f.toOriginal[i - 1])
            val c = f.text[i]
            if (c != ' ' && c != '\n') assertThat(p[f.toOriginal[i]]).isEqualTo(c)
        }
    }

    @Test
    fun textoDeIgualLongitudParaPageTextCleaner() {
        val p = page(12, body)
        val f = PdfPageFlow.flow(p, previous = page(11, "x"), next = null)
        assertThat(f.sameLength.length).isEqualTo(p.length)
        assertThat(f.sameLength).doesNotContain("Historia")
        assertThat(f.sameLength).contains("pa­​​nadero")
        // La voz lee una frase por oracion, no una por linea.
        val us = UtteranceBuilder().build(ChapterText(12, f.sameLength, null, "es"), idBase = 0)
        assertThat(us.map { it.spokenText }).containsExactly(
            "Era una mañana fría cuando el viejo panadero abrió la puerta de su tienda y miró la calle vacía con cierta tristeza en los ojos.",
            "Nadie vino aquel día.",
            "¿Dónde estarán todos?",
            "se preguntó en voz alta mientras barría el suelo.",
        ).inOrder()
        // Los rangos son indices de la pagina original.
        assertThat(p.substring(us[1].range.start.offset, us[1].range.endExclusive.offset)).isEqualTo("Nadie vino aquel día.")
    }

    /** Revision 2, B10: "II" al principio de pagina es el titulo del capitulo y se lee. */
    @Test
    fun romanoEnMayusculasAlPrincipioEsTitulo() {
        val p = "II\nEl segundo capítulo empieza aquí.\n15"
        val f = PdfPageFlow.flow(p, previous = "Texto anterior.\n14", next = "Texto siguiente.\n16")
        assertThat(f.excluded.map { p.substring(it.start, it.end) }).containsExactly("15")
        assertThat(f.text).startsWith("II")
    }

    /** Revision 2, B10: una linea que solo coincide con una de las dos vecinas no es cabecera. */
    @Test
    fun cabeceraExigeCoincidirConLasDosVecinas() {
        val p = "Poema 12\nVersos del poema doce.\n"
        val prev = "Poema 11\nVersos del poema once.\n"
        val next = "Notas finales\nOtra cosa.\n"
        val f = PdfPageFlow.flow(p, previous = prev, next = next)
        assertThat(f.excluded).isEmpty()
        assertThat(f.text).startsWith("Poema 12")
    }

    @Test
    fun sinVecinosSoloQuitaNumerosDePagina() {
        val p = "Título suelto\nTexto corrido de la pagina.\n- 7 -"
        val f = PdfPageFlow.flow(p)
        assertThat(f.excluded.map { p.substring(it.start, it.end) }).containsExactly("- 7 -")
        assertThat(f.text).isEqualTo("Título suelto Texto corrido de la pagina.")
    }
}
