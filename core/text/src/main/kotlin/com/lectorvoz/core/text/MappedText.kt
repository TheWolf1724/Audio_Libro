package com.lectorvoz.core.text

/**
 * Texto transformado con su mapa al texto de origen: el caracter `i` de [text] procede del
 * caracter `toSource[i]` del origen. El mapa es monotono no decreciente.
 */
class MappedText(val text: String, val toSource: IntArray) {
    init {
        require(text.length == toSource.size) { "Mapa de longitud distinta al texto" }
    }

    /**
     * Compone dos transformaciones: si este texto procede de un intermedio y [inner]
     * mapea ese intermedio a su origen, el resultado mapea directamente al origen.
     */
    fun composeWith(inner: IntArray): MappedText =
        MappedText(text, IntArray(toSource.size) { inner[toSource[it]] })

    override fun toString(): String = "MappedText($text)"

    companion object {
        /** Texto identico al origen. */
        fun identity(text: String): MappedText = MappedText(text, IntArray(text.length) { it })
    }
}

/**
 * Construye un [MappedText] colapsando espacios: nunca emite dos espacios seguidos ni
 * espacios iniciales, y recorta los finales en [build].
 */
internal class MappedTextBuilder(capacity: Int) {
    private val sb = StringBuilder(capacity + 16)
    private var map = IntArray(capacity + 16)

    val length: Int get() = sb.length

    /** Ultimo caracter emitido o '\u0000'. */
    fun last(): Char = if (sb.isEmpty()) '\u0000' else sb[sb.length - 1]

    private fun push(c: Char, src: Int) {
        if (sb.length == map.size) map = map.copyOf(map.size * 2)
        map[sb.length] = src
        sb.append(c)
    }

    /** Emite un caracter del origen tal cual. */
    fun char(c: Char, src: Int) {
        if (c == ' ') space(src) else push(c, src)
    }

    /** Emite un espacio (colapsado) asociado a [src]. */
    fun space(src: Int) {
        if (sb.isEmpty() || sb[sb.length - 1] == ' ') return
        push(' ', src)
    }

    /**
     * Emite [str] como sustitucion del rango de origen [srcStart, srcEnd): los caracteres se
     * reparten proporcionalmente sobre el rango para conservar la monotonia.
     */
    fun replace(str: String, srcStart: Int, srcEnd: Int) {
        val span = (srcEnd - srcStart).coerceAtLeast(1)
        val n = str.length
        for (i in 0 until n) {
            val src = srcStart + (i.toLong() * span / n).toInt()
            val c = str[i]
            if (c == ' ') space(src) else push(c, src)
        }
    }

    /** Quita el espacio final si lo hay (para pegar una puntuacion a la palabra previa). */
    fun trimTrailingSpace() {
        if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') sb.setLength(sb.length - 1)
    }

    fun build(): MappedText {
        var end = sb.length
        while (end > 0 && sb[end - 1] == ' ') end--
        return MappedText(sb.substring(0, end), map.copyOf(end))
    }
}
