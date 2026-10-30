package com.lectorvoz.format.epub

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Genera EPUB de prueba en disco (dentro de las pruebas, sin ficheros versionados). */
object EpubFixtures {
    const val CONTAINER_OPF = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""

    val PNG_BYTES = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

    /** Escribe un zip con `mimetype` sin comprimir primero y el resto comprimido. */
    fun zip(file: File, entries: List<Pair<String, ByteArray>>, withMimetype: Boolean = true): File {
        ZipOutputStream(file.outputStream()).use { zos ->
            if (withMimetype) {
                val data = "application/epub+zip".toByteArray()
                val e = ZipEntry("mimetype")
                e.method = ZipEntry.STORED
                e.size = data.size.toLong()
                e.compressedSize = data.size.toLong()
                e.crc = CRC32().also { it.update(data) }.value
                zos.putNextEntry(e)
                zos.write(data)
                zos.closeEntry()
            }
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return file
    }

    fun text(s: String) = s.toByteArray(Charsets.UTF_8)

    // ------------------------------------------------------------------ EPUB 3

    const val CAP1 = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="es" lang="es">
<head><title>Uno</title><style>p { color: red; }</style><script>var x = "<p>no</p>";</script></head>
<body>
  <h1 id="c1">Capítulo   uno</h1>
  <p style="text-align: center">Hola   <b>mundo</b>
     y <i>adiós</i>.</p>
  <p>Ver <a href="cap%202.xhtml#sec2">nota</a> y <a href="https://example.com">web</a> &amp; más&nbsp;texto.</p>
  <div><img src="../images/fig1.png" alt="Figura 1" width="100"/></div>
  <ul><li>Primero</li><li>Segundo<ol start="3"><li>Tres</li><li>Cuatro</li></ol></li></ul>
  <table><caption>Tabla</caption><tr><th>Nombre</th><th>Edad</th></tr><tr><td>Ana</td><td><p>30</p></td></tr></table>
  <hr/>
  <blockquote><p>Cita <span class="smallcaps">versal</span></p></blockquote>
  <pre>  linea 1
  linea 2</pre>
  <p>H<sub>2</sub>O y x<sup>2</sup> <code>c</code> <u>u</u> <s>s</s> <span epub:type="pagebreak" id="p5" title="5">5</span>fin</p>
  <figure><svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"><image xlink:href="../images/fig2.jpg"/></svg><figcaption>Pie</figcaption></figure>
</body></html>"""

    const val CAP2 = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Dos</title></head>
<body><p>Texto previo del capítulo dos.</p><h2 id="sec2">Sección</h2><p id="final">Más.</p></body></html>"""

    const val NAV = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Índice</title></head>
<body>
<nav epub:type="landmarks"><ol><li><a href="text/cap1.xhtml">Inicio</a></li></ol></nav>
<nav epub:type="toc" id="toc"><h1>Índice</h1>
<ol>
  <li><a href="text/cap1.xhtml">Capítulo uno</a>
    <ol><li><a href="text/cap1.xhtml#c1">Arranque</a></li></ol>
  </li>
  <li><a href="text/cap%202.xhtml#sec2">Sección dos</a></li>
  <li><span>Apéndices</span><ol><li><a href="text/cap%202.xhtml#final">Final</a></li></ol></li>
</ol></nav>
</body></html>"""

    const val OPF3 = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
  <dc:identifier>isbn-otro</dc:identifier>
  <dc:identifier id="uid">urn:uuid:1234</dc:identifier>
  <dc:title>Libro de   prueba</dc:title>
  <dc:creator>Ana Autora</dc:creator>
  <dc:creator>Beto Autor</dc:creator>
  <dc:language>es</dc:language>
  <dc:publisher>Editorial</dc:publisher>
  <dc:description>&lt;p&gt;Una &lt;b&gt;descripción&lt;/b&gt;&lt;/p&gt;</dc:description>
</metadata>
<manifest>
  <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
  <item id="c1" href="text/cap1.xhtml" media-type="application/xhtml+xml"/>
  <item id="c2" href="text/cap%202.xhtml" media-type="application/xhtml+xml"/>
  <item id="img1" href="images/fig1.png" media-type="image/png" properties="cover-image"/>
  <item id="img2" href="images/fig2.jpg" media-type="image/jpeg"/>
</manifest>
<spine><itemref idref="c1"/><itemref idref="c2"/></spine>
</package>"""

    fun epub3(dir: File, extra: List<Pair<String, ByteArray>> = emptyList()): File = zip(
        File(dir, "libro3.epub"),
        listOf(
            "META-INF/container.xml" to text(CONTAINER_OPF),
            "OEBPS/content.opf" to text(OPF3),
            "OEBPS/nav.xhtml" to text(NAV),
            "OEBPS/text/cap1.xhtml" to text(CAP1),
            "OEBPS/text/cap 2.xhtml" to text(CAP2),
            "OEBPS/images/fig1.png" to PNG_BYTES,
            "OEBPS/images/fig2.jpg" to byteArrayOf(1, 2, 3),
        ) + extra,
    )

    // ------------------------------------------------------------------ EPUB 2

    const val OPF2 = """<?xml version="1.0" encoding="UTF-8"?>
<opf:package xmlns:opf="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
<opf:metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
  <dc:title>Libro Dos</dc:title>
  <dc:creator opf:role="aut">Clara Autora</dc:creator>
  <dc:language>es-ES</dc:language>
  <dc:identifier id="BookId">ISBN 978-84-0000</dc:identifier>
  <opf:meta name="cover" content="portada"/>
</opf:metadata>
<opf:manifest>
  <opf:item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
  <opf:item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
  <opf:item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
  <opf:item id="ch3" href="ch3.xhtml" media-type="application/xhtml+xml"/>
  <opf:item id="portada" href="img/cover.jpg" media-type="image/jpeg"/>
</opf:manifest>
<opf:spine toc="ncx"><opf:itemref idref="ch1"/><opf:itemref idref="ch2"/><opf:itemref idref="ch3" linear="no"/></opf:spine>
</opf:package>"""

    const val NCX = """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
<head/><docTitle><text>Libro Dos</text></docTitle>
<navMap>
  <navPoint id="n1" playOrder="1"><navLabel><text>Parte I</text></navLabel><content src="ch1.xhtml"/>
    <navPoint id="n2" playOrder="2"><navLabel><text>Capítulo 1</text></navLabel><content src="ch1.xhtml#a1"/></navPoint>
    <navPoint id="n3" playOrder="3"><navLabel><text>Capítulo 2</text></navLabel><content src="ch2.xhtml"/></navPoint>
  </navPoint>
  <navPoint id="n4" playOrder="4"><navLabel><text>Final</text></navLabel><content src="ch3.xhtml"/></navPoint>
</navMap></ncx>"""

    fun chapter(body: String, lang: String? = null): String =
        """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"${lang?.let { " xml:lang=\"$it\"" } ?: ""}><head><title>t</title></head><body>$body</body></html>"""

    fun epub2(dir: File): File = zip(
        File(dir, "libro2.epub"),
        listOf(
            "META-INF/container.xml" to text(CONTAINER_OPF.replace("OEBPS/content.opf", "content.opf")),
            "content.opf" to text(OPF2),
            "toc.ncx" to text(NCX),
            "ch1.xhtml" to text(chapter("<h1>Parte I</h1><p>Intro.</p><p id=\"a1\">Empieza el capítulo uno.</p>")),
            "ch2.xhtml" to text(chapter("<p>Capítulo dos.</p>", "en")),
            "ch3.xhtml" to text(chapter("<p>Fin.</p>")),
            "img/cover.jpg" to byteArrayOf(9, 9, 9),
        ),
    )

    /** EPUB minimo con un unico capitulo [xhtml] en OEBPS/c.xhtml. */
    fun single(dir: File, name: String, xhtml: ByteArray, extra: List<Pair<String, ByteArray>> = emptyList()): File = zip(
        File(dir, name),
        listOf(
            "META-INF/container.xml" to text(CONTAINER_OPF),
            "OEBPS/content.opf" to text(
                """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Uno</dc:title></metadata>
<manifest><item id="c" href="c.xhtml" media-type="application/xhtml+xml"/></manifest>
<spine><itemref idref="c"/></spine></package>""",
            ),
            "OEBPS/c.xhtml" to xhtml,
        ) + extra,
    )
}
