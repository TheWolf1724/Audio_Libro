package com.lectorvoz.reader.render

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.createBitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import com.lectorvoz.format.pdf.PageSize
import com.lectorvoz.format.pdf.PdfBook
import com.lectorvoz.reader.ReaderColors
import kotlin.math.roundToInt

/** Donde cae la pagina PDF dentro del bitmap: escala (px por punto) y desplazamiento. */
data class PdfPlacement(val scale: Float, val offsetX: Float, val offsetY: Float, val width: Int, val height: Int) {
    /** Convierte un rectangulo en puntos de pagina a pixeles del bitmap. */
    fun toBitmap(r: RectF, out: RectF) {
        out.set(r.left * scale + offsetX, r.top * scale + offsetY, r.right * scale + offsetX, r.bottom * scale + offsetY)
    }

    companion object {
        /**
         * Ajuste a ancho; si la pagina no cabe de alto en [boxH] (modo paginas) se ajusta a la
         * pagina entera y se centra. Con [boxH] <= 0 (modo scroll) solo ajuste a ancho.
         */
        fun fit(size: PageSize, boxW: Int, boxH: Int): PdfPlacement {
            val sw = boxW / size.width.coerceAtLeast(1f)
            val scale = if (boxH > 0) minOf(sw, boxH / size.height.coerceAtLeast(1f)) else sw
            val w = (size.width * scale).roundToInt().coerceAtLeast(1)
            val h = (size.height * scale).roundToInt().coerceAtLeast(1)
            val ox = ((boxW - w) / 2f).coerceAtLeast(0f)
            val oy = if (boxH > 0) ((boxH - h) / 2f).coerceAtLeast(0f) else 0f
            return PdfPlacement(scale, ox, oy, w, h)
        }
    }
}

/** Rectangulos de resaltado de una pagina PDF, en puntos de pagina. */
data class PdfPageHighlights(
    val sentence: List<RectF> = emptyList(),
    val word: List<RectF> = emptyList(),
    val selection: List<RectF> = emptyList(),
    val search: List<RectF> = emptyList(),
    val searchCurrent: List<RectF> = emptyList(),
) {
    val isEmpty: Boolean
        get() = sentence.isEmpty() && word.isEmpty() && selection.isEmpty() && search.isEmpty() && searchCurrent.isEmpty()
}

/**
 * Render de paginas PDF: pdfium al tamano de la vista, recoloreado opcional con
 * [PdfRecolor] y resaltados en modo multiplicar (o aclarar sobre paginas recoloreadas
 * oscuras, donde multiplicar los haria invisibles). Usar desde un unico hilo; [release]
 * solo cuando ya no hay render en curso.
 */
class PdfPageRenderer(private val density: Float) {
    private var scratch: Bitmap? = null
    private val bgPaint = Paint()
    private val recolorPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var recolorKey = 0L
    private val blend = Paint(Paint.ANTI_ALIAS_FLAG)
    private val multiply = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    private val lighten = PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
    private val pageRect = Rect()
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tmp = RectF()
    private val src = Rect()

    suspend fun render(
        book: PdfBook,
        page: Int,
        target: Bitmap,
        placement: PdfPlacement,
        colors: ReaderColors,
        recolor: Boolean,
        highlights: PdfPageHighlights,
        footer: String?,
    ) {
        val canvas = Canvas(target)
        val left = (-placement.offsetX).roundToInt()
        val top = (-placement.offsetY).roundToInt()
        pageRect.set(
            placement.offsetX.roundToInt(), placement.offsetY.roundToInt(),
            (placement.offsetX + placement.width).roundToInt(), (placement.offsetY + placement.height).roundToInt(),
        )
        if (recolor) {
            val s = scratch?.takeIf { it.width == target.width && it.height == target.height && !it.isRecycled }
                ?: createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888).also {
                    scratch?.recycle(); scratch = it
                }
            book.renderPage(page, s, placement.scale, left, top)
            val key = (colors.readerBackground.toLong() shl 32) or (colors.readerText.toLong() and 0xFFFFFFFFL)
            if (key != recolorKey || recolorPaint.colorFilter == null) {
                recolorPaint.colorFilter = ColorMatrixColorFilter(PdfRecolor.colorMatrix(colors.readerBackground, colors.readerText))
                recolorKey = key
            }
            canvas.drawColor(colors.readerBackground)
            src.set(pageRect)
            canvas.drawBitmap(s, src, pageRect, recolorPaint)
        } else {
            book.renderPage(page, target, placement.scale, left, top)
            // Fuera de la pagina, el fondo del tema.
            bgPaint.color = colors.readerBackground
            val w = target.width.toFloat(); val h = target.height.toFloat()
            canvas.drawRect(0f, 0f, w, pageRect.top.toFloat(), bgPaint)
            canvas.drawRect(0f, pageRect.bottom.toFloat(), w, h, bgPaint)
            canvas.drawRect(0f, pageRect.top.toFloat(), pageRect.left.toFloat(), pageRect.bottom.toFloat(), bgPaint)
            canvas.drawRect(pageRect.right.toFloat(), pageRect.top.toFloat(), w, pageRect.bottom.toFloat(), bgPaint)
        }
        drawHighlights(canvas, placement, colors, darkPage = recolor && colors.isDark, hl = highlights)
        if (footer != null) {
            solid.color = colors.readerSecondaryText
            solid.textSize = 11f * density
            solid.textAlign = Paint.Align.CENTER
            canvas.drawText(footer, target.width / 2f, target.height - 6f * density, solid)
        }
    }

    private fun drawHighlights(canvas: Canvas, pl: PdfPlacement, colors: ReaderColors, darkPage: Boolean, hl: PdfPageHighlights) {
        if (hl.isEmpty) return
        blend.xfermode = if (darkPage) lighten else multiply
        fun fillAll(rects: List<RectF>, color: Int) {
            blend.color = color
            for (r in rects) { pl.toBitmap(r, tmp); canvas.drawRect(tmp, blend) }
        }
        fillAll(hl.sentence, colors.ttsSentenceHighlight)
        fillAll(hl.search, colors.searchHighlight)
        fillAll(hl.searchCurrent, colors.searchCurrentHighlight)
        fillAll(hl.selection, colors.selection)
        fillAll(hl.word, colors.ttsWordHighlight)

        // Senales no cromaticas (opacas).
        if (hl.sentence.isNotEmpty()) {
            var minLeft = Float.MAX_VALUE; var minTop = Float.MAX_VALUE; var maxBottom = -Float.MAX_VALUE
            for (r in hl.sentence) {
                pl.toBitmap(r, tmp)
                minLeft = minOf(minLeft, tmp.left); minTop = minOf(minTop, tmp.top); maxBottom = maxOf(maxBottom, tmp.bottom)
            }
            solid.color = colors.ttsSentenceIndicator
            val bar = 3f * density
            val x = (minLeft - 4f * density - bar).coerceAtLeast(0f)
            canvas.drawRect(x, minTop, x + bar, maxBottom, solid)
        }
        if (hl.word.isNotEmpty()) {
            solid.color = colors.ttsWordIndicator
            for (r in hl.word) {
                pl.toBitmap(r, tmp)
                canvas.drawRect(tmp.left, tmp.bottom, tmp.right, tmp.bottom + 2f * density, solid)
            }
        }
        stroke.color = colors.searchOutline
        stroke.strokeWidth = 1.5f * density
        for (r in hl.search) { pl.toBitmap(r, tmp); canvas.drawRect(tmp, stroke) }
        stroke.strokeWidth = 2.5f * density
        for (r in hl.searchCurrent) { pl.toBitmap(r, tmp); canvas.drawRect(tmp, stroke) }
    }

    /**
     * Capa del resaltado de la voz (ver HighlightLayer) con la misma geometria que
     * [drawHighlights]: fondos de frase y palabra mezclados, barra de la frase y subrayado de la
     * palabra opacos. [sentence] y [word] en coordenadas de pagina PDF.
     */
    fun voiceLayer(pl: PdfPlacement, colors: ReaderColors, darkPage: Boolean, sentence: List<RectF>, word: List<RectF>): HighlightLayer? {
        // Fondo bajo el resaltado: el del tema si la pagina esta recoloreada; si no, el papel blanco.
        val b = HighlightLayer.Builder(darkPage, if (darkPage) colors.readerBackground else android.graphics.Color.WHITE)
        val r = RectF()
        for (s in sentence) { pl.toBitmap(s, r); b.fill(colors.ttsSentenceHighlight, r.left, r.top, r.right, r.bottom) }
        for (w in word) { pl.toBitmap(w, r); b.fill(colors.ttsWordHighlight, r.left, r.top, r.right, r.bottom) }
        if (sentence.isNotEmpty()) {
            var minLeft = Float.MAX_VALUE; var minTop = Float.MAX_VALUE; var maxBottom = -Float.MAX_VALUE
            for (s in sentence) {
                pl.toBitmap(s, r)
                minLeft = minOf(minLeft, r.left); minTop = minOf(minTop, r.top); maxBottom = maxOf(maxBottom, r.bottom)
            }
            val bar = 3f * density
            val x = (minLeft - 4f * density - bar).coerceAtLeast(0f)
            b.solid(colors.ttsSentenceIndicator, x, minTop, x + bar, maxBottom)
        }
        for (w in word) {
            pl.toBitmap(w, r)
            b.solid(colors.ttsWordIndicator, r.left, r.bottom, r.right, r.bottom + 2f * density)
        }
        return b.build()
    }

    /** Libera el bitmap auxiliar del recoloreado. */
    fun release() {
        scratch?.recycle()
        scratch = null
    }
}
