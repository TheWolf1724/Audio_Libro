package com.lectorvoz.reader.render

import android.graphics.ColorMatrix

/**
 * Recoloreado de PDF por luminancia (docs/diseno/temas.json, notasUso): blanco ->
 * [background], negro -> [text], con interpolacion lineal segun la luminancia del pixel
 * (Rec. 709). No es una inversion: las fotos quedan en duotono legible y no hay negro puro.
 */
object PdfRecolor {
    /** Coeficientes de luminancia Rec. 709. */
    private const val LR = 0.2126f
    private const val LG = 0.7152f
    private const val LB = 0.0722f

    /**
     * Matriz 4x5 tal que out_c = text_c + (background_c - text_c) * L, con L la luminancia
     * 0..1 del pixel de entrada.
     */
    fun matrixValues(background: Int, text: Int): FloatArray {
        val out = FloatArray(20)
        val shifts = intArrayOf(16, 8, 0)
        for (row in 0..2) {
            val bg = (background shr shifts[row]) and 0xFF
            val tx = (text shr shifts[row]) and 0xFF
            val k = (bg - tx) / 255f
            out[row * 5] = k * LR
            out[row * 5 + 1] = k * LG
            out[row * 5 + 2] = k * LB
            out[row * 5 + 3] = 0f
            out[row * 5 + 4] = tx.toFloat()
        }
        out[18] = 1f // alfa sin cambios
        return out
    }

    fun colorMatrix(background: Int, text: Int): ColorMatrix = ColorMatrix(matrixValues(background, text))

    /** Aplica la matriz a un color (para pruebas y para pintar bordes coherentes). */
    fun apply(values: FloatArray, color: Int): Int {
        val a = (color ushr 24) and 0xFF
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        fun ch(row: Int): Int =
            (values[row * 5] * r + values[row * 5 + 1] * g + values[row * 5 + 2] * b + values[row * 5 + 3] * a +
                values[row * 5 + 4] + 0.5f).toInt().coerceIn(0, 255)
        return (a shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
    }
}
