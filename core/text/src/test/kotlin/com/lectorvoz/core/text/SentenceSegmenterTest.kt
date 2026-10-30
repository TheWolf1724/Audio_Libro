package com.lectorvoz.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SentenceSegmenterTest {
    private val seg = SentenceSegmenter()

    private fun split(text: String, max: Int = 300): List<String> =
        SentenceSegmenter(max).segment(text).map { text.substring(it.start, it.end) }

    @Test
    fun frasesSimples() {
        assertThat(split("Hola. Adios. ¿Que tal? ¡Bien!"))
            .containsExactly("Hola.", "Adios.", "¿Que tal?", "¡Bien!").inOrder()
    }

    @Test
    fun abreviaturasDeTratamientoNoCortan() {
        assertThat(split("El Sr. García y la Sra. López hablaron con el Dr. Pérez. Luego se fueron."))
            .containsExactly("El Sr. García y la Sra. López hablaron con el Dr. Pérez.", "Luego se fueron.").inOrder()
        assertThat(split("Vino D. Quijote con Dña. Dulcinea. Fin."))
            .containsExactly("Vino D. Quijote con Dña. Dulcinea.", "Fin.").inOrder()
    }

    @Test
    fun abreviaturasDeReferenciaNoCortan() {
        assertThat(split("Ver pág. 23 y núm. 4 del cap. 2. Siguiente."))
            .containsExactly("Ver pág. 23 y núm. 4 del cap. 2.", "Siguiente.").inOrder()
    }

    @Test
    fun etcCortaSoloAnteMayuscula() {
        assertThat(split("Peras, manzanas, etc. Luego vino."))
            .containsExactly("Peras, manzanas, etc.", "Luego vino.").inOrder()
        assertThat(split("Peras, manzanas, etc. y demás."))
            .containsExactly("Peras, manzanas, etc. y demás.")
    }

    @Test
    fun siglasConPuntosEeUu() {
        assertThat(split("Viajó a EE. UU. en 1990. Volvió pronto."))
            .containsExactly("Viajó a EE. UU. en 1990.", "Volvió pronto.").inOrder()
        assertThat(split("Nació en el 300 a. C. y murió joven."))
            .containsExactly("Nació en el 300 a. C. y murió joven.")
    }

    @Test
    fun iniciales() {
        assertThat(split("Lo escribió J. R. R. Tolkien. Era inglés."))
            .containsExactly("Lo escribió J. R. R. Tolkien.", "Era inglés.").inOrder()
    }

    @Test
    fun decimalesUrlYCorreoNoCortan() {
        assertThat(split("Pi vale 3.14 y e vale 2,71. Visita www.ejemplo.com o escribe a ana.p@correo.es. Gracias."))
            .containsExactly(
                "Pi vale 3.14 y e vale 2,71.",
                "Visita www.ejemplo.com o escribe a ana.p@correo.es.",
                "Gracias.",
            ).inOrder()
    }

    @Test
    fun puntosSuspensivos() {
        assertThat(split("Pensó... y calló. Después… Nada."))
            .containsExactly("Pensó... y calló.", "Después…", "Nada.").inOrder()
    }

    @Test
    fun dialogoConRayaNoCortaAntesDelInciso() {
        assertThat(split("—¿Qué hora es? —preguntó Martín. —Las tres —respondió."))
            .containsExactly("—¿Qué hora es? —preguntó Martín.", "—Las tres —respondió.").inOrder()
    }

    @Test
    fun comillasYParentesisDeCierreQuedanEnLaFrase() {
        assertThat(split("Dijo: «Ya voy.» Y se fue. (Era tarde.) Fin."))
            .containsExactly("Dijo: «Ya voy.»", "Y se fue.", "(Era tarde.)", "Fin.").inOrder()
    }

    @Test
    fun exclamacionSeguidaDeMinusculaNoCorta() {
        assertThat(split("¡Ay! dijo ella. Bien."))
            .containsExactly("¡Ay! dijo ella.", "Bien.").inOrder()
    }

    @Test
    fun saltosDeLineaYObjetosSonCortesDuros() {
        val t = "Titulo\n\nPrimer parrafo sin punto\n￼\nSegundo."
        assertThat(split(t)).containsExactly("Titulo", "Primer parrafo sin punto", "Segundo.").inOrder()
    }

    @Test
    fun ignoraFrasesSinLetras() {
        assertThat(split("— \n * * *\nHola.")).containsExactly("Hola.")
    }

    @Test
    fun ingles() {
        assertThat(split("Mr. Smith arrived at 5 p.m. He was late. Dr. Who? Yes!"))
            .containsExactly("Mr. Smith arrived at 5 p.m.", "He was late.", "Dr. Who?", "Yes!").inOrder()
    }

    @Test
    fun frasesLargasSePartenPorClausulas() {
        val clause = "esta es una clausula de prueba bastante larga"
        val text = (1..12).joinToString(", ") { clause } + "; y otra parte final que termina aqui."
        val parts = split(text, 120)
        assertThat(parts.size).isGreaterThan(3)
        parts.forEach { assertThat(it.length).isAtMost(120) }
        // Corta tras coma, punto y coma o espacio, nunca a mitad de palabra.
        parts.dropLast(1).forEach { assertThat(it.last()).isAnyOf(',', ';', 'a', 'e', 'o', 's') }
        assertThat(parts.joinToString(" ")).isEqualTo(text)
    }

    @Test
    fun palabraGiganteSinEspaciosSeCortaDuro() {
        val text = "a".repeat(700)
        val parts = split(text, 300)
        assertThat(parts.map { it.length }).containsExactly(300, 300, 100).inOrder()
    }

    @Test
    fun rangosOrdenadosYSinSolape() {
        val text = "Uno. Dos.\nTres. ¿Cuatro? ¡Cinco!"
        val spans = seg.segment(text)
        for (k in 1 until spans.size) assertThat(spans[k].start).isAtLeast(spans[k - 1].end)
    }

    @Test
    fun corteForzadoNoParteEmojis() {
        // x + 200 emojis: el limite (300) cae en el segundo char de un par sustituto.
        val text = "x" + "\uD83D\uDE00".repeat(200) + "y"
        assertThat(Character.isLowSurrogate(text[300])).isTrue()
        val spans = seg.segment(text)
        assertThat(spans).isNotEmpty()
        for (sp in spans) {
            assertThat(Character.isLowSurrogate(text[sp.start])).isFalse()
            assertThat(Character.isHighSurrogate(text[sp.end - 1])).isFalse()
        }
    }

    @Test
    fun llamadaDeNotaTrasElPuntoNoImpideElCorte() {
        assertThat(split("Era cierto.¹ Luego vino. Fin.[12] Otra.")).containsExactly(
            "Era cierto.¹", "Luego vino.", "Fin.[12]", "Otra.",
        ).inOrder()
    }

    @Test
    fun cortePreferidoEnComaConConjuncion() {
        val text = "Caminaron durante horas por el bosque oscuro, cansados, y al final llegaron a la casa del molino viejo."
        val spans = SentenceSegmenter(60).segment(text)
        assertThat(text.substring(spans[0].start, spans[0].end)).endsWith("cansados,")
        assertThat(spans[0].clause).isTrue()
        assertThat(spans.last().clause).isFalse()
    }

    @Test
    fun nuncaCortaDentroDeNumeroONombre() {
        val text = "Lo dijo ayer en voz muy alta el gran Rodrigo de Triana Fernandez sobre los 7 000 000 de marineros perdidos"
        val spans = SentenceSegmenter(40).segment(text)
        val parts = spans.map { text.substring(it.start, it.end) }
        for (p in parts) {
            assertThat(p).doesNotMatch(".*\\d$")
            assertThat(p.endsWith("Rodrigo")).isFalse()
            assertThat(p.endsWith("Rodrigo de")).isFalse()
            assertThat(p.endsWith("Triana")).isFalse()
        }
    }
}
