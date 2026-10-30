package com.lectorvoz.reader.render

import com.lectorvoz.core.model.LocatorRange
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import com.lectorvoz.reader.ReaderColors
import com.lectorvoz.reader.layout.ChapterLayout
import com.lectorvoz.reader.layout.MutableColorSpan

/**
 * Dibuja una pagina de un [ChapterLayout] con resaltados, encabezado y pie, segun
 * docs/diseno/TEMAS.md: frase = fondo + barra lateral de 3 dp (subrayado punteado si no hay
 * margen); palabra = fondo + color de texto propio + subrayado de 2 dp; busqueda = fondo +
 * contorno 1,5 dp (2,5 dp la actual); seleccion = fondo.
 *
 * No es thread-safe: usar desde un unico hilo (pone y quita spans en el texto del capitulo).
 */
class EpubPageRenderer(private val density: Float) {
    private val path = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dotted = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(2f * density, 2f * density), 0f)
    }
    private val chrome = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val fm = Paint.FontMetrics()
    private val wordSpan = MutableColorSpan()
    private val searchSpan = MutableColorSpan()

    /**
     * Dibuja la pagina [page] de [cl] en [canvas] (del tamano de la vista). [header] y
     * [footer] son opcionales (nulo = no dibujar).
     */
    fun draw(
        canvas: Canvas,
        cl: ChapterLayout,
        page: Int,
        colors: ReaderColors,
        hl: Highlights,
        header: String?,
        footer: String?,
    ) {
        val g = cl.geometry
        val layout = cl.layout
        val text = cl.text
        canvas.drawColor(colors.readerBackground)
        if (page !in 0 until cl.pageCount) return
        val firstLine = cl.pages.firstLine(page)
        val endLine = cl.pages.endLine(page)
        if (endLine <= firstLine) return
        val ps = cl.pages.startOffset(page)
        val pe = cl.pages.endOffset(page)
        val len = text.length
        val top = layout.getLineTop(firstLine).toFloat()
        val bottom = layout.getLineBottom(endLine - 1).toFloat()

        cl.paint.color = colors.readerText
        cl.colorHolder.link = colors.readerLink
        cl.colorHolder.divider = colors.readerDivider
        cl.paint.getFontMetrics(fm)

        val sentence = Highlights.clip(hl.sentence, cl.chapterIndex, len)?.intersect(ps, pe)
        val word = Highlights.clip(hl.word, cl.chapterIndex, len)?.intersect(ps, pe)
        val selection = Highlights.clip(hl.selection, cl.chapterIndex, len)?.intersect(ps, pe)
        val current = Highlights.clip(hl.searchCurrent, cl.chapterIndex, len)?.intersect(ps, pe)

        canvas.save()
        canvas.translate(g.columnLeft, g.contentTop - top)
        canvas.clipRect(-g.columnLeft, top, g.columnWidth + g.columnLeft, bottom)

        // Fondos (detras del texto).
        if (sentence != null) fillSelection(canvas, cl, sentence, colors.ttsSentenceHighlight)
        for (r in hl.search) {
            val s = Highlights.clip(r, cl.chapterIndex, len)?.intersect(ps, pe) ?: continue
            fillSelection(canvas, cl, s, if (r == hl.searchCurrent) colors.searchCurrentHighlight else colors.searchHighlight)
        }
        if (selection != null) fillSelection(canvas, cl, selection, colors.selection)
        if (word != null) {
            fill.color = colors.ttsWordHighlight
            forEachLineBox(cl, word) { l, t, r, b -> canvas.drawRect(l, t, r, b, fill) }
        }

        // Texto (con color propio para la palabra y la coincidencia actual).
        if (word != null) {
            wordSpan.color = colors.ttsWordText
            text.setSpan(wordSpan, word.first, word.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (current != null) {
            searchSpan.color = colors.searchCurrentText
            text.setSpan(searchSpan, current.first, current.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        try {
            layout.draw(canvas)
        } finally {
            text.removeSpan(wordSpan)
            text.removeSpan(searchSpan)
        }

        // Senales no cromaticas.
        if (word != null) {
            fill.color = colors.ttsWordIndicator
            val h = 2f * density
            forEachLineRect(cl, word) { l, _, r, _, baseline ->
                canvas.drawRect(l, baseline + h * 0.75f, r, baseline + h * 1.75f, fill)
            }
        }
        for (r in hl.search) {
            val s = Highlights.clip(r, cl.chapterIndex, len)?.intersect(ps, pe) ?: continue
            stroke.color = colors.searchOutline
            stroke.strokeWidth = (if (r == hl.searchCurrent) 2.5f else 1.5f) * density
            path.rewind()
            layout.getSelectionPath(s.first, s.last + 1, path)
            canvas.drawPath(path, stroke)
        }
        val barW = 3f * density
        val gap = 6f * density
        val useBar = g.columnLeft >= barW + gap + 2f * density
        if (sentence != null && !useBar) {
            dotted.color = colors.ttsSentenceIndicator
            dotted.strokeWidth = 1.5f * density
            forEachLineRect(cl, sentence) { l, _, r, _, baseline ->
                val y = baseline + 2.5f * density
                canvas.drawLine(l, y, r, y, dotted)
            }
        }
        canvas.restore()

        if (sentence != null && useBar) {
            val l0 = maxOf(firstLine, layout.getLineForOffset(sentence.first))
            val l1 = minOf(endLine - 1, layout.getLineForOffset(sentence.last))
            val yTop = g.contentTop + layout.getLineBaseline(l0) + fm.ascent - top
            val yBottom = g.contentTop + layout.getLineBaseline(l1) + fm.descent - top
            fill.color = colors.ttsSentenceIndicator
            // Parrafo de derecha a izquierda: la barra va en el margen derecho.
            val rtl = layout.getParagraphDirection(l0) == android.text.Layout.DIR_RIGHT_TO_LEFT
            val x = if (rtl) g.columnLeft + g.columnWidth + gap else g.columnLeft - gap - barW
            canvas.drawRect(x, yTop, x + barW, yBottom, fill)
        }

        // Encabezado y pie.
        chrome.textSize = g.chromeTextSizePx
        chrome.color = colors.readerSecondaryText
        if (header != null && g.headerHeight > 0f) {
            val t = TextUtils.ellipsize(header, chrome, g.columnWidth.toFloat(), TextUtils.TruncateAt.END)
            chrome.textAlign = Paint.Align.CENTER
            canvas.drawText(t, 0, t.length, g.viewport.widthPx / 2f, g.marginVerticalPx + g.chromeTextSizePx, chrome)
        }
        if (footer != null && g.footerHeight > 0f) {
            chrome.textAlign = Paint.Align.CENTER
            val y = g.viewport.heightPx - g.marginVerticalPx - g.chromeTextSizePx * 0.4f
            canvas.drawText(footer, g.viewport.widthPx / 2f, y, chrome)
        }
    }

    /**
     * Capa del resaltado de la voz (frase y palabra) de la pagina [page], en pixeles del bitmap,
     * con la misma geometria que [draw]: fondo de la frase por lineas, fondo y subrayado de la
     * palabra, y barra lateral (o linea bajo cada renglon si no cabe) de la frase. Nulo si nada
     * de eso cae en la pagina.
     */
    fun voiceLayer(cl: ChapterLayout, page: Int, colors: ReaderColors, sentenceRange: LocatorRange?, wordRange: LocatorRange?): HighlightLayer? {
        if (page !in 0 until cl.pageCount) return null
        val g = cl.geometry
        val layout = cl.layout
        val firstLine = cl.pages.firstLine(page)
        val endLine = cl.pages.endLine(page)
        if (endLine <= firstLine) return null
        val ps = cl.pages.startOffset(page)
        val pe = cl.pages.endOffset(page)
        val len = cl.text.length
        val sentence = Highlights.clip(sentenceRange, cl.chapterIndex, len)?.intersect(ps, pe)
        val word = Highlights.clip(wordRange, cl.chapterIndex, len)?.intersect(ps, pe)
        if (sentence == null && word == null) return null
        cl.paint.getFontMetrics(fm)
        val top = layout.getLineTop(firstLine).toFloat()
        val bottom = layout.getLineBottom(endLine - 1).toFloat()
        val dx = g.columnLeft.toFloat()
        val dy = g.contentTop - top
        val b = HighlightLayer.Builder(colors.isDark, colors.readerBackground)
        // En temas oscuros la palabra no puede llevar el texto oscuro de antes (una capa no
        // cambia el color del texto de debajo): relleno ambar tenue + subrayado amarillo.
        val wordFill = if (colors.isDark) mix(colors.ttsWordHighlight, colors.readerBackground, 0.35f) else colors.ttsWordHighlight
        val wordMark = if (colors.isDark) colors.ttsWordHighlight else colors.ttsWordIndicator
        if (sentence != null) {
            forEachLineBand(cl, sentence) { l, t, r, bt ->
                b.fill(colors.ttsSentenceHighlight, l + dx, maxOf(t, top) + dy, r + dx, minOf(bt, bottom) + dy)
            }
        }
        if (word != null) {
            val h = 2f * density
            forEachLineRect(cl, word) { l, t, r, bt, baseline ->
                b.fill(wordFill, l + dx, t + dy, r + dx, bt + dy)
                b.solid(wordMark, l + dx, baseline + h * 0.75f + dy, r + dx, baseline + h * 1.75f + dy)
            }
        }
        if (sentence != null) {
            val barW = 3f * density
            val gap = 6f * density
            if (g.columnLeft >= barW + gap + 2f * density) {
                val l0 = maxOf(firstLine, layout.getLineForOffset(sentence.first))
                val l1 = minOf(endLine - 1, layout.getLineForOffset(sentence.last))
                val yTop = g.contentTop + layout.getLineBaseline(l0) + fm.ascent - top
                val yBottom = g.contentTop + layout.getLineBaseline(l1) + fm.descent - top
                val rtl = layout.getParagraphDirection(l0) == android.text.Layout.DIR_RIGHT_TO_LEFT
                val x = if (rtl) g.columnLeft + g.columnWidth + gap else g.columnLeft - gap - barW
                b.solid(colors.ttsSentenceIndicator, x, yTop, x + barW, yBottom)
            } else {
                val w = 1.5f * density
                forEachLineRect(cl, sentence) { l, _, r, _, baseline ->
                    val y = baseline + 2.5f * density
                    b.solid(colors.ttsSentenceIndicator, l + dx, y - w / 2 + dy, r + dx, y + w / 2 + dy)
                }
            }
        }
        return b.build()
    }

    /** Bandas de linea completas (como el fondo de seleccion) de [r], linea a linea. */
    private inline fun forEachLineBand(cl: ChapterLayout, r: IntRange, block: (left: Float, top: Float, right: Float, bottom: Float) -> Unit) {
        val layout = cl.layout
        val s = r.first
        val e = r.last + 1
        val l0 = layout.getLineForOffset(s)
        val l1 = layout.getLineForOffset(maxOf(s, e - 1))
        for (line in l0..l1) {
            val ls = maxOf(s, layout.getLineStart(line))
            val le = minOf(e, layout.getLineEnd(line))
            if (le <= ls) continue
            val rtl = layout.getParagraphDirection(line) == android.text.Layout.DIR_RIGHT_TO_LEFT
            val x0 = layout.getPrimaryHorizontal(ls)
            val x1 = when {
                // Si la frase sigue en la linea siguiente, la banda llega al borde de la columna
                // (como el fondo de seleccion de Layout.getSelectionPath).
                e > layout.getLineEnd(line) -> if (rtl) 0f else layout.width.toFloat()
                le >= layout.getLineEnd(line) -> if (rtl) layout.getLineLeft(line) else layout.getLineRight(line)
                else -> layout.getPrimaryHorizontal(le)
            }
            block(minOf(x0, x1), layout.getLineTop(line).toFloat(), maxOf(x0, x1), layout.getLineBottom(line).toFloat())
        }
    }

    /** Mezcla [a] con [b] en proporcion [t] de [a] (opaco). */
    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = ((a shr shift and 0xFF) * t + (b shr shift and 0xFF) * (1 - t)).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun fillSelection(canvas: Canvas, cl: ChapterLayout, r: IntRange, color: Int) {
        path.rewind()
        cl.layout.getSelectionPath(r.first, r.last + 1, path)
        fill.color = color
        canvas.drawPath(path, fill)
    }

    /** Rectangulos ajustados al texto (sin interlineado) de [r], linea a linea. */
    private inline fun forEachLineRect(
        cl: ChapterLayout,
        r: IntRange,
        block: (left: Float, top: Float, right: Float, bottom: Float, baseline: Float) -> Unit,
    ) {
        val layout = cl.layout
        val s = r.first
        val e = r.last + 1
        val l0 = layout.getLineForOffset(s)
        val l1 = layout.getLineForOffset(maxOf(s, e - 1))
        for (line in l0..l1) {
            val ls = maxOf(s, layout.getLineStart(line))
            val le = minOf(e, layout.getLineEnd(line))
            if (le <= ls) continue
            val x0 = layout.getPrimaryHorizontal(ls)
            val x1 = if (le >= layout.getLineEnd(line)) {
                if (layout.getParagraphDirection(line) == android.text.Layout.DIR_RIGHT_TO_LEFT) layout.getLineLeft(line) else layout.getLineRight(line)
            } else {
                layout.getPrimaryHorizontal(le)
            }
            val base = layout.getLineBaseline(line).toFloat()
            block(minOf(x0, x1), base + fm.ascent, maxOf(x0, x1), base + fm.descent, base)
        }
    }

    private inline fun forEachLineBox(
        cl: ChapterLayout,
        r: IntRange,
        crossinline block: (left: Float, top: Float, right: Float, bottom: Float) -> Unit,
    ) = forEachLineRect(cl, r) { l, t, rr, b, _ -> block(l, t, rr, b) }
}

/** Interseccion con [s, e); null si queda vacia. */
internal fun IntRange.intersect(s: Int, e: Int): IntRange? {
    val a = maxOf(first, s)
    val b = minOf(last + 1, e)
    return if (b > a) a until b else null
}
