package com.lectorvoz.core.text

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.ChapterText
import com.lectorvoz.core.model.PauseKind
import org.junit.Test

/** Auditoria 2026-11-25: casos concretos que se leian mal; los casos de siempre no cambian. */
class AuditoriaTextoTest {
    private val norm = SpanishNormalizer()
    private fun say(t: String) = norm.normalize(t).text

    @Test
    fun inicialTrasNombreNoEsRomano() {
        assertThat(say("Jorge L. Borges")).doesNotContain("cincuenta")
        assertThat(say("Edgar M. Poe")).doesNotContain("mil")
        // Los reyes siguen leyendose.
        assertThat(say("Felipe II reino.")).contains("segundo")
        assertThat(say("Carlos V reino.")).contains("quinto")
        assertThat(say("Isabel II reino.")).contains("segunda")
    }

    @Test
    fun letraDeEtiquetaNoEsRomano() {
        assertThat(say("Vive en el piso C.")).doesNotContain("cien")
        assertThat(say("Ver apartado D.")).doesNotContain("quinientos")
        assertThat(say("Capítulo C.")).contains("cien")
        assertThat(say("Capítulo IV.")).contains("cuatro")
        assertThat(say("Nivel III.")).contains("tres")
        assertThat(say("Fase II.")).contains("dos")
    }

    @Test
    fun fraccionesSeLeenComoFracciones() {
        assertThat(say("Abierto 24/7.")).contains("veinticuatro séptimos")
        assertThat(say("Nota 10/10.")).contains("diez décimos")
        assertThat(say("Quedan 3/4 de hora.")).contains("tres cuartos")
        assertThat(say("Queda 1/2 litro.")).contains("un medio")
    }

    @Test
    fun versosConSaltoWindowsNoSonFinDeEstrofa() {
        val verso = "Nunca supe donde\r\nla luna se escondia\r\nentre las ramas viejas\r\ny no volvio a salir"
        val lf = UtteranceBuilder().build(ChapterText(0, verso.replace("\r\n", "\n"), "T", "es"))
        val crlf = UtteranceBuilder().build(ChapterText(0, verso, "T", "es"))
        assertThat(crlf.map { it.pauseAfter }).isEqualTo(lf.map { it.pauseAfter })
        assertThat(crlf.dropLast(1).map { it.pauseAfter }).doesNotContain(PauseKind.PARAGRAPH)
    }
}
