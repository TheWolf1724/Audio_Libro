package com.lectorvoz.core.text

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.ChapterText
import org.junit.Test

/**
 * Numeros tras "nivel" y letras "estilizadas" (cursiva o negrita hechas con caracteres
 * especiales): antes la voz decia "nivel", callaba el numero y seguia.
 */
class NivelYCursivaTest {
    private val builder = UtteranceBuilder(SpanishNormalizer())

    private fun spoken(text: String): String =
        builder.build(ChapterText(0, text, null, "es")).joinToString(" ") { it.spokenText }

    @Test
    fun elNumeroDelNivelSeLee() {
        assertThat(spoken("Has subido al nivel 5.")).contains("nivel cinco")
        assertThat(spoken("Nivel ３ alcanzado.")).contains("tres")
        assertThat(spoken("Nivel 𝟑 alcanzado.")).contains("tres")
        assertThat(spoken("Bajas al nivel Ⅲ ahora.")).contains("nivel tres")
        assertThat(spoken("Bajas al nivel III ahora.")).contains("nivel tres")
        assertThat(spoken("Llegas al nivel ③ por fin.")).contains("nivel tres")
    }

    @Test
    fun unNumeroTrasNivelNoEsUnaNota() {
        // El superindice se conserva (la voz neuronal lo lee como cifra); antes se borraba.
        assertThat(spoken("Ya estas en el nivel³ y sigues.")).contains("nivel³")
        assertThat(spoken("Ya estas en el nivel [3] y sigues.")).contains("tres")
        // Una nota de verdad se sigue quitando.
        assertThat(spoken("Lo dijo el autor³ y siguio.")).doesNotContain("tres")
    }

    @Test
    fun laCursivaEstilizadaSeLee() {
        assertThat(spoken("𝘕𝘪𝘷𝘦𝘭 3 completado.")).contains("Nivel tres")
        assertThat(spoken("𝐄𝐥 𝐬𝐢𝐬𝐭𝐞𝐦𝐚 𝐭𝐞 𝐬𝐚𝐥𝐮𝐝𝐚.")).contains("El sistema te saluda")
        // Cifras de estilo antiguo que algunos PDF dejan en la zona de uso privado.
        assertThat(spoken("Nivel  completado.")).contains("tres")
    }
}
