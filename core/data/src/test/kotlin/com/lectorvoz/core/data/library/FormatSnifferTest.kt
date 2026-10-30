package com.lectorvoz.core.data.library

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.BookFormat
import org.junit.Test
import kotlin.random.Random

class FormatSnifferTest {
    @Test
    fun pdfConYSinBasuraPrevia() {
        assertThat(FormatSniffer.detect("%PDF-1.4 ...".toByteArray())).isEqualTo(BookFormat.PDF)
        assertThat(FormatSniffer.detect(ByteArray(100) + "%PDF-1.4".toByteArray())).isEqualTo(BookFormat.PDF)
        assertThat(FormatSniffer.detect("hola".toByteArray())).isNull()
    }

    @Test
    fun zipSoloEsEpubConMimetypeOExtension() {
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(26) + "mimetypeapplication/epub+zip".toByteArray()
        assertThat(FormatSniffer.detect(zip)).isEqualTo(BookFormat.EPUB)
        val zipSinMime = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(40)
        assertThat(FormatSniffer.detect(zipSinMime, "x.zip")).isNull()
        assertThat(FormatSniffer.detect(zipSinMime, "Libro.EPUB")).isEqualTo(BookFormat.EPUB)
    }

    @Test
    fun hashRapidoNoDependeDelTamanoDeBloque() {
        val data = Random(1).nextBytes(300_000)
        val a = QuickHasher().apply { update(data, 0, data.size) }.digest()
        val b = QuickHasher().apply {
            var i = 0
            while (i < data.size) { val n = minOf(777, data.size - i); update(data, i, n); i += n }
        }.digest()
        assertThat(a).isEqualTo(b)
        val c = data.copyOf().also { it[150_000] = (it[150_000] + 1).toByte() }
        // Un cambio en medio no se ve (hash rapido), pero un cambio de tamano o de final si.
        assertThat(QuickHasher().apply { update(c, 0, c.size) }.digest()).isEqualTo(a)
        val d = data.copyOf(data.size - 1)
        assertThat(QuickHasher().apply { update(d, 0, d.size) }.digest()).isNotEqualTo(a)
    }
}
