package com.lectorvoz.core.text

import com.lectorvoz.core.model.ChapterDocument
import com.lectorvoz.core.model.ChapterText
import com.lectorvoz.core.model.FootnoteMode
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.LocatorRange
import com.lectorvoz.core.model.PauseKind
import com.lectorvoz.core.model.Utterance
import com.lectorvoz.core.model.UtteranceKind
import com.lectorvoz.core.model.VoiceRole
import java.util.BitSet

/**
 * Convierte el texto de un capitulo en unidades para la voz ([Utterance]).
 *
 * Pasos: estructura (parrafos tipados: titulos, listas, poesia, tablas, separadores, numeros
 * de pagina y notas), dialogos (narrador/personaje), frases (con cortes en puntos naturales),
 * idioma por frase (es/en), y texto hablado: sin llamadas de nota, con micropausa antes de
 * una cursiva aislada, con el marcador de lista leido, normalizado con el normalizador del
 * idioma de la frase y terminado siempre en puntuacion (coma en cortes internos, punto en el
 * resto). Supertonic no admite SSML: la prosodia se transmite solo con la puntuacion y con
 * `pauseAfter`.
 *
 * Los rangos de cada [Utterance] son posiciones del texto original del capitulo;
 * `spokenToDisplay` apunta a posiciones de `displayText`. Las unidades sinteticas
 * (CHAPTER_ANNOUNCE y las notas leidas al final del parrafo) tienen rango vacio y su propio
 * `displayText`.
 *
 * Es inmutable y sin estado: se puede usar desde varios hilos.
 *
 * @param normalizer normalizador del espanol (o del idioma principal).
 * @param englishNormalizer normalizador del ingles; nulo: [EnglishNormalizer].
 */
class UtteranceBuilder(
    private val normalizer: TextNormalizer = SpanishNormalizer(),
    private val dictionary: PronunciationDictionary = PronunciationDictionary.EMPTY,
    private val maxChars: Int = SentenceSegmenter.DEFAULT_MAX_CHARS,
    englishNormalizer: TextNormalizer? = null,
) {
    private val segmenter = SentenceSegmenter(maxChars)
    private val esNormalizer: TextNormalizer = if (LanguageTracker.normalizeTag(normalizer.language) == "en") SpanishNormalizer() else normalizer
    private val enNormalizer: TextNormalizer = englishNormalizer
        ?: if (LanguageTracker.normalizeTag(normalizer.language) == "en") normalizer else EnglishNormalizer()

    /**
     * Unidades de [chapter] (texto plano: PDF o texto sin estructura) desde [fromOffset] (si
     * cae a mitad de palabra, desde el inicio de esa palabra).
     *
     * Identificadores estables: `id = idBase + (inicio de la unidad shl 8) + ranura` (ranura 0
     * para el texto, 1 para el anuncio de capitulo y 2.. para las notas de un parrafo). No
     * dependen de [fromOffset]: la misma frase tiene el mismo id se construya desde donde se
     * construya (aunque empiece a mitad de ella), y dos frases distintas del mismo capitulo
     * nunca lo comparten. Con el [idBase] por defecto ([idBase] del capitulo) son unicos en
     * todo el libro.
     */
    fun build(
        chapter: ChapterText,
        fromOffset: Int = 0,
        idBase: Long = idBase(chapter.chapter),
        options: TextOptions = TextOptions(),
    ): List<Utterance> =
        run(ChapterStructure.fromPlain(chapter.text), chapter.chapter, chapter.title, chapter.language, fromOffset, idBase, options)

    /**
     * Unidades de un capitulo EPUB ya analizado: usa los tipos de bloque (titulos, listas,
     * tablas), las cursivas y los enlaces a notas. Identificadores como en la otra variante.
     */
    fun build(
        document: ChapterDocument,
        fromOffset: Int = 0,
        idBase: Long = idBase(document.index),
        options: TextOptions = TextOptions(),
    ): List<Utterance> =
        run(ChapterStructure.fromDocument(document, options.chapterPath), document.index, document.title, document.language, fromOffset, idBase, options)

    // ------------------------------------------------------------------ piezas

    /** Unidad aun sin texto hablado. [synthetic] es el texto de las unidades sinteticas. */
    private class Piece(
        val start: Int,
        val end: Int,
        val kind: UtteranceKind,
        val role: VoiceRole,
        var pause: PauseKind,
        val para: Para?,
        val marker: String? = null,
        val synthetic: String? = null,
        /** Ranura del id (0 texto, 1 anuncio, 2.. notas del parrafo). */
        val slot: Int = 0,
    ) {
        var language: String = "es"
    }

    private fun run(
        st: ChapterStructure,
        chapter: Int,
        title: String?,
        language: String?,
        fromOffset: Int,
        idBase: Long,
        options: TextOptions,
    ): List<Utterance> {
        val text = st.text
        val from = fromOffset.coerceIn(0, text.length)
        val masked: CharSequence = if (st.noteCalls.isEmpty()) text else MaskedText(text, st.noteCalls)
        val pieces = ArrayList<Piece>()
        val forced = LanguageTracker.forcedOf(options.readingLanguage)
        val hint = forced ?: LanguageTracker.normalizeTag(language) ?: "es"

        if (options.announceChapters && from == 0) announce(st, options, title, hint)?.let { pieces.add(it) }

        val paras = st.paras
        for ((pi, para) in paras.withIndex()) {
            when (para.type) {
                ParaType.SKIP -> continue
                ParaType.SEPARATOR -> {
                    bump(pieces, PauseKind.SECTION)
                    continue
                }
                ParaType.TABLE_ROW -> {
                    if (!options.readTables) {
                        bump(pieces, PauseKind.PARAGRAPH)
                        continue
                    }
                    if (para.headerRow) continue
                }
                ParaType.HEADING -> bump(pieces, PauseKind.SECTION)
                else -> {}
            }
            val first = pieces.size
            addParagraph(masked, para, paras.getOrNull(pi + 1), pieces)
            if (pieces.size > first && para.type == ParaType.LIST_ITEM) {
                val p0 = pieces[first]
                pieces[first] = Piece(p0.start, p0.end, p0.kind, p0.role, p0.pause, p0.para, para.marker ?: "", null, p0.slot)
            }
            if (options.footnotes == FootnoteMode.END_OF_PARAGRAPH && pieces.size > first) {
                addNotes(st, para, options, pieces)
            }
        }
        // Puntos suspensivos: pausa mayor que la de una frase.
        for (p in pieces) {
            if (p.pause == PauseKind.SENTENCE && p.synthetic == null && endsWithEllipsis(text, p.start, p.end)) p.pause = PauseKind.VERSE
        }
        if (pieces.isNotEmpty()) pieces.last().pause = PauseKind.CHAPTER

        // Idioma por frase (se calcula tambien antes de fromOffset para heredar bien).
        val tracker = LanguageTracker(hint, forced)
        for (p in pieces) {
            p.language = when {
                p.kind == UtteranceKind.CHAPTER_ANNOUNCE -> forced ?: tracker.base
                p.synthetic != null -> tracker.next(p.synthetic)
                else -> tracker.next(text, p.start, p.end)
            }
        }

        val out = ArrayList<Utterance>(pieces.size)
        for (p in pieces) {
            val id = idBase + (p.start.toLong() shl SLOT_BITS) + p.slot
            val u = if (p.synthetic != null) {
                if (p.kind != UtteranceKind.CHAPTER_ANNOUNCE && p.start <= from && from > 0) continue
                synthetic(p, chapter, id)
            } else {
                if (p.end <= from) continue
                var start = p.start
                if (from > start) {
                    start = from
                    while (start > p.start && !text[start - 1].isWhitespace()) start--
                    while (start < p.end && text[start].isWhitespace()) start++
                    if (!SentenceSegmenter.hasReadable(masked, start, p.end)) continue
                }
                utterance(st, p, start, chapter, id)
            }
            if (u == null) {
                // Sin nada que pronunciar: su pausa pasa a la unidad anterior.
                if (out.isNotEmpty() && out.last().pauseAfter < p.pause) out[out.size - 1] = out.last().copy(pauseAfter = p.pause)
                continue
            }
            out.add(u)
        }
        return out
    }

    /** Divide un parrafo en piezas: tramos de narrador/personaje y, dentro, frases. */
    private fun addParagraph(masked: CharSequence, para: Para, next: Para?, pieces: MutableList<Piece>) {
        val kind = when (para.type) {
            ParaType.HEADING -> UtteranceKind.HEADING
            ParaType.LIST_ITEM -> UtteranceKind.LIST_ITEM
            ParaType.TABLE_ROW -> UtteranceKind.TABLE
            ParaType.VERSE -> UtteranceKind.VERSE_LINE
            else -> UtteranceKind.SENTENCE
        }
        val roles = if (para.type == ParaType.TEXT || para.type == ParaType.LIST_ITEM) {
            Dialogs.split(masked, para.start, para.end)
        } else {
            listOf(RoleSpan(para.start, para.end, VoiceRole.NARRATOR))
        }
        val endPause = when (para.type) {
            ParaType.HEADING -> PauseKind.SECTION
            ParaType.TABLE_ROW -> if (next?.type == ParaType.TABLE_ROW) PauseKind.SENTENCE else PauseKind.PARAGRAPH
            ParaType.VERSE -> {
                val multiLine = masked.indexOfAny(charArrayOf('\n', '\r'), para.start).let { it in para.start until para.end }
                if (multiLine || para.stanzaEnd || next?.type != ParaType.VERSE) PauseKind.PARAGRAPH else PauseKind.VERSE
            }
            else -> PauseKind.PARAGRAPH
        }
        for ((ri, rs) in roles.withIndex()) {
            val spans = segmenter.segment(masked, rs.start, rs.end)
            for ((si, sp) in spans.withIndex()) {
                val lastOfRole = si == spans.lastIndex
                val lastOfPara = lastOfRole && ri == roles.lastIndex
                val pause = when {
                    sp.clause -> PauseKind.CLAUSE
                    lastOfPara -> endPause
                    lastOfRole -> roleChangePause(masked, sp, roles[ri + 1])
                    lineEnd(masked, sp.end, rs.end) -> if (para.type == ParaType.VERSE) PauseKind.VERSE else PauseKind.SENTENCE
                    else -> PauseKind.SENTENCE
                }
                pieces.add(Piece(sp.start, sp.end, kind, rs.role, pause, para))
            }
        }
    }

    /** Pausa al cambiar de papel: CLAUSE, o SENTENCE si se cierra frase y lo siguiente empieza otra. */
    private fun roleChangePause(text: CharSequence, sp: TextSpan, next: RoleSpan): PauseKind {
        var k = sp.end - 1
        while (k > sp.start && isCloser(text[k])) k--
        val endsSentence = text[k] == '.' || text[k] == '!' || text[k] == '?' || text[k] == '…'
        var q = next.start
        while (q < next.end && !text[q].isLetterOrDigit()) q++
        val nextUpper = q < next.end && text[q].isUpperCase()
        return if (endsSentence && nextUpper) PauseKind.SENTENCE else PauseKind.CLAUSE
    }

    private fun lineEnd(text: CharSequence, end: Int, limit: Int): Boolean {
        var k = end
        while (k < limit && (text[k] == ' ' || text[k] == '\t')) k++
        return k < limit && (text[k] == '\n' || text[k] == '\r')
    }

    private fun bump(pieces: MutableList<Piece>, pause: PauseKind) {
        val last = pieces.lastOrNull() ?: return
        if (last.pause < pause) last.pause = pause
    }

    /** Notas del parrafo leidas a continuacion (modo END_OF_PARAGRAPH). */
    private fun addNotes(st: ChapterStructure, para: Para, options: TextOptions, pieces: MutableList<Piece>) {
        val calls = st.noteCalls
        var k = lowerBound(calls, para.start)
        var added = false
        var slot = SLOT_FIRST_NOTE
        val endPause = pieces.last().pause
        while (k < calls.size && calls[k].start < para.end) {
            val href = calls[k].href
            k++
            if (href == null) continue
            val raw = options.footnoteTexts[href] ?: st.localNotes[href] ?: continue
            val note = cleanNote(raw)
            if (!SentenceSegmenter.hasReadable(note, 0, note.length)) continue
            if (!added) {
                pieces.last().pause = PauseKind.SENTENCE
                added = true
            }
            pieces.add(Piece(para.end, para.end, UtteranceKind.FOOTNOTE, VoiceRole.NARRATOR, PauseKind.SENTENCE, para, synthetic = note, slot = slot))
            slot = (slot + 1).coerceAtMost(SLOT_MAX)
        }
        if (added) pieces.last().pause = endPause
    }

    private fun announce(st: ChapterStructure, options: TextOptions, title0: String?, lang: String): Piece? {
        var title = (options.chapterTitle ?: title0)?.trim()?.takeIf { it.isNotEmpty() }
        val number = options.chapterNumber
        // Si el capitulo ya empieza con ese titulo, no se repite.
        val firstPara = st.paras.firstOrNull { it.type != ParaType.SKIP && it.type != ParaType.SEPARATOR }
        if (title != null && firstPara != null && firstPara.end - firstPara.start <= 120) {
            val h = simplify(st.text.substring(firstPara.start, firstPara.end))
            val t = simplify(title)
            if (t.isNotEmpty() && (h == t || h.endsWith(t))) title = null
        }
        val word = if (lang == "en") "Chapter" else "Capítulo"
        val titleHasWord = title != null && CHAPTER_WORD.containsMatchIn(title)
        val display = when {
            title != null && (number == null || titleHasWord) -> title
            title != null -> "$word $number. $title"
            number != null -> "$word $number"
            else -> return null
        }
        return Piece(0, 0, UtteranceKind.CHAPTER_ANNOUNCE, VoiceRole.NARRATOR, PauseKind.SECTION, null, synthetic = display, slot = SLOT_ANNOUNCE)
    }

    // ------------------------------------------------------------------ texto hablado

    private fun normalizerFor(lang: String): TextNormalizer = if (lang == "en") enNormalizer else esNormalizer

    private fun speak(display: String, lang: String): MappedText {
        val norm = normalizerFor(lang)
        return if (dictionary.isEmpty) {
            norm.normalize(display)
        } else {
            val d = dictionary.apply(display)
            norm.normalize(d.text).composeWith(d.toSource)
        }
    }

    private fun synthetic(p: Piece, chapter: Int, id: Long): Utterance? {
        val display = p.synthetic ?: return null
        var spoken = speak(display, p.language)
        if (p.kind == UtteranceKind.FOOTNOTE) spoken = prefix(spoken, if (p.language == "en") "Note." else "Nota.")
        spoken = finish(trimLeading(spoken), p.pause, display.length)
        if (spoken.text.isBlank()) return null
        val loc = Locator(chapter, p.start)
        return Utterance(
            id = id,
            range = LocatorRange(loc, loc),
            displayText = display,
            spokenText = spoken.text,
            spokenToDisplay = spoken.toSource,
            language = p.language,
            kind = p.kind,
            pauseAfter = p.pause,
            role = p.role,
        )
    }

    private fun utterance(st: ChapterStructure, p: Piece, start: Int, chapter: Int, id: Long): Utterance? {
        val text = st.text
        val end = p.end
        val display = text.substring(start, end)
        val pre = preText(st, p, start, end, display)
        var spoken = if (pre == null) {
            speak(display, p.language)
        } else {
            speak(pre.text, p.language).composeWith(pre.toSource)
        }
        val marker = p.marker
        if (marker != null && start == p.start) markerWords(marker, p.language)?.let { spoken = prefix(spoken, it) }
        spoken = finish(trimLeading(spoken), p.pause, display.length)
        if (!SentenceSegmenter.hasReadable(spoken.text, 0, spoken.text.length)) return null
        return Utterance(
            id = id,
            range = LocatorRange(Locator(chapter, start), Locator(chapter, end)),
            displayText = display,
            spokenText = spoken.text,
            spokenToDisplay = spoken.toSource,
            language = p.language,
            kind = p.kind,
            pauseAfter = p.pause,
            role = p.role,
        )
    }

    /**
     * Texto previo a la normalizacion: quita las llamadas de nota, inserta una coma antes de
     * una cursiva aislada y "cabecera: " antes de cada celda de una fila con cabeceras.
     * Null si no hay cambios.
     */
    private fun preText(st: ChapterStructure, p: Piece, a: Int, b: Int, display: String): MappedText? {
        var removed: BooleanArray? = null
        var inserts: MutableList<Pair<Int, String>>? = null
        val calls = st.noteCalls
        var k = lowerBound(calls, a)
        if (k > 0 && calls[k - 1].end > a) k--
        while (k < calls.size && calls[k].start < b) {
            val c = calls[k]
            val r = removed ?: BooleanArray(display.length).also { removed = it }
            for (i in maxOf(a, c.start) until minOf(b, c.end)) r[i - a] = true
            k++
        }
        // Micropausa antes de una cursiva aislada.
        val ital = st.italics
        if (ital.isNotEmpty()) {
            var j = lowerBoundPairs(ital, a)
            while (j < ital.size && ital[j] < b) {
                val s = ital[j]
                val e = minOf(ital[j + 1], b)
                j += 2
                if (s <= a || !isolatedItalic(st.text, s, e, a, b)) continue
                var w = s
                while (w > a && st.text[w - 1].isWhitespace()) w--
                if (w == s || !st.text[w - 1].isLetterOrDigit()) continue
                (inserts ?: ArrayList<Pair<Int, String>>().also { list -> inserts = list })
                    .add((w - a) to ",")
            }
        }
        val para = p.para
        val headers = para?.headers
        if (para != null && headers != null && para.type == ParaType.TABLE_ROW) {
            val cells = ChapterStructure.cells(st.text, para.start, para.end)
            for ((ci, cell) in cells.withIndex()) {
                var cs = cell.first
                while (cs < cell.second && st.text[cs].isWhitespace()) cs++
                if (cs < a || cs >= b || ci >= headers.size) continue
                val h = headers[ci]
                if (h.isEmpty() || !SentenceSegmenter.hasReadable(st.text, cs, cell.second)) continue
                (inserts ?: ArrayList<Pair<Int, String>>().also { list -> inserts = list }).add((cs - a) to "$h: ")
            }
        }
        if (removed == null && inserts == null) return null
        val ins = inserts?.sortedBy { it.first }.orEmpty()
        val sb = StringBuilder(display.length + 16)
        val map = IntArray(display.length + ins.sumOf { it.second.length } + 1)
        var n = 0
        var q = 0
        for (i in display.indices) {
            while (q < ins.size && ins[q].first == i) {
                for (ch in ins[q].second) {
                    sb.append(ch)
                    map[n++] = i
                }
                q++
            }
            if (removed?.get(i) == true) continue
            sb.append(display[i])
            map[n++] = i
        }
        if (n == 0) {
            // Todo eliminado: se deja un espacio para no perder el mapa.
            sb.append(' ')
            map[n++] = 0
        }
        return MappedText(sb.toString(), map.copyOf(n))
    }

    /** Cursiva de una palabra o expresion corta dentro de una frase, que no es un titulo. */
    private fun isolatedItalic(text: String, s: Int, e: Int, a: Int, b: Int): Boolean {
        if (e <= s) return false
        var words = 0
        var inWord = false
        for (i in s until e) {
            val l = text[i].isLetterOrDigit()
            if (l && !inWord) words++
            inWord = l
        }
        if (words == 0 || words > 4) return false
        // Frase entera (o casi) en cursiva: no.
        if ((e - s) * 2 > (b - a)) return false
        // Titulos de obras y nombres: empiezan por mayuscula.
        var f = s
        while (f < e && !text[f].isLetterOrDigit()) f++
        if (f < e && text[f].isUpperCase()) return false
        return true
    }

    companion object {
        private const val SLOT_BITS = 8
        private const val SLOT_ANNOUNCE = 1
        private const val SLOT_FIRST_NOTE = 2
        private const val SLOT_MAX = (1 shl SLOT_BITS) - 1

        /** Base de ids del capitulo [chapter]: deja 40 bits para posicion y ranura. */
        fun idBase(chapter: Int): Long = chapter.toLong() shl 40

        private val CHAPTER_WORD = Regex("^(?:cap[ií]tulo|chapter|parte|part|libro|book)\\b", RegexOption.IGNORE_CASE)
        private val NOTE_BACKREF = Regex("^\\s*(?:[\\[(]?\\d{1,4}[\\])]?[.:)]?|[*†‡]+|↑|\\^)\\s+")
        private const val CLOSERS = ")]}»”\"’'›"

        private fun isCloser(c: Char) = c in CLOSERS

        private fun simplify(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

        private fun endsWithEllipsis(text: String, s: Int, e: Int): Boolean {
            var k = e - 1
            while (k > s && isCloser(text[k])) k--
            return text[k] == '…' || (k >= s + 2 && text[k] == '.' && text[k - 1] == '.' && text[k - 2] == '.')
        }

        /** Texto de la nota sin la referencia de vuelta inicial ("1.", "[1]", "↑"). */
        internal fun cleanNote(raw: String): String = NOTE_BACKREF.replaceFirst(raw.trim(), "").trim()

        /** Marcador de lista a pronunciar: "1." -> "uno."; "a." -> "a."; viñetas -> null. */
        internal fun markerWords(marker: String, lang: String): String? {
            val m = marker.trim().trimEnd('.', ')')
            if (m.isEmpty() || m.none { it.isLetterOrDigit() }) return null
            val n = m.toLongOrNull()
                ?: if (m.length > 1 && SpanishNormalizer.isRoman(m.uppercase())) SpanishNormalizer.romanValue(m.uppercase()).toLong() else null
            val w = when {
                n != null && lang == "en" -> EnglishNormalizer.cardinal(n)
                n != null -> SpanishNumbers.cardinal(n)
                else -> m.lowercase()
            }
            return "$w."
        }

        /** Antepone [p] (mapeado al inicio) a [t]. */
        private fun prefix(t: MappedText, p: String): MappedText {
            val text = p + " " + t.text
            val map = IntArray(text.length)
            for (i in t.toSource.indices) map[p.length + 1 + i] = t.toSource[i]
            return MappedText(text, map)
        }

        /** Quita la puntuacion suelta del principio (", dijo" -> "dijo"). */
        private fun trimLeading(t: MappedText): MappedText {
            var k = 0
            val s = t.text
            while (k < s.length && (s[k] == ' ' || s[k] in ",.;:)]}…")) k++
            if (k == 0) return t
            return MappedText(s.substring(k), t.toSource.copyOfRange(k, s.length))
        }

        /**
         * Garantiza la puntuacion final: en cortes internos (CLAUSE) coma si no hay ninguna;
         * en el resto, punto (sustituye , ; : finales). Conserva ? ! y puntos suspensivos.
         */
        internal fun finish(t: MappedText, pause: PauseKind, displayLength: Int): MappedText {
            var s = t.text
            var map = t.toSource
            var end = s.length
            while (end > 0 && s[end - 1] == ' ') end--
            // "¡Fuego!»." -> "¡Fuego!": el punto tras ? o ! sobra.
            while (end > 1 && s[end - 1] == '.' && (s[end - 2] == '?' || s[end - 2] == '!')) end--
            if (end == 0) return t
            var k = end - 1
            while (k > 0 && (s[k] == ')' || s[k] == ']')) k--
            val last = s[k]
            val src = if (map.isNotEmpty()) map[end - 1] else 0
            // En cortes internos y finales de verso: coma (entonacion suspendida); si no, punto.
            val clause = pause == PauseKind.CLAUSE || pause == PauseKind.NONE || pause == PauseKind.VERSE
            return when {
                last == '?' || last == '!' || last == '.' || last == '…' -> if (end == s.length) t else MappedText(s.substring(0, end), map.copyOf(end))
                clause && (last == ',' || last == ';' || last == ':') -> if (end == s.length) t else MappedText(s.substring(0, end), map.copyOf(end))
                !clause && (last == ',' || last == ';' || last == ':') && k == end - 1 -> {
                    s = s.substring(0, k) + "."
                    MappedText(s, map.copyOf(end))
                }
                else -> {
                    s = s.substring(0, end) + if (clause) "," else "."
                    map = map.copyOf(end + 1)
                    map[end] = src.coerceAtMost(maxOf(0, displayLength - 1))
                    MappedText(s, map)
                }
            }
        }

        private fun lowerBound(calls: List<NoteCall>, pos: Int): Int {
            var lo = 0
            var hi = calls.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (calls[mid].start < pos) lo = mid + 1 else hi = mid
            }
            return lo
        }

        /** Indice (par) del primer rango [s, e) de [pairs] (ordenados por inicio) con s >= pos. */
        private fun lowerBoundPairs(pairs: IntArray, pos: Int): Int {
            var lo = 0
            var hi = pairs.size / 2
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (pairs[mid * 2] < pos) lo = mid + 1 else hi = mid
            }
            return lo * 2
        }
    }

    /** Vista del texto con las llamadas de nota como ')' para que no estorben al segmentar. */
    private class MaskedText(private val text: String, calls: List<NoteCall>) : CharSequence {
        private val mask = BitSet(text.length).also { m -> for (c in calls) m.set(c.start, c.end) }
        override val length: Int get() = text.length
        override fun get(index: Int): Char = if (mask.get(index)) ')' else text[index]
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            StringBuilder(endIndex - startIndex).also { sb -> for (i in startIndex until endIndex) sb.append(get(i)) }
        override fun toString(): String = subSequence(0, length).toString()
    }
}
