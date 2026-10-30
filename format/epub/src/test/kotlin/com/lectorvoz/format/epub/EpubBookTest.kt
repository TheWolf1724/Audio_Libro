package com.lectorvoz.format.epub

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.Block
import com.lectorvoz.core.model.BlockAlign
import com.lectorvoz.core.model.BlockKind
import com.lectorvoz.core.model.BookFormat
import com.lectorvoz.core.model.ChapterDocument.Companion.OBJECT_CHAR
import com.lectorvoz.core.model.InlineSpan
import com.lectorvoz.core.model.InlineStyle
import com.lectorvoz.core.model.Locator
import com.lectorvoz.format.epub.EpubFixtures.text
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpubBookTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun Block.Text.str(plain: String) = plain.substring(start, end)

    @Test
    fun epub3MetadatosPortadaEIndice() = runTest {
        EpubBook.open(EpubFixtures.epub3(tmp.root)).use { book ->
            assertThat(book.format).isEqualTo(BookFormat.EPUB)
            assertThat(book.version).isEqualTo("3.0")
            val m = book.metadata
            assertThat(m.title).isEqualTo("Libro de prueba")
            assertThat(m.authors).containsExactly("Ana Autora", "Beto Autor").inOrder()
            assertThat(m.language).isEqualTo("es")
            assertThat(m.publisher).isEqualTo("Editorial")
            assertThat(m.description).isEqualTo("Una descripción")
            assertThat(m.identifier).isEqualTo("urn:uuid:1234")
            assertThat(m.coverHref).isEqualTo("OEBPS/images/fig1.png")
            assertThat(book.chapterCount).isEqualTo(2)
            assertThat(book.chapterPath(1)).isEqualTo("OEBPS/text/cap 2.xhtml")

            val toc = book.toc
            assertThat(toc.map { it.title }).containsExactly("Capítulo uno", "Sección dos", "Apéndices").inOrder()
            assertThat(toc[0].target).isEqualTo(Locator(0, 0))
            assertThat(toc[0].children.single().title).isEqualTo("Arranque")
            assertThat(toc[0].children.single().target).isEqualTo(Locator(0, 0))
            val cap2 = book.chapter(1).plainText
            assertThat(toc[1].target).isEqualTo(Locator(1, cap2.indexOf("Sección")))
            assertThat(toc[2].children.single().target).isEqualTo(Locator(1, cap2.indexOf("Más.")))
            // Sin enlace propio: toma el destino de su primer hijo.
            assertThat(toc[2].target).isEqualTo(toc[2].children.single().target)

            assertThat(book.resource("OEBPS/images/fig1.png")).isEqualTo(EpubFixtures.PNG_BYTES)
            assertThat(book.resource("OEBPS/images/no-existe.png")).isNull()
            assertThat(book.locate("OEBPS/text/cap 2.xhtml#sec2")).isEqualTo(toc[1].target)
        }
    }

    @Test
    fun epub3CapituloBloquesYEstilos() = runTest {
        EpubBook.open(EpubFixtures.epub3(tmp.root)).use { book ->
            val doc = book.chapter(0)
            val p = doc.plainText
            assertThat(p).isEqualTo(
                "Capítulo uno\n" +
                    "Hola mundo y adiós.\n" +
                    "Ver nota y web & más texto.\n" +
                    OBJECT_CHAR + "\n" +
                    "Primero\nSegundo\nTres\nCuatro\n" +
                    "Tabla\nNombre | Edad\nAna | 30\n" +
                    "Cita versal\n" +
                    "  linea 1\n  linea 2\n" +
                    "H2O y x2 c u s fin\n" +
                    OBJECT_CHAR + "\n" +
                    "Pie",
            )
            assertThat(doc.title).isEqualTo("Capítulo uno")
            assertThat(doc.language).isEqualTo("es")
            assertThat(doc.anchors["c1"]).isEqualTo(0)
            assertThat(doc.anchors["p5"]).isEqualTo(p.indexOf("fin"))

            val texts = doc.blocks.filterIsInstance<Block.Text>()
            fun block(s: String) = texts.first { it.str(p) == s }

            assertThat(block("Capítulo uno").kind).isEqualTo(BlockKind.Heading(1))
            val hola = block("Hola mundo y adiós.")
            assertThat(hola.align).isEqualTo(BlockAlign.CENTER)
            assertThat(hola.spans).containsExactly(
                InlineSpan(p.indexOf("mundo"), p.indexOf("mundo") + 5, InlineStyle.Bold),
                InlineSpan(p.indexOf("adiós"), p.indexOf("adiós") + 5, InlineStyle.Italic),
            ).inOrder()
            val ver = block("Ver nota y web & más texto.")
            assertThat(ver.spans.map { it.style }).containsExactly(
                InlineStyle.Link("OEBPS/text/cap 2.xhtml#sec2"),
                InlineStyle.Link("https://example.com"),
            ).inOrder()

            val img = doc.blocks.filterIsInstance<Block.Image>()
            assertThat(img.map { it.href }).containsExactly("OEBPS/images/fig1.png", "OEBPS/images/fig2.jpg").inOrder()
            assertThat(img[0].alt).isEqualTo("Figura 1")
            assertThat(img[0].widthPx).isEqualTo(100)
            img.forEach { assertThat(p[it.start]).isEqualTo(OBJECT_CHAR) }

            assertThat(block("Primero").kind).isEqualTo(BlockKind.ListItem("•"))
            assertThat(block("Segundo").kind).isEqualTo(BlockKind.ListItem("•"))
            assertThat(block("Tres").kind).isEqualTo(BlockKind.ListItem("3."))
            assertThat(block("Cuatro").kind).isEqualTo(BlockKind.ListItem("4."))
            assertThat(block("Tres").depth).isEqualTo(2)

            assertThat(block("Tabla").kind).isEqualTo(BlockKind.Caption)
            assertThat(block("Nombre | Edad").spans.map { it.style }).containsExactly(InlineStyle.Bold, InlineStyle.Bold)
            assertThat(doc.blocks.filterIsInstance<Block.Rule>().single().start).isEqualTo(p.indexOf("Ana | 30") + 8)

            val cita = block("Cita versal")
            assertThat(cita.kind).isEqualTo(BlockKind.Blockquote)
            assertThat(cita.depth).isEqualTo(1)
            assertThat(cita.spans.single()).isEqualTo(InlineSpan(p.indexOf("versal"), p.indexOf("versal") + 6, InlineStyle.SmallCaps))

            assertThat(block("  linea 1\n  linea 2").kind).isEqualTo(BlockKind.Preformatted)
            val styles = block("H2O y x2 c u s fin").spans.map { it.style }
            assertThat(styles).containsExactly(
                InlineStyle.Subscript, InlineStyle.Superscript, InlineStyle.Monospace,
                InlineStyle.Underline, InlineStyle.Strikethrough,
            ).inOrder()
            assertThat(block("Pie").kind).isEqualTo(BlockKind.Caption)

            // Bloques ordenados, sin solape y separados por '\n'.
            var prevEnd = -1
            for (b in doc.blocks.filter { it !is Block.Rule }) {
                assertThat(b.start).isGreaterThan(prevEnd)
                if (prevEnd >= 0) assertThat(p[b.start - 1]).isEqualTo('\n')
                prevEnd = b.end
            }
        }
    }

    @Test
    fun chapterTextDevuelveTextoTituloEIdioma() = runTest {
        EpubBook.open(EpubFixtures.epub3(tmp.root)).use { book ->
            val t = book.chapterText(1)
            assertThat(t.chapter).isEqualTo(1)
            assertThat(t.text).isEqualTo("Texto previo del capítulo dos.\nSección\nMás.")
            assertThat(t.title).isEqualTo("Sección dos")
            assertThat(t.language).isEqualTo("es")
        }
    }

    @Test
    fun epub2ConNcxAnidadoYPortadaMeta() = runTest {
        EpubBook.open(EpubFixtures.epub2(tmp.root)).use { book ->
            assertThat(book.version).isEqualTo("2.0")
            assertThat(book.metadata.title).isEqualTo("Libro Dos")
            assertThat(book.metadata.authors).containsExactly("Clara Autora")
            assertThat(book.metadata.identifier).isEqualTo("ISBN 978-84-0000")
            assertThat(book.metadata.coverHref).isEqualTo("img/cover.jpg")
            assertThat(book.chapterCount).isEqualTo(3)
            val toc = book.toc
            assertThat(toc.map { it.title }).containsExactly("Parte I", "Final").inOrder()
            assertThat(toc[0].children.map { it.title }).containsExactly("Capítulo 1", "Capítulo 2").inOrder()
            val ch1 = book.chapter(0).plainText
            assertThat(toc[0].children[0].target).isEqualTo(Locator(0, ch1.indexOf("Empieza")))
            assertThat(toc[0].children[1].target).isEqualTo(Locator(1, 0))
            assertThat(toc[1].target).isEqualTo(Locator(2, 0))
            assertThat(book.chapterText(1).language).isEqualTo("en")
            assertThat(book.chapterText(1).title).isEqualTo("Capítulo 2")
            assertThat(book.chapterText(0).language).isEqualTo("es-ES")
        }
    }

    @Test
    fun xhtmlMalFormadoSeLeeIgualmente() = runTest {
        val xhtml = "<html><body><p>Uno<p>Dos &eacute; &copy;<div/>Tres <b>negrita <i>mixta</b> fin</i><br/>Cuatro<p/>Cinco" +
            "<script>alert('x')</script><style>p{}</style>"
        EpubBook.open(EpubFixtures.single(tmp.root, "mal.epub", text(xhtml))).use { book ->
            val doc = book.chapter(0)
            assertThat(doc.plainText).isEqualTo("Uno\nDos é ©\nTres negrita mixta fin\nCuatro\nCinco")
            val b = doc.blocks.filterIsInstance<Block.Text>().first { doc.plainText.substring(it.start, it.end).startsWith("Tres") }
            assertThat(b.spans.filter { it.style == InlineStyle.Bold }.map { doc.plainText.substring(it.start, it.end) })
                .containsExactly("negrita mixta")
        }
    }

    @Test
    fun codificacionLatin1DeclaradaEnXml() = runTest {
        val xhtml = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><html><body><p>Año pequeño</p></body></html>"
        EpubBook.open(EpubFixtures.single(tmp.root, "latin1.epub", xhtml.toByteArray(Charsets.ISO_8859_1))).use { book ->
            assertThat(book.chapter(0).plainText).isEqualTo("Año pequeño")
        }
    }

    @Test
    fun cacheLruDeCapitulos() = runTest {
        val chapters = (1..5).map { "OEBPS/c$it.xhtml" to text(EpubFixtures.chapter("<p>Capítulo $it</p>")) }
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Cinco</dc:title></metadata>
<manifest>${(1..5).joinToString("") { "<item id=\"c$it\" href=\"c$it.xhtml\" media-type=\"application/xhtml+xml\"/>" }}</manifest>
<spine>${(1..5).joinToString("") { "<itemref idref=\"c$it\"/>" }}</spine></package>"""
        val file = EpubFixtures.zip(
            tmp.newFile("cinco.epub"),
            listOf("META-INF/container.xml" to text(EpubFixtures.CONTAINER_OPF), "OEBPS/content.opf" to text(opf)) + chapters,
        )
        EpubBook.open(file, cacheSize = 3).use { book ->
            assertThat(book.parseCount).isEqualTo(0)
            book.chapter(0)
            book.chapter(0)
            assertThat(book.parseCount).isEqualTo(1)
            book.chapter(1)
            book.chapter(2)
            book.chapter(0) // sigue en cache
            assertThat(book.parseCount).isEqualTo(3)
            book.chapter(3) // expulsa el menos usado (1)
            book.chapter(1)
            assertThat(book.parseCount).isEqualTo(5)
            assertThat(book.chapterText(4).text).isEqualTo("Capítulo 5")
        }
    }

    @Test
    fun rutaZipConPuntoPuntoSeIgnora() {
        val opf = EpubFixtures.OPF3.replace("href=\"text/cap1.xhtml\"", "href=\"../../evil.xhtml\"")
        val file = EpubFixtures.zip(
            tmp.newFile("malo.epub"),
            listOf(
                "META-INF/container.xml" to text(EpubFixtures.CONTAINER_OPF),
                "OEBPS/content.opf" to text(opf),
                "../../evil.xhtml" to text("<p>malo</p>"),
            ),
        )
        // La entrada peligrosa del manifiesto se ignora; el resto del libro se abre.
        EpubBook.open(file).use { book ->
            assertThat(book.chapterCount).isEqualTo(1)
            assertThat(book.chapterPath(0)).isEqualTo("OEBPS/text/cap 2.xhtml")
        }
    }

    @Test
    fun entradaZipMaliciosaNoEsAccesible() = runTest {
        val file = EpubFixtures.epub3(tmp.root, extra = listOf("../fuera.txt" to text("secreto")))
        EpubBook.open(file).use { book ->
            assertThat(book.resource("fuera.txt")).isNull()
            val e = runCatching { book.resource("../fuera.txt") }.exceptionOrNull()
            assertThat(e).isInstanceOf(EpubException.UnsafePath::class.java)
            val e2 = runCatching { book.resource("/etc/passwd") }.exceptionOrNull()
            assertThat(e2).isInstanceOf(EpubException.UnsafePath::class.java)
        }
    }

    @Test
    fun bombaZipSeDetecta() = runTest {
        // 3 MB de espacios comprimen a unos pocos KB: ratio muy superior a 100.
        val bomb = ByteArray(3 * 1024 * 1024) { ' '.code.toByte() }
        val xhtml = text("<html><body><p>a</p>") + bomb + text("</body></html>")
        EpubBook.open(EpubFixtures.single(tmp.root, "bomba.epub", xhtml)).use { book ->
            val e = runCatching { book.chapter(0) }.exceptionOrNull()
            assertThat(e).isInstanceOf(EpubException.ZipBomb::class.java)
        }
    }

    @Test
    fun recursoDemasiadoGrande() = runTest {
        val big = text(EpubFixtures.chapter("<p>" + "texto variado 123 ".repeat(2000) + "</p>"))
        val limits = EpubLimits(maxResourceBytes = 10_000)
        EpubBook.open(EpubFixtures.single(tmp.root, "grande.epub", big), limits).use { book ->
            val e = runCatching { book.chapter(0) }.exceptionOrNull()
            assertThat(e).isInstanceOf(EpubException.TooLarge::class.java)
        }
    }

    @Test
    fun drmDetectado() {
        val enc = """<?xml version="1.0"?>
<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
<enc:EncryptedData><enc:EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"/>
<enc:CipherData><enc:CipherReference URI="OEBPS/text/cap1.xhtml"/></enc:CipherData></enc:EncryptedData></encryption>"""
        val file = EpubFixtures.epub3(tmp.root, extra = listOf("META-INF/encryption.xml" to text(enc)))
        val e = assertThrows(EpubException.Drm::class.java) { EpubBook.open(file) }
        assertThat(e.message).contains("OEBPS/text/cap1.xhtml")
    }

    @Test
    fun ofuscacionDeFuentesNoEsDrm() = runTest {
        val enc = """<?xml version="1.0"?>
<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
<enc:EncryptedData><enc:EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/>
<enc:CipherData><enc:CipherReference URI="OEBPS/fonts/f.otf"/></enc:CipherData></enc:EncryptedData></encryption>"""
        val file = EpubFixtures.epub3(tmp.root, extra = listOf("META-INF/encryption.xml" to text(enc)))
        EpubBook.open(file).use { assertThat(it.chapter(0).plainText).startsWith("Capítulo uno") }
    }

    /** Una fuente oculta con un metodo desconocido tampoco es DRM: el texto se lee igual. */
    @Test
    fun fuenteOcultaConOtroMetodoNoEsDrm() = runTest {
        val enc = """<?xml version="1.0"?>
<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
<enc:EncryptedData><enc:EncryptionMethod Algorithm="urn:ejemplo:ocultacion-de-fuentes"/>
<enc:CipherData><enc:CipherReference URI="OEBPS/fonts/f.TTF"/></enc:CipherData></enc:EncryptedData></encryption>"""
        val file = EpubFixtures.epub3(tmp.root, extra = listOf("META-INF/encryption.xml" to text(enc)))
        EpubBook.open(file).use { assertThat(it.chapter(0).plainText).startsWith("Capítulo uno") }
    }

    @Test
    fun xmlConEntidadExternaNoSeResuelve() = runTest {
        val opf = EpubFixtures.OPF3
            .replace("<?xml version=\"1.0\" encoding=\"UTF-8\"?>", "<?xml version=\"1.0\"?><!DOCTYPE package [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>")
            .replace("<dc:title>Libro de   prueba</dc:title>", "<dc:title>T &xxe;</dc:title>")
        val file = EpubFixtures.zip(
            tmp.newFile("xxe.epub"),
            listOf(
                "META-INF/container.xml" to text(EpubFixtures.CONTAINER_OPF),
                "OEBPS/content.opf" to text(opf),
                "OEBPS/nav.xhtml" to text(EpubFixtures.NAV),
                "OEBPS/text/cap1.xhtml" to text(EpubFixtures.CAP1),
                "OEBPS/text/cap 2.xhtml" to text(EpubFixtures.CAP2),
            ),
        )
        EpubBook.open(file).use { book ->
            assertThat(book.metadata.title).doesNotContain("root:")
            assertThat(book.metadata.title).startsWith("T")
        }
    }

    @Test
    fun anidamientoProfundoNoDesbordaLaPila() = runTest {
        val xhtml = "<html><body>" + "<div>".repeat(5000) + "hondo" + "</div>".repeat(5000) + "<p>fuera</p></body></html>"
        EpubBook.open(EpubFixtures.single(tmp.root, "hondo.epub", text(xhtml))).use { book ->
            assertThat(book.chapter(0).plainText).isEqualTo("hondo\nfuera")
        }
    }

    @Test
    fun zipCorruptoEsInvalido() {
        val f = tmp.newFile("roto.epub")
        f.writeText("esto no es un zip")
        assertThrows(EpubException.Invalid::class.java) { EpubBook.open(f) }
    }

    @Test
    fun sinContainerBuscaElOpf() = runTest {
        val file = EpubFixtures.zip(
            tmp.newFile("sincontainer.epub"),
            listOf(
                "OEBPS/content.opf" to text(EpubFixtures.OPF3),
                "OEBPS/nav.xhtml" to text(EpubFixtures.NAV),
                "OEBPS/text/cap1.xhtml" to text(EpubFixtures.CAP1),
                "OEBPS/text/cap 2.xhtml" to text(EpubFixtures.CAP2),
            ),
        )
        EpubBook.open(file).use { assertThat(it.metadata.title).isEqualTo("Libro de prueba") }
    }
}
