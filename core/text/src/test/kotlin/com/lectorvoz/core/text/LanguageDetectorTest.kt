package com.lectorvoz.core.text

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.ChapterText
import com.lectorvoz.core.model.ReadingLanguage
import org.junit.Test

class LanguageDetectorTest {
    private fun lang(s: String) = LanguageDetector.detect(s).language

    @Test
    fun frasesClaras() {
        assertThat(lang("El perro de mi vecino ladra todas las noches.")).isEqualTo("es")
        assertThat(lang("The dog of my neighbour barks every night.")).isEqualTo("en")
        assertThat(lang("¿Dónde está?")).isEqualTo("es")
        assertThat(lang("I don't think she's coming.")).isEqualTo("en")
        assertThat(lang("Mañana iré a la montaña con los niños.")).isEqualTo("es")
    }

    @Test
    fun nombresPropiosNoCambianElIdioma() {
        assertThat(lang("Ayer vi a John Smith y a Mary Jones en New York.")).isEqualTo("es")
        assertThat(lang("Yesterday I saw José García and María López in Madrid.")).isEqualTo("en")
    }

    @Test
    fun sinPruebasNoDecide() {
        val g = LanguageDetector.detect("1234 ...")
        assertThat(g.language).isEmpty()
        assertThat(g.confidence).isEqualTo(0f)
    }

    @Test
    fun seguidorHeredaEnFrasesCortasODudosas() {
        val t = LanguageTracker("es", null)
        assertThat(t.next("The meeting was over and everybody went home.")).isEqualTo("en")
        assertThat(t.next("OK.")).isEqualTo("en") // dudosa: hereda
        assertThat(t.next("dijo él.")).isEqualTo("es") // vuelve al idioma base con poco
        val e = LanguageTracker("en-GB", null)
        assertThat(e.next("Hm.")).isEqualTo("en")
        assertThat(LanguageTracker(null, null).next("Hm.")).isEqualTo("es")
        assertThat(LanguageTracker("es", "en").next("Hola, ¿qué tal estás hoy?")).isEqualTo("en")
    }

    @Test
    fun builderAsignaIdiomaYNormalizadorPorFrase() {
        val b = UtteranceBuilder()
        val text = "Llegó a las 3:30. Then he said that the train was late at 3:30. Y se fue."
        val us = b.build(ChapterText(0, text, null, "es"), idBase = 0)
        assertThat(us.map { it.language }).containsExactly("es", "en", "es").inOrder()
        assertThat(us[0].spokenText).contains("tres y media")
        assertThat(us[1].spokenText).contains("three thirty")
        val forced = b.build(ChapterText(0, text, null, "es"), idBase = 0, options = TextOptions(readingLanguage = ReadingLanguage.SPANISH))
        assertThat(forced.map { it.language }.toSet()).containsExactly("es")
        assertThat(forced[1].spokenText).contains("tres y media")
    }
}
