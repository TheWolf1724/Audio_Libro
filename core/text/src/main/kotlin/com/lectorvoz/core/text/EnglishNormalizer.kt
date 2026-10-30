package com.lectorvoz.core.text

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Normalizador para ingles: numeros (cardinales, decimales, ordinales 1st/2nd, anos y
 * decadas), fechas ("October 12, 1492", "4 July 1776", 12/25/2020, 2021-03-01), horas
 * (3:30, 10:05 pm), monedas ($, £, €), porcentajes, fracciones, romanos en contexto
 * ("Chapter IV", "Henry VIII"), abreviaturas comunes (Mr., Dr., e.g., i.e., etc., vs.),
 * URL (solo el dominio) y correos. Quita comillas y rayas; conserva la puntuacion.
 */
class EnglishNormalizer : TextNormalizer {
    override val language: String = "en"

    override fun normalize(text: String): MappedText = AsciiDigits.normalizeFolded(text) { Run(it).run() }

    private class Run(private val s: String) {
        private val n = s.length
        private val out = MappedTextBuilder(n + n / 3)
        private var prevWord: String? = null
        private val mUrl: Matcher = URL.matcher(s).useTransparentBounds(true)
        private val mEmail: Matcher = EMAIL.matcher(s).useTransparentBounds(true)
        private val mTime: Matcher = TIME.matcher(s).useTransparentBounds(true)
        private val mIso: Matcher = ISO_DATE.matcher(s).useTransparentBounds(true)
        private val mDate: Matcher = NUM_DATE.matcher(s).useTransparentBounds(true)
        private val mNum: Matcher = NUMBER.matcher(s).useTransparentBounds(true)

        fun run(): MappedText {
            var i = 0
            while (i < n) {
                val c = s[i]
                i = when {
                    c.isWhitespace() || c == SentenceSegmenter.OBJECT_CHAR -> {
                        out.space(i)
                        i + 1
                    }
                    isAsciiDigit(c) -> number(i, null, i)
                    c.isLetter() -> word(i)
                    else -> symbol(i)
                }
            }
            return out.build()
        }

        private fun looking(m: Matcher, at: Int): Boolean {
            m.region(at, n)
            return m.lookingAt()
        }

        // ------------------------------------------------------------ palabras

        private fun word(i: Int): Int {
            val c0 = s[i]
            if ((c0 == 'h' || c0 == 'w' || c0 == 'H' || c0 == 'W') && looking(mUrl, i)) {
                var e = mUrl.end()
                while (e > i && s[e - 1] in ".,;:!?)]»”’'\"") e--
                return readUrl(i, e)
            }
            if (looking(mEmail, i)) {
                if (out.last().isLetterOrDigit()) out.space(i)
                spell(i, mEmail.end())
                prevWord = null
                return mEmail.end()
            }
            abbreviation(i)?.let { return it }
            if (i > 0 && isAsciiDigit(s[i - 1])) out.space(i)
            var e = i
            while (e < n) {
                val c = s[e]
                if (c.isLetter() || isMark(c)) {
                    e++
                } else if ((c == '\'' || c == '’') && e > i && e + 1 < n && s[e + 1].isLetter()) {
                    e++
                } else {
                    break
                }
            }
            val w = s.substring(i, e)
            if (!roman(i, e, w)) {
                for (k in i until e) out.char(if (s[k] == '’') '\'' else s[k], k)
            }
            prevWord = w
            return e
        }

        private fun abbreviation(i: Int): Int? {
            if (i > 0 && s[i - 1].isLetter()) return null
            for (a in ABBREVIATIONS) {
                val end = i + a.key.length
                if (!s.regionMatches(i, a.key, 0, a.key.length, ignoreCase = a.ignoreCase)) continue
                if (end < n && s[end].isLetterOrDigit()) continue
                val next = nextNonSpace(end)
                when (a.cond) {
                    Cond.CAPITAL -> if (next == null || !s[next].isUpperCase()) continue
                    Cond.DIGIT -> if (next == null || !isAsciiDigit(s[next])) continue
                    Cond.NONE -> {}
                }
                out.replace(a.expansion, i, end)
                prevWord = a.expansion.substringAfterLast(' ')
                return end
            }
            return null
        }

        private fun roman(i: Int, e: Int, w: String): Boolean {
            if (!SpanishNormalizer.isRoman(w)) return false
            val value = SpanishNormalizer.romanValue(w)
            val prev = prevWord
            if (prev != null && prev.lowercase() in ROMAN_CONTEXT) {
                out.replace(cardinal(value.toLong()), i, e)
                prevWord = w
                return true
            }
            if (w == "I" || prev == null || value > 40) return false
            if (prev.length < 3 || !prev[0].isUpperCase() || prev.drop(1).any { !it.isLowerCase() }) return false
            if (prev.lowercase() in NAME_STOP) return false
            val ord = ordinal(value.toLong())
            out.replace("the " + ord.replaceFirstChar { it.uppercaseChar() }, i, e)
            prevWord = w
            return true
        }

        private fun readUrl(i: Int, e: Int): Int {
            val (hs, he) = SpanishNormalizer.urlHost(s, i, e)
            if (out.last().isLetterOrDigit()) out.space(i)
            if (hs >= he) {
                spell(i, e)
            } else {
                out.replace("link to", i, hs)
                out.space(hs)
                spell(hs, he)
            }
            prevWord = null
            return e
        }

        /** Lee una direccion: "dot", "at", "slash"... */
        private fun spell(a: Int, b: Int) {
            for (p in a until b) {
                val c = s[p]
                val w = when (c) {
                    '.' -> "dot"
                    '/' -> "slash"
                    '-' -> "dash"
                    '_' -> "underscore"
                    '@' -> "at"
                    ':' -> "colon"
                    else -> null
                }
                if (w != null) {
                    out.space(p)
                    out.replace(w, p, p + 1)
                    out.space(p)
                } else if (c.isLetterOrDigit()) {
                    out.char(c, p)
                } else {
                    out.space(p)
                }
            }
        }

        // ------------------------------------------------------------ numeros

        private fun number(i: Int, currency: Char?, start: Int): Int {
            if (out.last().isLetterOrDigit()) out.space(start)
            if (currency == null) {
                if (looking(mTime, i)) time()?.let { return it }
                if (looking(mIso, i)) {
                    val y = mIso.group(1).toInt()
                    val m = mIso.group(2).toInt()
                    val d = mIso.group(3).toInt()
                    if (m in 1..12 && d in 1..31) return emitDate(m, d, y.toString(), start, mIso.end())
                }
                if (looking(mDate, i)) {
                    var m = mDate.group(1).toInt()
                    var d = mDate.group(2).toInt()
                    if (m > 12 && d <= 12) m = d.also { d = m }
                    if (m in 1..12 && d in 1..31) return emitDate(m, d, mDate.group(3), start, mDate.end())
                }
            }
            if (!looking(mNum, i)) {
                out.char(s[i], i)
                return i + 1
            }
            val intText = mNum.group(1)
            val intDigits = intText.filter { isAsciiDigit(it) }
            val frac = mNum.group(2)
            val suffix = mNum.group(3)
            var end = mNum.end()
            val prev = prevWord
            prevWord = null
            if (intDigits.length > 18) {
                out.replace(intDigits.map { ONES[it - '0'] }.joinToString(" "), start, end)
                return end
            }
            val v = intDigits.toLong()
            val words = StringBuilder()
            when {
                currency != null -> {
                    val cur = CURRENCIES.getValue(currency)
                    words.append(cardinal(v)).append(' ').append(if (v == 1L) cur.first else cur.second)
                    if (frac != null && frac.length == 2 && frac != "00") {
                        val cents = frac.toLong()
                        words.append(" and ").append(cardinal(cents)).append(if (cents == 1L) " cent" else " cents")
                    } else if (frac != null && frac != "00") {
                        words.setLength(0)
                        words.append(cardinal(v)).append(" point ").append(frac.map { ONES[it - '0'] }.joinToString(" "))
                            .append(' ').append(cur.second)
                    }
                    out.replace(words.toString(), start, end)
                    return end
                }
                suffix == "s" && frac == null -> {
                    val base = if (intDigits.length == 4) year(v.toInt()) else cardinal(v)
                    words.append(if (base.endsWith("y")) base.dropLast(1) + "ies" else base + "s")
                }
                suffix != null -> words.append(ordinal(v))
                frac == null && prev != null && prev.lowercase() in MONTHS_SET && v in 1..31 && intText.length <= 2 ->
                    words.append(ordinal(v))
                frac == null && intText.length <= 2 && v in 1..31 && nextIsMonth(end) -> {
                    words.append("the ").append(ordinal(v)).append(" of")
                }
                frac == null && intDigits.length == 4 && intText.length == 4 && v in 1100..2099 && !nextIsUnitLike(end) ->
                    words.append(year(v.toInt()))
                else -> {
                    // Fraccion simple: 1/2, 3/4
                    if (frac == null && end + 1 < n && s[end] == '/' && isAsciiDigit(s[end + 1])) {
                        var e = end + 1
                        while (e < n && isAsciiDigit(s[e])) e++
                        if (e - end - 1 <= 4) {
                            val den = s.substring(end + 1, e).toLong()
                            out.replace(fraction(v, den), start, e)
                            return e
                        }
                    }
                    words.append(cardinal(v))
                    if (frac != null) words.append(" point ").append(frac.map { ONES[it - '0'] }.joinToString(" "))
                }
            }
            var k = end
            if (k < n && s[k] == ' ') k++
            if (k < n && s[k] == '%') {
                words.append(" percent")
                end = k + 1
            }
            out.replace(words.toString(), start, end)
            return end
        }

        private fun time(): Int? {
            val h = mTime.group(1).toInt()
            val m = mTime.group(2).toInt()
            if (h > 24 || m > 59) return null
            val ampm = mTime.group(3)
            val sb = StringBuilder(cardinal(h.toLong()))
            when {
                m == 0 && ampm == null && h <= 12 -> sb.append(" o'clock")
                m == 0 -> if (ampm == null) sb.append(" hundred")
                m < 10 -> sb.append(" oh ").append(ONES[m])
                else -> sb.append(' ').append(cardinal(m.toLong()))
            }
            if (ampm != null) sb.append(if (ampm[0] == 'a' || ampm[0] == 'A') " a m" else " p m")
            out.replace(sb.toString(), mTime.start(), mTime.end())
            prevWord = null
            return mTime.end()
        }

        private fun emitDate(m: Int, d: Int, y: String, start: Int, end: Int): Int {
            val yv = y.toInt().let { if (y.length == 2) 2000 + it else it }
            val words = MONTHS[m - 1] + " " + ordinal(d.toLong()) + ", " +
                (if (yv in 1100..2099) year(yv) else cardinal(yv.toLong()))
            out.replace(words, start, end)
            prevWord = null
            return end
        }

        private fun nextIsMonth(from: Int): Boolean {
            val p = nextNonSpace(from) ?: return false
            if (p == from) return false
            var e = p
            while (e < n && s[e].isLetter()) e++
            return s.substring(p, e) in MONTHS_SET_CAP
        }

        private fun nextIsUnitLike(from: Int): Boolean {
            var k = from
            if (k < n && s[k] == ' ') k++
            if (k >= n) return false
            if (s[k] == '%') return true
            var e = k
            while (e < n && s[e].isLetter()) e++
            return s.substring(k, e).lowercase() in UNIT_WORDS
        }

        // ------------------------------------------------------------ simbolos

        private fun symbol(i: Int): Int {
            val c = s[i]
            prevWord = null
            if (c in CURRENCIES && i + 1 < n && isAsciiDigit(s[i + 1])) return number(i + 1, c, i)
            if ((c == '-' || c == '−') && i + 1 < n && isAsciiDigit(s[i + 1])) {
                if (i >= 4 && isYear(i - 4) && isYear(i + 1)) {
                    out.replace(" to ", i, i + 1)
                    return i + 1
                }
                if (i == 0 || s[i - 1].isWhitespace() || s[i - 1] == '(') {
                    out.replace("minus ", i, i + 1)
                    return i + 1
                }
            }
            SYMBOLS[c]?.let { w ->
                out.space(i)
                out.replace(w, i, i + 1)
                out.space(i)
                return i + 1
            }
            when (c) {
                '.', ',', ';', ':', '!', '?', '…', '(', ')', '[', ']' -> out.char(c, i)
                '|' -> {
                    out.trimTrailingSpace()
                    out.char(',', i)
                    out.space(i)
                }
                else -> {
                    val type = Character.getType(c).toByte()
                    if (c in SILENT || type == Character.OTHER_SYMBOL || type == Character.SURROGATE ||
                        type == Character.PRIVATE_USE || type == Character.CONTROL || type == Character.DASH_PUNCTUATION ||
                        type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
                    ) {
                        if (out.last().isLetterOrDigit() && i + 1 < n && s[i + 1].isLetterOrDigit()) out.space(i)
                    } else if (type != Character.FORMAT) {
                        out.char(c, i)
                    }
                }
            }
            return i + 1
        }

        private fun isYear(p: Int): Boolean {
            if (p < 0 || p + 4 > n) return false
            for (k in p until p + 4) if (!isAsciiDigit(s[k])) return false
            if (p > 0 && isAsciiDigit(s[p - 1])) return false
            if (p + 4 < n && isAsciiDigit(s[p + 4])) return false
            return true
        }

        private fun nextNonSpace(from: Int): Int? {
            var p = from
            while (p < n && (s[p] == ' ' || s[p] == ' ')) p++
            return if (p < n) p else null
        }
    }

    private enum class Cond { NONE, CAPITAL, DIGIT }

    private class Abbr(val key: String, val expansion: String, val cond: Cond = Cond.NONE, val ignoreCase: Boolean = false)

    companion object {
        private val URL: Pattern = Pattern.compile("(?:https?://|www\\.)[^\\s<>«»\"“”]+", Pattern.CASE_INSENSITIVE)
        private val EMAIL: Pattern = Pattern.compile("[\\p{L}\\d._%+-]{1,64}@[\\p{L}\\d-]{1,63}(?:\\.[\\p{L}\\d-]{1,63}){1,8}")
        private val TIME: Pattern =
            Pattern.compile("(\\d{1,2}):(\\d{2})(?!\\d)(?: ?([AaPp]\\.? ?[Mm]\\.?)(?![\\p{L}\\d]))?")
        private val ISO_DATE: Pattern = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})(?![\\d])")
        private val NUM_DATE: Pattern = Pattern.compile("(\\d{1,2})/(\\d{1,2})/(\\d{4}|\\d{2})(?![\\d/])")
        private val NUMBER: Pattern =
            Pattern.compile("(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d+))?(?:(st|nd|rd|th|s)(?![\\p{L}\\d]))?(?![\\d])")

        private val ONES = arrayOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
            "eighteen", "nineteen",
        )
        private val TENS = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
        private val SCALES = arrayOf(
            1_000_000_000_000_000_000L to "quintillion", 1_000_000_000_000_000L to "quadrillion",
            1_000_000_000_000L to "trillion", 1_000_000_000L to "billion", 1_000_000L to "million",
            1_000L to "thousand",
        )
        private val MONTHS = arrayOf(
            "January", "February", "March", "April", "May", "June", "July", "August", "September",
            "October", "November", "December",
        )
        private val MONTHS_SET_CAP = MONTHS.toHashSet()
        private val MONTHS_SET = MONTHS.map { it.lowercase() }.toHashSet()
        private val CURRENCIES = mapOf('$' to ("dollar" to "dollars"), '£' to ("pound" to "pounds"), '€' to ("euro" to "euros"))
        private val SYMBOLS = mapOf(
            '&' to "and", '+' to "plus", '=' to "equals", '@' to "at", '%' to "percent", '×' to "times",
            '÷' to "divided by", '±' to "plus or minus", '§' to "section", '<' to "less than", '>' to "greater than",
        )
        private val SILENT = hashSetOf(
            '"', '\'', '«', '»', '“', '”', '‘', '’', '‹', '›', '„', '—', '–', '-', '‐', '‑', '*', '_', '#',
            '~', '^', '`', '\\', '/', '·', '•', '¶', '†', '‡', '{', '}', '©', '®', '™',
        )
        private val UNIT_WORDS = hashSetOf("km", "m", "cm", "mm", "kg", "g", "lb", "lbs", "ft", "in", "mi", "miles", "meters", "people", "years", "times")
        private val ROMAN_CONTEXT = hashSetOf(
            "chapter", "chapters", "part", "book", "volume", "vol", "act", "scene", "section", "war",
            "article", "appendix", "canto", "unit", "lesson", "level", "phase", "stage", "round",
            "psalm", "title", "grade", "class", "type", "super", "bowl", "episode", "season",
        )
        private val NAME_STOP = hashSetOf(
            "the", "and", "but", "then", "when", "she", "they", "what", "this", "that", "with", "from",
            "for", "not", "was", "were", "are", "has", "had", "have", "his", "her", "its", "our",
            "their", "you", "who", "why", "how", "where", "there", "here", "said", "asked", "now",
            "yes", "all", "one", "if", "so", "as", "at", "in", "on", "of", "to", "by", "or",
        )
        private val ABBREVIATIONS: List<Abbr> = listOf(
            Abbr("Mr.", "Mister"), Abbr("Mrs.", "Missus"), Abbr("Ms.", "Miz"), Abbr("Dr.", "Doctor"),
            Abbr("Prof.", "Professor"), Abbr("St.", "Saint", Cond.CAPITAL), Abbr("Jr.", "Junior"),
            Abbr("Sr.", "Senior"), Abbr("Gen.", "General", Cond.CAPITAL), Abbr("Capt.", "Captain"),
            Abbr("Lt.", "Lieutenant"), Abbr("Sgt.", "Sergeant"), Abbr("Col.", "Colonel", Cond.CAPITAL),
            Abbr("Gov.", "Governor"), Abbr("Rev.", "Reverend"), Abbr("Mt.", "Mount", Cond.CAPITAL),
            Abbr("e.g.", "for example", ignoreCase = true), Abbr("i.e.", "that is", ignoreCase = true),
            Abbr("etc.", "et cetera"), Abbr("vs.", "versus"), Abbr("approx.", "approximately"),
            Abbr("No.", "number", Cond.DIGIT), Abbr("no.", "number", Cond.DIGIT),
            Abbr("Nos.", "numbers", Cond.DIGIT), Abbr("Fig.", "figure"), Abbr("fig.", "figure"),
            Abbr("Vol.", "volume"), Abbr("vol.", "volume"), Abbr("Ch.", "chapter"), Abbr("ch.", "chapter"),
            Abbr("a.m.", "a m"), Abbr("p.m.", "p m"), Abbr("U.S.", "U S"), Abbr("U.K.", "U K"),
            Abbr("Inc.", "Incorporated"), Abbr("Ltd.", "Limited"), Abbr("Bros.", "Brothers"),
        ).sortedByDescending { it.key.length }

        /** Cardinal ingles de [n] >= 0. */
        fun cardinal(n: Long): String {
            if (n == 0L) return "zero"
            val parts = ArrayList<String>()
            var rest = n
            for ((scale, name) in SCALES) {
                if (rest >= scale) {
                    parts.add(below1000((rest / scale).toInt()) + " " + name)
                    rest %= scale
                }
            }
            if (rest > 0) parts.add(below1000(rest.toInt()))
            return parts.joinToString(" ")
        }

        private fun below1000(n: Int): String {
            val sb = StringBuilder()
            if (n >= 100) {
                sb.append(ONES[n / 100]).append(" hundred")
                if (n % 100 != 0) sb.append(' ')
            }
            val r = n % 100
            if (r in 1..19) sb.append(ONES[r]) else if (r >= 20) {
                sb.append(TENS[r / 10])
                if (r % 10 != 0) sb.append('-').append(ONES[r % 10])
            }
            return sb.toString()
        }

        private fun year(y: Int): String = when {
            y in 2000..2009 -> cardinal(y.toLong())
            y % 100 == 0 -> cardinal((y / 100).toLong()) + " hundred"
            y % 100 < 10 -> cardinal((y / 100).toLong()) + " oh " + ONES[y % 100]
            else -> cardinal((y / 100).toLong()) + " " + below1000(y % 100)
        }

        private fun fraction(num: Long, den: Long): String {
            val name = when (den) {
                2L -> if (num == 1L) "half" else "halves"
                4L -> if (num == 1L) "quarter" else "quarters"
                in 3L..10L -> ordinal(den) + if (num == 1L) "" else "s"
                else -> null
            }
            return if (name != null) {
                (if (num == 1L) "one" else cardinal(num)) + " " + name
            } else {
                cardinal(num) + " over " + cardinal(den)
            }
        }

        private val IRREGULAR = mapOf(
            "one" to "first", "two" to "second", "three" to "third", "five" to "fifth",
            "eight" to "eighth", "nine" to "ninth", "twelve" to "twelfth",
        )

        /** Ordinal ingles ("twenty-first"). */
        fun ordinal(n: Long): String {
            val c = cardinal(n)
            val cut = maxOf(c.lastIndexOf(' '), c.lastIndexOf('-')) + 1
            val last = c.substring(cut)
            val ord = IRREGULAR[last] ?: if (last.endsWith("y")) last.dropLast(1) + "ieth" else last + "th"
            return c.substring(0, cut) + ord
        }

        private fun isMark(c: Char): Boolean {
            val t = Character.getType(c).toByte()
            return t == Character.NON_SPACING_MARK || t == Character.COMBINING_SPACING_MARK
        }
    }
}
