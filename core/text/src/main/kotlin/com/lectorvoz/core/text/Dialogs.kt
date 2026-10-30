package com.lectorvoz.core.text

import com.lectorvoz.core.model.VoiceRole

/** Tramo [start, end) de un parrafo dicho por [role]. */
internal class RoleSpan(val start: Int, val end: Int, val role: VoiceRole)

/**
 * Separa un parrafo en tramos de narrador y de personaje para poder usar otra voz.
 *
 * - Raya (—, ―, o – al inicio del parrafo): si el parrafo empieza por raya, habla el
 *   personaje; cada raya posterior que abre un inciso ("palabra —dijo") pasa al narrador y la
 *   raya que lo cierra ("—dijo—." o "—dijo—, y") devuelve la palabra al personaje. Tambien
 *   "Martin dijo: —Ven." abre dialogo tras dos puntos.
 * - Comillas (« », “ ”, " "): el texto citado es dialogo si termina en puntuacion dentro de
 *   las comillas, si va pegado a un verbo de habla ("«Ven», dijo"; "dijo: «Ven»") o si
 *   ocupa el inicio del parrafo con 3 o mas palabras. Una cita corta o un titulo
 *   ("la palabra «hola»", «Cien años de soledad») sigue siendo narrador.
 *
 * Los signos (raya y comillas) quedan dentro de los tramos; el normalizador no los pronuncia.
 */
internal object Dialogs {
    private const val RAYA = '—'

    private fun isRaya(c: Char) = c == RAYA || c == '―'

    fun split(text: CharSequence, ps: Int, pe: Int): List<RoleSpan> {
        var p = ps
        while (p < pe && text[p].isWhitespace()) p++
        if (p >= pe) return emptyList()
        val first = text[p]
        if (isRaya(first) || first == '–') return rayaDialog(text, p, pe, first, VoiceRole.DIALOG, p)
        // Narrador; "dijo: —Ven aqui." abre dialogo con raya tras dos puntos.
        var k = p
        while (k < pe) {
            if (isRaya(text[k]) && k > p + 1) {
                var b = k - 1
                while (b > p && text[b].isWhitespace()) b--
                if (text[b] == ':' && k + 1 < pe && !text[k + 1].isWhitespace()) {
                    val out = ArrayList<RoleSpan>()
                    out.addAll(quotes(text, p, k))
                    out.addAll(rayaDialog(text, k, pe, text[k], VoiceRole.DIALOG, k))
                    return out
                }
            }
            k++
        }
        return quotes(text, p, pe)
    }

    /** Dialogo con raya desde [start] (que es una raya de apertura). */
    private fun rayaDialog(text: CharSequence, start: Int, pe: Int, dash: Char, initial: VoiceRole, segStart0: Int): List<RoleSpan> {
        val out = ArrayList<RoleSpan>()
        var role = initial
        var segStart = segStart0
        var i = start + 1
        val dashes = if (dash == '–') "–" else "—―"
        while (i < pe) {
            val c = text[i]
            if (c in dashes) {
                if (role == VoiceRole.DIALOG) {
                    // Apertura de inciso: espacio antes y texto pegado despues. Tras fin de
                    // frase y con mayuscula es otro turno de dialogo ("—¿Vienes? —Sí.").
                    if (text[i - 1].isWhitespace() && i + 1 < pe && !text[i + 1].isWhitespace() &&
                        !newTurn(text, segStart, i, pe)
                    ) {
                        add(out, text, segStart, i, role)
                        segStart = i
                        role = VoiceRole.NARRATOR
                    }
                } else if (text[i - 1].isWhitespace()) {
                    // Nuevo turno tras fin de frase: "—preguntó. —Las tres."
                    var b = i - 1
                    while (b > segStart && text[b].isWhitespace()) b--
                    if (text[b] in ".?!…" && i + 1 < pe && !text[i + 1].isWhitespace()) {
                        add(out, text, segStart, i, role)
                        segStart = i
                        role = VoiceRole.DIALOG
                    }
                } else {
                    // Cierre del inciso: la puntuacion pegada se queda con el narrador.
                    var j = i + 1
                    while (j < pe && text[j] in ".,;:") j++
                    add(out, text, segStart, j, role)
                    segStart = j
                    role = VoiceRole.DIALOG
                    i = j
                    continue
                }
            }
            i++
        }
        add(out, text, segStart, pe, role)
        return out
    }

    /** true si la raya en [i] abre un turno nuevo: fin de frase antes y mayuscula despues. */
    private fun newTurn(text: CharSequence, segStart: Int, i: Int, pe: Int): Boolean {
        var b = i - 1
        while (b > segStart && text[b].isWhitespace()) b--
        if (text[b] !in ".?!…") return false
        var q = i + 1
        while (q < pe && !text[q].isLetterOrDigit()) q++
        return q < pe && text[q].isUpperCase()
    }

    /** Narrador con posibles citas entre comillas que son dialogo. */
    private fun quotes(text: CharSequence, ps: Int, pe: Int): List<RoleSpan> {
        val out = ArrayList<RoleSpan>()
        var segStart = ps
        var i = ps
        val closes = matchQuotes(text, ps, pe)
        while (i < pe) {
            val c = text[i]
            if (c != '«' && c != '“' && c != '"') {
                i++
                continue
            }
            val end = closes[i - ps]
            if (end < 0) {
                i++
                continue
            }
            // Puntuacion pegada tras la comilla de cierre: con el dialogo.
            var after = end + 1
            while (after < pe && text[after] in ",.;:") after++
            if (isDialogQuote(text, ps, i, end, after, pe)) {
                add(out, text, segStart, i, VoiceRole.NARRATOR)
                add(out, text, i, after, VoiceRole.DIALOG)
                segStart = after
            }
            i = end + 1
        }
        add(out, text, segStart, pe, VoiceRole.NARRATOR)
        return out
    }

    /**
     * Cierre de cada comilla de apertura de [ps, pe) en una sola pasada (indice relativo a
     * [ps]; -1 si no se cierra): « » y “ ” con anidamiento (pila por tipo), '"' con la
     * siguiente '"'. Lineal aunque haya muchas comillas sin cerrar.
     */
    private fun matchQuotes(text: CharSequence, ps: Int, pe: Int): IntArray {
        val closes = IntArray(pe - ps) { -1 }
        val guillemets = IntStack()
        val curly = IntStack()
        var lastStraight = -1
        for (k in ps until pe) {
            when (text[k]) {
                '«' -> guillemets.push(k)
                '»' -> if (guillemets.isNotEmpty()) closes[guillemets.pop() - ps] = k
                '“' -> curly.push(k)
                '”' -> if (curly.isNotEmpty()) closes[curly.pop() - ps] = k
                '"' -> {
                    if (lastStraight >= 0) closes[lastStraight - ps] = k
                    lastStraight = k
                }
            }
        }
        return closes
    }

    /** Pila de enteros sin cajas. */
    private class IntStack {
        private var data = IntArray(8)
        private var size = 0
        fun push(v: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }
        fun pop(): Int = data[--size]
        fun isNotEmpty() = size > 0
    }

    private fun isDialogQuote(text: CharSequence, ps: Int, open: Int, close: Int, after: Int, pe: Int): Boolean {
        // Ultimo caracter significativo dentro de las comillas.
        var k = close - 1
        while (k > open && text[k].isWhitespace()) k--
        if (k <= open) return false
        if (text[k] in ".,!?…;") return true
        // Verbo de habla justo despues ("», dijo") o antes ("dijo: «").
        if (startsWithSpeechVerb(text, after, pe)) return true
        var b = open - 1
        while (b >= ps && text[b].isWhitespace()) b--
        if (b >= ps && text[b] == ':') return true
        if (b >= ps && text[b] == ',') {
            var ws = b
            while (ws > ps && text[ws - 1].isLetter()) ws--
            if (ws < b && text.subSequence(ws, b).toString().lowercase() in SPEECH_VERBS) return true
        }
        // Al inicio del parrafo y con 3 o mas palabras.
        var q = open - 1
        while (q >= ps && text[q].isWhitespace()) q--
        if (q < ps && words(text, open + 1, close) >= 3) return true
        return false
    }

    private fun startsWithSpeechVerb(text: CharSequence, from: Int, pe: Int): Boolean {
        var k = from
        while (k < pe && text[k].isWhitespace()) k++
        val s = k
        while (k < pe && text[k].isLetter()) k++
        return k > s && text.subSequence(s, k).toString().lowercase() in SPEECH_VERBS
    }

    private fun words(text: CharSequence, s: Int, e: Int): Int {
        var n = 0
        var inWord = false
        for (k in s until e) {
            val l = text[k].isLetterOrDigit()
            if (l && !inWord) n++
            inWord = l
        }
        return n
    }

    private fun add(out: MutableList<RoleSpan>, text: CharSequence, s0: Int, e0: Int, role: VoiceRole) {
        var s = s0
        var e = e0
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        if (s >= e) return
        if (!SentenceSegmenter.hasReadable(text, s, e)) {
            // Signos sueltos: se pegan al tramo anterior para no perder rango.
            if (out.isNotEmpty()) {
                val last = out.removeAt(out.size - 1)
                out.add(RoleSpan(last.start, e, last.role))
            }
            return
        }
        if (out.isNotEmpty() && out.last().role == role) {
            val last = out.removeAt(out.size - 1)
            out.add(RoleSpan(last.start, e, role))
            return
        }
        out.add(RoleSpan(s, e, role))
    }

    /** Verbos de habla frecuentes (es/en) para reconocer incisos y citas. */
    val SPEECH_VERBS: Set<String> = hashSetOf(
        "dijo", "dije", "dice", "digo", "decía", "preguntó", "pregunté", "pregunta", "respondió",
        "respondí", "responde", "contestó", "contesté", "exclamó", "gritó", "grité", "susurró",
        "murmuró", "añadió", "replicó", "insistió", "comentó", "explicó", "afirmó", "repitió",
        "suspiró", "musitó", "concluyó", "advirtió", "ordenó", "pidió", "rogó", "señaló", "pensó",
        "said", "says", "asked", "asks", "replied", "answered", "cried", "shouted", "whispered",
        "muttered", "added", "exclaimed", "called", "told", "thought", "yelled", "continued",
    )
}
