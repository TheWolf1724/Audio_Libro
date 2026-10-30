package com.lectorvoz.core.text

/** Genero gramatical para concordar numerales. */
enum class Gender { MASCULINE, FEMININE }

/** Conversion de numeros a palabras en espanol (escala larga: millon, billon, trillon). */
object SpanishNumbers {
    private val UNITS = arrayOf(
        "cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve",
        "diez", "once", "doce", "trece", "catorce", "quince", "dieciséis", "diecisiete",
        "dieciocho", "diecinueve", "veinte", "veintiuno", "veintidós", "veintitrés",
        "veinticuatro", "veinticinco", "veintiséis", "veintisiete", "veintiocho", "veintinueve",
    )
    private val TENS = arrayOf(
        "", "", "", "treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa",
    )
    private val HUNDREDS = arrayOf(
        "", "ciento", "doscientos", "trescientos", "cuatrocientos", "quinientos", "seiscientos",
        "setecientos", "ochocientos", "novecientos",
    )
    private const val MILLION = 1_000_000L
    private const val BILLION = 1_000_000_000_000L
    private const val TRILLION = 1_000_000_000_000_000_000L

    /**
     * Cardinal de [n] (n >= 0). [gender] concuerda unidades y centenas ("una", "doscientas");
     * [apocope] usa "un"/"veintiún" (delante de sustantivo masculino).
     */
    fun cardinal(n: Long, gender: Gender = Gender.MASCULINE, apocope: Boolean = false): String {
        require(n >= 0) { "negativo: $n" }
        if (n == 0L) return "cero"
        val sb = StringBuilder()
        appendCardinal(sb, n, gender, apocope)
        return sb.toString()
    }

    private fun appendCardinal(sb: StringBuilder, n: Long, g: Gender, apocope: Boolean) {
        var rest = n
        for ((scale, sing, plural) in SCALES) {
            if (rest >= scale) {
                val q = rest / scale
                rest %= scale
                if (q == 1L) {
                    sb.append("un ").append(sing)
                } else {
                    appendBelowMillion(sb, q, Gender.MASCULINE, true)
                    sb.append(' ').append(plural)
                }
                if (rest > 0) sb.append(' ')
            }
        }
        if (rest > 0) appendBelowMillion(sb, rest, g, apocope)
    }

    private val SCALES = listOf(
        Triple(TRILLION, "trillón", "trillones"),
        Triple(BILLION, "billón", "billones"),
        Triple(MILLION, "millón", "millones"),
    )

    private fun appendBelowMillion(sb: StringBuilder, n: Long, g: Gender, apocope: Boolean) {
        val th = (n / 1000).toInt()
        val r = (n % 1000).toInt()
        if (th > 0) {
            if (th != 1) {
                appendBelowThousand(sb, th, g, true)
                sb.append(' ')
            }
            sb.append("mil")
            if (r > 0) sb.append(' ')
        }
        if (r > 0) appendBelowThousand(sb, r, g, apocope)
    }

    private fun appendBelowThousand(sb: StringBuilder, n: Int, g: Gender, apocope: Boolean) {
        val h = n / 100
        val r = n % 100
        if (h > 0) {
            if (h == 1 && r == 0) {
                sb.append("cien")
            } else {
                val word = HUNDREDS[h]
                if (g == Gender.FEMININE && h > 1) sb.append(word, 0, word.length - 2).append("as")
                else sb.append(word)
            }
            if (r > 0) sb.append(' ')
        }
        if (r > 0) appendBelowHundred(sb, r, g, apocope)
    }

    private fun appendBelowHundred(sb: StringBuilder, n: Int, g: Gender, apocope: Boolean) {
        if (n < 30) {
            if (n == 1 || n == 21) {
                val base = if (n == 1) "un" else "veintiun"
                when {
                    g == Gender.FEMININE -> sb.append(base).append('a')
                    apocope -> sb.append(if (n == 1) "un" else "veintiún")
                    else -> sb.append(base).append('o')
                }
            } else {
                sb.append(UNITS[n])
            }
            return
        }
        sb.append(TENS[n / 10])
        val u = n % 10
        if (u > 0) {
            sb.append(" y ")
            appendBelowHundred(sb, u, g, apocope)
        }
    }

    /** Lee una cadena de digitos uno a uno ("0", "5" -> "cero cinco"). */
    fun digits(s: CharSequence): String {
        val sb = StringBuilder()
        for (c in s) {
            val d = Character.digit(c, 10)
            if (d < 0) continue
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(UNITS[d])
        }
        return sb.toString()
    }

    private val ORD_UNITS = arrayOf(
        "", "primero", "segundo", "tercero", "cuarto", "quinto", "sexto", "séptimo", "octavo",
        "noveno",
    )
    private val ORD_TENS = arrayOf(
        "", "décimo", "vigésimo", "trigésimo", "cuadragésimo", "quincuagésimo", "sexagésimo",
        "septuagésimo", "octogésimo", "nonagésimo",
    )

    /**
     * Ordinal de [n] (1..199 con palabras; mayores, cardinal). [apocope] da "primer"/"tercer"
     * en masculino singular; [plural] anade la -s.
     */
    fun ordinal(n: Int, gender: Gender = Gender.MASCULINE, apocope: Boolean = false, plural: Boolean = false): String {
        if (n <= 0 || n >= 200) return cardinal(n.toLong().coerceAtLeast(0), gender)
        val parts = ArrayList<String>(3)
        var r = n
        if (r >= 100) {
            parts.add("centésimo")
            r -= 100
        }
        when (r) {
            0 -> {}
            11 -> parts.add("undécimo")
            12 -> parts.add("duodécimo")
            in 13..19 -> parts.add("decimo" + ORD_UNITS[r - 10])
            else -> {
                if (r >= 10) parts.add(ORD_TENS[r / 10])
                if (r % 10 > 0) parts.add(ORD_UNITS[r % 10])
            }
        }
        val sb = StringBuilder()
        for ((i, p) in parts.withIndex()) {
            if (i > 0) sb.append(' ')
            val last = i == parts.size - 1
            var w = if (gender == Gender.FEMININE) p.dropLast(1) + "a" else p
            if (last && gender == Gender.MASCULINE && apocope && !plural && (w.endsWith("primero") || w.endsWith("tercero"))) {
                w = w.dropLast(1)
            }
            if (plural) w += "s"
            sb.append(w)
        }
        return sb.toString()
    }

    private val MONTHS = arrayOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre",
        "octubre", "noviembre", "diciembre",
    )

    /** Nombre del mes 1..12. */
    fun month(m: Int): String = MONTHS[m - 1]
}
