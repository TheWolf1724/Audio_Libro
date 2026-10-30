package com.lectorvoz.reader.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode

/**
 * Resaltado de la voz (frase y palabra) como CAPA encima del bitmap de la pagina, en pixeles del
 * bitmap. Antes cada frase o palabra redibujaba la pagina entera (en PDF, un render de pdfium) y
 * subia el bitmap nuevo a la GPU; ahora la pagina se dibuja una vez y al avanzar la voz solo se
 * pintan estos rectangulos.
 *
 * Los rellenos se mezclan con lo que hay debajo para que el texto siga legible: MULTIPLY en
 * temas claros (el fondo toma el color y el texto oscuro queda igual) y SCREEN en oscuros (el
 * fondo oscuro toma el color y el texto claro queda igual). Son los modos que la aceleracion por
 * hardware admite en todas las versiones de la app (LIGHTEN/DARKEN solo desde Android 9). Las
 * senales (barra de la frase, subrayado de la palabra) van opacas encima.
 */
class HighlightLayer private constructor(
    private val dark: Boolean,
    private val fillColors: IntArray,
    private val fillRects: Array<FloatArray>,
    private val solidColors: IntArray,
    private val solidRects: Array<FloatArray>,
) {
    /** Dibuja la capa sobre una pagina dibujada en ([left], [top]) con escala ([sx], [sy]). */
    fun draw(canvas: Canvas, left: Float, top: Float, sx: Float = 1f, sy: Float = 1f) {
        canvas.save()
        canvas.translate(left, top)
        if (sx != 1f || sy != 1f) canvas.scale(sx, sy)
        blend.xfermode = if (dark) screen else multiply
        for (i in fillColors.indices) {
            blend.color = fillColors[i]
            rects(canvas, fillRects[i], blend)
        }
        for (i in solidColors.indices) {
            solid.color = solidColors[i]
            rects(canvas, solidRects[i], solid)
        }
        canvas.restore()
    }

    private fun rects(canvas: Canvas, r: FloatArray, paint: Paint) {
        var k = 0
        while (k + 3 < r.size) {
            canvas.drawRect(r[k], r[k + 1], r[k + 2], r[k + 3], paint)
            k += 4
        }
    }

    /**
     * Construye una capa; los rectangulos se dan como (izquierda, arriba, derecha, abajo).
     * [background] es el fondo de la pagina (uniforme): los colores de relleno se corrigen para
     * que, mezclados con el, den EXACTAMENTE el color de antes sobre el fondo (con SCREEN el
     * color saldria mas claro; con MULTIPLY, mas oscuro sobre fondos no blancos como el sepia).
     */
    class Builder(private val dark: Boolean, private val background: Int) {
        private val fills = LinkedHashMap<Int, ArrayList<Float>>()
        private val solids = LinkedHashMap<Int, ArrayList<Float>>()

        fun fill(color: Int, l: Float, t: Float, r: Float, b: Float) = add(fills, inverse(color), l, t, r, b)

        /** Color que, mezclado con [background] (SCREEN u MULTIPLY), da [color]. */
        private fun inverse(color: Int): Int {
            fun ch(shift: Int): Int {
                val c = (color shr shift and 0xFF) / 255f
                val bg = (background shr shift and 0xFF) / 255f
                val v = if (dark) {
                    if (bg >= 0.999f) c else 1f - (1f - c) / (1f - bg)
                } else {
                    if (bg <= 0.001f) c else c / bg
                }
                return (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            }
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        fun solid(color: Int, l: Float, t: Float, r: Float, b: Float) = add(solids, color, l, t, r, b)

        private fun add(m: MutableMap<Int, ArrayList<Float>>, color: Int, l: Float, t: Float, r: Float, b: Float) {
            if (r <= l || b <= t) return
            m.getOrPut(color) { ArrayList() }.apply { add(l); add(t); add(r); add(b) }
        }

        /** Nulo si no hay nada que pintar. */
        fun build(): HighlightLayer? {
            if (fills.isEmpty() && solids.isEmpty()) return null
            return HighlightLayer(
                dark,
                fills.keys.toIntArray(), fills.values.map { it.toFloatArray() }.toTypedArray(),
                solids.keys.toIntArray(), solids.values.map { it.toFloatArray() }.toTypedArray(),
            )
        }
    }

    private companion object {
        // Solo se dibuja desde el hilo principal (onDraw): pinturas compartidas sin asignar por fotograma.
        val blend = Paint(Paint.ANTI_ALIAS_FLAG)
        val solid = Paint(Paint.ANTI_ALIAS_FLAG)
        val multiply = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        val screen = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
}
