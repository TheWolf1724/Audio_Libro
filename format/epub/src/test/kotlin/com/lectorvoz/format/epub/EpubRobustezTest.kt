package com.lectorvoz.format.epub

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.Block
import com.lectorvoz.core.model.BlockKind
import com.lectorvoz.core.model.InlineStyle
import com.lectorvoz.format.epub.EpubFixtures.text
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Hallazgos de la revision 1 en format/epub (A1, M1, M2, M5, B2, B3, B4, B9) y notas. */
class EpubRobustezTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun opf(manifest: String, spine: String, extraMeta: String = "") =
        """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title>$extraMeta</metadata>
<manifest>$manifest</manifest><spine>$spine</spine></package>"""

    private fun book(name: String, opf: String, files: List<Pair<String, ByteArray>>) = EpubFixtures.zip(
        tmp.newFile(name),
        listOf(
            "META-INF/container.xml" to text(EpubFixtures.CONTAINER_OPF),
            "OEBPS/content.opf" to text(opf),
        ) + files,
    )

    @Test
    fun capituloImagenEnSubcarpetaConservaLaRuta() = runTest {
        val f = book(
            "comic.epub",
            opf("""<item id="p1" href="images/p1.jpg" media-type="image/jpeg"/>""", """<itemref idref="p1"/>"""),
            listOf("OEBPS/images/p1.jpg" to byteArrayOf(1, 2, 3)),
        )
        EpubBook.open(f).use { b ->
            val img = b.chapter(0).blocks.single() as Block.Image
            assertThat(img.href).isEqualTo("OEBPS/images/p1.jpg")
            assertThat(b.resource(img.href)).isEqualTo(byteArrayOf(1, 2, 3))
        }
    }

    @Test
    fun ncxMuyAnidadoNoDesbordaLaPila() = runTest {
        val depth = 20_000
        val sb = StringBuilder()
        repeat(depth) { sb.append("<navPoint><navLabel><text>N$it</text></navLabel><content src=\"c.xhtml\"/>") }
        repeat(depth) { sb.append("</navPoint>") }
        val ncx = """<?xml version="1.0"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>$sb</navMap></ncx>"""
        val f = book(
            "ncx.epub",
            opf(
                """<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/><item id="c" href="c.xhtml" media-type="application/xhtml+xml"/>""",
                """<itemref idref="c"/>""",
            ),
            listOf("OEBPS/toc.ncx" to text(ncx), "OEBPS/c.xhtml" to text(EpubFixtures.chapter("<p>Hola.</p>"))),
        )
        EpubBook.open(f).use { b ->
            assertThat(b.toc.first().title).isEqualTo("N0")
            var d = 0
            var e = b.toc.firstOrNull()
            while (e != null) {
                d++
                e = e.children.firstOrNull()
            }
            assertThat(d).isAtMost(PackageParser.MAX_TOC_DEPTH)
            assertThat(b.chapter(0).plainText).isEqualTo("Hola.")
        }
    }

    @Test
    fun capituloRotoNoImpideAbrirElLibro() = runTest {
        // El indice apunta con fragmento a un capitulo demasiado grande.
        val nav = """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
<nav epub:type="toc"><ol><li><a href="a.xhtml">A</a></li><li><a href="b.xhtml#x">B</a></li></ol></nav></body></html>"""
        val big = text(EpubFixtures.chapter("<p id=\"x\">" + "texto largo ".repeat(3000) + "</p>"))
        val f = book(
            "roto.epub",
            opf(
                """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
<item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/>""",
                """<itemref idref="a"/><itemref idref="b"/>""",
            ),
            listOf("OEBPS/nav.xhtml" to text(nav), "OEBPS/a.xhtml" to text(EpubFixtures.chapter("<p>Bien.</p>")), "OEBPS/b.xhtml" to big),
        )
        EpubBook.open(f, EpubLimits(maxXhtmlBytes = 10_000)).use { b ->
            assertThat(b.toc.map { it.title }).containsExactly("A", "B").inOrder()
            assertThat(b.toc[1].target.chapter).isEqualTo(1)
            assertThat(b.chapter(0).plainText).isEqualTo("Bien.")
            val e = runCatching { b.chapter(1) }.exceptionOrNull()
            assertThat(e).isInstanceOf(EpubException.TooLarge::class.java)
        }
    }

    @Test
    fun limiteEspecificoParaXhtml() = runTest {
        val limits = EpubLimits()
        assertThat(limits.maxXhtmlBytes).isEqualTo(8L * 1024 * 1024)
        assertThat(limits.maxXhtmlBytes).isLessThan(limits.maxResourceBytes)
        // Una imagen mayor que el limite de XHTML se puede leer.
        val img = ByteArray(20_000) { (it % 251).toByte() }
        val f = EpubFixtures.single(tmp.root, "img.epub", text(EpubFixtures.chapter("<p>a</p>")), listOf("OEBPS/i.bin" to img))
        EpubBook.open(f, EpubLimits(maxXhtmlBytes = 10_000)).use { b ->
            assertThat(b.resource("OEBPS/i.bin")).isEqualTo(img)
        }
    }

    @Test
    fun manifiestoConRutaPeligrosaSeIgnoraYSpineVacioUsaXhtml() = runTest {
        val f = book(
            "b4.epub",
            opf(
                """<item id="mal" href="../../evil.xhtml" media-type="application/xhtml+xml"/>
<item id="c" href="c.xhtml" media-type="application/xhtml+xml"/>""",
                "",
            ),
            listOf("OEBPS/c.xhtml" to text(EpubFixtures.chapter("<p>Hola.</p>"))),
        )
        EpubBook.open(f).use { b ->
            assertThat(b.chapterCount).isEqualTo(1)
            assertThat(b.chapter(0).plainText).isEqualTo("Hola.")
        }
    }

    @Test
    fun fragmentoCodificadoSeDecodifica() = runTest {
        val body = """<p>Ver <a href="#nota%C3%B1">1</a>.</p><p id="notañ">Nota.</p>"""
        EpubBook.open(EpubFixtures.single(tmp.root, "frag.epub", text(EpubFixtures.chapter(body)))).use { b ->
            val doc = b.chapter(0)
            val link = (doc.blocks[0] as Block.Text).spans.map { it.style }.filterIsInstance<InlineStyle.Link>().single()
            assertThat(link.href).isEqualTo("OEBPS/c.xhtml#notañ")
            assertThat(b.locate(link.href)!!.offset).isEqualTo(doc.plainText.indexOf("Nota."))
            assertThat(b.locate("OEBPS/c.xhtml#nota%C3%B1")!!.offset).isEqualTo(doc.plainText.indexOf("Nota."))
        }
    }

    @Test
    fun percentDecodeExigeDosDigitosHex() {
        assertThat(EpubPaths.percentDecode("a%-1b")).isEqualTo("a%-1b")
        assertThat(EpubPaths.percentDecode("a%+Fb")).isEqualTo("a%+Fb")
        assertThat(EpubPaths.percentDecode("a%20b%C3%B1")).isEqualTo("a bñ")
        assertThat(EpubPaths.percentDecode("fin%2")).isEqualTo("fin%2")
        assertThat(EpubPaths.percentDecode("x%41")).isEqualTo("xA")
    }

    @Test
    fun brAlInicioYPreSinPrimerSalto() = runTest {
        val body = "<p><br/>Uno<br/>dos</p><pre>\nlinea</pre>"
        EpubBook.open(EpubFixtures.single(tmp.root, "br.epub", text(EpubFixtures.chapter(body)))).use { b ->
            assertThat(b.chapter(0).plainText).isEqualTo("Uno\ndos\nlinea")
        }
    }

    @Test
    fun textosDeNotasSeResuelvenEnOtroCapitulo() = runTest {
        val c1 = EpubFixtures.chapter("""<p>Hecho<a epub:type="noteref" href="notas.xhtml#n1"><sup>1</sup></a> cierto.</p>""")
        val notas = EpubFixtures.chapter("""<aside epub:type="footnote" id="n1"><p><a href="c.xhtml">1.</a> Fuente: archivo.</p></aside>""")
        val f = book(
            "notas.epub",
            opf(
                """<item id="c" href="c.xhtml" media-type="application/xhtml+xml"/><item id="n" href="notas.xhtml" media-type="application/xhtml+xml"/>""",
                """<itemref idref="c"/><itemref idref="n"/>""",
            ),
            listOf("OEBPS/c.xhtml" to text(c1), "OEBPS/notas.xhtml" to text(notas)),
        )
        EpubBook.open(f).use { b ->
            val texts = b.footnoteTexts(0)
            assertThat(texts).containsExactly("OEBPS/notas.xhtml#n1", "1. Fuente: archivo.")
        }
    }

    @Test
    fun encabezadosYListasConservanSuTipo() = runTest {
        val body = "<h2>Título</h2><ol><li>Uno</li></ol>"
        EpubBook.open(EpubFixtures.single(tmp.root, "k.epub", text(EpubFixtures.chapter(body)))).use { b ->
            val kinds = b.chapter(0).blocks.map { (it as Block.Text).kind }
            assertThat(kinds).containsExactly(BlockKind.Heading(2), BlockKind.ListItem("1.")).inOrder()
        }
    }
}
