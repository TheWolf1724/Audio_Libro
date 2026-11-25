package com.lectorvoz.format.epub

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.format.epub.EpubFixtures.text
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Auditoria 4: filas/celdas ocultas de una tabla y respaldo NCX cuando el nav no se puede leer. */
class Auditoria4Test {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun filasYCeldasOcultasNoSeLeen() = runTest {
        val f = EpubFixtures.zip(
            tmp.newFile("t.epub"),
            listOf(
                "META-INF/container.xml" to text(EpubFixtures.CONTAINER_OPF),
                "OEBPS/content.opf" to text(
                    """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title></metadata>
<manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a"/></spine></package>""",
                ),
                "OEBPS/a.xhtml" to text(
                    EpubFixtures.chapter(
                        """<table><tr><td>Uno</td><td hidden="hidden">SECRETO</td><td>Dos</td></tr><tr style="display: none"><td>OCULTA</td></tr><tr><td>Tres</td></tr></table>""",
                    ),
                ),
            ),
        )
        EpubBook.open(f).use { b ->
            val t = b.chapter(0).plainText
            assertThat(t).contains("Uno")
            assertThat(t).contains("Tres")
            assertThat(t).doesNotContain("SECRETO")
            assertThat(t).doesNotContain("OCULTA")
        }
    }

    @Test
    fun navQueFaltaCaeAlNcx() = runTest {
        val f = EpubFixtures.zip(
            tmp.newFile("n.epub"),
            listOf(
                "META-INF/container.xml" to text(EpubFixtures.CONTAINER_OPF),
                "OEBPS/content.opf" to text(
                    """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title></metadata>
<manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/><item id="ncx" href="t.ncx" media-type="application/x-dtbncx+xml"/></manifest><spine toc="ncx"><itemref idref="a"/></spine></package>""",
                ),
                "OEBPS/a.xhtml" to text(EpubFixtures.chapter("<p>Hola.</p>")),
                "OEBPS/t.ncx" to text(
                    """<?xml version="1.0"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap><navPoint id="n1"><navLabel><text>Principio</text></navLabel><content src="a.xhtml"/></navPoint></navMap></ncx>""",
                ),
            ),
        )
        EpubBook.open(f).use { b ->
            assertThat(b.toc.map { it.title }).containsExactly("Principio")
        }
    }
}
