package com.lectorvoz.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EnglishNormalizerTest {
    private val norm = EnglishNormalizer()

    private fun say(t: String): String {
        val r = norm.normalize(t)
        SpanishNormalizerTest.assertMapValid(r, t.length)
        return r.text
    }

    @Test
    fun numeros() {
        assertThat(say("I have 21 cats")).isEqualTo("I have twenty-one cats")
        assertThat(say("It cost 1,250 pounds")).isEqualTo("It cost one thousand two hundred fifty pounds")
        assertThat(say("Pi is 3.14")).isEqualTo("Pi is three point one four")
        assertThat(say("In 1984 and 2005")).isEqualTo("In nineteen eighty-four and two thousand five")
        assertThat(say("the 1st and 22nd")).isEqualTo("the first and twenty-second")
        assertThat(say("up 5% for $3")).isEqualTo("up five percent for three dollars")
        assertThat(say("It was -5 degrees")).isEqualTo("It was minus five degrees")
        assertThat(say("the 1990s")).isEqualTo("the nineteen nineties")
    }

    @Test
    fun monedas() {
        assertThat(say("It costs $5.50.")).isEqualTo("It costs five dollars and fifty cents.")
        assertThat(say("Only £1 and €20")).isEqualTo("Only one pound and twenty euros")
    }

    @Test
    fun horas() {
        assertThat(say("at 3:30 today")).isEqualTo("at three thirty today")
        assertThat(say("at 10:05 pm")).isEqualTo("at ten oh five p m")
        assertThat(say("at 7:00 a.m. sharp")).isEqualTo("at seven a m sharp")
        assertThat(say("at 9:00 then")).isEqualTo("at nine o'clock then")
    }

    @Test
    fun fechas() {
        assertThat(say("On October 12, 1492 they")).isEqualTo("On October twelfth, fourteen ninety-two they")
        assertThat(say("on 4 July 1776.")).isEqualTo("on the fourth of July seventeen seventy-six.")
        assertThat(say("Due 12/25/2020 now")).isEqualTo("Due December twenty-fifth, twenty twenty now")
        assertThat(say("Due 2021-03-01 now")).isEqualTo("Due March first, twenty twenty-one now")
    }

    @Test
    fun abreviaturas() {
        assertThat(say("Mr. Smith met Dr. Jones")).isEqualTo("Mister Smith met Doctor Jones")
        assertThat(say("Mrs. Brown and Ms. Lee")).isEqualTo("Missus Brown and Miz Lee")
        assertThat(say("fruit, e.g. apples, i.e. food")).isEqualTo("fruit, for example apples, that is food")
        assertThat(say("cats, dogs, etc. and more")).isEqualTo("cats, dogs, et cetera and more")
        assertThat(say("Ali vs. Frazier")).isEqualTo("Ali versus Frazier")
        assertThat(say("St. Paul lived")).isEqualTo("Saint Paul lived")
        assertThat(say("No. 5 is")).isEqualTo("number five is")
    }

    @Test
    fun romanosEnContexto() {
        assertThat(say("Chapter IV begins")).isEqualTo("Chapter four begins")
        assertThat(say("Henry VIII married")).isEqualTo("Henry the Eighth married")
        assertThat(say("World War II ended")).isEqualTo("World War two ended")
        assertThat(say("I think I can")).isEqualTo("I think I can")
    }

    @Test
    fun urlYCorreo() {
        assertThat(say("see https://www.example.com/a/b now")).isEqualTo("see link to example dot com now")
        assertThat(say("mail info@example.org now")).isEqualTo("mail info at example dot org now")
    }

    @Test
    fun comillasYApostrofos() {
        assertThat(say("\"Don't\" — she said")).isEqualTo("Don't she said")
    }

    @Test
    fun digitosUnicode() {
        assertThat(say("I have ３ cats")).isEqualTo("I have three cats")
    }

    @Test
    fun seleccionPorIdioma() {
        assertThat(TextNormalizers.forLanguage("en-GB")).isInstanceOf(EnglishNormalizer::class.java)
        assertThat(TextNormalizers.forLanguage("es-ES")).isInstanceOf(SpanishNormalizer::class.java)
        assertThat(TextNormalizers.forLanguage(null)).isInstanceOf(SpanishNormalizer::class.java)
    }
}
