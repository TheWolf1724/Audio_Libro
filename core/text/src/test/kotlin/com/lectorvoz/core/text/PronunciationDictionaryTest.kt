package com.lectorvoz.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PronunciationDictionaryTest {
    @Test
    fun reglaLiteralPalabraCompletaSinMayusculas() {
        val d = PronunciationDictionary(listOf(PronunciationRule("Tolkien", "Tolkin")))
        val r = d.apply("tolkien y TOLKIENS y Tolkien.")
        assertThat(r.text).isEqualTo("Tolkin y TOLKIENS y Tolkin.")
        SpanishNormalizerTest.assertMapValid(r, "tolkien y TOLKIENS y Tolkien.".length)
    }

    @Test
    fun reglaSensibleAMayusculas() {
        val d = PronunciationDictionary(listOf(PronunciationRule("US", "uve ese", caseSensitive = true)))
        assertThat(d.apply("US y us").text).isEqualTo("uve ese y us")
    }

    @Test
    fun reglaRegexConGrupos() {
        val d = PronunciationDictionary(
            listOf(PronunciationRule("(\\d+)ºC", "$1 grados", isRegex = true)),
        )
        val r = d.apply("Hace 30ºC hoy")
        assertThat(r.text).isEqualTo("Hace 30 grados hoy")
        // "hoy" sigue apuntando a su posicion original.
        assertThat(r.toSource[r.text.indexOf("hoy")]).isEqualTo("Hace 30ºC hoy".indexOf("hoy"))
    }

    @Test
    fun reglasEncadenadasYBorrado() {
        val d = PronunciationDictionary(
            listOf(
                PronunciationRule("[*]+", "", isRegex = true),
                PronunciationRule("Hermione", "Jermaioni"),
            ),
        )
        val src = "**Hermione** llegó"
        val r = d.apply(src)
        assertThat(r.text).isEqualTo("Jermaioni llegó")
        assertThat(r.toSource[0]).isEqualTo(2)
        SpanishNormalizerTest.assertMapValid(r, src.length)
    }

    @Test
    fun regexInvalidaSeIgnora() {
        val bad = PronunciationRule("(abc", "x", isRegex = true)
        val d = PronunciationDictionary(listOf(bad, PronunciationRule("hola", "ola")))
        assertThat(d.invalidRules).containsExactly(bad)
        assertThat(d.apply("hola").text).isEqualTo("ola")
    }

    @Test
    fun seAplicaAntesDeNormalizarEnElBuilder() {
        val d = PronunciationDictionary(listOf(PronunciationRule("XV", "quince bis")))
        val b = UtteranceBuilder(SpanishNormalizer(), d)
        val u = b.build(com.lectorvoz.core.model.ChapterText(0, "Es el XV.", null, "es"), idBase = 1).single()
        assertThat(u.spokenText).isEqualTo("Es el quince bis.")
        assertThat(u.spokenToDisplay[u.spokenText.indexOf("quince")]).isEqualTo(6)
    }
}
