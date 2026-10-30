package com.lectorvoz.reader.layout

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan

/** Anade [extraPx] bajo la ultima linea de un parrafo (espacio entre parrafos). */
internal class ParagraphSpacingSpan(private val extraPx: Int) : LineHeightSpan {
    override fun chooseHeight(
        text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt,
    ) {
        val spanEnd = (text as? android.text.Spanned)?.getSpanEnd(this) ?: return
        if (end >= spanEnd) {
            fm.descent += extraPx
            fm.bottom += extraPx
        }
    }
}

/**
 * Margen izquierdo con marcador de lista opcional dibujado en la primera linea del
 * parrafo. [firstExtra] es la sangria adicional de la primera linea.
 */
internal class MarginSpan(
    private val restMargin: Int,
    private val firstExtra: Int,
    private val marker: String?,
    private val markerGap: Int,
) : LeadingMarginSpan {
    override fun getLeadingMargin(first: Boolean): Int = if (first) restMargin + firstExtra else restMargin

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?,
    ) {
        val m = marker ?: return
        if (!first) return
        val spanned = text as? android.text.Spanned ?: return
        if (spanned.getSpanStart(this) != start) return
        val w = p.measureText(m)
        val mx: Float = if (dir >= 0) x + restMargin - markerGap - w else (x - restMargin + markerGap).toFloat()
        c.drawText(m, mx, baseline.toFloat(), p)
    }
}

/** Cambia la tipografia (familia de lectura con su peso). */
internal class TypefaceStyleSpan(private val typeface: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) { tp.typeface = typeface }
    override fun updateMeasureState(tp: TextPaint) { tp.typeface = typeface }
}

/** Enlace: color leido en el momento de dibujar (cambiar de tema no obliga a repaginar). */
internal class LinkSpan(val href: String, private val colors: ColorHolder) : CharacterStyle() {
    override fun updateDrawState(tp: TextPaint) {
        tp.color = colors.link
        tp.isUnderlineText = true
    }
}

/** Color del texto de un rango (palabra leida); se reutiliza una sola instancia. */
internal class MutableColorSpan : CharacterStyle() {
    var color: Int = 0
    override fun updateDrawState(tp: TextPaint) { tp.color = color }
}

/** Colores que leen los spans al dibujar. */
internal class ColorHolder {
    @Volatile var link: Int = 0xFF154C97.toInt()
    @Volatile var divider: Int = 0xFFCCC6BB.toInt()
}

/**
 * Hueco de una imagen de [width] x [height] pixeles alineado abajo (como `ImageSpan`
 * `ALIGN_BOTTOM`). La maquetacion solo necesita el tamano; el bitmap lo pone el contenido
 * justo antes de dibujar la pagina y lo quita despues ([bitmap]), asi la maquetacion no
 * retiene imagenes decodificadas. Solo se dibuja en el hilo de render.
 */
internal class ImageBoxSpan(val href: String, val width: Int, val height: Int) : ReplacementSpan() {
    @Volatile var bitmap: Bitmap? = null
    private val dst = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) {
            fm.ascent = -height
            fm.descent = 0
            fm.top = fm.ascent
            fm.bottom = 0
        }
        return width
    }

    override fun draw(
        canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint,
    ) {
        val b = bitmap ?: return
        if (b.isRecycled) return
        dst.set(x, (bottom - height).toFloat(), x + width, bottom.toFloat())
        canvas.drawBitmap(b, null, dst, this.paint)
    }
}

/**
 * Separador horizontal (`<hr>`): anade [gapPx] encima de la primera linea del parrafo
 * ([above]) o debajo de la ultima (si el separador cierra el capitulo) y dibuja una raya
 * centrada del 40 % del ancho en medio del hueco, con el color `readerDivider`.
 */
internal class RuleSpan(
    private val gapPx: Int,
    private val strokePx: Float,
    private val above: Boolean,
    private val colors: ColorHolder,
) : LineHeightSpan, LineBackgroundSpan {
    override fun chooseHeight(
        text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt,
    ) {
        val sp = text as? Spanned ?: return
        if (above) {
            if (start != sp.getSpanStart(this)) return
            fm.ascent -= gapPx
            fm.top -= gapPx
        } else {
            if (end < sp.getSpanEnd(this)) return
            fm.descent += gapPx
            fm.bottom += gapPx
        }
    }

    override fun drawBackground(
        canvas: Canvas, paint: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, lineNumber: Int,
    ) {
        val sp = text as? Spanned ?: return
        val y = if (above) {
            if (start != sp.getSpanStart(this)) return
            top + gapPx / 2f
        } else {
            if (end < sp.getSpanEnd(this)) return
            bottom - gapPx / 2f
        }
        val oldColor = paint.color
        val oldWidth = paint.strokeWidth
        paint.color = colors.divider
        paint.strokeWidth = strokePx
        val cx = (left + right) / 2f
        val half = (right - left) * 0.2f
        canvas.drawLine(cx - half, y, cx + half, y, paint)
        paint.color = oldColor
        paint.strokeWidth = oldWidth
    }
}

/** Ocupa el caracter de una imagen que no se pudo cargar sin dibujar nada. */
internal class EmptyReplacementSpan : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int = 0
    override fun draw(
        canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint,
    ) = Unit
}
