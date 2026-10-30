package com.lectorvoz.core.text

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Normalizador espanol para voz.
 *
 * Convierte numeros (cardinales con genero, decimales, ordinales, fechas, horas,
 * porcentajes, monedas y unidades), numeros romanos en contexto claro, abreviaturas, siglas
 * deletreables, simbolos, URL y correos. Las comillas y la raya de dialogo no se pronuncian.
 * Recorre el texto una sola vez; los patrones se compilan una vez. Los digitos Unicode no
 * ASCII se convierten antes a ASCII. Las URL se leen solo por su dominio ("enlace a ejemplo
 * punto com").
 *
 * Heuristica conocida: "D." seguido de mayuscula se lee "don" tambien cuando es una letra
 * suelta a final de frase ("la letra D. Luego..."); se acepta por ser mucho mas frecuente
 * el tratamiento.
 *
 * @param spellAcronyms siglas que siempre se deletrean.
 * @param wordAcronyms siglas que siempre se leen como palabra.
 */
class SpanishNormalizer(
    private val spellAcronyms: Set<String> = DEFAULT_SPELL,
    private val wordAcronyms: Set<String> = DEFAULT_WORD_ACRONYMS,
) : TextNormalizer {
    override val language: String = "es"

    override fun normalize(text: String): MappedText = AsciiDigits.normalizeFolded(text) { Run(it).run() }

    private inner class Run(private val s: String) {
        private val n = s.length
        private val out = MappedTextBuilder(n + n / 3)
        private var prevWord: String? = null
        private val capsSentence = isCapsSentence(s)
        private val mUrl: Matcher = URL.matcher(s).useTransparentBounds(true)
        private val mEmail: Matcher = EMAIL.matcher(s).useTransparentBounds(true)
        private val mDate: Matcher = DATE.matcher(s).useTransparentBounds(true)
        private val mTime: Matcher = TIME.matcher(s).useTransparentBounds(true)
        private val mGrouped: Matcher = GROUPED.matcher(s).useTransparentBounds(true)
        private val mPlain: Matcher = PLAIN.matcher(s).useTransparentBounds(true)
        private val mOrdinal: Matcher = ORDINAL.matcher(s).useTransparentBounds(true)

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

        // ---------------------------------------------------------------- palabras

        private fun word(i: Int): Int {
            if (s[i] == 'h' || s[i] == 'w' || s[i] == 'H' || s[i] == 'W') {
                if (looking(mUrl, i)) return readUrl(i, trimUrlEnd(i, mUrl.end()))
            }
            if (looking(mEmail, i)) return spellAddress(i, mEmail.end(), false)
            abbreviation(i)?.let { return it }
            var e = i
            while (e < n) {
                val c = s[e]
                if (c.isLetter() || isMark(c)) {
                    e++
                } else if (isApostrophe(c) && e > i && e + 1 < n && s[e + 1].isLetter()) {
                    e++
                } else {
                    break
                }
            }
            val w = s.substring(i, e)
            if (!roman(i, e, w) && !acronym(i, e, w)) {
                for (k in i until e) out.char(s[k], k)
            }
            prevWord = w
            return e
        }

        private fun abbreviation(i: Int): Int? {
            val list = ABBREVIATIONS[s[i]] ?: return null
            for (a in list) {
                val end = i + a.key.length
                if (!s.regionMatches(i, a.key, 0, a.key.length)) continue
                if (a.key.last().isLetter() && end < n && s[end].isLetterOrDigit()) continue
                if (a.capitalAfter) {
                    val nw = nextWordStart(end) ?: continue
                    if (!s[nw].isUpperCase()) continue
                    var p = i - 1
                    while (p >= 0 && s[p] == ' ') p--
                    if (p >= 0 && s[p] == '.') continue // iniciales: "J. D. Salinger"
                }
                out.replace(a.expansion, i, end)
                prevWord = a.expansion.substringAfterLast(' ')
                return end
            }
            return null
        }

        private fun roman(i: Int, e: Int, w: String): Boolean {
            if (!isRoman(w)) return false
            val value = romanValue(w)
            val prev = prevWord
            val prevLower = prev?.lowercase()
            if (prevLower != null && prevLower in ROMAN_CONTEXT) {
                out.replace(SpanishNumbers.cardinal(value.toLong()), i, e)
                return true
            }
            if (capsSentence) return false
            val next = nextWordStart(e)?.let { readWord(it) }
            if (prev != null && isNameLike(prev) && value <= 60) {
                if (w in AMBIGUOUS_ROMAN && next != null && next.length > 1 && next.all { !it.isLowerCase() }) return false
                val g = if (isFeminineName(prev)) Gender.FEMININE else Gender.MASCULINE
                val words = if (value <= 10) SpanishNumbers.ordinal(value, g) else SpanishNumbers.cardinal(value.toLong(), g)
                out.replace(words, i, e)
                return true
            }
            if (prevLower != null && prevLower in ARTICLES && next != null && next[0].isUpperCase() &&
                w.length >= 2 && w !in AMBIGUOUS_ROMAN
            ) {
                val g = if (prevLower in FEMININE_DETERMINERS) Gender.FEMININE else Gender.MASCULINE
                out.replace(SpanishNumbers.ordinal(value, g), i, e)
                return true
            }
            return false
        }

        private fun acronym(i: Int, e: Int, w: String): Boolean {
            if (w.length < 2) return false
            for (c in w) if (c !in 'A'..'Z' && c != 'Ñ') return false
            if (w in wordAcronyms) return false
            val spell = w in spellAcronyms || (!capsSentence && !isRoman(w) && !pronounceable(w))
            if (!spell) return false
            val sb = StringBuilder()
            for (c in w) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(LETTER_NAMES[c])
            }
            out.replace(sb.toString(), i, e)
            return true
        }

        /**
         * Lee una URL solo por su dominio: "https://www.ejemplo.com/a/b?x=1" se lee
         * "enlace a ejemplo punto com" (la ruta no aporta nada al oido).
         */
        private fun readUrl(i: Int, e: Int): Int {
            val (hs, he) = urlHost(s, i, e)
            if (hs >= he) return spellAddress(i, e, true)
            if (out.last().isLetterOrDigit()) out.space(i)
            out.replace("enlace a", i, hs)
            out.space(hs)
            spellAddress(hs, he, false)
            prevWord = null
            return e
        }

        /** Lee una URL o correo: "punto", "barra", "arroba"... */
        private fun spellAddress(i: Int, e: Int, isUrl: Boolean): Int {
            var p = i
            if (isUrl) {
                val lower = s.substring(i, minOf(e, i + 8)).lowercase()
                if (lower.startsWith("https://")) p += 8 else if (lower.startsWith("http://")) p += 7
            }
            if (out.last().isLetterOrDigit()) out.space(i)
            while (p < e) {
                val c = s[p]
                val word = when (c) {
                    '.' -> "punto"
                    '/' -> "barra"
                    '-' -> "guion"
                    '_' -> "guion bajo"
                    '@' -> "arroba"
                    ':' -> "dos puntos"
                    else -> null
                }
                if (word != null) {
                    out.space(p)
                    out.replace(word, p, p + 1)
                    out.space(p)
                } else if (c.isLetterOrDigit()) {
                    out.char(c, p)
                } else {
                    out.space(p)
                }
                p++
            }
            prevWord = null
            return e
        }

        private fun trimUrlEnd(i: Int, e0: Int): Int {
            var e = e0
            while (e > i && s[e - 1] in ".,;:!?)]»”’'\"") e--
            return e
        }

        // ---------------------------------------------------------------- numeros

        /**
         * Lee el numero que empieza en [i]. [currency] es un simbolo de moneda previo
         * ("$5") que empieza en [start].
         */
        private fun number(i: Int, currency: Char?, start: Int): Int {
            if (out.last().isLetterOrDigit()) out.space(start)
            if (currency == null) {
                if (looking(mDate, i)) dateOrNull()?.let { return it }
                if (looking(mTime, i)) timeOrNull(i)?.let { return it }
            }
            val numEnd: Int
            val intDigits: String
            var frac: String? = null
            var sep = ','
            if (looking(mGrouped, i)) {
                numEnd = mGrouped.end()
                val body = s.substring(i, numEnd)
                val comma = body.indexOf(',')
                val intPart = if (comma >= 0) body.substring(0, comma) else body
                intDigits = intPart.filter { isAsciiDigit(it) }
                if (comma >= 0) frac = body.substring(comma + 1)
            } else {
                if (!looking(mPlain, i)) {
                    // No deberia ocurrir (solo llegan digitos ASCII), pero nunca end() sin coincidencia.
                    out.char(s[i], i)
                    return i + 1
                }
                numEnd = mPlain.end()
                val body = s.substring(i, numEnd)
                val k = body.indexOfFirst { it == '.' || it == ',' }
                if (k >= 0) {
                    intDigits = body.substring(0, k)
                    frac = body.substring(k + 1)
                    sep = body[k]
                } else {
                    intDigits = body
                }
            }
            prevWordForGender = prevWord
            prevWord = null
            // Numeros demasiado largos o con ceros a la izquierda: cifra a cifra.
            if (intDigits.length > 18 || (intDigits.length > 1 && intDigits[0] == '0' && frac == null)) {
                out.replace(SpanishNumbers.digits(intDigits), start, numEnd)
                return numEnd
            }
            val value = intDigits.toLong()
            // Ordinales: 1.º, 2.ª, 3er, 1.er
            if (frac == null && currency == null && looking(mOrdinal, numEnd) && intDigits.length <= 3) {
                val suf = mOrdinal.group().trimStart('.')
                val fem = suf == "ª" || suf == "a" || suf == "as" || suf == "ra"
                val plural = suf.endsWith("s")
                val apoc = suf == "er"
                val words = SpanishNumbers.ordinal(
                    value.toInt(),
                    if (fem) Gender.FEMININE else Gender.MASCULINE,
                    apoc,
                    plural,
                )
                out.replace(words, start, mOrdinal.end())
                return mOrdinal.end()
            }
            // Fracciones simples: 1/2, 3/4
            if (frac == null && currency == null && numEnd + 1 < n && s[numEnd] == '/' && isAsciiDigit(s[numEnd + 1])) {
                var e = numEnd + 1
                while (e < n && isAsciiDigit(s[e])) e++
                if (e - numEnd - 1 <= 6) {
                    val den = s.substring(numEnd + 1, e).toLong()
                    out.replace(fraction(value, den), start, e)
                    return e
                }
            }
            if (currency != null) {
                out.replace(readQuantity(value, frac, sep, CURRENCIES.getValue(currency), true), start, numEnd)
                return numEnd
            }
            // Sufijo: %, moneda o unidad
            var k = numEnd
            if (k < n && (s[k] == ' ' || s[k] == ' ' || s[k] == ' ')) k++
            if (k < n) {
                val c = s[k]
                if (c == '%' || c == '‰') {
                    val words = readDecimalOrInt(value, frac, sep, Gender.MASCULINE, false) +
                        if (c == '%') " por ciento" else " por mil"
                    out.replace(words, start, k + 1)
                    return k + 1
                }
                if (c in CURRENCY_SYMBOLS) {
                    out.replace(readQuantity(value, frac, sep, CURRENCIES.getValue(c), true), start, k + 1)
                    return k + 1
                }
                val unitEnd = unitTokenEnd(k)
                if (unitEnd > k && (k == numEnd || k == numEnd + 1)) {
                    val unit = UNITS[s.substring(k, unitEnd)]
                    if (unit != null) {
                        out.replace(readQuantity(value, frac, sep, unit, false), start, unitEnd)
                        return unitEnd
                    }
                }
            }
            // Sin sufijo: concordancia con la palabra siguiente.
            val nextStart = nextWordStart(numEnd)
            val nextWord = if (nextStart != null && nextStart <= numEnd + 2) readWord(nextStart) else null
            var gender = articleGender(prevWordForGender)
            var apoc = false
            var addDe = false
            if (nextWord != null && nextWord[0].isLowerCase() && nextWord.lowercase() !in FUNCTION_WORDS) {
                gender = nounGender(nextWord)
                apoc = true
                addDe = frac == null && value >= 1_000_000 && value % 1_000_000 == 0L
            }
            val words = readDecimalOrInt(value, frac, sep, gender, apoc) + if (addDe) " de" else ""
            out.replace(words, start, numEnd)
            return numEnd
        }

        private var prevWordForGender: String? = null

        private fun dateOrNull(): Int? {
            val d = mDate.group(1).toInt()
            val m = mDate.group(3).toInt()
            val y = mDate.group(4)
            if (d !in 1..31 || m !in 1..12) return null
            val words = SpanishNumbers.cardinal(d.toLong()) + " de " + SpanishNumbers.month(m) +
                " de " + SpanishNumbers.cardinal(y.toLong())
            out.replace(words, mDate.start(), mDate.end())
            prevWord = null
            return mDate.end()
        }

        private fun timeOrNull(i: Int): Int? {
            val h = mTime.group(1).toInt()
            val m = mTime.group(2).toInt()
            if (h > 24 || m > 59) return null
            val hasH = mTime.group(3) != null
            val hw = SpanishNumbers.cardinal(h.toLong(), Gender.FEMININE)
            val words = if (hasH || h == 0 || h > 12 || (mTime.group(1).length == 2 && s[i] == '0')) {
                val hours = if (h == 1) "una hora" else "$hw horas"
                when (m) {
                    0 -> hours
                    1 -> "$hours y un minuto"
                    else -> "$hours y ${SpanishNumbers.cardinal(m.toLong(), apocope = true)} minutos"
                }
            } else {
                when (m) {
                    0 -> "$hw en punto"
                    15 -> "$hw y cuarto"
                    30 -> "$hw y media"
                    else -> "$hw y ${SpanishNumbers.cardinal(m.toLong())}"
                }
            }
            out.replace(words, i, mTime.end())
            prevWord = null
            return mTime.end()
        }

        private fun readDecimalOrInt(value: Long, frac: String?, sep: Char, g: Gender, apoc: Boolean): String {
            if (frac == null) return SpanishNumbers.cardinal(value, g, apoc)
            val intW = SpanishNumbers.cardinal(value, g, false)
            val fracW = if (frac.length <= 3 && frac[0] != '0') SpanishNumbers.cardinal(frac.toLong()) else SpanishNumbers.digits(frac)
            return intW + (if (sep == ',') " coma " else " punto ") + fracW
        }

        private fun readQuantity(value: Long, frac: String?, sep: Char, unit: Measure, isCurrency: Boolean): String {
            if (frac != null) {
                if (isCurrency && frac.length == 2 && unit.subSingular != null) {
                    val cents = frac.toLong()
                    val main = if (value > 0) {
                        SpanishNumbers.cardinal(value, unit.gender, true) + " " + (if (value == 1L) unit.singular else unit.plural)
                    } else {
                        null
                    }
                    if (cents == 0L) return main ?: ("cero " + unit.plural)
                    val sub = SpanishNumbers.cardinal(cents, Gender.MASCULINE, true) + " " +
                        (if (cents == 1L) unit.subSingular else unit.subPlural)
                    return if (main == null) sub else "$main con $sub"
                }
                return readDecimalOrInt(value, frac, sep, unit.gender, false) + " " + unit.plural
            }
            val de = if (value >= 1_000_000 && value % 1_000_000 == 0L) " de" else ""
            return SpanishNumbers.cardinal(value, unit.gender, true) + de + " " +
                (if (value == 1L) unit.singular else unit.plural)
        }

        private fun fraction(num: Long, den: Long): String {
            val name = FRACTION_NAMES[den.toInt().takeIf { den in 2..10 } ?: 0]
            return if (name != null) {
                SpanishNumbers.cardinal(num, Gender.MASCULINE, true) + " " + (if (num == 1L) name else name + "s")
            } else {
                SpanishNumbers.cardinal(num) + " barra " + SpanishNumbers.cardinal(den)
            }
        }

        /** Fin del posible simbolo de unidad que empieza en [k] (letras, ², ³, º, /). */
        private fun unitTokenEnd(k: Int): Int {
            var e = k
            while (e < n) {
                val c = s[e]
                if (c.isLetter() || c == '²' || c == '³' || c == 'º' || c == '°' ||
                    (isAsciiDigit(c) && e > k && s[e - 1].isLetter()) ||
                    (c == '/' && e > k && e + 1 < n && s[e + 1].isLetter())
                ) {
                    e++
                } else {
                    break
                }
            }
            if (e < n && isAsciiDigit(s[e])) return k
            return e
        }

        // ---------------------------------------------------------------- simbolos

        private fun symbol(i: Int): Int {
            val c = s[i]
            prevWord = null
            // Moneda delante del numero: "$5", "€ 20"
            if (c in CURRENCY_SYMBOLS) {
                var j = i + 1
                if (j < n && s[j] == ' ') j++
                if (j < n && isAsciiDigit(s[j])) return number(j, c, i)
            }
            if ((c == '-' || c == '−') && i + 1 < n && isAsciiDigit(s[i + 1])) {
                if (i >= 4 && i + 5 <= n && isYear(i - 4) && isYear(i + 1)) {
                    out.replace(" a ", i, i + 1)
                    return i + 1
                }
                if (i == 0 || s[i - 1].isWhitespace() || s[i - 1] == '(') {
                    out.replace("menos ", i, i + 1)
                    return i + 1
                }
            }
            SYMBOL_WORDS[c]?.let { word ->
                out.space(i)
                out.replace(word, i, i + 1)
                out.space(i)
                return i + 1
            }
            when (c) {
                '.', ',', ';', ':', '!', '?', '¿', '¡', '…', '(', ')', '[', ']' -> out.char(c, i)
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
                        softSeparator(i)
                    } else if (type == Character.FORMAT) {
                        // invisible (guion blando, ancho cero): nada
                    } else {
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

        /** Simbolo mudo: separa con un espacio si esta entre dos palabras. */
        private fun softSeparator(i: Int) {
            if (out.last().isLetterOrDigit() && i + 1 < n && s[i + 1].isLetterOrDigit()) out.space(i)
        }

        // ---------------------------------------------------------------- utilidades

        private fun nextWordStart(from: Int): Int? {
            var p = from
            while (p < n && (s[p] == ' ' || s[p] == ' ')) p++
            return if (p < n && s[p].isLetter()) p else null
        }

        private fun readWord(from: Int): String {
            var e = from
            while (e < n && (s[e].isLetter() || isMark(s[e]))) e++
            return s.substring(from, e)
        }
    }

    /** Unidad de medida o moneda con su genero. */
    private class Measure(
        val singular: String,
        val plural: String,
        val gender: Gender = Gender.MASCULINE,
        val subSingular: String? = null,
        val subPlural: String? = null,
    )

    private class Abbreviation(val key: String, val expansion: String, val capitalAfter: Boolean = false)

    companion object {
        /** Rango [inicio, fin) del dominio de la URL [i, e) sin esquema ni "www.". */
        internal fun urlHost(s: String, i: Int, e: Int): Pair<Int, Int> {
            var p = i
            val schemeEnd = s.indexOf("://", i)
            if (schemeEnd in i until e) p = schemeEnd + 3
            if (s.regionMatches(p, "www.", 0, 4, ignoreCase = true)) p += 4
            var q = p
            while (q < e && s[q] != '/' && s[q] != ':' && s[q] != '?' && s[q] != '#') q++
            while (q > p && s[q - 1] == '.') q--
            return p to q
        }

        private val URL: Pattern = Pattern.compile("(?:https?://|www\\.)[^\\s<>«»\"“”]+", Pattern.CASE_INSENSITIVE)
        private val EMAIL: Pattern = Pattern.compile("[\\p{L}\\d._%+-]{1,64}@[\\p{L}\\d-]{1,63}(?:\\.[\\p{L}\\d-]{1,63}){1,8}")
        private val DATE: Pattern = Pattern.compile("(\\d{1,2})([/.-])(\\d{1,2})\\2(\\d{4}|\\d{2})(?!\\d)")
        private val TIME: Pattern = Pattern.compile("(\\d{1,2}):(\\d{2})(?!\\d)(?: ?(h|hs|hrs)(?![\\p{L}\\d]))?")
        private val GROUPED: Pattern = Pattern.compile("\\d{1,3}(?:[.\\u00A0\\u202F]\\d{3})+(?:,\\d+)?(?![\\d])|\\d{1,3}(?: \\d{3})+(?!\\d|[.,]\\d)")
        private val PLAIN: Pattern = Pattern.compile("\\d+(?:[.,]\\d+)?")
        private val ORDINAL: Pattern = Pattern.compile("(?:\\.?[ºª]|\\.(?:o|a|os|as|er|ra)|(?:er|ra|os|as))(?![\\p{L}\\d])")

        /** Siglas que se deletrean siempre. */
        val DEFAULT_SPELL: Set<String> = hashSetOf(
            "RTVE", "TVE", "DNI", "UE", "PP", "PIB", "ADN", "ARN", "ONG", "CEO", "BBVA", "UGT",
            "CCOO", "PC", "CD", "DVD", "USB", "URL", "PDF", "IBM", "BBC", "CNN", "FBI", "PNV",
            "ERC", "UCI", "IES", "CSIC", "TV", "SMS", "GPS", "HTML", "IA", "CV", "NIF", "CIF",
            "IRPF", "IPC", "DGT",
        )

        /** Siglas que se leen como palabra. */
        val DEFAULT_WORD_ACRONYMS: Set<String> = hashSetOf(
            "ONU", "OTAN", "UNESCO", "NASA", "OVNI", "SIDA", "RENFE", "UNICEF", "FIFA", "UEFA",
            "OPEP", "INEM", "IVA", "ESO", "PSOE", "AVE", "PYME", "BOE", "RAE", "CIA", "FAQ",
            "ISBN", "LOL", "OMS", "OCDE", "SEPE",
        )

        private val LETTER_NAMES = mapOf(
            'A' to "a", 'B' to "be", 'C' to "ce", 'D' to "de", 'E' to "e", 'F' to "efe",
            'G' to "ge", 'H' to "hache", 'I' to "i", 'J' to "jota", 'K' to "ka", 'L' to "ele",
            'M' to "eme", 'N' to "ene", 'Ñ' to "eñe", 'O' to "o", 'P' to "pe", 'Q' to "cu",
            'R' to "erre", 'S' to "ese", 'T' to "te", 'U' to "u", 'V' to "uve", 'W' to "uve doble",
            'X' to "equis", 'Y' to "i griega", 'Z' to "zeta",
        )

        private val ABBREVIATIONS: Map<Char, List<Abbreviation>> = listOf(
            Abbreviation("Sr.", "señor"), Abbreviation("Sres.", "señores"),
            Abbreviation("Sra.", "señora"), Abbreviation("Sras.", "señoras"),
            Abbreviation("Srta.", "señorita"), Abbreviation("Dr.", "doctor"),
            Abbreviation("Dra.", "doctora"), Abbreviation("Dres.", "doctores"),
            Abbreviation("Dña.", "doña"), Abbreviation("D.ª", "doña"),
            Abbreviation("D.", "don", capitalAfter = true),
            Abbreviation("Ud.", "usted"), Abbreviation("Uds.", "ustedes"),
            Abbreviation("Vd.", "usted"), Abbreviation("Vds.", "ustedes"),
            Abbreviation("etc.", "etcétera"), Abbreviation("pág.", "página"),
            Abbreviation("págs.", "páginas"), Abbreviation("Pág.", "página"),
            Abbreviation("pag.", "página"), Abbreviation("núm.", "número"),
            Abbreviation("Núm.", "número"), Abbreviation("nº", "número"), Abbreviation("n.º", "número"),
            Abbreviation("Nº", "número"), Abbreviation("N.º", "número"),
            Abbreviation("cap.", "capítulo"), Abbreviation("Cap.", "capítulo"),
            Abbreviation("vol.", "volumen"), Abbreviation("Vol.", "volumen"),
            Abbreviation("aprox.", "aproximadamente"),
            Abbreviation("p. ej.", "por ejemplo"), Abbreviation("p.ej.", "por ejemplo"),
            Abbreviation("a. C.", "antes de Cristo"), Abbreviation("a.C.", "antes de Cristo"),
            Abbreviation("d. C.", "después de Cristo"), Abbreviation("d.C.", "después de Cristo"),
            Abbreviation("a. m.", "a eme"), Abbreviation("p. m.", "pe eme"),
            Abbreviation("EE. UU.", "Estados Unidos"), Abbreviation("EE.UU.", "Estados Unidos"),
            Abbreviation("EEUU", "Estados Unidos"),
            Abbreviation("Avda.", "avenida"), Abbreviation("avda.", "avenida"),
            Abbreviation("c/", "calle"), Abbreviation("C/", "calle"),
            Abbreviation("tel.", "teléfono"), Abbreviation("Tel.", "teléfono"),
            Abbreviation("ej.", "ejemplo"), Abbreviation("Ej.", "ejemplo"),
            Abbreviation("Sto.", "santo"), Abbreviation("Sta.", "santa"),
            Abbreviation("S. A.", "sociedad anónima"), Abbreviation("S.A.", "sociedad anónima"),
            Abbreviation("admón.", "administración"), Abbreviation("art.", "artículo"),
            Abbreviation("Art.", "artículo"), Abbreviation("fig.", "figura"), Abbreviation("Fig.", "figura"),
            Abbreviation("Prof.", "profesor"), Abbreviation("Profa.", "profesora"),
            Abbreviation("Lic.", "licenciado"), Abbreviation("Ing.", "ingeniero"),
            Abbreviation("Excmo.", "excelentísimo"), Abbreviation("Excma.", "excelentísima"),
            Abbreviation("Ilmo.", "ilustrísimo"), Abbreviation("Mr.", "míster"),
            Abbreviation("izq.", "izquierda"), Abbreviation("dcha.", "derecha"),
        ).sortedByDescending { it.key.length }.groupBy { it.key[0] }

        private val CURRENCY_SYMBOLS = mapOf('€' to "EUR", '$' to "USD", '£' to "GBP")

        private val CURRENCIES: Map<Char, Measure> = mapOf(
            '€' to Measure("euro", "euros", Gender.MASCULINE, "céntimo", "céntimos"),
            '$' to Measure("dólar", "dólares", Gender.MASCULINE, "centavo", "centavos"),
            '£' to Measure("libra", "libras", Gender.FEMININE, "penique", "peniques"),
        )

        private val UNITS: Map<String, Measure> = buildMap {
            val km = Measure("kilómetro", "kilómetros")
            put("km", km)
            put("m", Measure("metro", "metros"))
            put("cm", Measure("centímetro", "centímetros"))
            put("mm", Measure("milímetro", "milímetros"))
            put("kg", Measure("kilo", "kilos"))
            put("g", Measure("gramo", "gramos"))
            put("mg", Measure("miligramo", "miligramos"))
            put("t", Measure("tonelada", "toneladas", Gender.FEMININE))
            put("l", Measure("litro", "litros"))
            put("L", Measure("litro", "litros"))
            put("ml", Measure("mililitro", "mililitros"))
            put("cl", Measure("centilitro", "centilitros"))
            put("h", Measure("hora", "horas", Gender.FEMININE))
            put("min", Measure("minuto", "minutos"))
            put("s", Measure("segundo", "segundos"))
            put("seg", Measure("segundo", "segundos"))
            put("ha", Measure("hectárea", "hectáreas", Gender.FEMININE))
            put("km/h", Measure("kilómetro por hora", "kilómetros por hora"))
            put("m/s", Measure("metro por segundo", "metros por segundo"))
            for (k in listOf("m2", "m²")) put(k, Measure("metro cuadrado", "metros cuadrados"))
            for (k in listOf("km2", "km²")) put(k, Measure("kilómetro cuadrado", "kilómetros cuadrados"))
            for (k in listOf("cm2", "cm²")) put(k, Measure("centímetro cuadrado", "centímetros cuadrados"))
            for (k in listOf("m3", "m³")) put(k, Measure("metro cúbico", "metros cúbicos"))
            for (k in listOf("cm3", "cm³")) put(k, Measure("centímetro cúbico", "centímetros cúbicos"))
            for (k in listOf("ºC", "°C")) put(k, Measure("grado centígrado", "grados centígrados"))
            for (k in listOf("ºF", "°F")) put(k, Measure("grado Fahrenheit", "grados Fahrenheit"))
            put("°", Measure("grado", "grados"))
            put("KB", Measure("kilobyte", "kilobytes"))
            put("kB", Measure("kilobyte", "kilobytes"))
            put("MB", Measure("megabyte", "megabytes"))
            put("GB", Measure("gigabyte", "gigabytes"))
            put("TB", Measure("terabyte", "terabytes"))
            put("kW", Measure("kilovatio", "kilovatios"))
            put("kWh", Measure("kilovatio hora", "kilovatios hora"))
            put("W", Measure("vatio", "vatios"))
            put("V", Measure("voltio", "voltios"))
            put("Hz", Measure("hercio", "hercios"))
            put("MHz", Measure("megahercio", "megahercios"))
            put("GHz", Measure("gigahercio", "gigahercios"))
            put("EUR", CURRENCIES.getValue('€'))
            put("USD", CURRENCIES.getValue('$'))
        }

        private val FRACTION_NAMES = arrayOf(
            null, null, "medio", "tercio", "cuarto", "quinto", "sexto", "séptimo", "octavo",
            "noveno", "décimo",
        )

        private val SYMBOL_WORDS = mapOf(
            '&' to "y", '+' to "más", '=' to "igual a", '@' to "arroba", '%' to "por ciento",
            '‰' to "por mil", '×' to "por", '§' to "párrafo", '€' to "euros", '$' to "dólares",
            '£' to "libras", '<' to "menor que", '>' to "mayor que", '≤' to "menor o igual que",
            '≥' to "mayor o igual que", '÷' to "entre", '±' to "más menos",
        )

        private val SILENT = hashSetOf(
            '"', '\'', '«', '»', '“', '”', '‘', '’', '‹', '›', '„', '—', '–', '-', '‐', '‑',
            '*', '_', '#', '~', '^', '`', '\\', '/', '·', '•', '¶', '†', '‡', '°', 'º', 'ª',
            '{', '}', '©', '®', '™',
        )

        private val ROMAN_CONTEXT = hashSetOf(
            "siglo", "siglos", "capítulo", "capitulo", "capítulos", "tomo", "tomos", "volumen",
            "libro", "parte", "acto", "escena", "canto", "título", "titulo", "artículo",
            "articulo", "apartado", "sección", "seccion", "fase", "lección", "leccion", "tema",
            "anexo", "apéndice", "jornada", "cuaderno", "milenio", "dinastía", "legislatura",
            "olimpiada", "edición", "edicion",
        )

        /** Romanos que tambien son palabras corrientes. */
        private val AMBIGUOUS_ROMAN = hashSetOf("I", "MI", "DI", "VI", "LI", "MIX", "DC", "CD", "CV", "CL", "CC", "MC", "MD", "XL", "DIV", "MIL", "LID", "CID", "VID")

        private val ARTICLES = hashSetOf("el", "la", "los", "las")
        private val FEMININE_DETERMINERS = hashSetOf(
            "la", "las", "una", "unas", "esta", "estas", "esa", "esas", "aquella", "aquellas",
        )

        private val NAME_STOP = hashSetOf(
            "yo", "el", "la", "los", "las", "lo", "le", "les", "se", "me", "te", "nos", "os", "mi",
            "tu", "su", "sus", "que", "qué", "cuando", "donde", "pero", "por", "para", "con", "sin",
            "una", "uno", "unos", "unas", "este", "esta", "ese", "esa", "aquel", "aquella", "aquí",
            "allí", "ahora", "luego", "entonces", "después", "antes", "también", "tampoco", "así",
            "pues", "sí", "no", "ella", "ellos", "ellas", "usted", "ustedes", "nosotros",
            "vosotros", "hoy", "ayer", "mañana", "quien", "quién", "como", "cómo", "muy", "más",
            "menos", "del", "al", "ya", "ni", "hay", "era", "fue", "es", "son", "soy", "eres",
            "todo", "toda", "todos", "todas", "nada", "algo", "nadie", "alguien", "and", "the",
            "then", "but", "when", "and", "she", "they", "what", "dijo", "vio", "dije",
        )

        private val FEMININE_NAMES = hashSetOf(
            "isabel", "leonor", "beatriz", "inés", "mercedes", "carmen", "pilar", "consuelo",
            "dolores", "raquel", "ester", "rut", "abigail",
        )

        private val FUNCTION_WORDS = hashSetOf(
            "de", "del", "por", "y", "e", "o", "u", "a", "al", "en", "con", "sin", "que", "coma",
            "punto", "es", "son", "era", "fue", "más", "menos", "entre", "para", "como", "ni",
            "pero", "se", "si", "no", "sobre", "hasta", "desde", "tras", "contra", "según",
            "durante", "mediante", "ante", "bajo", "lo", "la", "le", "les", "los", "las", "un",
            "una", "me", "te", "nos", "os", "ya", "muy", "solo", "sólo", "cuando", "donde",
            "porque", "mientras", "aunque", "pues", "ha", "he", "han", "había", "habían", "x",
            "vs", "of", "and", "the", "to", "in", "or", "on", "at", "su", "sus", "mi", "tu",
        )

        private val MASC_EXCEPTIONS: Set<String> = hashSetOf(
            "día", "mapa", "problema", "programa", "tema", "sistema", "idioma", "planeta", "clima",
            "poema", "drama", "esquema", "dilema", "cometa", "sofá", "papá", "pijama", "tranvía",
            "aroma", "fantasma", "telegrama", "diploma", "emblema", "enigma", "dogma", "lema",
            "panorama", "prisma", "síntoma", "trauma", "guardia", "policía", "atleta", "artista",
            "ajedrez", "pez", "juez", "lápiz", "dólar",
        ).let { base -> base + base.map { it + "s" } + setOf("días", "lápices", "peces", "jueces", "dólares") }

        private val FEM_EXCEPTIONS: Set<String> = hashSetOf(
            "mano", "foto", "moto", "radio", "vez", "noche", "gente", "parte", "clase", "calle",
            "mujer", "flor", "luz", "imagen", "carne", "fuente", "llave", "nave", "nube", "suerte",
            "torre", "muerte", "sangre", "leche", "frase", "base", "red", "sed", "pared", "piel",
            "miel", "sal", "cárcel", "señal", "catedral", "razón", "canción", "nariz", "raíz",
            "cruz", "paz", "voz", "hoz", "tos", "col", "mies", "ley", "grey", "fe", "orden",
        ).let { base ->
            base + base.map { if (it.last() in "aeiou") it + "s" else if (it.endsWith("z")) it.dropLast(1) + "ces" else it + "es" } +
                setOf("veces", "imágenes", "leyes", "razones", "canciones")
        }

        private val FEM_SUFFIXES = arrayOf(
            "a", "as", "ción", "ciones", "sión", "siones", "dad", "dades", "tad", "tades", "tud",
            "tudes", "umbre", "umbres", "itis", "ez",
        )

        /** Genero probable de un sustantivo espanol. */
        internal fun nounGender(word: String): Gender {
            val l = word.lowercase()
            if (l in MASC_EXCEPTIONS) return Gender.MASCULINE
            if (l in FEM_EXCEPTIONS) return Gender.FEMININE
            for (suf in FEM_SUFFIXES) {
                if (l.endsWith(suf) && (suf != "ez" || l.length > 4)) return Gender.FEMININE
            }
            return Gender.MASCULINE
        }

        private fun articleGender(prev: String?): Gender =
            if (prev != null && prev.lowercase() in FEMININE_DETERMINERS) Gender.FEMININE else Gender.MASCULINE

        private fun isNameLike(w: String): Boolean {
            if (w.length < 3 || !w[0].isUpperCase()) return false
            for (k in 1 until w.length) if (!w[k].isLowerCase()) return false
            return w.lowercase() !in NAME_STOP
        }

        private fun isFeminineName(w: String): Boolean {
            val l = w.lowercase()
            return l in FEMININE_NAMES || (l.endsWith("a") && l != "borja" && l != "luca")
        }

        private val ROMAN_RE: Pattern = Pattern.compile("M{0,3}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3})")

        internal fun isRoman(w: String): Boolean {
            if (w.isEmpty() || w.length > 12) return false
            for (c in w) if (c !in "IVXLCDM") return false
            return ROMAN_RE.matcher(w).matches()
        }

        internal fun romanValue(w: String): Int {
            var total = 0
            var prev = 0
            for (k in w.length - 1 downTo 0) {
                val v = when (w[k]) {
                    'I' -> 1; 'V' -> 5; 'X' -> 10; 'L' -> 50; 'C' -> 100; 'D' -> 500; else -> 1000
                }
                if (v < prev) total -= v else {
                    total += v
                    prev = v
                }
            }
            return total
        }

        private const val VOWELS = "AEIOUÁÉÍÓÚÜ"
        private val ONSETS = hashSetOf("BL", "BR", "CL", "CR", "DR", "FL", "FR", "GL", "GR", "PL", "PR", "TR", "CH", "LL")
        private val CODAS = "LNRSDZXYJ"

        /** Heuristica: true si la sigla se puede leer como palabra (alterna vocales y consonantes). */
        internal fun pronounceable(w: String): Boolean {
            if (w.none { it in VOWELS }) return false
            var k = 0
            while (k < w.length && w[k] !in VOWELS) k++
            if (k > 2 || (k == 2 && w.substring(0, 2) !in ONSETS)) return false
            var run = 0
            for (c in w) {
                if (c in VOWELS) run = 0 else {
                    run++
                    if (run > 2) return false
                }
            }
            var t = 0
            var p = w.length - 1
            while (p >= 0 && w[p] !in VOWELS) {
                t++
                p--
            }
            if (t > 1) return false
            if (t == 1 && w.last() !in CODAS) return false
            return true
        }

        private fun isCapsSentence(s: String): Boolean {
            var words = 0
            var caps = 0
            var i = 0
            val n = s.length
            while (i < n) {
                if (s[i].isLetter()) {
                    var e = i
                    var upper = true
                    while (e < n && s[e].isLetter()) {
                        if (s[e].isLowerCase()) upper = false
                        e++
                    }
                    if (e - i >= 2) {
                        words++
                        if (upper) caps++
                    }
                    i = e
                } else {
                    i++
                }
            }
            return caps >= 3 && caps * 10 >= words * 6
        }

        private fun isMark(c: Char): Boolean {
            val t = Character.getType(c).toByte()
            return t == Character.NON_SPACING_MARK || t == Character.COMBINING_SPACING_MARK
        }

        private fun isApostrophe(c: Char) = c == '\'' || c == '’'
    }
}
