package com.lectorvoz.core.text

/**
 * Pagina de PDF preparada para la voz (ver [PdfPageFlow.flow]).
 *
 * @property text texto fluido: sin cabeceras ni pies, con los saltos de linea de dentro de un
 *   parrafo convertidos en espacio, los guiones de fin de linea quitados ("pala-\nbra" ->
 *   "palabra") y un unico '\n' entre parrafos.
 * @property toOriginal `toOriginal[i]` es el indice en la pagina original del caracter `i`
 *   de [text] (monotono no decreciente).
 * @property excluded rangos [start, end) de la pagina original que son cabecera, pie o
 *   numero de pagina (sin el salto de linea).
 * @property sameLength texto de IGUAL longitud que la pagina original, para contratos que
 *   solo admiten rangos excluidos (`PageTextCleaner`): los caracteres excluidos pasan a ' ',
 *   los saltos de linea internos de parrafo a ' ', y en un guion de fin de linea el guion
 *   pasa a U+00AD (guion blando) y el salto y los espacios que lo siguen a U+200B (espacio
 *   de ancho cero); ambos son invisibles para el normalizador, asi que "pala-\nbra" se lee
 *   "palabra". Los indices de este texto son los de la pagina original.
 */
class FlowedPage(
    val text: String,
    val toOriginal: IntArray,
    val excluded: List<TextSpan>,
    val sameLength: String,
) {
    override fun toString(): String = "FlowedPage($text)"
}

/**
 * Prepara el texto de una pagina de PDF (con un '\n' por linea impresa, como lo da pdfium)
 * para leerlo en voz alta. Arregla que cada linea del PDF fuese una frase: las lineas de un
 * mismo parrafo se unen y solo queda un corte entre parrafos.
 *
 * Una linea de las [zoneLines] primeras o ultimas lineas no vacias es cabecera o pie si es un
 * numero de pagina ("12", "- 12 -", "Pagina 12 de 300", "xii"; un romano en mayusculas como
 * "II" no, porque suele ser un titulo) o si su firma (minusculas, con numeros y romanos como
 * '#') aparece en la misma zona de TODAS las paginas vecinas que se den (anterior y
 * siguiente): coincidir con una sola no basta ("Poema 12" en una pagina suelta).
 *
 * Un salto de linea es fin de parrafo si hay una linea en blanco, si la linea siguiente
 * empieza por raya o vineta, o si la anterior termina en puntuacion de cierre y es corta
 * (menos del 80 % de la longitud tipica) o la siguiente va sangrada. Un guion al final de
 * linea seguido de minuscula une la palabra.
 *
 * Funcion pura y sin estado.
 */
object PdfPageFlow {
    private class Line(val start: Int, val end: Int) {
        var contentStart = start
        var contentEnd = end
        val blank: Boolean get() = contentStart >= contentEnd
    }

    /**
     * Prepara [page] usando [previous] y [next] (paginas vecinas, si existen) para reconocer
     * cabeceras y pies repetidos.
     */
    fun flow(page: String, previous: String? = null, next: String? = null, zoneLines: Int = 2): FlowedPage {
        val lines = splitLines(page)
        val topSigs = ArrayList<Set<String>>()
        val bottomSigs = ArrayList<Set<String>>()
        for (nb in listOfNotNull(previous, next)) {
            val nl = splitLines(nb)
            topSigs.add(zone(nl, true, zoneLines).map { PdfHeaderFooterFilter.signature(nb.substring(it.contentStart, it.contentEnd)) }.toSet())
            bottomSigs.add(zone(nl, false, zoneLines).map { PdfHeaderFooterFilter.signature(nb.substring(it.contentStart, it.contentEnd)) }.toSet())
        }
        val excluded = HashSet<Line>()
        markEdge(page, zone(lines, true, zoneLines), topSigs, excluded)
        markEdge(page, zone(lines, false, zoneLines), bottomSigs, excluded)

        val same = page.toCharArray()
        for (l in excluded) for (k in l.start until l.end) if (!page[k].isWhitespace()) same[k] = ' '

        val kept = lines.filter { it !in excluded }
        val lengths = kept.filter { !it.blank }.map { it.contentEnd - it.contentStart }.sorted()
        val typical = if (lengths.isEmpty()) 0 else lengths[(lengths.size * 3) / 4]

        val sb = StringBuilder(page.length)
        var map = IntArray(page.length + 1)
        fun push(c: Char, src: Int) {
            if (sb.length == map.size) map = map.copyOf(map.size * 2)
            map[sb.length] = src
            sb.append(c)
        }

        var prev: Line? = null
        var blankBetween = false
        for (l in kept) {
            if (l.blank) {
                if (prev != null) blankBetween = true
                continue
            }
            val p = prev
            if (p != null) {
                val firstBreak = firstLineBreak(page, p.contentEnd, l.contentStart)
                val prevText = page.substring(p.contentStart, p.contentEnd)
                val c0 = page[l.contentStart]
                val indented = l.contentStart > l.start
                val endsSentence = endsSentence(prevText)
                val hyphen = p.contentEnd - p.contentStart >= 2 && isHyphen(page[p.contentEnd - 1]) &&
                    page[p.contentEnd - 2].isLetter() && c0.isLowerCase() && !blankBetween
                val paragraph = blankBetween || c0 == '—' || c0 == '–' || c0 == '•' ||
                    (indented && endsSentence) || (endsSentence && prevText.length < typical * 0.8)
                when {
                    hyphen -> {
                        // "pala-\nbra": el guion y el salto desaparecen.
                        sb.setLength(sb.length - 1) // quita el guion ya emitido
                        same[p.contentEnd - 1] = '­'
                        for (k in p.contentEnd until l.contentStart) same[k] = '​'
                    }
                    paragraph -> push('\n', firstBreak)
                    else -> {
                        push(' ', firstBreak)
                        for (k in p.contentEnd until l.contentStart) if (page[k] == '\n' || page[k] == '\r') same[k] = ' '
                    }
                }
            }
            for (k in l.contentStart until l.contentEnd) push(page[k], k)
            blankBetween = false
            prev = l
        }
        val excludedSpans = excluded.sortedBy { it.start }.map { TextSpan(it.start, it.end) }
        return FlowedPage(sb.toString(), map.copyOf(sb.length), excludedSpans, String(same))
    }

    private fun firstLineBreak(page: String, from: Int, to: Int): Int {
        for (k in from until to) if (page[k] == '\n' || page[k] == '\r') return k
        return from.coerceAtMost(page.length - 1)
    }

    private fun zone(lines: List<Line>, top: Boolean, n: Int): List<Line> {
        val nonBlank = lines.filter { !it.blank }
        return if (top) nonBlank.take(n) else nonBlank.takeLast(n).reversed()
    }

    /** Marca desde el borde las lineas de [zone] que son numero de pagina o se repiten en todas las vecinas. */
    private fun markEdge(page: String, zone: List<Line>, neighbours: List<Set<String>>, out: MutableSet<Line>) {
        for (l in zone) {
            val line = page.substring(l.contentStart, l.contentEnd)
            val sig = PdfHeaderFooterFilter.signature(line)
            val repeated = sig.length >= 3 && neighbours.isNotEmpty() && neighbours.all { sig in it }
            if (PdfHeaderFooterFilter.isPageNumberLine(line) || repeated) out.add(l) else break
        }
    }

    private fun endsSentence(t: String): Boolean = t.isEmpty() || t.last() in ".!?:»\"”…)"

    private fun isHyphen(c: Char) = c == '-' || c == '­' || c == '‐' || c == '\u0002'

    private fun splitLines(text: String): List<Line> {
        val out = ArrayList<Line>()
        var s = 0
        var i = 0
        while (i <= text.length) {
            if (i == text.length || text[i] == '\n' || text[i] == '\r') {
                val l = Line(s, i)
                while (l.contentStart < l.contentEnd && text[l.contentStart].isWhitespace()) l.contentStart++
                while (l.contentEnd > l.contentStart && text[l.contentEnd - 1].isWhitespace()) l.contentEnd--
                out.add(l)
                if (i < text.length && text[i] == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                s = i + 1
            }
            i++
        }
        return out
    }
}
