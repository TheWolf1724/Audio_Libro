package com.lectorvoz.core.text

import com.lectorvoz.core.model.Block
import com.lectorvoz.core.model.BlockKind
import com.lectorvoz.core.model.ChapterDocument
import com.lectorvoz.core.model.InlineStyle
import com.lectorvoz.core.model.NoteCallLabel

/** Tipo de parrafo para la voz. */
internal enum class ParaType { TEXT, HEADING, LIST_ITEM, TABLE_ROW, VERSE, PREFORMATTED, SEPARATOR, SKIP }

/** Parrafo [start, end) del texto del capitulo con su tipo. */
internal class Para(val start: Int, val end: Int, var type: ParaType, val marker: String? = null) {
    /** Cabeceras de columna para leer "cabecera: celda" (filas de datos de una tabla). */
    var headers: List<String>? = null

    /** Fila de cabecera ya leida con cada fila de datos: no se lee sola. */
    var headerRow = false

    /** Le sigue una linea en blanco (fin de estrofa en poesia). */
    var stanzaEnd = false
}

/** Llamada de nota [start, end) (no se pronuncia) con el enlace a la nota si se conoce. */
internal class NoteCall(val start: Int, val end: Int, val href: String?)

/**
 * Estructura de un capitulo para la voz: parrafos tipados, llamadas de nota, cursivas y
 * textos de notas resueltos dentro del propio capitulo. Se obtiene del [ChapterDocument]
 * (EPUB: tipos de bloque y estilos) o, si solo hay texto plano, por heuristicas de lineas.
 */
internal class ChapterStructure(
    val text: String,
    val paras: List<Para>,
    val noteCalls: List<NoteCall>,
    /** Rangos en cursiva, como pares [inicio, fin) ordenados. */
    val italics: IntArray,
    val localNotes: Map<String, String>,
) {
    companion object {
        private const val CELL_SEPARATOR = " | "
        private const val MAX_VERSE_LINE = 60
        private val SUPERSCRIPT_DIGITS = "⁰¹²³⁴⁵⁶⁷⁸⁹"
        private val UNIT_WORDS = hashSetOf("km", "cm", "mm", "dm", "in", "ft")
        private const val NUM_WORDS = "uno|una|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|once|doce|" +
            "primero|primera|segundo|segunda|tercero|tercera|cuarto|cuarta|quinto|quinta|sexto|sexta|" +
            "s[eé]ptimo|s[eé]ptima|octavo|octava|noveno|novena|d[eé]cimo|d[eé]cima|[uú]ltimo|[uú]ltima|" +
            "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|first|second|third|last"
        private val HEADING_WORDS = Regex(
            "^(?:cap[ií]tulo|chapter|parte|part|libro|book|acto|act|escena|scene|canto|tomo|volumen|volume|lecci[oó]n|lesson)" +
                "\\s+(?:\\d{1,3}|[IVXLCDM]{1,7}|$NUM_WORDS)\\b\\.?(?:\\s*[:.\\-—–]?\\s+.*)?$|" +
                "^(?:pr[oó]logo|ep[ií]logo|prologue|epilogue|introducci[oó]n|introduction|ap[eé]ndice|appendix|" +
                "pr[eé]facio|preface|nota del autor|agradecimientos|acknowledgements|[ií]ndice|contents)\\b\\.?(?:\\s*[:.\\-—–]\\s*.*)?$",
            RegexOption.IGNORE_CASE,
        )
        private val ROMAN_HEADING = Regex("^[IVXLCDM]{1,7}\\.?$")
        private val PAGE_NUMBER = Regex(
            "^(?:(?i:p[aá]g(?:ina)?\\.?|page|p\\.)\\s*)?[-–—\\[(]?\\s*(?:\\d{1,4}|[ivxlc]{1,7})\\s*[-–—\\])]?(?:\\s*(?:de|of|/)\\s*\\d{1,4})?$",
        )
        private val LIST_LINE = Regex("^(?:\\d{1,3}[.)]|[a-z][.)]|[A-Z]\\)|[•◦▪‣∙·*])\\s+\\S.*")

        /** Estructura a partir de texto plano (PDF o texto sin bloques). */
        fun fromPlain(text: String): ChapterStructure {
            val paras = ArrayList<Para>()
            var s = 0
            val n = text.length
            for (i in 0..n) {
                if (i == n || text[i] == '\n' || text[i] == '\r') {
                    val before = paras.size
                    addLine(text, s, i, paras, null)
                    if (paras.size == before && paras.isNotEmpty() && isBlank(text, s, i)) paras.last().stanzaEnd = true
                    s = i + 1
                }
            }
            markVerseRuns(text, paras)
            val calls = scanNoteCalls(text, paras, emptyList())
            return ChapterStructure(text, paras, calls, IntArray(0), emptyMap())
        }

        /**
         * Estructura a partir del capitulo EPUB analizado. [chapterPath] es la ruta del
         * capitulo en el contenedor (como en `InlineStyle.Link`): las notas se resuelven en el
         * propio capitulo solo si el enlace apunta a esa ruta. Sin ruta, solo si el destino
         * parece una nota (empieza por la llamada o tiene un enlace de vuelta).
         */
        fun fromDocument(doc: ChapterDocument, chapterPath: String? = null): ChapterStructure {
            val text = doc.plainText
            val paras = ArrayList<Para>()
            val links = ArrayList<NoteCall>()
            val italics = ArrayList<Int>()
            val textBlocks = ArrayList<Block.Text>()
            val linkStarts = ArrayList<Int>()
            for (b in doc.blocks) {
                when (b) {
                    is Block.Text -> {
                        textBlocks.add(b)
                        val s = b.start.coerceIn(0, text.length)
                        val e = b.end.coerceIn(s, text.length)
                        when (val k = b.kind) {
                            is BlockKind.Heading -> trimmed(text, s, e)?.let { paras.add(Para(it.first, it.second, ParaType.HEADING)) }
                            is BlockKind.ListItem -> trimmed(text, s, e)?.let {
                                paras.add(Para(it.first, it.second, ParaType.LIST_ITEM, k.marker))
                            }
                            is BlockKind.Preformatted -> trimmed(text, s, e)?.let { paras.add(Para(it.first, it.second, ParaType.PREFORMATTED)) }
                            else -> addLine(text, s, e, paras, b)
                        }
                        collectSpans(text, b, links, italics)
                        for (sp in b.spans) if (sp.style is InlineStyle.Link) linkStarts.add(sp.start)
                    }
                    is Block.Rule -> paras.add(Para(b.start, b.start, ParaType.SEPARATOR))
                    is Block.Image -> {}
                }
            }
            markVerseRuns(text, paras)
            markTableHeaders(text, paras, textBlocks)
            val calls = scanNoteCalls(text, paras, links)
            val local = HashMap<String, String>()
            for (c in calls) {
                val href = c.href ?: continue
                val frag = href.substringAfter('#', "")
                if (frag.isEmpty()) continue
                if (chapterPath != null && href.substringBefore('#') != chapterPath) continue
                val off = doc.anchors[frag] ?: continue
                if (off < c.end) continue
                val para = paras.firstOrNull { it.end > off && it.type != ParaType.SEPARATOR } ?: continue
                // Un titulo nunca es cuerpo de nota (indices y referencias cruzadas).
                if (para.type == ParaType.HEADING || para.type == ParaType.SKIP) continue
                val start = maxOf(off, para.start)
                if (chapterPath == null && !looksLikeNote(text, start, para.end, text.substring(c.start, c.end), linkStarts)) continue
                para.type = ParaType.SKIP // cuerpo de la nota: no se lee en su sitio
                local[href] = text.substring(start, para.end).trim()
            }
            return ChapterStructure(text, paras, calls, italics.toIntArray(), local)
        }

        /**
         * true si [start, end) parece el cuerpo de una nota: empieza por la llamada [label]
         * ("3", "[3]") o contiene un enlace (el de vuelta a la llamada).
         */
        internal fun looksLikeNote(text: String, start: Int, end: Int, label: String, linkStarts: List<Int>): Boolean {
            if (linkStarts.any { it in start until end }) return true
            val core = label.trim().trim('[', ']', '(', ')', '.').trim()
            if (core.isEmpty()) return false
            var k = start
            while (k < end && (text[k].isWhitespace() || text[k] == '[' || text[k] == '(')) k++
            if (!text.startsWith(core, k)) return false
            val after = k + core.length
            return after < end && !text[after].isLetterOrDigit()
        }

        private fun trimmed(text: String, s0: Int, e0: Int): Pair<Int, Int>? {
            var s = s0
            var e = e0
            while (s < e && text[s].isWhitespace()) s++
            while (e > s && text[e - 1].isWhitespace()) e--
            if (!SentenceSegmenter.hasReadable(text, s, e) && !isDecorative(text, s, e)) return null
            return s to e
        }

        /** Clasifica una linea o bloque de parrafo y lo anade. */
        private fun addLine(text: String, s0: Int, e0: Int, paras: MutableList<Para>, block: Block.Text?) {
            var s = s0
            var e = e0
            while (s < e && (text[s].isWhitespace() || text[s] == SentenceSegmenter.OBJECT_CHAR)) s++
            while (e > s && (text[e - 1].isWhitespace() || text[e - 1] == SentenceSegmenter.OBJECT_CHAR)) e--
            if (s >= e) return
            if (!SentenceSegmenter.hasReadable(text, s, e)) {
                if (isDecorative(text, s, e)) paras.add(Para(s, e, ParaType.SEPARATOR))
                return
            }
            val line = text.substring(s, e)
            val type = when {
                CELL_SEPARATOR in line -> ParaType.TABLE_ROW
                '\n' !in line && isPageNumber(line) -> ParaType.SKIP
                line.length <= 80 && '\n' !in line && (HEADING_WORDS.matches(line) && !line.trimEnd().endsWith(",")) -> ParaType.HEADING
                ROMAN_HEADING.matches(line) -> ParaType.HEADING
                line.length <= 60 && isAllCaps(line) -> ParaType.HEADING
                LIST_LINE.matches(line) && '\n' !in line -> ParaType.LIST_ITEM
                block != null && '\n' in line && isVerseBlock(line) -> ParaType.VERSE
                else -> ParaType.TEXT
            }
            paras.add(Para(s, e, type))
        }

        /** Numero de pagina suelto ("12", "- 12 -", "Pagina 12 de 300", "xii"). */
        internal fun isPageNumber(line: String): Boolean {
            if (!PAGE_NUMBER.matches(line)) return false
            val letters = line.filter { it in "ivxlc" }
            // "xii" si; "civil" no (no es un romano valido).
            return letters.isEmpty() || line.any { isAsciiDigit(it) } || SpanishNormalizer.isRoman(letters.uppercase())
        }

        private fun isBlank(text: String, s: Int, e: Int): Boolean {
            for (k in s until e) if (!text[k].isWhitespace()) return false
            return true
        }

        private fun isAllCaps(line: String): Boolean {
            var letters = 0
            for (c in line) {
                if (c.isLetter()) {
                    if (c.isLowerCase()) return false
                    letters++
                }
            }
            return letters >= 4 && line.trimEnd().lastOrNull() !in setOf(',', ';')
        }

        private fun isDecorative(text: String, s: Int, e: Int): Boolean {
            var any = false
            for (k in s until e) {
                val c = text[k]
                if (c.isWhitespace()) continue
                if (c.isLetterOrDigit()) return false
                any = true
            }
            return any
        }

        private fun isVerseBlock(line: String): Boolean {
            val lines = line.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size < 2) return false
            return lines.all { it.length <= MAX_VERSE_LINE + 15 } && lines.sumOf { it.length } / lines.size <= MAX_VERSE_LINE
        }

        /** Series de 3 o mas lineas cortas sin puntuacion final en la mayoria: poesia. */
        private fun markVerseRuns(text: String, paras: List<Para>) {
            var i = 0
            while (i < paras.size) {
                if (!isVerseCandidate(text, paras[i])) {
                    i++
                    continue
                }
                var j = i
                while (j < paras.size && isVerseCandidate(text, paras[j])) j++
                val run = j - i
                if (run >= 3) {
                    var open = 0
                    for (k in i until j) {
                        val last = text[paras[k].end - 1]
                        if (last !in ".!?…»\"”)") open++
                    }
                    if (open * 2 >= run) for (k in i until j) paras[k].type = ParaType.VERSE
                }
                i = j
            }
        }

        private fun isVerseCandidate(text: String, p: Para): Boolean {
            if (p.type != ParaType.TEXT) return false
            val len = p.end - p.start
            if (len > MAX_VERSE_LINE || len < 3) return false
            val c = text[p.start]
            return c != '—' && c != '–' && c != '-' && c != '―'
        }

        private fun collectSpans(text: String, b: Block.Text, links: MutableList<NoteCall>, italics: MutableList<Int>) {
            val sups = ArrayList<Pair<Int, Int>>()
            for (sp in b.spans) {
                val s = sp.start.coerceIn(0, text.length)
                val e = sp.end.coerceIn(s, text.length)
                when (val st = sp.style) {
                    is InlineStyle.Italic -> {
                        italics.add(s)
                        italics.add(e)
                    }
                    is InlineStyle.Link -> {
                        val href = st.href
                        if ('#' in href && !href.contains("://") && isNoteCallLabel(text.substring(s, e))) {
                            links.add(NoteCall(s, e, href))
                        }
                    }
                    is InlineStyle.Superscript -> sups.add(s to e)
                    else -> {}
                }
            }
            for ((s, e) in sups) {
                if (links.any { it.start <= s && it.end >= e }) continue
                val label = text.substring(s, e)
                if (isNoteCallLabel(label) && glued(text, s, b.start)) links.add(NoteCall(s, e, null))
            }
        }

        internal fun isNoteCallLabel(label: String): Boolean = NoteCallLabel.matches(label)

        /** true si la llamada en [k] va pegada a una palabra (de 2+ letras, no unidad) o a puntuacion. */
        private fun glued(text: String, k: Int, min: Int): Boolean {
            if (k <= min) return false
            val p = text[k - 1]
            if (p in ".,;:!?»”\")’") return true
            if (!p.isLetter()) return false
            var b = k - 1
            while (b > min && text[b - 1].isLetter()) b--
            val w = text.substring(b, k)
            return w.length >= 2 && w.lowercase() !in UNIT_WORDS
        }

        /** Llamadas de nota: enlaces/superindices (EPUB) y patrones del texto. */
        private fun scanNoteCalls(text: String, paras: List<Para>, fromSpans: List<NoteCall>): List<NoteCall> {
            val out = ArrayList<NoteCall>(fromSpans)
            for (p in paras) {
                if (p.type == ParaType.SKIP || p.type == ParaType.SEPARATOR || p.type == ParaType.PREFORMATTED) continue
                var k = p.start
                while (k < p.end) {
                    val c = text[k]
                    if (c in SUPERSCRIPT_DIGITS) {
                        var e = k
                        while (e < p.end && text[e] in SUPERSCRIPT_DIGITS) e++
                        if (glued(text, k, p.start)) out.add(NoteCall(k, e, null))
                        k = e
                        continue
                    }
                    if ((c == '[' || c == '(') && k > p.start) {
                        val close = if (c == '[') ']' else ')'
                        var e = k + 1
                        while (e < p.end && isAsciiDigit(text[e]) && e - k <= 3) e++
                        if (e > k + 1 && e < p.end && text[e] == close) {
                            val ok = if (c == '[') true else glued(text, k, p.start)
                            if (ok) out.add(NoteCall(k, e + 1, null))
                            k = e + 1
                            continue
                        }
                    }
                    k++
                }
            }
            out.sortBy { it.start }
            // Sin solapes.
            val clean = ArrayList<NoteCall>(out.size)
            for (c in out) if (clean.isEmpty() || c.start >= clean.last().end) clean.add(c)
            return clean
        }

        /** Cabeceras de tabla: primera fila con todas las celdas en negrita (th). */
        private fun markTableHeaders(text: String, paras: List<Para>, blocks: List<Block.Text>) {
            var i = 0
            while (i < paras.size) {
                if (paras[i].type != ParaType.TABLE_ROW) {
                    i++
                    continue
                }
                var j = i
                while (j < paras.size && paras[j].type == ParaType.TABLE_ROW) j++
                val first = paras[i]
                val cells = cells(text, first.start, first.end)
                val block = blocks.firstOrNull { it.start <= first.start && it.end >= first.end }
                val bold = block?.spans?.filter { it.style == InlineStyle.Bold }.orEmpty()
                val isHeader = j - i >= 2 && cells.size >= 2 && cells.all { (s, e) -> bold.any { it.start <= s && it.end >= e } }
                if (isHeader) {
                    val names = cells.map { (s, e) -> text.substring(s, e).trim() }
                    var used = false
                    for (k in i + 1 until j) {
                        if (cells(text, paras[k].start, paras[k].end).size == names.size) {
                            paras[k].headers = names
                            used = true
                        }
                    }
                    first.headerRow = used
                }
                i = j
            }
        }

        /** Celdas [inicio, fin) de una fila separada por " | ". */
        internal fun cells(text: String, s: Int, e: Int): List<Pair<Int, Int>> {
            val out = ArrayList<Pair<Int, Int>>()
            var a = s
            while (true) {
                val k = text.indexOf(CELL_SEPARATOR, a)
                if (k < 0 || k >= e) {
                    out.add(a to e)
                    break
                }
                out.add(a to k)
                a = k + CELL_SEPARATOR.length
            }
            return out
        }
    }
}
