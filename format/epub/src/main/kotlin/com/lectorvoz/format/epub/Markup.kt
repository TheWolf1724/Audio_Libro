package com.lectorvoz.format.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.nio.charset.Charset

/** Lectura de XML y XHTML con jsoup (tolerante y sin resolver entidades externas). */
internal object Markup {
    private val XML_ENCODING = Regex("""^<\?xml[^>]*encoding\s*=\s*["']([A-Za-z0-9._-]+)["']""")
    private val META_CHARSET = Regex("""<meta[^>]+charset\s*=\s*["']?([A-Za-z0-9._-]+)""", RegexOption.IGNORE_CASE)

    // Etiquetas autocerradas de XHTML que el parser HTML trataria como abiertas.
    private val SELF_CLOSING = Regex("""<([a-zA-Z][\w:.-]*)(\s[^<>]*?)?/>""")
    private val VOID = hashSetOf(
        "br", "hr", "img", "input", "meta", "link", "area", "base", "col", "embed", "param",
        "source", "track", "wbr", "image",
    )

    /** Decodifica bytes de XML/XHTML: BOM, declaracion XML, meta charset o UTF-8. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
        val name = XML_ENCODING.find(head)?.groupValues?.get(1) ?: META_CHARSET.find(head)?.groupValues?.get(1)
        val cs = name?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        return String(bytes, cs)
    }

    /** Analiza XML (container, OPF, NCX, encryption). jsoup no carga entidades externas. */
    fun parseXml(bytes: ByteArray): Document = Jsoup.parse(decode(bytes), "", Parser.xmlParser())

    /** Analiza XHTML con el parser HTML tolerante, expandiendo antes `<div/>` a `<div></div>`. */
    fun parseXhtml(bytes: ByteArray): Document {
        val text = decode(bytes)
        val fixed = SELF_CLOSING.replace(text) { m ->
            val tag = m.groupValues[1]
            if (tag.lowercase().substringAfterLast(':') in VOID) m.value else "<$tag${m.groupValues[2]}></$tag>"
        }
        return Jsoup.parse(fixed, "", Parser.htmlParser())
    }
}

/** Nombre local de la etiqueta en minusculas, sin prefijo de espacio de nombres. */
internal fun Element.localName(): String = normalName().substringAfterLast(':')

/** Descendientes (incluido el propio) con nombre local [name]. */
internal fun Element.byLocalName(name: String): List<Element> = allElements.filter { it.localName() == name }

/** Hijos directos con nombre local [name]. */
internal fun Element.childrenByLocalName(name: String): List<Element> = children().filter { it.localName() == name }

/** Valor de un atributo ignorando el prefijo ("xlink:href" o "href"). */
internal fun Element.attrLocal(name: String): String? {
    for (a in attributes()) {
        if (a.key.equals(name, ignoreCase = true) || a.key.substringAfterLast(':').equals(name, ignoreCase = true)) {
            return a.value
        }
    }
    return null
}
