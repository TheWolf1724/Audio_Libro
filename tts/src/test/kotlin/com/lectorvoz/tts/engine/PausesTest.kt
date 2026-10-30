package com.lectorvoz.tts.engine

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.PauseKind
import com.lectorvoz.core.model.ReadingLanguage
import com.lectorvoz.core.model.UtteranceKind
import com.lectorvoz.core.model.VoiceSettings
import com.lectorvoz.tts.utt
import org.junit.Test
import java.util.Locale

/** Pausas por tipo, ritmo adaptativo, idioma por frase y eleccion de voz del sistema. */
class PausesTest {

    @Test
    fun pausasPorTipoPorElFactor() {
        val s = VoiceSettings()
        assertThat(Pauses.afterMs(PauseKind.NONE, s)).isEqualTo(0)
        assertThat(Pauses.afterMs(PauseKind.CLAUSE, s)).isEqualTo(120)
        assertThat(Pauses.afterMs(PauseKind.SENTENCE, s)).isEqualTo(320)
        assertThat(Pauses.afterMs(PauseKind.VERSE, s)).isEqualTo(450)
        assertThat(Pauses.afterMs(PauseKind.PARAGRAPH, s)).isEqualTo(650)
        assertThat(Pauses.afterMs(PauseKind.SECTION, s)).isEqualTo(1100)
        assertThat(Pauses.afterMs(PauseKind.CHAPTER, s)).isEqualTo(1600)
        val half = s.copy(punctuationPause = 0.5f, pauseParagraphMs = 800)
        assertThat(Pauses.afterMs(PauseKind.PARAGRAPH, half)).isEqualTo(400)
        assertThat(Pauses.afterMs(PauseKind.SENTENCE, s.copy(punctuationPause = 0f))).isEqualTo(0)
        assertThat(Pauses.samples(320, 44_100)).isEqualTo(14_112)
    }

    @Test
    fun ritmoAdaptativoEnTitulosYCifras() {
        val s = VoiceSettings()
        assertThat(AdaptivePace.factor(utt(1, 0, 0, "Una frase normal."), s)).isEqualTo(1f)
        assertThat(AdaptivePace.factor(utt(1, 0, 0, "Capitulo dos", kind = UtteranceKind.HEADING), s))
            .isEqualTo(AdaptivePace.HEADING)
        assertThat(AdaptivePace.factor(utt(1, 0, 0, "Capitulo dos", kind = UtteranceKind.CHAPTER_ANNOUNCE), s))
            .isEqualTo(AdaptivePace.HEADING)
        // Muchas cifras densas: el minimo.
        assertThat(AdaptivePace.factor(utt(1, 0, 0, "Entre 1492 y 1521, 3.500 km."), s)).isEqualTo(0.9f)
        // Pocas cifras: sin cambio.
        assertThat(AdaptivePace.factor(utt(1, 0, 0, "En el ano 2000 llovio mucho en toda la comarca."), s)).isEqualTo(1f)
        // Desactivado.
        assertThat(
            AdaptivePace.factor(utt(1, 0, 0, "1492 1521", kind = UtteranceKind.HEADING), s.copy(adaptivePace = false)),
        ).isEqualTo(1f)
    }

    @Test
    fun idiomaPorFraseOForzado() {
        val auto = VoiceSettings()
        assertThat(SpeechLanguage.of(utt(1, 0, 0, "Hi", language = "en"), auto)).isEqualTo("en")
        assertThat(SpeechLanguage.of(utt(1, 0, 0, "Hi", language = "en-GB"), auto)).isEqualTo("en")
        assertThat(SpeechLanguage.of(utt(1, 0, 0, "Hola", language = "es-ES"), auto)).isEqualTo("es")
        assertThat(SpeechLanguage.of(utt(1, 0, 0, "Ola", language = "pt"), auto)).isEqualTo("es")
        val en = auto.copy(readingLanguage = ReadingLanguage.ENGLISH)
        assertThat(SpeechLanguage.of(utt(1, 0, 0, "Hola", language = "es"), en)).isEqualTo("en")
        val es = auto.copy(readingLanguage = ReadingLanguage.SPANISH)
        assertThat(SpeechLanguage.of(utt(1, 0, 0, "Hi", language = "en"), es)).isEqualTo("es")
    }

    @Test
    fun mejorVozDelSistemaPorIdioma() {
        val voices = listOf(
            SystemVoice("es-us-x-red", Locale.forLanguageTag("es-US"), 400, false),
            SystemVoice("es-es-x-buena", Locale.forLanguageTag("es-ES"), 400, false),
            SystemVoice("es-es-x-red", Locale.forLanguageTag("es-ES"), 500, true),
            SystemVoice("es-es-x-mala", Locale.forLanguageTag("es-ES"), 200, false),
            SystemVoice("en-gb-x-a", Locale.forLanguageTag("en-GB"), 400, false),
            SystemVoice("en-us-x-b", Locale.forLanguageTag("en-US"), 300, false),
            SystemVoice("en-us-x-c", Locale.forLanguageTag("en-US"), 400, false, latency = 200),
            SystemVoice("en-us-x-d", Locale.forLanguageTag("en-US"), 400, false, latency = 100, notInstalled = true),
            SystemVoice("fr-fr-x-a", Locale.forLanguageTag("fr-FR"), 500, false),
        )
        assertThat(SystemVoicePicker.best(voices, "es")!!.name).isEqualTo("es-es-x-buena")
        assertThat(SystemVoicePicker.best(voices, "en")!!.name).isEqualTo("en-us-x-c")
        // Sin en-US: en-GB.
        assertThat(SystemVoicePicker.best(voices.filterNot { it.locale.country == "US" }, "en")!!.name)
            .isEqualTo("en-gb-x-a")
        // La voz elegida por el usuario manda si es del idioma.
        assertThat(SystemVoicePicker.best(voices, "es", userVoice = "es-us-x-red")!!.name).isEqualTo("es-us-x-red")
        assertThat(SystemVoicePicker.best(voices, "en", userVoice = "es-us-x-red")!!.name).isEqualTo("en-us-x-c")
        assertThat(SystemVoicePicker.best(voices, "de")).isNull()
        assertThat(SystemVoicePicker.defaultLocale("en")).isEqualTo(Locale.forLanguageTag("en-US"))
    }
}
