package com.lectorvoz.app.ui.reader

import androidx.compose.runtime.Immutable
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.LocatorRange
import com.lectorvoz.core.model.TocEntry
import kotlin.math.roundToLong

/** Entrada del indice aplanada para listas. */
@Immutable
data class TocItem(val title: String, val locator: Locator, val depth: Int)

/** Aplana el indice jerarquico (con profundidad para sangrar). */
fun flattenToc(toc: List<TocEntry>, depth: Int = 0, out: MutableList<TocItem> = ArrayList()): List<TocItem> {
    for (e in toc) {
        out += TocItem(e.title, e.target, depth)
        flattenToc(e.children, depth + 1, out)
    }
    return out
}

/** Resultado de busqueda en el libro. */
@Immutable
data class SearchHit(val range: LocatorRange, val excerpt: String)

/** Coincidencia de una busqueda: [start, end) en el texto ORIGINAL. */
data class Match(val start: Int, val end: Int)

/**
 * Busca [query] en [text] sin distinguir mayusculas ni tildes y TOLERANDO el maquetado del texto:
 * saltos de linea y espacios repetidos valen como un solo espacio ("de la\nMancha" se encuentra con
 * "de la Mancha") y las palabras partidas con guion al final de linea ("Man-\ncha") se leen unidas.
 * Los rangos devueltos son del texto original, aunque la coincidencia cruce uno de esos saltos.
 */
fun findMatches(text: String, query: String, max: Int = 200): List<Match> {
    val q = normalizeForSearch(query.trim()).text
    if (q.isEmpty()) return emptyList()
    val t = normalizeForSearch(text)
    val out = ArrayList<Match>()
    var i = t.text.indexOf(q)
    while (i >= 0 && out.size < max) {
        val lastNorm = i + q.length - 1
        out += Match(t.start[i], t.end[lastNorm])
        i = t.text.indexOf(q, i + q.length)
    }
    return out
}

/** Busca [query] en [text] (sin distinguir mayusculas ni tildes); devuelve inicios. */
fun findAll(text: String, query: String, max: Int = 200): List<Int> = findMatches(text, query, max).map { it.start }

/** Texto plegado para buscar, con el rango original (inicio/fin) de cada caracter plegado. */
private class SearchText(val text: String, val start: IntArray, val end: IntArray)

private fun normalizeForSearch(s: String): SearchText {
    val sb = StringBuilder(s.length)
    val start = IntArray(s.length)
    val end = IntArray(s.length)
    var n = 0
    var i = 0
    fun emit(c: Char, from: Int, to: Int) {
        sb.append(c); start[n] = from; end[n] = to; n++
    }
    while (i < s.length) {
        val c = s[i]
        when {
            // Guion blando y espacio de ancho cero: invisibles.
            c == '\u00AD' || c == '\u200B' -> i++
            c.isWhitespace() -> {
                // Una racha de blancos (incluidos saltos de linea) cuenta como UN espacio.
                var j = i
                while (j < s.length && (s[j].isWhitespace() || s[j] == '\u200B' || s[j] == '\u00AD')) j++
                if (n > 0 && sb[n - 1] != ' ') emit(' ', i, j)
                i = j
            }
            c == '-' && i > 0 && s[i - 1].isLetter() && hyphenBreak(s, i) -> {
                // "Man-\ncha": el guion y el salto desaparecen y la palabra queda unida.
                var j = i + 1
                while (j < s.length && s[j].isWhitespace()) j++
                i = j
            }
            else -> { emit(foldChar(c), i, i + 1); i++ }
        }
    }
    return SearchText(sb.toString(), start.copyOf(n), end.copyOf(n))
}

/** El guion en [i] esta al final de linea y la linea siguiente sigue en minuscula (palabra partida). */
private fun hyphenBreak(s: String, i: Int): Boolean {
    var j = i + 1
    var sawBreak = false
    while (j < s.length && s[j].isWhitespace()) {
        if (s[j] == '\n' || s[j] == '\r') sawBreak = true
        j++
    }
    return sawBreak && j < s.length && s[j].isLowerCase()
}

/** Minusculas y sin tildes conservando la longitud (para que los indices sigan valiendo). */
fun foldSameLength(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s) sb.append(foldChar(c))
    return sb.toString()
}

private fun fold(s: String): String = foldSameLength(s)

private fun foldChar(c: Char): Char = when (val l = c.lowercaseChar()) {
    'á', 'à', 'ä', 'â' -> 'a'
    'é', 'è', 'ë', 'ê' -> 'e'
    'í', 'ì', 'ï', 'î' -> 'i'
    'ó', 'ò', 'ö', 'ô' -> 'o'
    'ú', 'ù', 'ü', 'û' -> 'u'
    else -> l
}

/** Fragmento de [text] alrededor de [start, end) para mostrar un resultado. */
fun excerpt(text: String, start: Int, end: Int, context: Int = 40): String {
    val a = (start - context).coerceAtLeast(0)
    val b = (end + context).coerceAtMost(text.length)
    val pre = if (a > 0) "..." else ""
    val post = if (b < text.length) "..." else ""
    return pre + text.substring(a, b).replace('\n', ' ').trim() + post
}

/**
 * Posicion de la fraccion [f] del libro por caracteres (deslizador de progreso, M12):
 * [length] da la longitud conocida de cada capitulo (nula si no se conoce; se estima con la
 * media de los conocidos). Sin ninguna conocida (PDF), el capitulo proporcional.
 */
fun locatorForFraction(f: Float, chapterCount: Int, length: (Int) -> Int?): Locator {
    if (chapterCount <= 0) return Locator.START
    val frac = f.coerceIn(0f, 1f)
    val known = (0 until chapterCount).mapNotNull(length)
    if (known.isEmpty()) return Locator((frac * chapterCount).toInt().coerceIn(0, chapterCount - 1), 0)
    val avg = known.average()
    val lens = DoubleArray(chapterCount) { length(it)?.toDouble() ?: avg }
    val target = frac * lens.sum()
    var acc = 0.0
    for (i in 0 until chapterCount) {
        if (acc + lens[i] > target) {
            val off = (target - acc).toInt().coerceIn(0, (lens[i].toInt() - 1).coerceAtLeast(0))
            return Locator(i, if (length(i) == null) 0 else off)
        }
        acc += lens[i]
    }
    return Locator(chapterCount - 1, ((lens.last().toInt() - 1).coerceAtLeast(0)).takeIf { length(chapterCount - 1) != null } ?: 0)
}

/**
 * Mantener la pantalla encendida (A3): solo si el usuario lo pide en ajustes y, ademas,
 * mientras suena la voz o hubo un toque hace menos de [idleMs]. En pausa, al acabar o al
 * saltar el temporizador se deja apagar sola.
 */
fun shouldKeepScreenOn(setting: Boolean, playing: Boolean, sinceTouchMs: Long, idleMs: Long = SCREEN_IDLE_MS): Boolean =
    setting && (playing || sinceTouchMs < idleMs)

/** Minutos sin toques ni voz tras los que la pantalla del lector se puede apagar. */
const val SCREEN_IDLE_MS = 10 * 60_000L

/** Tiempos restantes estimados. */
@Immutable
data class Remaining(val chapterMs: Long?, val bookMs: Long?)

/**
 * Tiempo restante de capitulo y libro: caracteres que faltan / velocidad efectiva. Si la voz
 * aun no ha medido su velocidad ([measuredCps] nulo) se estima con [BASE_CPS] x velocidad.
 */
fun remainingTime(chapterChars: Int, bookChars: Long, measuredCps: Float?, speed: Float): Remaining {
    val cps = measuredCps?.takeIf { it > 0f } ?: (BASE_CPS * speed.coerceAtLeast(0.1f))
    fun ms(chars: Long) = if (chars <= 0) 0L else (chars / cps * 1000f).roundToLong()
    return Remaining(ms(chapterChars.toLong()), ms(bookChars))
}

/** Caracteres por segundo de la voz neuronal a velocidad 1 (estimacion inicial). */
const val BASE_CPS = 14f

/** "1 h 5 min", "12 min", "menos de 1 min". */
fun formatDuration(ms: Long?): String {
    if (ms == null) return "-"
    val min = ms / 60_000
    return when {
        min < 1 -> "menos de 1 min"
        min < 60 -> "$min min"
        else -> "${min / 60} h ${min % 60} min"
    }
}
