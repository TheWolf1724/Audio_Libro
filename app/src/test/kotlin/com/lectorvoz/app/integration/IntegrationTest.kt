package com.lectorvoz.app.integration

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.app.ui.reader.findAll
import com.lectorvoz.app.ui.reader.formatDuration
import com.lectorvoz.app.ui.reader.remainingTime
import com.lectorvoz.core.data.pronunciation.PronunciationEntry
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.Utterance
import com.lectorvoz.core.model.UtteranceKind
import com.lectorvoz.core.model.VoiceSettings
import com.lectorvoz.format.epub.EpubBook
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Adaptadores de integration/ contra :core:text y :format:epub reales, y logica pura del lector. */
class IntegrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun epub(): File {
        val f = File(tmp.root, "prueba.epub")
        fun xhtml(body: String) = """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xml:lang="es"><head><title>t</title></head><body>$body</body></html>"""
        val entries = listOf(
            "META-INF/container.xml" to """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "OEBPS/content.opf" to """<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">x</dc:identifier><dc:title>Prueba</dc:title><dc:language>es</dc:language></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a"/><itemref idref="b"/></spine></package>""",
            "OEBPS/nav.xhtml" to """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>i</title></head><body><nav epub:type="toc"><ol><li><a href="a.xhtml">Primero</a></li><li><a href="b.xhtml">Segundo</a></li></ol></nav></body></html>""",
            "OEBPS/a.xhtml" to xhtml("<h1>Primero</h1><p>Era una noche oscura. Llovia sin parar.</p><p>El tren llego tarde.</p>"),
            "OEBPS/b.xhtml" to xhtml("<p>Al dia siguiente salio el sol. Todo cambio.</p>"),
        )
        ZipOutputStream(f.outputStream()).use { z ->
            val mime = "application/epub+zip".toByteArray()
            z.putNextEntry(ZipEntry("mimetype").apply { method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = size; crc = CRC32().apply { update(mime) }.value })
            z.write(mime); z.closeEntry()
            for ((n, t) in entries) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() }
        }
        return f
    }

    private suspend fun all(p: UtteranceProviderImpl): List<Utterance> {
        val out = ArrayList<Utterance>()
        var u = p.at(Locator.START)
        while (u != null && out.size < 100) { out += u; u = p.next(u) }
        return out
    }

    @Test
    fun proveedorEpubRecorreElLibroCruzandoCapitulos() = runTest {
        val book = EpubBook.open(epub())
        try {
            val p = UtteranceProviderImpl(BookContent.Epub(book), VoiceSettings(announceChapters = false), toc = book.toc)
            val list = all(p)
            assertThat(list.map { it.range.start.chapter }.distinct()).containsExactly(0, 1).inOrder()
            assertThat(list.joinToString(" ") { it.displayText }).contains("Llovia sin parar")
            assertThat(list.last().displayText).contains("Todo cambio")
            // Anterior de la primera del capitulo 1 = ultima del capitulo 0.
            val firstB = list.first { it.range.start.chapter == 1 }
            assertThat(p.previous(firstB)).isEqualTo(list[list.indexOf(firstB) - 1])
            assertThat(p.previous(list.first())).isNull()
            assertThat(p.next(list.last())).isNull()
            // Longitud = texto plano del capitulo (misma escala que Locator.offset).
            assertThat(p.chapterLength(0)).isEqualTo(book.chapter(0).plainText.length)
            // Frase que contiene una posicion intermedia.
            val text = book.chapter(0).plainText
            val pos = text.indexOf("tren")
            assertThat(p.textAt(Locator(0, pos))).contains("tren")
            // Idioma y tipos rellenados por :core:text.
            assertThat(list.all { it.language == "es" }).isTrue()
            assertThat(list.first().kind).isEqualTo(UtteranceKind.HEADING)
        } finally {
            book.close()
        }
    }

    @Test
    fun anunciarCapitulosYDiccionario() = runTest {
        val book = EpubBook.open(epub())
        try {
            val p = UtteranceProviderImpl(BookContent.Epub(book), VoiceSettings(announceChapters = true), toc = book.toc)
            // El capitulo 0 empieza con su titulo: no se repite. El 1 no lo tiene: se anuncia.
            assertThat(p.at(Locator.START)!!.kind).isEqualTo(UtteranceKind.HEADING)
            val second = p.at(Locator(1, 0))!!
            assertThat(second.kind).isEqualTo(UtteranceKind.CHAPTER_ANNOUNCE)
            assertThat(second.displayText).contains("Segundo")
            p.update(VoiceSettings(announceChapters = false), listOf(PronunciationEntry(pattern = "tren", replacement = "ferrocarril")))
            val spoken = all(p).joinToString(" ") { it.spokenText }
            assertThat(spoken).contains("ferrocarril")
            assertThat(spoken).doesNotContain("tren")
        } finally {
            book.close()
        }
    }

    @Test
    fun adaptadorPdfQuitaCabecerasRepetidasYNumeros() {
        val a = PdfCleanerAdapter(book = null)
        val p1 = "MI LIBRO DE PRUEBA\nEl texto de la pagina uno sigue\nen esta linea.\n1"
        val p2 = "MI LIBRO DE PRUEBA\nOtra pagina con su propio texto\nque continua.\n2"
        val p3 = "MI LIBRO DE PRUEBA\nY la tercera pagina termina\naqui.\n3"
        a.remember(0, p1); a.remember(2, p3)
        val f = a.flowFor(1, p2)
        assertThat(f.text).doesNotContain("MI LIBRO")
        assertThat(f.text).contains("Otra pagina con su propio texto que continua.")
        assertThat(f.text.trim()).doesNotMatch(".*\\b2$")
        assertThat(f.sameLength.length).isEqualTo(p2.length)
        // El mapa apunta a indices de la pagina original.
        val i = f.text.indexOf("Otra")
        assertThat(p2.substring(f.toOriginal[i], f.toOriginal[i] + 4)).isEqualTo("Otra")
        // Sin filtro no se quita la cabecera.
        a.enabled = false
        assertThat(a.flowFor(1, p2).text).contains("MI LIBRO")
    }

    @Test
    fun herramientasDeTexto() {
        assertThat(TextTools.wordAt("Hola, mundo cruel", 8)).isEqualTo("mundo")
        assertThat(TextTools.wordAt("Hola, mundo", 4)).isEqualTo("mundo")
        assertThat(TextTools.wordAt("   ", 1)).isNull()
        val prev = TextTools.spokenPreview(listOf(PronunciationEntry(pattern = "SQL", replacement = "ese cu ele")), "Uso SQL a diario.")
        assertThat(prev).contains("ese cu ele")
        assertThat(TextTools.invalidPatterns(listOf(PronunciationEntry(pattern = "([", replacement = "x", isRegex = true)))).containsExactly("([")
    }

    @Test
    fun logicaDelLector() {
        assertThat(findAll("Él dijo: el ÉL es él.", "el")).containsExactly(0, 9, 12, 18).inOrder()
        assertThat(findAll("abc", " ")).isEmpty()
        // 1400 caracteres a 14 c/s = 100 s; con velocidad medida 28 c/s = 50 s.
        assertThat(remainingTime(1400, 2800, null, 1f).chapterMs).isEqualTo(100_000)
        assertThat(remainingTime(1400, 2800, 28f, 1f).bookMs).isEqualTo(100_000)
        assertThat(remainingTime(1400, 0, null, 2f).chapterMs).isEqualTo(50_000)
        assertThat(formatDuration(30_000)).isEqualTo("menos de 1 min")
        assertThat(formatDuration(65 * 60_000L)).isEqualTo("1 h 5 min")
    }
}
