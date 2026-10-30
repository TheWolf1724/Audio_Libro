package com.lectorvoz.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Firmas y numeros de pagina (el reflujo se prueba en [PdfPageFlowTest]). */
class PdfHeaderFooterFilterTest {
    @Test
    fun firmasYNumerosDePagina() {
        assertThat(PdfHeaderFooterFilter.signature("Página 12 de 300")).isEqualTo("página # de #")
        assertThat(PdfHeaderFooterFilter.isPageNumber("página # de #")).isTrue()
        assertThat(PdfHeaderFooterFilter.isPageNumber(PdfHeaderFooterFilter.signature("xii"))).isTrue()
        assertThat(PdfHeaderFooterFilter.isPageNumber(PdfHeaderFooterFilter.signature("— 45 —"))).isTrue()
        assertThat(PdfHeaderFooterFilter.isPageNumber(PdfHeaderFooterFilter.signature("12 / 300"))).isTrue()
        assertThat(PdfHeaderFooterFilter.isPageNumber(PdfHeaderFooterFilter.signature("Hola 12"))).isFalse()
    }

    /** Revision 2, B10: un romano en mayusculas es un titulo, no un numero de pagina. */
    @Test
    fun romanoEnMayusculasNoEsNumeroDePagina() {
        assertThat(PdfHeaderFooterFilter.isPageNumberLine("II")).isFalse()
        assertThat(PdfHeaderFooterFilter.isPageNumberLine("XIV.")).isFalse()
        assertThat(PdfHeaderFooterFilter.isPageNumberLine("xii")).isTrue()
        assertThat(PdfHeaderFooterFilter.isPageNumberLine("- 7 -")).isTrue()
        assertThat(PdfHeaderFooterFilter.isPageNumberLine("Página 3 de 10")).isTrue()
    }
}
