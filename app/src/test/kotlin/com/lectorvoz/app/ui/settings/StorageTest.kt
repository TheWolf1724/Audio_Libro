package com.lectorvoz.app.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StorageTest {
    @Test
    fun formateaTamanos() {
        assertThat(formatBytes(512)).isEqualTo("512 B")
        assertThat(formatBytes(2048)).isEqualTo("2 KB")
        assertThat(formatBytes(5L * 1024 * 1024)).isEqualTo("5 MB")
        assertThat(formatBytes(3L * 1024 * 1024 * 1024)).contains("GB")
    }

    @Test
    fun elInformeSumaPorConceptos() {
        val a = BookStorage(1, "A", copyBytes = 100, coverBytes = 10, ocrBytes = 5, audioBytes = 50)
        val b = BookStorage(2, "B", copyBytes = 200, coverBytes = 20, ocrBytes = 0, audioBytes = 0)
        val r = StorageReport(books = listOf(a, b), ocrModelBytes = 1000, freeBytes = 1, deviceBytes = 2)
        assertThat(a.totalBytes).isEqualTo(165)
        assertThat(r.copies).isEqualTo(300)
        assertThat(r.covers).isEqualTo(30)
        assertThat(r.ocr).isEqualTo(5)
        assertThat(r.audio).isEqualTo(50)
        assertThat(r.total).isEqualTo(300 + 30 + 5 + 50 + 1000)
    }
}
