package com.lectorvoz.core.text

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.ChapterText
import org.junit.Test

/**
 * Regresion de "tarjeta de juego": numeros pelados y palabras largas en mayusculas
 * (texto sintetico propio, no del libro). Cubre BUG 1 (ChapterStructure) y BUG 2
 * (SpanishNormalizer).
 */
class GameCardTest {
    private val builder = UtteranceBuilder(SpanishNormalizer())
    private val norm = SpanishNormalizer()

    private fun chapter(text: String) = ChapterText(0, text, null, "es")

    private fun say(text: String): String = norm.normalize(text).text

    private fun spokenOf(text: String): String =
        builder.build(chapter(text)).joinToString(" ") { it.spokenText }

    // ---------------------------------------------------------------- BUG 1

    /** Un numero suelto (capitulo de un solo parrafo) se lee, no se descarta. */
    @Test
    fun numeroSueltoSeLee() {
        val us = builder.build(chapter("3000"))
        assertThat(us).isNotEmpty()
        assertThat(us.joinToString(" ") { it.spokenText }).contains("tres mil")
    }

    /**
     * Tarjeta multilinea embebida en narrativa (como la parte pdfium a mitad de
     * capitulo): ninguna linea se descarta ni se deletrea.
     */
    @Test
    fun tarjetaMultilineaSeLeeEntera() {
        val card = "El guerrero abrio el cofre polvoriento y dentro encontro una pequena tarjeta de juego.\n" +
            "3000\n" +
            "+5 de fuerza\n" +
            "-10 PV\n" +
            "NIVEL 3\n" +
            "3 vales por una copa (club desperado).\n" +
            "canjear en el club desperado por una «copa» gratis.\n" +
            "Guardo la tarjeta en su mochila remendada y siguio descendiendo por la mazmorra."
        val spoken = spokenOf(card)
        assertThat(spoken).contains("tres mil")
        assertThat(spoken).contains("más cinco de fuerza")
        assertThat(spoken).contains("menos diez pe uve")
        assertThat(spoken).contains("nivel tres")
        assertThat(spoken).contains("tres vales por una copa")
        assertThat(spoken).contains("canjear en el club desperado")
        // Ninguna letra suelta deletreada: no debe aparecer la secuencia tipica de deletreo.
        assertThat(spoken).doesNotContain("pe uve pe uve")
    }

    // ---------------------------------------------------------------- BUG 2

    @Test
    fun mayusculasLargasSeLeenComoPalabra() {
        // Se leen como palabra y en minuscula (la voz neural deforma las palabras en mayuscula).
        assertThat(say("LOGRO DESBLOQUEADO")).isEqualTo("logro desbloqueado")
        assertThat(say("DESBLOQUEADO")).isEqualTo("desbloqueado")
        assertThat(say("NIVEL DE PELIGRO MAXIMO")).isEqualTo("nivel de peligro maximo")
        assertThat(say("ESTADISTICAS DEL JUGADOR")).isEqualTo("estadisticas del jugador")
    }

    // ---------------------------------------------------------------- Regresiones

    /** Numeros de pagina de borde (primer/ultimo parrafo) se siguen descartando. */
    @Test
    fun numeroDePaginaDeBordeSeDescarta() {
        val spoken = spokenOf("12\nContenido real aqui largo y claro.\n- 300 -")
        assertThat(spoken).doesNotContain("doce")
        assertThat(spoken).doesNotContain("trescientos")
        assertThat(spoken).contains("Contenido real aqui largo y claro")
    }

    /** "Pagina 12 de 300" se descarta aunque este a mitad de capitulo (numero decorado). */
    @Test
    fun paginaDecoradaEnMedioSeDescarta() {
        val spoken = spokenOf("Primera frase larga de contenido real.\nPagina 12 de 300\nSegunda frase larga de contenido real.")
        assertThat(spoken).doesNotContain("doce")
        assertThat(spoken).contains("Primera frase")
        assertThat(spoken).contains("Segunda frase")
    }

    /** Siglas genuinas se siguen deletreando y "-10 PV" lee "pe uve". */
    @Test
    fun siglasYSimbolosRegresion() {
        assertThat(say("Lo emitió RTVE")).isEqualTo("Lo emitió erre te uve e")
        assertThat(say("Su DNI caducó")).isEqualTo("Su de ene i caducó")
        assertThat(say("Perdió -10 PV ahí")).isEqualTo("Perdió menos diez pe uve ahí")
    }
}
