package com.lectorvoz.format.epub

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.format.epub.EpubFixtures.text
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Revision 3 de format/epub: capitulos ilegibles, mayusculas, codificacion de nombres e imagenes. */
class CapitulosIlegiblesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun opf(manifest: String, spine: String) =
        """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title></metadata>
<manifest>$manifest</manifest><spine>$spine</spine></package>"""

    private fun book(name: String, opf: String, files: List<Pair<String, ByteArray>>) = EpubFixtures.zip(
        tmp.newFile(name),
        listOf(
            "META-INF/container.xml" to text(EpubFixtures.CONTAINER_OPF),
            "OEBPS/content.opf" to text(opf),
        ) + files,
    )

    private val twoChapters = opf(
        """<item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/>""",
        """<itemref idref="a"/><itemref idref="b"/>""",
    )

    @Test
    fun capituloQueFaltaEsUnAvisoYElSiguienteSeLee() = runTest {
        val f = book("falta.epub", twoChapters, listOf("OEBPS/b.xhtml" to text(EpubFixtures.chapter("<p>Segundo.</p>"))))
        EpubBook.open(f).use { b ->
            val doc = b.chapter(0)
            assertThat(doc.plainText).startsWith(EpubBook.UNAVAILABLE)
            assertThat(doc.plainText).contains("Falta el recurso")
            assertThat(doc.blocks).hasSize(2)
            assertThat(b.chapterText(0).text).startsWith(EpubBook.UNAVAILABLE)
            assertThat(b.chapter(1).plainText).isEqualTo("Segundo.")
        }
    }

    @Test
    fun rutaConOtraCapitalizacionSeEncuentra() = runTest {
        val f = book(
            "mayus.epub",
            opf("""<item id="a" href="Text/Cap1.XHTML" media-type="application/xhtml+xml"/><item id="i" href="Images/F.png" media-type="image/png"/>""", """<itemref idref="a"/>"""),
            listOf(
                "OEBPS/text/cap1.xhtml" to text(EpubFixtures.chapter("<p>Hola.</p>")),
                "OEBPS/images/f.png" to EpubFixtures.PNG_BYTES,
            ),
        )
        EpubBook.open(f).use { b ->
            assertThat(b.chapter(0).plainText).isEqualTo("Hola.")
            assertThat(b.resource("OEBPS/Images/F.png")).isEqualTo(EpubFixtures.PNG_BYTES)
        }
    }

    @Test
    fun nombresNoUtf8SeLeen() = runTest {
        val f = File(tmp.root, "latin1.epub")
        ZipOutputStream(f.outputStream(), Charsets.ISO_8859_1).use { zos ->
            for ((name, data) in listOf(
                "META-INF/container.xml" to text(EpubFixtures.CONTAINER_OPF),
                "OEBPS/content.opf" to text(
                    opf("""<item id="a" href="cap%C3%ADtulo.xhtml" media-type="application/xhtml+xml"/>""", """<itemref idref="a"/>"""),
                ),
                "OEBPS/capítulo.xhtml" to text(EpubFixtures.chapter("<p>Texto.</p>")),
            )) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        EpubBook.open(f).use { b -> assertThat(b.chapter(0).plainText).isEqualTo("Texto.") }
    }

    @Test
    fun zipVacioOConBasuraEsInvalido() {
        val f = tmp.newFile("vacio.epub")
        assertThrows(EpubException.Invalid::class.java) { EpubBook.open(f) }
    }

    @Test
    fun abrirSoloParaMetadatosNoAnalizaCapitulos() = runTest {
        val nav = """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
<nav epub:type="toc"><ol><li><a href="a.xhtml#x">A</a></li><li><a href="b.xhtml#y">B</a></li></ol></nav></body></html>"""
        val f = book(
            "meta.epub",
            opf(
                """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
<item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/>""",
                """<itemref idref="a"/><itemref idref="b"/>""",
            ),
            listOf(
                "OEBPS/nav.xhtml" to text(nav),
                "OEBPS/a.xhtml" to text(EpubFixtures.chapter("<p>Antes.</p><p id=\"x\">Equis.</p>")),
                "OEBPS/b.xhtml" to text(EpubFixtures.chapter("<p id=\"y\">Ye.</p>")),
            ),
        )
        EpubBook.open(f).use { b ->
            assertThat(b.metadata.title).isEqualTo("T")
            assertThat(b.parseCount).isEqualTo(0)
            // El indice sigue resolviendo los fragmentos al pedirlo.
            assertThat(b.toc[0].target.offset).isEqualTo("Antes.\n".length)
            assertThat(b.chapter(0).title).isEqualTo("A")
        }
    }

    @Test
    fun imagenConLimitePropioYPrefijo() = runTest {
        val img = ByteArray(50_000) { (it % 251).toByte() }
        val f = EpubFixtures.single(tmp.root, "img.epub", text(EpubFixtures.chapter("<p>a</p>")), listOf("OEBPS/i.bin" to img))
        EpubBook.open(f, EpubLimits(maxImageBytes = 10_000)).use { b ->
            assertThat(b.resource("OEBPS/i.bin")).isEqualTo(img)
            assertThat(runCatching { b.imageResource("OEBPS/i.bin") }.exceptionOrNull())
                .isInstanceOf(EpubException.TooLarge::class.java)
            assertThat(b.resourcePrefix("OEBPS/i.bin", 1000)).isEqualTo(img.copyOf(1000))
            assertThat(b.resourcePrefix("OEBPS/i.bin", 100_000)).isEqualTo(img)
            assertThat(b.resourcePrefix("OEBPS/no.bin", 1000)).isNull()
        }
    }

    @Test
    fun codificacionDeclaradaConEspaciosInicialesYTrasMasDe1Kb() {
        val latin = "\n  <?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><p>año</p>".toByteArray(Charsets.ISO_8859_1)
        assertThat(Markup.decode(latin)).contains("año")
        val comment = "<!--" + " ".repeat(2000) + "-->"
        val html = "<html><head>$comment<meta charset=\"ISO-8859-1\"/></head><body>ñu</body></html>".toByteArray(Charsets.ISO_8859_1)
        assertThat(Markup.decode(html)).contains("ñu")
    }
}
