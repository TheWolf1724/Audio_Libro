package com.lectorvoz.format.epub

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.format.epub.EpubFixtures.text
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Revision 2 (M8/B11): `footnoteTexts` solo acepta destinos que parecen notas. */
class NotasRevision2Test {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun book(name: String, chapters: List<Pair<String, String>>): java.io.File {
        val manifest = chapters.mapIndexed { i, (href, _) ->
            """<item id="c$i" href="${href.replace("%", "%25")}" media-type="application/xhtml+xml"/>"""
        }.joinToString("")
        val spine = chapters.indices.joinToString("") { """<itemref idref="c$it"/>""" }
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title></metadata>
<manifest>$manifest</manifest><spine>$spine</spine></package>"""
        return EpubFixtures.zip(
            tmp.newFile(name),
            listOf(
                "META-INF/container.xml" to text(EpubFixtures.CONTAINER_OPF),
                "OEBPS/content.opf" to text(opf),
            ) + chapters.map { (href, body) -> "OEBPS/$href" to text(EpubFixtures.chapter(body)) },
        )
    }

    @Test
    fun referenciaCruzadaATituloOParrafoNoEsNota() = runTest {
        val c1 = """<p>Ver la parte <a href="c2.xhtml#t2">II</a> y el apartado <a href="c2.xhtml#p3">3</a>.</p>"""
        val c2 = """<h2 id="t2">II</h2><p id="p3">Un párrafo normal de la segunda parte.</p>"""
        EpubBook.open(book("cruzadas.epub", listOf("c1.xhtml" to c1, "c2.xhtml" to c2))).use { b ->
            assertThat(b.footnoteTexts(0)).isEmpty()
        }
    }

    @Test
    fun notaSinEnlaceDeVueltaQueEmpiezaPorLaLlamada() = runTest {
        val c1 = """<p>Hecho<a href="notas.xhtml#n7"><sup>7</sup></a> cierto.</p>"""
        val notas = """<p id="n7">7. Fuente: archivo municipal.</p>"""
        EpubBook.open(book("notas.epub", listOf("c1.xhtml" to c1, "notas.xhtml" to notas))).use { b ->
            assertThat(b.footnoteTexts(0)).containsExactly("OEBPS/notas.xhtml#n7", "7. Fuente: archivo municipal.")
        }
    }

    @Test
    fun capitulosDeNotasNoExpulsanLosDelLector() = runTest {
        val c1 = """<p>Hecho<a href="notas.xhtml#n1"><sup>1</sup></a> cierto.</p>"""
        val notas = """<p id="n1"><a href="c1.xhtml">1</a> Nota.</p>"""
        EpubBook.open(book("cache.epub", listOf("c1.xhtml" to c1, "notas.xhtml" to notas)), cacheSize = 1).use { b ->
            b.chapter(0)
            assertThat(b.footnoteTexts(0)).hasSize(1)
            val parses = b.parseCount
            b.chapter(0) // sigue en la cache
            assertThat(b.parseCount).isEqualTo(parses)
        }
    }

    @Test
    fun rutaConPorcientoLiteralNoSeDecodificaDosVeces() = runTest {
        val c1 = """<p>Hecho<a href="a%2541.xhtml#n1"><sup>1</sup></a> cierto.</p>"""
        val notas = """<p id="n1">1. Nota con ruta rara.</p>"""
        EpubBook.open(book("porciento.epub", listOf("c1.xhtml" to c1, "a%41.xhtml" to notas))).use { b ->
            assertThat(b.footnoteTexts(0)).containsExactly("OEBPS/a%41.xhtml#n1", "1. Nota con ruta rara.")
        }
    }
}
