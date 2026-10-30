package com.lectorvoz.core.text

/**
 * Rango semiabierto [start, end) dentro de un texto. [clause] es true si el rango termina en
 * un corte interno de una frase larga (no en fin de frase): la pausa debe ser corta.
 */
data class TextSpan(val start: Int, val end: Int, val clause: Boolean = false) {
    val length: Int get() = end - start
}

/**
 * Segmentador de frases para espanol (razonable para ingles).
 *
 * Reglas: los saltos de linea y el caracter de objeto (imagenes) son cortes duros; un
 * terminador (. ! ? ...) corta si va seguido de espacio y de algo que puede empezar frase
 * (mayuscula, digito, signo de apertura, comilla, raya seguida de mayuscula). No corta tras
 * abreviaturas, iniciales ni dentro de numeros, URL o correos (no hay espacio tras el punto).
 * Una llamada de nota pegada al terminador ("fin.¹ Otra", "fin.[3] Otra") no impide el corte.
 *
 * Las frases mas largas que [maxChars] se parten en puntos naturales, por orden de
 * preferencia: punto y coma o dos puntos; coma seguida de conjuncion o relativo ("y", "que",
 * "pero", "which"...); coma; parentesis o raya; espacio antes de conjuncion; espacio. Nunca
 * se corta dentro de un numero ("7 000"), entre las palabras de un nombre propio ni entre los
 * dos `char` de un par sustituto (emoji).
 */
class SentenceSegmenter(
    val maxChars: Int = DEFAULT_MAX_CHARS,
) {
    init {
        require(maxChars >= 20) { "maxChars demasiado pequeno: $maxChars" }
    }

    private val minClause = (maxChars / 4).coerceAtLeast(10)

    /** Devuelve las frases de [text] en [from, to), recortadas y sin frases vacias. */
    fun segment(text: CharSequence, from: Int = 0, to: Int = text.length): List<TextSpan> {
        val out = ArrayList<TextSpan>()
        var bs = from
        var i = from
        while (i <= to) {
            if (i == to || isHardBreak(text[i])) {
                if (i > bs) segmentBlock(text, bs, i, out)
                bs = i + 1
            }
            i++
        }
        return out
    }

    private fun segmentBlock(text: CharSequence, bs: Int, be: Int, out: MutableList<TextSpan>) {
        var start = bs
        var i = bs
        while (i < be) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?' || c == '…' || c == '‼' || c == '⁇') {
                var j = i + 1
                while (j < be && isTerminator(text[j])) j++
                val runLen = j - i
                while (j < be && isCloser(text[j])) j++
                val afterNote = skipNoteCall(text, j, be)
                if (afterNote > j) {
                    j = afterNote
                    while (j < be && isCloser(text[j])) j++
                }
                if (j >= be) {
                    i = j
                    continue
                }
                if (!text[j].isWhitespace()) {
                    i = j
                    continue
                }
                var k = j
                while (k < be && text[k].isWhitespace()) k++
                if (k >= be) {
                    i = k
                    continue
                }
                if (isBoundary(text, bs, i, runLen, c, k, be)) {
                    emit(text, start, j, out)
                    start = k
                }
                i = k
                continue
            }
            i++
        }
        emit(text, start, be, out)
    }

    private fun isBoundary(
        text: CharSequence,
        bs: Int,
        termPos: Int,
        runLen: Int,
        term: Char,
        next: Int,
        be: Int,
    ): Boolean {
        if (!canStartSentence(text, next, be)) return false
        if (term != '.' || runLen != 1) return true
        if (isListMarker(text, bs, termPos)) return false
        val nextUpper = text[next].isUpperCase() || !text[next].isLetter()
        // Abreviaturas de varias piezas: "a. C.", "EE. UU."...
        for (abbr in MULTI_ABBREVIATIONS) {
            val s = termPos + 1 - abbr.length
            if (s >= bs && regionEquals(text, s, abbr) && (s == bs || !text[s - 1].isLetterOrDigit())) {
                return text[next].isUpperCase() && abbr in ENDING_MULTI
            }
        }
        var ws = termPos
        while (ws > bs && text[ws - 1].isLetter()) ws--
        val wlen = termPos - ws
        if (wlen == 0) return true
        if (wlen == 1) return false // inicial o letra suelta
        val word = text.subSequence(ws, termPos).toString()
        val lower = word.lowercase()
        if (lower in TITLE_ABBREVIATIONS) return false
        if (lower in END_ABBREVIATIONS) return nextUpper && text[next].isLetter()
        if (wlen == 2 && word[0] == word[1] && word[0].isUpperCase()) return false // EE., UU., FF.
        return true
    }

    /** true si [bs, termPos) es solo un marcador de lista al inicio del bloque ("1", "a", "iv"). */
    private fun isListMarker(text: CharSequence, bs: Int, termPos: Int): Boolean {
        var s = bs
        while (s < termPos && text[s].isWhitespace()) s++
        val len = termPos - s
        if (len <= 0 || len > 4) return false
        var digits = true
        var letters = true
        for (k in s until termPos) {
            if (!isAsciiDigit(text[k])) digits = false
            if (!text[k].isLetter()) letters = false
        }
        if (digits) return len <= 3
        if (!letters) return false
        return len == 1 || SpanishNormalizer.isRoman(text.subSequence(s, termPos).toString().uppercase())
    }

    /** true si en [k] puede empezar una frase nueva. */
    private fun canStartSentence(text: CharSequence, k: Int, be: Int): Boolean {
        var p = k
        // Signos iniciales: raya, comillas, apertura; mira la primera letra tras ellos.
        while (p < be && (isOpener(text[p]) || text[p] == ' ')) p++
        if (p >= be) return true
        val c = text[p]
        if (c.isLetter()) return !c.isLowerCase()
        return true
    }

    private fun emit(text: CharSequence, s0: Int, e0: Int, out: MutableList<TextSpan>) {
        var s = s0
        var e = e0
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        if (!hasReadable(text, s, e)) return
        if (e - s <= maxChars) {
            out.add(TextSpan(s, e))
        } else {
            splitLong(text, s, e, out)
        }
    }

    private fun splitLong(text: CharSequence, s0: Int, e: Int, out: MutableList<TextSpan>) {
        var s = s0
        while (e - s > maxChars) {
            val limit = s + maxChars
            var best = -1
            var bestRank = 0
            var p = limit
            while (p > s + minClause) {
                // corte despues de text[p-1]
                val rank = cutRank(text, s, p, e)
                if (rank > bestRank) {
                    bestRank = rank
                    best = p
                    if (rank >= RANK_SEMICOLON) break
                }
                p--
            }
            if (best < 0) best = forcedCut(text, s, limit)
            var cut = best
            while (cut > s && text[cut - 1].isWhitespace()) cut--
            if (hasReadable(text, s, cut)) out.add(TextSpan(s, cut, clause = true))
            s = best
            while (s < e && text[s].isWhitespace()) s++
        }
        if (s < e && hasReadable(text, s, e)) out.add(TextSpan(s, e))
    }

    /** Calidad de cortar entre text[p-1] y text[p] (0 = no se puede). */
    private fun cutRank(text: CharSequence, s: Int, p: Int, e: Int): Int {
        if (p >= e || !text[p].isWhitespace()) return 0
        val prev = text[p - 1]
        var q = p
        while (q < e && text[q].isWhitespace()) q++
        if (q >= e) return 0
        val next = text[q]
        return when {
            prev == ';' || prev == ':' -> RANK_SEMICOLON
            prev == ',' -> if (startsWithWord(text, q, e, CONJUNCTIONS)) RANK_COMMA_CONJ else RANK_COMMA
            prev == ')' || prev == '—' || prev == '–' || prev == '|' || next == '(' || next == '—' -> RANK_PAREN
            prev.isWhitespace() -> 0
            isAsciiDigit(prev) && isAsciiDigit(next) -> 0 // "7 000", "1 2"
            prev == '.' && wordLengthBefore(text, s, p - 1) <= 3 -> 0 // "Sr. García", "J. R."
            inName(text, s, p, q, e) -> 0
            startsWithWord(text, q, e, CONJUNCTIONS) -> RANK_BEFORE_CONJ
            else -> RANK_SPACE
        }
    }

    /** Corte forzado sin espacios: no parte pares sustitutos ni series de digitos. */
    private fun forcedCut(text: CharSequence, s: Int, limit: Int): Int {
        var c = limit
        if (c > s + 1 && Character.isLowSurrogate(text[c]) && Character.isHighSurrogate(text[c - 1])) c--
        var d = c
        while (d > s + 1 && isAsciiDigit(text[d - 1]) && isAsciiDigit(text[d])) d--
        if (d > s + minClause) c = d
        return c
    }

    /** true si el espacio en p separa dos piezas de un nombre propio ("Rodrigo de Triana"). */
    private fun inName(text: CharSequence, s: Int, p: Int, q: Int, e: Int): Boolean {
        if (!text[q].isUpperCase()) return false
        // palabra anterior
        var ws = p
        while (ws > s && text[ws - 1].isLetter()) ws--
        if (ws == p) return false
        if (text[ws].isUpperCase()) return true
        val w = text.subSequence(ws, p).toString()
        if (w in NAME_PARTICLES) {
            var k = ws - 1
            while (k > s && text[k].isWhitespace()) k--
            return k >= s && text[k].isLetter() && run {
                var b = k
                while (b > s && text[b - 1].isLetter()) b--
                text[b].isUpperCase()
            }
        }
        return false
    }

    private fun wordLengthBefore(text: CharSequence, s: Int, dot: Int): Int {
        var b = dot
        while (b > s && text[b - 1].isLetter()) b--
        return if (b == dot) 99 else dot - b
    }

    private fun startsWithWord(text: CharSequence, q: Int, e: Int, words: Set<String>): Boolean {
        var k = q
        while (k < e && text[k].isLetter() && k - q < 12) k++
        if (k == q || (k < e && text[k].isLetter())) return false
        return text.subSequence(q, k).toString().lowercase() in words
    }

    companion object {
        const val DEFAULT_MAX_CHARS = 300

        private const val RANK_SEMICOLON = 50
        private const val RANK_COMMA_CONJ = 45
        private const val RANK_COMMA = 35
        private const val RANK_PAREN = 25
        private const val RANK_BEFORE_CONJ = 15
        private const val RANK_SPACE = 10

        /** Conjunciones y relativos que suelen abrir una clausula (es y en). */
        internal val CONJUNCTIONS = hashSetOf(
            "y", "e", "o", "u", "ni", "pero", "mas", "sino", "aunque", "porque", "pues", "que",
            "cuando", "donde", "mientras", "como", "si", "quien", "quienes", "cual", "cuales",
            "cuyo", "cuya", "cuyos", "cuyas", "luego", "entonces", "sin", "para",
            "and", "but", "or", "nor", "yet", "so", "because", "although", "though", "while",
            "which", "who", "whom", "whose", "that", "where", "when", "if", "unless", "since",
        )

        private val NAME_PARTICLES = hashSetOf("de", "del", "la", "las", "los", "y", "van", "von", "da", "di", "le", "du", "of", "the")

        private val SUPERSCRIPT_DIGITS = "⁰¹²³⁴⁵⁶⁷⁸⁹"

        /**
         * Si en [j] empieza una llamada de nota (superindices "¹²", "[12]"), devuelve su fin;
         * si no, [j].
         */
        internal fun skipNoteCall(text: CharSequence, j: Int, be: Int): Int {
            var k = j
            while (k < be && text[k] in SUPERSCRIPT_DIGITS) k++
            if (k > j) return k
            if (j < be && text[j] == '[') {
                k = j + 1
                while (k < be && isAsciiDigit(text[k]) && k - j <= 3) k++
                if (k > j + 1 && k < be && text[k] == ']') return k + 1
            }
            return j
        }

        private val TITLE_ABBREVIATIONS = hashSetOf(
            "sr", "sra", "srta", "sres", "sras", "dr", "dra", "dres", "dña", "dª", "don",
            "sto", "sta", "mr", "mrs", "ms", "prof", "profa", "lic", "ing", "arq", "gral",
            "cap", "caps", "vol", "vols", "pág", "págs", "pag", "pags", "núm", "núms", "num",
            "art", "arts", "fig", "figs", "tel", "telf", "avda", "av", "ud", "uds", "vd", "vds",
            "st", "vs", "ej", "aprox", "dpto", "ed", "op", "cit", "ibíd", "ibid", "lám",
            "col", "cf", "cfr", "pp", "ntra", "ntro", "mons",
            "excmo", "excma", "ilmo", "ilma", "rvdo", "pdte", "admón", "tít", "cía",
        )
        private val END_ABBREVIATIONS = hashSetOf("etc", "etcétera", "ss", "sig", "sigs", "jr")
        private val MULTI_ABBREVIATIONS = listOf(
            "EE. UU.", "EE.UU.", "a. C.", "d. C.", "a.C.", "d.C.", "a. m.", "p. m.", "a.m.",
            "p.m.", "p. ej.", "p.ej.", "S. A.", "S.A.", "S. L.", "S.L.",
        )
        private val ENDING_MULTI = hashSetOf(
            "EE. UU.", "EE.UU.", "a. C.", "d. C.", "a.C.", "d.C.", "a. m.", "p. m.", "a.m.",
            "p.m.", "S. A.", "S.A.", "S. L.", "S.L.",
        )

        internal fun isHardBreak(c: Char): Boolean =
            c == '\n' || c == '\r' || c == ' ' || c == ' ' || c == OBJECT_CHAR

        private fun isTerminator(c: Char) =
            c == '.' || c == '!' || c == '?' || c == '…' || c == '‼' || c == '⁇'

        private fun isCloser(c: Char) =
            c == '»' || c == '”' || c == '"' || c == '’' || c == '\'' || c == ')' ||
                c == ']' || c == '}' || c == '›'

        private fun isOpener(c: Char) =
            c == '—' || c == '–' || c == '-' || c == '«' || c == '“' || c == '"' || c == '‘' ||
                c == '\'' || c == '(' || c == '[' || c == '¿' || c == '¡' || c == '‹'

        /** Caracter de objeto (imagen) de `ChapterDocument.OBJECT_CHAR`. */
        internal const val OBJECT_CHAR = '￼'

        /** true si hay alguna letra o digito en [s, e). */
        internal fun hasReadable(text: CharSequence, s: Int, e: Int): Boolean {
            for (i in s until e) if (text[i].isLetterOrDigit()) return true
            return false
        }

        private fun regionEquals(text: CharSequence, start: Int, s: String): Boolean {
            if (start < 0 || start + s.length > text.length) return false
            for (i in s.indices) if (text[start + i] != s[i]) return false
            return true
        }
    }
}
