package com.lectorvoz.format.pdf

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SplitAuthorsTest {
    @Test fun apellidoComaNombreEsUnAutor() { assertThat(splitAuthors("Garcia Marquez, Gabriel")).containsExactly("Garcia Marquez, Gabriel") }
    @Test fun puntoYComaSeparaAutores() { assertThat(splitAuthors("Ana; Juan").map { it.trim() }).containsExactly("Ana", "Juan").inOrder() }
    @Test fun variosNombresCompletosConComa() { assertThat(splitAuthors("Ana Ruiz, Juan Gil").map { it.trim() }).containsExactly("Ana Ruiz", "Juan Gil").inOrder() }
    @Test fun tresComasSonTresAutores() { assertThat(splitAuthors("A, B, C")).hasSize(3) }
}
