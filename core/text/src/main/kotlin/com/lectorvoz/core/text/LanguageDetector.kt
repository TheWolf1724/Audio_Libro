package com.lectorvoz.core.text

import com.lectorvoz.core.model.ReadingLanguage

/**
 * Idioma probable de un texto. [language] es "es", "en" o "" (sin pruebas); [confidence] va
 * de 0 (nada claro) a 1; [evidence] es la puntuacion total reunida (crece con la longitud) y
 * [margin] la diferencia de puntuacion a favor de [language].
 */
data class LanguageGuess(val language: String, val confidence: Float, val evidence: Float, val margin: Float = 0f)

/**
 * Detector ligero espanol/ingles, sin dependencias ni modelos: suma pruebas de cada idioma.
 *
 * - Palabras funcionales frecuentes (articulos, preposiciones, pronombres, auxiliares y verbos
 *   de habla) de cada idioma; las ambiguas ("a", "no", "me", "he", "son") no cuentan.
 * - Signos ¿ ¡, tildes y ñ: prueba fuerte de espanol. Contracciones inglesas ('s, n't, 're,
 *   'll, 've, 'm, 'd): prueba fuerte de ingles.
 * - Bigramas y terminaciones de letras: "th", "sh", "wh", "ck", "w", "k", -ing, -ly (ingles);
 *   -ción, -mente, -ado/-ido, -aba, -os/-as (espanol).
 * - Las palabras con mayuscula que no abren la frase se ignoran: los nombres propios
 *   ("John Smith", "Nueva York") no cambian el idioma de la frase.
 */
object LanguageDetector {
    /** Detecta el idioma de [text] en [start, end). */
    fun detect(text: CharSequence, start: Int = 0, end: Int = text.length): LanguageGuess {
        var es = 0f
        var en = 0f
        var i = start
        var sentenceStart = true
        var strongEs = 0f
        while (i < end) {
            val c = text[i]
            if (c == '¿' || c == '¡') {
                strongEs += 3f
                sentenceStart = true
                i++
                continue
            }
            if (!c.isLetter()) {
                if (c == '.' || c == '!' || c == '?' || c == '—' || c == '«' || c == '“' || c == '"' || c == ':') sentenceStart = true
                i++
                continue
            }
            var e = i
            var apos = -1
            while (e < end) {
                val d = text[e]
                if (d.isLetter()) {
                    e++
                } else if ((d == '\'' || d == '’') && e + 1 < end && text[e + 1].isLetter() && e > i) {
                    if (apos < 0) apos = e
                    e++
                } else {
                    break
                }
            }
            val first = sentenceStart
            sentenceStart = false
            val wl = e - i
            val cap = text[i].isUpperCase()
            // Nombres propios y siglas dentro de la frase: no cuentan ("I" si, es ingles).
            if (wl == 1 && text[i] == 'I') {
                en += 1f
                i = e
                continue
            }
            val w = text.subSequence(i, e).toString().lowercase()
            if (cap && !first) {
                // Nombres propios: no cuentan; salvo tratamientos y meses/dias ingleses (en
                // espanol los meses y dias van en minuscula).
                EN_CAPITALIZED[w]?.let { en += it }
                i = e
                continue
            }
            if (cap) EN_CAPITALIZED[w]?.let { en += it }
            if (apos >= 0) {
                val suf = w.substring(apos - i + 1)
                if (suf in EN_CONTRACTIONS) en += 2f
            } else {
                ES_WORDS[w]?.let { es += it }
                EN_WORDS[w]?.let { en += it }
            }
            var accents = 0
            for (k in w.indices) {
                val ch = w[k]
                if (ch == 'ñ') accents += 2
                else if (ch in "áéíóúü") accents++
            }
            if (accents > 0) strongEs += minOf(accents, 2) * 1.5f
            if (wl >= 3) {
                if (w.endsWith("ing") && wl >= 5) en += 0.6f
                if (w.endsWith("ly") && wl >= 5) en += 0.4f
                if (w.endsWith("ed") && wl >= 5 && w !in ES_ED) en += 0.4f
                if (w.endsWith("y") && wl >= 4 && w !in ES_Y) en += 0.3f
                if (w.endsWith("ción") || w.endsWith("mente") || w.endsWith("ado") || w.endsWith("ido") ||
                    w.endsWith("aba") || w.endsWith("ada") || w.endsWith("idad")
                ) {
                    es += 0.6f
                } else if ((w.endsWith("os") || w.endsWith("as")) && wl >= 4) {
                    es += 0.3f
                }
                if ("th" in w || "sh" in w || "wh" in w || "ck" in w || "ght" in w || "ee" in w || "oo" in w) en += 0.5f
                if ('w' in w || 'k' in w) en += 0.3f
                if ("ll" in w && w.startsWith("ll")) es += 0.3f
                if ("rr" in w || "qu" in w && !w.startsWith("qu")) es += 0.2f
            }
            i = e
        }
        es += minOf(strongEs, 8f)
        val total = es + en
        if (total <= 0f) return LanguageGuess("", 0f, 0f, 0f)
        val lang = if (es >= en) "es" else "en"
        val margin = kotlin.math.abs(es - en)
        val conf = (margin / total) * minOf(1f, total / 3f)
        return LanguageGuess(lang, conf, total, margin)
    }

    private val EN_CONTRACTIONS = hashSetOf("s", "t", "re", "ll", "ve", "m", "d")
    private val ES_ED = hashSetOf("usted", "ustedes", "pared", "merced", "césped", "huésped", "abad")
    private val ES_Y = hashSetOf("estoy", "jersey", "convoy", "virrey", "muy")

    /** Palabras inglesas que cuentan aunque vayan con mayuscula dentro de la frase. */
    private val EN_CAPITALIZED: Map<String, Float> = HashMap<String, Float>().apply {
        for (w in listOf("mr", "mrs", "ms")) put(w, 1.5f)
        for (w in listOf(
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "january",
            "february", "march", "april", "june", "july", "august", "september", "october",
            "november", "december",
        )) put(w, 1f)
    }

    private fun weights(w: Float, vararg words: String): Map<String, Float> = words.associateWith { w }

    private val ES_WORDS: Map<String, Float> = HashMap<String, Float>().apply {
        putAll(
            weights(
                1f, "de", "la", "que", "el", "en", "y", "los", "se", "del", "las", "un", "por", "con",
                "una", "su", "para", "es", "al", "lo", "como", "más", "pero", "sus", "le", "ya", "o",
                "fue", "este", "ha", "sí", "porque", "esta", "entre", "cuando", "muy", "sin", "sobre",
                "también", "hasta", "hay", "donde", "quien", "desde", "todo", "nos", "durante", "todos",
                "uno", "les", "ni", "contra", "otros", "ese", "eso", "ante", "ellos", "e", "esto", "mí",
                "antes", "algunos", "qué", "unos", "yo", "otro", "otras", "otra", "él", "tanto", "esa",
                "estos", "mucho", "quienes", "nada", "muchos", "cual", "poco", "ella", "estar", "estas",
                "algunas", "algo", "nosotros", "mi", "mis", "tú", "te", "ti", "tu", "tus", "ellas",
                "vosotros", "os", "esos", "esas", "estoy", "estás", "está", "estamos", "están", "era",
                "eran", "fui", "fueron", "ser", "soy", "eres", "somos", "había", "habían", "tiene",
                "tienen", "tengo", "pues", "aquí", "allí", "ahora", "entonces", "después", "siempre",
                "nunca", "así", "aunque", "mientras", "luego", "hacia", "según", "tras", "cómo", "dónde",
                "cuándo", "quién", "cuál", "usted", "ustedes", "señor", "señora", "sea", "va",
                "voy", "vamos", "hace", "hizo", "puede", "creo", "sabe", "bien", "dos", "tres", "año",
                "años", "vez", "día", "casa", "hombre", "mujer", "gracias", "hola", "sí", "dentro",
                "fuera", "cada", "aquel", "aquella", "mismo", "misma", "ese", "del", "al", "unas", "le",
            ),
        )
        putAll(
            weights(
                1.5f, "dijo", "dice", "preguntó", "respondió", "contestó", "exclamó", "gritó", "murmuró",
                "susurró", "añadió", "replicó", "pensó", "estaba", "estaban", "tenía", "hacía", "podía",
                "quería", "sabía", "decía",
            ),
        )
    }

    private val EN_WORDS: Map<String, Float> = HashMap<String, Float>().apply {
        putAll(
            weights(
                1f, "the", "of", "and", "to", "in", "is", "it", "you", "that", "was", "for", "on", "are",
                "with", "as", "his", "they", "be", "at", "have", "this", "from", "or", "had", "by", "but",
                "not", "what", "all", "were", "we", "when", "your", "can", "there", "an", "which", "she",
                "do", "how", "their", "if", "will", "up", "other", "about", "out", "many", "then", "them",
                "these", "so", "some", "her", "would", "make", "like", "him", "into", "time", "look",
                "more", "could", "people", "my", "than", "first", "been", "who", "its", "now", "did",
                "get", "made", "may", "our", "us", "any", "just", "know", "should", "very", "where",
                "after", "before", "because", "does", "here", "why", "over", "only", "well", "also",
                "back", "even", "still", "never", "always", "too", "said", "says", "asked", "replied",
                "went", "came", "got", "being", "every", "through", "while", "those", "it's", "don't",
                "i'm", "he's", "she's", "can't", "won't", "man", "woman", "day", "night", "house",
                "something", "nothing", "think", "thought", "little", "old", "new", "good", "way",
                "one", "two", "three", "yes",
            ),
        )
        putAll(weights(2f, "hello", "thanks", "please", "sorry", "okay", "the", "with", "would", "should", "could"))
        putAll(weights(0.5f, "pm", "am"))
    }
}

/**
 * Asigna idioma a frases consecutivas: forzado si [forced] no es nulo; si no, detecta y, si
 * la frase es corta o dudosa, hereda el idioma de la frase anterior (al principio, [hint]:
 * el idioma del libro o capitulo, o espanol). Salir del idioma base exige pruebas claras;
 * volver a el basta con alguna prueba (un inciso "dijo" tras una cita inglesa vuelve al
 * espanol).
 */
internal class LanguageTracker(hint: String?, private val forced: String?) {
    /** Idioma base del libro o capitulo. */
    val base: String = normalizeTag(hint) ?: "es"

    var current: String = base
        private set

    /** Idioma de la frase [text] en [start, end). */
    fun next(text: CharSequence, start: Int = 0, end: Int = text.length): String {
        if (forced != null) return forced
        val g = LanguageDetector.detect(text, start, end)
        if (g.language.isEmpty() || g.language == current) return current
        val margin = g.margin
        val ratio = if (g.evidence > 0f) margin / g.evidence else 0f
        val switch = if (g.language == base) {
            margin >= 1f && ratio >= 0.5f
        } else {
            margin >= 2f && ratio >= 0.6f
        }
        if (switch) current = g.language
        return current
    }

    companion object {
        /** "es"/"en" a partir de una etiqueta BCP-47; null si no es ninguno de los dos. */
        fun normalizeTag(tag: String?): String? {
            val t = tag?.trim()?.lowercase() ?: return null
            return when {
                t == "en" || t.startsWith("en-") || t.startsWith("en_") -> "en"
                t == "es" || t.startsWith("es-") || t.startsWith("es_") -> "es"
                else -> null
            }
        }

        /** Idioma forzado por el ajuste, o null en automatico. */
        fun forcedOf(r: ReadingLanguage): String? = when (r) {
            ReadingLanguage.AUTO -> null
            ReadingLanguage.SPANISH -> "es"
            ReadingLanguage.ENGLISH -> "en"
        }
    }
}
