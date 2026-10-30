package com.lectorvoz.core.text

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.text.SpanishNumbers.cardinal
import com.lectorvoz.core.text.SpanishNumbers.ordinal
import org.junit.Test

class SpanishNumbersTest {
    @Test
    fun cardinalesBasicos() {
        assertThat(cardinal(0)).isEqualTo("cero")
        assertThat(cardinal(1)).isEqualTo("uno")
        assertThat(cardinal(15)).isEqualTo("quince")
        assertThat(cardinal(16)).isEqualTo("dieciséis")
        assertThat(cardinal(21)).isEqualTo("veintiuno")
        assertThat(cardinal(22)).isEqualTo("veintidós")
        assertThat(cardinal(30)).isEqualTo("treinta")
        assertThat(cardinal(31)).isEqualTo("treinta y uno")
        assertThat(cardinal(99)).isEqualTo("noventa y nueve")
        assertThat(cardinal(100)).isEqualTo("cien")
        assertThat(cardinal(101)).isEqualTo("ciento uno")
        assertThat(cardinal(500)).isEqualTo("quinientos")
        assertThat(cardinal(777)).isEqualTo("setecientos setenta y siete")
    }

    @Test
    fun milesYAnos() {
        assertThat(cardinal(1000)).isEqualTo("mil")
        assertThat(cardinal(1492)).isEqualTo("mil cuatrocientos noventa y dos")
        assertThat(cardinal(2024)).isEqualTo("dos mil veinticuatro")
        assertThat(cardinal(21000)).isEqualTo("veintiún mil")
        assertThat(cardinal(100_000)).isEqualTo("cien mil")
        assertThat(cardinal(101_000)).isEqualTo("ciento un mil")
    }

    @Test
    fun millonesYBillones() {
        assertThat(cardinal(1_000_000)).isEqualTo("un millón")
        assertThat(cardinal(2_500_000)).isEqualTo("dos millones quinientos mil")
        assertThat(cardinal(21_000_000)).isEqualTo("veintiún millones")
        assertThat(cardinal(1_000_000_000)).isEqualTo("mil millones")
        assertThat(cardinal(1_000_000_000_000)).isEqualTo("un billón")
        assertThat(cardinal(3_000_000_000_001)).isEqualTo("tres billones uno")
        assertThat(cardinal(1_000_000_000_000_000_000)).isEqualTo("un trillón")
    }

    @Test
    fun genero() {
        assertThat(cardinal(1, Gender.FEMININE)).isEqualTo("una")
        assertThat(cardinal(21, Gender.FEMININE)).isEqualTo("veintiuna")
        assertThat(cardinal(200, Gender.FEMININE)).isEqualTo("doscientas")
        assertThat(cardinal(1, apocope = true)).isEqualTo("un")
        assertThat(cardinal(21, apocope = true)).isEqualTo("veintiún")
        assertThat(cardinal(41, apocope = true)).isEqualTo("cuarenta y un")
        assertThat(cardinal(200_000, Gender.FEMININE)).isEqualTo("doscientas mil")
        assertThat(cardinal(2_000_000, Gender.FEMININE)).isEqualTo("dos millones")
    }

    @Test
    fun ordinales() {
        assertThat(ordinal(1)).isEqualTo("primero")
        assertThat(ordinal(1, apocope = true)).isEqualTo("primer")
        assertThat(ordinal(3, apocope = true)).isEqualTo("tercer")
        assertThat(ordinal(2, Gender.FEMININE)).isEqualTo("segunda")
        assertThat(ordinal(10)).isEqualTo("décimo")
        assertThat(ordinal(11)).isEqualTo("undécimo")
        assertThat(ordinal(13)).isEqualTo("decimotercero")
        assertThat(ordinal(21)).isEqualTo("vigésimo primero")
        assertThat(ordinal(22, Gender.FEMININE)).isEqualTo("vigésima segunda")
        assertThat(ordinal(2, plural = true)).isEqualTo("segundos")
        assertThat(ordinal(100)).isEqualTo("centésimo")
    }
}
