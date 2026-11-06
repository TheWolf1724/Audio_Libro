package com.lectorvoz.reader

import com.lectorvoz.core.model.Locator

/**
 * Progreso por caracteres desde una posicion de lectura (para el tiempo restante a la
 * velocidad de la voz y el modo solo escuchar).
 */
data class CharProgress(
    /** Caracteres desde la posicion hasta el final del capitulo. */
    val chapterRemaining: Int,
    /** Caracteres desde la posicion hasta el final del libro. */
    val bookRemaining: Long,
    /** Fraccion leida del capitulo 0..1. */
    val chapterFraction: Float,
    /** Fraccion leida del libro 0..1. */
    val bookFraction: Float,
    /** false si alguna longitud de capitulo es estimada. */
    val exact: Boolean,
)

/** Calculo puro de [CharProgress] (probado en JVM). */
object TextProgress {
    /**
     * [charCount] da la longitud conocida de cada capitulo (null si no se conoce). Los
     * capitulos sin longitud se estiman con la media de los conocidos; sin ninguno conocido
     * todo es 0 y no exacto.
     */
    fun compute(position: Locator, chapterCount: Int, charCount: (Int) -> Int?): CharProgress {
        if (chapterCount <= 0) return CharProgress(0, 0, 0f, 0f, true)
        val ch = position.chapter.coerceIn(0, chapterCount - 1)
        // Snapshot de las longitudes en UNA sola pasada: antes se llamaba a charCount(i) DOS veces
        // por capitulo (suma + before/after) y cada llamada toma un lock; en un EPUB de ~300
        // capitulos eran ~600 adquisiciones de cerrojo por cada frase leida, en el hilo principal.
        val lens = arrayOfNulls<Int>(chapterCount)
        var knownSum = 0L
        var known = 0
        for (i in 0 until chapterCount) {
            val c = charCount(i)
            lens[i] = c
            if (c != null) { knownSum += c; known++ }
        }
        if (known == 0) return CharProgress(0, 0, 0f, 0f, false)
        val avg = knownSum.toDouble() / known
        fun len(i: Int): Double = lens[i]?.toDouble() ?: avg
        val chapterLen = len(ch)
        val offset = position.offset.toDouble().coerceIn(0.0, chapterLen)
        val chapterRemaining = (chapterLen - offset).toLong().coerceAtLeast(0)
        var before = 0.0
        var after = 0.0
        for (i in 0 until chapterCount) {
            if (i < ch) before += len(i) else if (i > ch) after += len(i)
        }
        val total = before + chapterLen + after
        val read = before + offset
        return CharProgress(
            chapterRemaining = chapterRemaining.toInt(),
            bookRemaining = chapterRemaining + after.toLong(),
            chapterFraction = if (chapterLen > 0) (offset / chapterLen).toFloat().coerceIn(0f, 1f) else 0f,
            bookFraction = if (total > 0) (read / total).toFloat().coerceIn(0f, 1f) else 0f,
            exact = known == chapterCount,
        )
    }
}
