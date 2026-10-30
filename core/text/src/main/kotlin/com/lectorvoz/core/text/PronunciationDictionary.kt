package com.lectorvoz.core.text

import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Regla del diccionario de pronunciacion del usuario.
 *
 * @param pattern texto literal o expresion regular (si [isRegex]).
 * @param replacement sustitucion; en RegEx admite `$1`..`$9` y `${nombre}`.
 * @param caseSensitive distingue mayusculas.
 * @param wholeWord solo para texto literal: exige que no haya letras o digitos pegados.
 */
data class PronunciationRule(
    val pattern: String,
    val replacement: String,
    val isRegex: Boolean = false,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = true,
    val enabled: Boolean = true,
)

/**
 * Diccionario de pronunciacion: aplica las reglas en orden, antes de normalizar, y conserva
 * el mapa de indices al texto original. Las reglas RegEx invalidas se ignoran y se listan en
 * [invalidRules].
 */
class PronunciationDictionary(rules: List<PronunciationRule>) {
    private val compiled: List<Pair<Pattern, PronunciationRule>>

    /** Reglas cuya expresion no compila (para avisar en la interfaz). */
    val invalidRules: List<PronunciationRule>

    init {
        val ok = ArrayList<Pair<Pattern, PronunciationRule>>()
        val bad = ArrayList<PronunciationRule>()
        for (r in rules) {
            if (!r.enabled || r.pattern.isEmpty()) continue
            val flags = if (r.caseSensitive) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            val regex = when {
                r.isRegex -> r.pattern
                r.wholeWord -> "(?<![\\p{L}\\p{N}])" + Pattern.quote(r.pattern) + "(?![\\p{L}\\p{N}])"
                else -> Pattern.quote(r.pattern)
            }
            try {
                ok.add(Pattern.compile(regex, flags) to r)
            } catch (e: PatternSyntaxException) {
                bad.add(r)
            }
        }
        compiled = ok
        invalidRules = bad
    }

    val isEmpty: Boolean get() = compiled.isEmpty()

    /** Aplica las reglas a [text]; el mapa apunta a posiciones de [text]. */
    fun apply(text: String): MappedText {
        var current = MappedText.identity(text)
        for ((pattern, rule) in compiled) {
            val m = pattern.matcher(current.text)
            if (!m.find()) continue
            val src = current.text
            val b = MappedTextBuilderRaw(src.length + 16)
            var last = 0
            do {
                val s = m.start()
                val e = m.end()
                if (e == s) continue // coincidencias vacias: se ignoran
                for (k in last until s) b.push(src[k], current.toSource[k])
                val repl = if (rule.isRegex) expand(m, rule.replacement) else rule.replacement
                val a = current.toSource[s]
                val z = current.toSource[e - 1] + 1
                b.spread(repl, a, z)
                last = e
            } while (m.find())
            for (k in last until src.length) b.push(src[k], current.toSource[k])
            current = b.build()
        }
        return current
    }

    /** Expande `$n`, `${nombre}` y `\$` de una sustitucion RegEx. */
    private fun expand(m: Matcher, repl: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < repl.length) {
            val c = repl[i]
            if (c == '\\' && i + 1 < repl.length) {
                sb.append(repl[i + 1])
                i += 2
            } else if (c == '$' && i + 1 < repl.length && repl[i + 1].isDigit()) {
                val g = repl[i + 1] - '0'
                if (g <= m.groupCount()) sb.append(m.group(g) ?: "")
                i += 2
            } else if (c == '$' && i + 1 < repl.length && repl[i + 1] == '{') {
                val close = repl.indexOf('}', i + 2)
                if (close < 0) {
                    sb.append(c)
                    i++
                } else {
                    val name = repl.substring(i + 2, close)
                    val v = try {
                        m.group(name)
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                    sb.append(v ?: "")
                    i = close + 1
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** Constructor de texto sin colapsar espacios (el diccionario respeta el texto). */
    private class MappedTextBuilderRaw(capacity: Int) {
        private val sb = StringBuilder(capacity)
        private var map = IntArray(capacity)

        fun push(c: Char, src: Int) {
            if (sb.length == map.size) map = map.copyOf(map.size * 2 + 16)
            map[sb.length] = src
            sb.append(c)
        }

        fun spread(str: String, a: Int, z: Int) {
            val span = (z - a).coerceAtLeast(1)
            for (i in str.indices) push(str[i], a + (i.toLong() * span / str.length).toInt())
        }

        fun build() = MappedText(sb.toString(), map.copyOf(sb.length))
    }

    companion object {
        /** Diccionario sin reglas. */
        val EMPTY = PronunciationDictionary(emptyList())
    }
}
