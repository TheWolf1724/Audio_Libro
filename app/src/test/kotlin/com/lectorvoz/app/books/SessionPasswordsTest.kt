package com.lectorvoz.app.books

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionPasswordsTest {
    @Test
    fun guardaPorLibroYSeOlvida() {
        val p = SessionPasswords()
        assertThat(p.get(1)).isNull()
        p.set(1, "clave")
        p.set(2, "otra")
        assertThat(p.get(1)).isEqualTo("clave")
        assertThat(p.get(2)).isEqualTo("otra")
        p.forget(1)
        assertThat(p.get(1)).isNull()
        assertThat(p.get(2)).isEqualTo("otra")
    }
}
