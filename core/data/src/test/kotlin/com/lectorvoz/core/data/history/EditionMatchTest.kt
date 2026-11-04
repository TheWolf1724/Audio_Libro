package com.lectorvoz.core.data.history

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.BookFormat
import org.junit.Test

/** Parecido entre lecturas para proponer "es otra edicion de...". */
class EditionMatchTest {
    private fun e(id: Long, title: String, author: String? = null) =
        HistoryEntry(id, null, title, listOfNotNull(author), BookFormat.EPUB, null, 0L, null, 0f, 0L)

    @Test
    fun mismoLibroEnOtraEdicion() {
        val nuevo = e(1, "Carl el mazmorrero (Edición ilustrada)", "Matt Dinniman")
        val viejo = e(2, "Carl el Mazmorrero", "Dinniman, Matt")
        assertThat(EditionMatch.candidates(nuevo, listOf(viejo))).containsExactly(viejo)
        // Titulo sacado del nombre del fichero y sin autor.
        val fichero = e(3, "carl_el_mazmorrero-epub")
        assertThat(EditionMatch.candidates(fichero, listOf(viejo))).containsExactly(viejo)
    }

    @Test
    fun enCasoDeDudaNoPropone() {
        val uno = e(1, "Carl el mazmorrero 2", "Matt Dinniman")
        // Otro tomo de la serie: los numeros no coinciden.
        assertThat(EditionMatch.candidates(uno, listOf(e(2, "Carl el mazmorrero 3", "Matt Dinniman")))).isEmpty()
        // Mismo titulo: sin autor en uno se propone; con autores distintos, no.
        assertThat(EditionMatch.candidates(e(3, "La isla"), listOf(e(4, "La isla", "Otra Persona")))).hasSize(1)
        assertThat(EditionMatch.candidates(e(5, "La isla", "Ana Garcia"), listOf(e(6, "La isla", "Otra Persona")))).isEmpty()
        // Poco parecido.
        assertThat(EditionMatch.candidates(e(7, "El nombre del viento"), listOf(e(8, "El temor de un hombre sabio")))).isEmpty()
    }
}
