package com.lectorvoz.format.epub

import com.lectorvoz.core.model.Block
import com.lectorvoz.core.model.BlockAlign
import com.lectorvoz.core.model.BlockKind
import com.lectorvoz.core.model.ChapterDocument
import com.lectorvoz.core.model.InlineSpan
import com.lectorvoz.core.model.InlineStyle
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Convierte un documento XHTML de un capitulo en [ChapterDocument]: bloques, estilos en
 * linea, imagenes y anclas, con el espacio en blanco colapsado como en un navegador.
 *
 * @param chapterPath ruta del capitulo en el contenedor (para resolver enlaces e imagenes).
 */
internal class XhtmlConverter(private val chapterPath: String) {

    /** Contexto heredado por los descendientes. */
    private data class Ctx(
        val kind: BlockKind = BlockKind.Paragraph,
        val align: BlockAlign? = null,
        val depth: Int = 0,
        val pre: Boolean = false,
        val inlineOnly: Boolean = false,
        val listLevel: Int = 0,
    )

    private val sb = StringBuilder()
    private val blocks = ArrayList<Block>()
    private val spans = ArrayList<InlineSpan>()
    private val anchors = LinkedHashMap<String, Int>()
    private val pendingAnchors = ArrayList<String>()
    private var blockOpen = false
    private var blockStart = 0
    private var blockKind: BlockKind = BlockKind.Paragraph
    private var blockAlign: BlockAlign? = null
    private var blockDepth = 0
    private var pendingSpace = false
    private var pendingSeparator: String? = null
    private var pendingMarker: String? = null
    private var ctx = Ctx()
    private var headingTitle: String? = null

    /** true justo tras abrir un <pre>: su primer salto de linea se ignora (como en HTML). */
    private var preStart = false

    /** Convierte [doc]; [index] y [fallbackLanguage] van al resultado. */
    fun convert(doc: Document, index: Int, fallbackLanguage: String?): ChapterDocument {
        val html = doc.selectFirst("html")
        val body = doc.body()
        val language = listOfNotNull(
            body.attr("xml:lang"), body.attr("lang"), html?.attr("xml:lang"), html?.attr("lang"),
        ).firstOrNull { it.isNotBlank() } ?: fallbackLanguage
        val docTitle = doc.selectFirst("head > title")?.text()?.takeIf { it.isNotBlank() }
        ctx = Ctx(align = parseAlign(body))
        walkChildren(body, 0)
        closeBlock()
        val text = sb.toString()
        for (a in pendingAnchors) anchors.putIfAbsent(a, text.length)
        val fixedAnchors = anchors.mapValues { it.value.coerceIn(0, text.length) }
        return ChapterDocument(
            index = index,
            title = headingTitle ?: docTitle,
            language = language,
            plainText = text,
            blocks = attachSpans(),
            anchors = fixedAnchors,
        )
    }

    /**
     * Capitulo formado por una unica imagen (spine con image/x). [path] ya es una ruta del
     * contenedor: no se resuelve otra vez (antes se duplicaba el directorio).
     */
    fun imageOnly(index: Int, path: String): ChapterDocument {
        sb.append(ChapterDocument.OBJECT_CHAR)
        blocks.add(Block.Image(0, path, null))
        return ChapterDocument(index, null, null, sb.toString(), blocks)
    }

    // ------------------------------------------------------------------ recorrido

    private fun walkChildren(el: Element, level: Int) {
        for (child in el.childNodes()) walk(child, level + 1)
    }

    private fun walk(node: Node, level: Int) {
        when (node) {
            is TextNode -> text(node.wholeText)
            is Element -> element(node, level)
            else -> {} // comentarios, doctype...
        }
    }

    private fun element(el: Element, level: Int) {
        val name = el.localName()
        if (name in SKIP) return
        recordAnchor(el)
        if (level > MAX_DEPTH) {
            text(el.text())
            return
        }
        val style = el.attr("style").lowercase()
        if (el.hasAttr("hidden") || style.replace(" ", "").contains("display:none")) return
        val epubType = el.attr("epub:type").lowercase()
        if (epubType.contains("pagebreak") || el.attr("role") == "doc-pagebreak") return

        when (name) {
            "br" -> {
                if (ctx.inlineOnly) {
                    pendingSpace = true
                } else if (blockOpen && sb.length > blockStart) {
                    // Un <br> al inicio del bloque no deja una linea en blanco.
                    trimTrailingSpaces()
                    if (sb.length > blockStart) sb.append('\n')
                    pendingSpace = false
                }
                return
            }
            "img" -> {
                image(el.attr("src"), el.attr("alt").ifBlank { null }, el.attr("width"), el.attr("height"))
                return
            }
            "image" -> {
                image(el.attrLocal("href") ?: "", null, el.attr("width"), el.attr("height"))
                return
            }
            "svg" -> {
                val img = el.byLocalName("image").firstOrNull()
                if (img != null) image(img.attrLocal("href") ?: "", el.selectFirst("title")?.text(), img.attr("width"), img.attr("height"))
                return
            }
            "hr" -> {
                if (ctx.inlineOnly) return
                closeBlock()
                blocks.add(Block.Rule(sb.length))
                return
            }
            "table" -> if (!ctx.inlineOnly) {
                table(el, level)
                return
            }
            "ul", "ol" -> if (!ctx.inlineOnly) {
                list(el, level, ordered = name == "ol")
                return
            }
        }

        if (name in BLOCKS && !ctx.inlineOnly) {
            val align = parseAlign(el) ?: ctx.align
            val newCtx = when (name) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> ctx.copy(kind = BlockKind.Heading(name[1] - '0'), align = align)
                "blockquote" -> ctx.copy(kind = BlockKind.Blockquote, align = align, depth = ctx.depth + 1)
                "pre" -> ctx.copy(kind = BlockKind.Preformatted, align = align, pre = true)
                "figcaption", "caption" -> ctx.copy(kind = BlockKind.Caption, align = align)
                "dd" -> ctx.copy(align = align, depth = ctx.depth + 1)
                "center" -> ctx.copy(align = BlockAlign.CENTER)
                else -> ctx.copy(align = align)
            }
            closeBlock()
            val saved = ctx
            ctx = newCtx
            if (name == "pre") preStart = true
            val before = sb.length
            withInlineStyles(el, name) { walkChildren(el, level) }
            closeBlock()
            ctx = saved
            if (headingTitle == null && name.length == 2 && name[0] == 'h' && name[1] in '1'..'6' && sb.length > before) {
                headingTitle = el.text().trim().ifEmpty { null }
            }
            return
        }
        if (name in BLOCKS) {
            // Dentro de una celda: los bloques solo separan con un espacio.
            pendingSpace = true
            withInlineStyles(el, name) { walkChildren(el, level) }
            pendingSpace = true
            return
        }
        withInlineStyles(el, name) { walkChildren(el, level) }
    }

    /** Ejecuta [body] registrando los estilos en linea que aporta [el]. */
    private inline fun withInlineStyles(el: Element, name: String, body: () -> Unit) {
        val styles = inlineStyles(el, name)
        if (styles.isEmpty()) {
            body()
            return
        }
        val start = contentPosition()
        body()
        val end = sb.length
        if (end > start) for (s in styles) spans.add(InlineSpan(start, end, s))
    }

    /** Posicion donde caera el proximo caracter de contenido. */
    private fun contentPosition(): Int = when {
        !blockOpen -> if (sb.isEmpty()) 0 else sb.length + 1
        pendingSeparator != null && sb.length > blockStart -> sb.length + pendingSeparator!!.length
        pendingSpace && sb.length > blockStart && sb[sb.length - 1] != '\n' -> sb.length + 1
        else -> sb.length
    }

    private fun inlineStyles(el: Element, name: String): List<InlineStyle> {
        val out = ArrayList<InlineStyle>(2)
        when (name) {
            "b", "strong" -> out.add(InlineStyle.Bold)
            "i", "em", "cite", "var", "dfn" -> out.add(InlineStyle.Italic)
            "u", "ins" -> out.add(InlineStyle.Underline)
            "s", "strike", "del" -> out.add(InlineStyle.Strikethrough)
            "sup" -> out.add(InlineStyle.Superscript)
            "sub" -> out.add(InlineStyle.Subscript)
            "code", "kbd", "samp", "tt" -> out.add(InlineStyle.Monospace)
            "th" -> out.add(InlineStyle.Bold)
            "a" -> {
                val href = el.attr("href")
                if (href.isNotBlank()) resolveLink(href)?.let { out.add(InlineStyle.Link(it)) }
            }
        }
        val style = el.attr("style")
        if (style.isNotEmpty()) {
            val decl = parseStyle(style)
            decl["font-weight"]?.let { w ->
                if ((w == "bold" || w == "bolder" || (w.toIntOrNull() ?: 0) >= 600) && InlineStyle.Bold !in out) out.add(InlineStyle.Bold)
            }
            decl["font-style"]?.let { if ((it == "italic" || it == "oblique") && InlineStyle.Italic !in out) out.add(InlineStyle.Italic) }
            val deco = (decl["text-decoration"] ?: "") + " " + (decl["text-decoration-line"] ?: "")
            if ("underline" in deco && InlineStyle.Underline !in out) out.add(InlineStyle.Underline)
            if ("line-through" in deco && InlineStyle.Strikethrough !in out) out.add(InlineStyle.Strikethrough)
            val variant = (decl["font-variant"] ?: "") + " " + (decl["font-variant-caps"] ?: "")
            if ("small-caps" in variant) out.add(InlineStyle.SmallCaps)
            when (decl["vertical-align"]) {
                "super" -> if (InlineStyle.Superscript !in out) out.add(InlineStyle.Superscript)
                "sub" -> if (InlineStyle.Subscript !in out) out.add(InlineStyle.Subscript)
            }
        }
        if (InlineStyle.SmallCaps !in out) {
            val cls = el.className().lowercase()
            if (cls.isNotEmpty() && cls.split(' ').any { it == "sc" || "smallcap" in it || "small-caps" in it || "versalita" in it }) {
                out.add(InlineStyle.SmallCaps)
            }
        }
        return out
    }

    private fun resolveLink(href: String): String? {
        if (EpubPaths.isExternal(href)) return href
        val frag = EpubPaths.percentDecode(href.substringAfter('#', ""))
        val path = if (href.startsWith("#")) {
            chapterPath
        } else {
            try {
                EpubPaths.resolve(chapterPath, href)
            } catch (e: EpubException.UnsafePath) {
                return null
            }
        }
        return if (frag.isEmpty()) path else "$path#$frag"
    }

    // ------------------------------------------------------------------ listas y tablas

    private fun list(el: Element, level: Int, ordered: Boolean) {
        closeBlock()
        val saved = ctx
        ctx = ctx.copy(
            kind = BlockKind.Paragraph,
            depth = ctx.depth + 1,
            listLevel = ctx.listLevel + 1,
            align = parseAlign(el) ?: ctx.align,
        )
        var counter = el.attr("start").trim().toIntOrNull() ?: 1
        val type = el.attr("type").trim()
        val bullet = BULLETS[(ctx.listLevel - 1) % BULLETS.size]
        for (child in el.childNodes()) {
            if (child is Element && child.localName() == "li") {
                recordAnchor(child)
                child.attr("value").trim().toIntOrNull()?.let { counter = it }
                closeBlock()
                pendingMarker = if (ordered) orderedMarker(counter, type) else bullet
                withInlineStyles(child, "li") { walkChildren(child, level + 1) }
                closeBlock()
                pendingMarker = null
                counter++
            } else {
                walk(child, level + 1)
            }
        }
        closeBlock()
        ctx = saved
    }

    private fun table(el: Element, level: Int) {
        closeBlock()
        val saved = ctx
        for (cap in el.childrenByLocalName("caption")) {
            ctx = saved.copy(kind = BlockKind.Caption)
            walkChildren(cap, level + 1)
            closeBlock()
        }
        ctx = saved.copy(kind = BlockKind.Paragraph, align = parseAlign(el) ?: saved.align)
        for (row in rowsOf(el)) {
            recordAnchor(row)
            closeBlock()
            for (cell in row.children()) {
                val cn = cell.localName()
                if (cn != "td" && cn != "th") continue
                recordAnchor(cell)
                pendingSeparator = CELL_SEPARATOR
                val rowCtx = ctx
                ctx = rowCtx.copy(inlineOnly = true)
                withInlineStyles(cell, cn) { walkChildren(cell, level + 2) }
                ctx = rowCtx
            }
            pendingSeparator = null
            closeBlock()
        }
        ctx = saved
    }

    /** Filas de la tabla en orden (thead, tbody, tfoot o tr directos), sin tablas anidadas. */
    private fun rowsOf(table: Element): List<Element> {
        val rows = ArrayList<Element>()
        for (c in table.children()) {
            when (c.localName()) {
                "tr" -> rows.add(c)
                "thead", "tbody", "tfoot" -> rows.addAll(c.childrenByLocalName("tr"))
            }
        }
        return rows
    }

    // ------------------------------------------------------------------ texto

    private fun text(raw: String) {
        if (raw.isEmpty()) return
        if (ctx.pre && !ctx.inlineOnly) {
            for (c in raw) {
                if (c == '\r') continue
                if (c == ChapterDocument.OBJECT_CHAR) continue
                if (preStart) {
                    preStart = false
                    if (c == '\n') continue
                }
                ensureBlock()
                sb.append(c)
            }
            return
        }
        for (c in raw) {
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C') {
                pendingSpace = true
                continue
            }
            if (c == ChapterDocument.OBJECT_CHAR) continue
            ensureBlock()
            val hasContent = sb.length > blockStart
            val sep = pendingSeparator
            if (sep != null) {
                if (hasContent) sb.append(sep)
                pendingSeparator = null
                pendingSpace = false
            } else if (pendingSpace && hasContent && sb[sb.length - 1] != '\n') {
                sb.append(' ')
            }
            pendingSpace = false
            sb.append(c)
        }
    }

    private fun ensureBlock() {
        if (blockOpen) return
        if (sb.isNotEmpty()) sb.append('\n')
        blockStart = sb.length
        blockOpen = true
        blockKind = pendingMarker?.let { BlockKind.ListItem(it) } ?: ctx.kind
        pendingMarker = null
        blockAlign = ctx.align
        blockDepth = ctx.depth
        pendingSpace = false
        for (a in pendingAnchors) anchors.putIfAbsent(a, blockStart)
        pendingAnchors.clear()
    }

    private fun trimTrailingSpaces() {
        while (sb.length > blockStart && (sb[sb.length - 1] == ' ' || sb[sb.length - 1] == '\n')) {
            sb.setLength(sb.length - 1)
        }
    }

    private fun closeBlock() {
        if (!blockOpen) return
        trimTrailingSpaces()
        if (sb.length == blockStart) {
            if (blockStart > 0) sb.setLength(blockStart - 1)
        } else {
            blocks.add(Block.Text(blockStart, sb.length, blockKind, emptyList(), blockAlign, blockDepth))
        }
        blockOpen = false
        pendingSpace = false
        pendingSeparator = null
    }

    private fun image(src: String, alt: String?, width: String?, height: String?) {
        if (src.isBlank() || EpubPaths.isExternal(src)) return
        val href = try {
            EpubPaths.resolve(chapterPath, src)
        } catch (e: EpubException.UnsafePath) {
            return
        }
        if (ctx.inlineOnly) {
            // En una tabla la imagen se sustituye por su texto alternativo.
            if (!alt.isNullOrBlank()) text(" $alt ")
            return
        }
        closeBlock()
        if (sb.isNotEmpty()) sb.append('\n')
        val start = sb.length
        sb.append(ChapterDocument.OBJECT_CHAR)
        for (a in pendingAnchors) anchors.putIfAbsent(a, start)
        pendingAnchors.clear()
        blocks.add(Block.Image(start, href, alt, px(width), px(height)))
    }

    private fun recordAnchor(el: Element) {
        val id = el.id().ifEmpty { if (el.localName() == "a") el.attr("name") else "" }
        if (id.isEmpty() || anchors.containsKey(id)) return
        if (blockOpen) anchors[id] = contentPosition() else pendingAnchors.add(id)
    }

    /** Reparte los estilos entre los bloques de texto, recortados a su rango. */
    private fun attachSpans(): List<Block> {
        if (spans.isEmpty()) return blocks
        val text = blocks.withIndex().filter { it.value is Block.Text }
        val starts = IntArray(text.size) { text[it].value.start }
        val perBlock = HashMap<Int, MutableList<InlineSpan>>()
        for (sp in spans) {
            var k = starts.binarySearch(sp.start).let { if (it >= 0) it else -it - 2 }.coerceAtLeast(0)
            while (k < text.size) {
                val b = text[k].value
                if (b.start >= sp.end) break
                val s = maxOf(sp.start, b.start)
                val e = minOf(sp.end, b.end)
                if (e > s) perBlock.getOrPut(text[k].index) { ArrayList() }.add(InlineSpan(s, e, sp.style))
                k++
            }
        }
        return blocks.mapIndexed { i, b ->
            val list = perBlock[i]
            if (list != null && b is Block.Text) b.copy(spans = list.sortedBy { it.start }) else b
        }
    }

    companion object {
        private const val MAX_DEPTH = 200
        const val CELL_SEPARATOR = " | "
        private val BULLETS = arrayOf("•", "◦", "▪")
        private val SKIP = hashSetOf(
            "script", "style", "head", "noscript", "template", "title", "case", "object", "audio",
            "video", "iframe", "button", "input", "select", "textarea",
        )
        private val BLOCKS = hashSetOf(
            "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "li", "dl", "dt", "dd",
            "pre", "figure", "figcaption", "caption", "section", "article", "header", "footer",
            "aside", "nav", "main", "address", "center", "tr", "td", "th", "thead", "tbody",
            "tfoot", "body", "html", "details", "summary", "hgroup", "form", "fieldset", "legend",
            "ul", "ol", "table",
        )

        private fun px(v: String?): Int? = v?.trim()?.removeSuffix("px")?.toIntOrNull()?.takeIf { it > 0 }

        private fun orderedMarker(n: Int, type: String): String = when (type) {
            "a" -> alpha(n).lowercase() + "."
            "A" -> alpha(n) + "."
            "i" -> roman(n).lowercase() + "."
            "I" -> roman(n) + "."
            else -> "$n."
        }

        private fun alpha(n0: Int): String {
            if (n0 <= 0) return n0.toString()
            var n = n0
            val sb = StringBuilder()
            while (n > 0) {
                n--
                sb.append('A' + n % 26)
                n /= 26
            }
            return sb.reverse().toString()
        }

        private fun roman(n0: Int): String {
            if (n0 <= 0 || n0 >= 4000) return n0.toString()
            val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val syms = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
            var n = n0
            val sb = StringBuilder()
            for (i in vals.indices) while (n >= vals[i]) {
                sb.append(syms[i])
                n -= vals[i]
            }
            return sb.toString()
        }

        /** Declaraciones CSS en linea en minusculas ("text-align" -> "center"). */
        fun parseStyle(style: String): Map<String, String> {
            val out = HashMap<String, String>()
            for (decl in style.split(';')) {
                val c = decl.indexOf(':')
                if (c <= 0) continue
                out[decl.substring(0, c).trim().lowercase()] =
                    decl.substring(c + 1).replace("!important", "").trim().lowercase()
            }
            return out
        }

        fun parseAlign(el: Element): BlockAlign? {
            val style = el.attr("style")
            val v = (if (style.isNotEmpty()) parseStyle(style)["text-align"] else null) ?: el.attr("align").lowercase()
            return when (v) {
                "left", "start" -> BlockAlign.START
                "right", "end" -> BlockAlign.END
                "center" -> BlockAlign.CENTER
                "justify" -> BlockAlign.JUSTIFY
                else -> null
            }
        }
    }
}
