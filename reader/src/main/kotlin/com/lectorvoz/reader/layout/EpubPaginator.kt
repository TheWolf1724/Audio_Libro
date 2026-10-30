package com.lectorvoz.reader.layout

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import com.lectorvoz.core.model.Block
import com.lectorvoz.core.model.BlockAlign
import com.lectorvoz.core.model.BlockKind
import com.lectorvoz.core.model.ChapterDocument
import com.lectorvoz.core.model.InlineStyle
import com.lectorvoz.reader.FontProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Capitulo maquetado: texto con estilos, [StaticLayout] de todo el capitulo y su corte en
 * paginas. Inmutable salvo los spans de color que el renderizador pone y quita en su
 * hilo unico.
 */
class ChapterLayout internal constructor(
    val chapterIndex: Int,
    val title: String?,
    val text: SpannableStringBuilder,
    /** Texto plano del capitulo (inmutable; se puede leer desde cualquier hilo). */
    val plainText: String,
    val layout: StaticLayout,
    val pages: PageMap,
    val geometry: PageGeometry,
    val settings: LayoutSettings,
    internal val paint: TextPaint,
    internal val colorHolder: ColorHolder,
) {
    val pageCount: Int get() = pages.pageCount

    /** Pagina que contiene el desplazamiento [offset] del texto plano. */
    fun pageForOffset(offset: Int): Int = pages.pageForOffset(offset)

    /** Texto plano de la pagina [page] (para TalkBack). No toca los spans: cualquier hilo. */
    fun pageText(page: Int): String {
        val s = pages.startOffset(page).coerceIn(0, plainText.length)
        val e = pages.endOffset(page).coerceIn(s, plainText.length)
        return plainText.substring(s, e).replace(ChapterDocument.OBJECT_CHAR, ' ')
    }

    /** Huecos de imagen de la pagina [page] (solo en el hilo de render). */
    internal fun imagesOn(page: Int): Array<ImageBoxSpan> =
        text.getSpans(pages.startOffset(page), pages.endOffset(page), ImageBoxSpan::class.java)
}

/**
 * Paginador EPUB: [ChapterDocument] + ajustes + tamano de vista -> [ChapterLayout].
 * Se ejecuta en [Dispatchers.Default] (o el despachador pedido) y comprueba la cancelacion
 * entre fases. Las imagenes solo aportan su tamano ([ImageLoader.size]); no se decodifican
 * al maquetar, asi que contar paginas en fondo da exactamente el mismo corte.
 */
class EpubPaginator(
    private val fonts: FontProvider = FontProvider.Default,
    private val images: ImageLoader? = null,
) {
    internal val colorHolder = ColorHolder()

    suspend fun paginate(
        chapter: ChapterDocument,
        settings: LayoutSettings,
        viewport: Viewport,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): ChapterLayout =
        withContext(dispatcher) {
            require(!viewport.isEmpty) { "Vista sin tamano" }
            val ctx = currentCoroutineContext()
            val baseTypeface = fonts.typeface(settings.fontFamily, settings.fontWeight, false)
            var geometry = PageGeometry.compute(viewport, settings)
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                textSize = geometry.textSizePx
                typeface = baseTypeface
                letterSpacing = settings.letterSpacingEm
                if (Build.VERSION.SDK_INT >= 29) wordSpacing = settings.wordSpacingEm * geometry.textSizePx
                chapter.language?.let { lang -> runCatching { textLocale = Locale.forLanguageTag(lang) } }
            }
            val avg = paint.measureText(SAMPLE) / SAMPLE.length / geometry.textSizePx
            if (avg > 0f && avg.isFinite()) geometry = PageGeometry.compute(viewport, settings, avg)

            val text = buildText(chapter, settings, geometry, paint)
            ctx.ensureActive()
            val layout = buildLayout(text, paint, geometry.columnWidth, settings)
            ctx.ensureActive()
            val pages = cutPages(layout, geometry.contentHeight)
            ChapterLayout(chapter.index, chapter.title, text, chapter.plainText, layout, pages, geometry, settings, paint, colorHolder)
        }

    private suspend fun buildText(
        chapter: ChapterDocument, s: LayoutSettings, g: PageGeometry, paint: TextPaint,
    ): SpannableStringBuilder {
        val text = chapter.plainText
        val sb = SpannableStringBuilder(text)
        val em = g.textSizePx
        val paraSpacing = (s.paragraphSpacingEm * em).roundToInt()
        val indentEm = s.firstLineIndentEm ?: 0f
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        fun paraEnd(end: Int): Int = if (end < text.length && text[end] == '\n') end + 1 else end.coerceAtMost(text.length)
        fun paraStartOk(start: Int): Boolean = start == 0 || (start <= text.length && text[start - 1] == '\n')

        for (block in chapter.blocks) {
            val start = block.start.coerceIn(0, text.length)
            when (block) {
                is Block.Text -> {
                    val end = block.end.coerceIn(start, text.length)
                    val pEnd = paraEnd(end)
                    if (pEnd <= start) continue
                    val isParagraphStart = paraStartOk(start)
                    val depthMargin = (block.depth * 1.5f * em).roundToInt()
                    when (val kind = block.kind) {
                        is BlockKind.Heading -> {
                            val scale = when (kind.level) { 1 -> 1.5f; 2 -> 1.35f; 3 -> 1.2f; else -> 1.1f }
                            sb.setSpan(RelativeSizeSpan(scale), start, end, flags)
                            sb.setSpan(StyleSpan(Typeface.BOLD), start, end, flags)
                        }
                        is BlockKind.Paragraph -> if (isParagraphStart && (indentEm > 0f || depthMargin > 0)) {
                            sb.setSpan(MarginSpan(depthMargin, (indentEm * em).roundToInt(), null, 0), start, pEnd, flags)
                        }
                        is BlockKind.ListItem -> if (isParagraphStart) {
                            val margin = depthMargin + (1.5f * em).roundToInt()
                            sb.setSpan(MarginSpan(margin, 0, kind.marker, (0.4f * em).roundToInt()), start, pEnd, flags)
                        }
                        is BlockKind.Blockquote -> if (isParagraphStart) {
                            sb.setSpan(MarginSpan(depthMargin + (1.5f * em).roundToInt(), 0, null, 0), start, pEnd, flags)
                        }
                        is BlockKind.Preformatted -> {
                            sb.setSpan(TypefaceSpan("monospace"), start, end, flags)
                            sb.setSpan(RelativeSizeSpan(0.9f), start, end, flags)
                        }
                        is BlockKind.Caption -> {
                            sb.setSpan(RelativeSizeSpan(0.9f), start, end, flags)
                            sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, flags)
                        }
                    }
                    val align = when {
                        block.align == BlockAlign.CENTER || block.kind is BlockKind.Caption -> Layout.Alignment.ALIGN_CENTER
                        block.align == BlockAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
                        else -> null
                    }
                    if (align != null && isParagraphStart) sb.setSpan(AlignmentSpan.Standard(align), start, pEnd, flags)
                    if (paraSpacing > 0) sb.setSpan(ParagraphSpacingSpan(paraSpacing), start, pEnd, flags)
                    for (span in block.spans) applyInline(sb, span.start, span.end, span.style, text.length)
                }
                is Block.Image -> {
                    if (start >= text.length) continue
                    val maxW = g.columnWidth
                    // El multiplicador de interlineado tambien se aplica a la linea de la imagen.
                    val maxH = ((g.contentHeight - paraSpacing) / s.lineSpacing.coerceAtLeast(1f) * 0.95f).toInt()
                        .coerceAtLeast(1)
                    val needSize = block.widthPx == null || block.heightPx == null
                    val natural = if (needSize) runCatching { images?.size(block.href) }.getOrNull() else null
                    val box = ImageFit.fit(natural, block.widthPx, block.heightPx, g.viewport.density, maxW, maxH)
                    if (box != null && (natural != null || images != null)) {
                        sb.setSpan(ImageBoxSpan(block.href, box.width, box.height), start, start + 1, flags)
                        if (paraStartOk(start)) {
                            sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, paraEnd(start + 1), flags)
                        }
                    } else {
                        sb.setSpan(EmptyReplacementSpan(), start, start + 1, flags)
                    }
                }
                is Block.Rule -> addRule(sb, text, start, em)
            }
            currentCoroutineContext().ensureActive()
        }
        return sb
    }

    /** Separador: hueco con raya encima del parrafo que empieza en [at] (o bajo el ultimo). */
    private fun addRule(sb: SpannableStringBuilder, text: String, at: Int, em: Float) {
        if (text.isEmpty()) return
        val gap = (em * 1.5f).roundToInt().coerceAtLeast(1)
        val stroke = (em / 16f).coerceAtLeast(1f)
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        if (at < text.length) {
            val ps = if (at <= 0) 0 else text.lastIndexOf('\n', at - 1) + 1
            val nl = text.indexOf('\n', ps)
            val pe = if (nl < 0) text.length else nl + 1
            if (pe > ps) sb.setSpan(RuleSpan(gap, stroke, above = true, colors = colorHolder), ps, pe, flags)
        } else {
            val ps = text.lastIndexOf('\n', text.length - 2).let { if (it < 0) 0 else it + 1 }
            sb.setSpan(RuleSpan(gap, stroke, above = false, colors = colorHolder), ps, text.length, flags)
        }
    }

    private fun applyInline(sb: SpannableStringBuilder, s0: Int, e0: Int, style: InlineStyle, len: Int) {
        val s = s0.coerceIn(0, len)
        val e = e0.coerceIn(s, len)
        if (e <= s) return
        val f = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        when (style) {
            InlineStyle.Bold -> sb.setSpan(StyleSpan(Typeface.BOLD), s, e, f)
            InlineStyle.Italic -> sb.setSpan(StyleSpan(Typeface.ITALIC), s, e, f)
            InlineStyle.Underline -> sb.setSpan(UnderlineSpan(), s, e, f)
            InlineStyle.Strikethrough -> sb.setSpan(StrikethroughSpan(), s, e, f)
            InlineStyle.Superscript -> {
                sb.setSpan(SuperscriptSpan(), s, e, f); sb.setSpan(RelativeSizeSpan(0.75f), s, e, f)
            }
            InlineStyle.Subscript -> {
                sb.setSpan(SubscriptSpan(), s, e, f); sb.setSpan(RelativeSizeSpan(0.75f), s, e, f)
            }
            InlineStyle.Monospace -> sb.setSpan(TypefaceSpan("monospace"), s, e, f)
            InlineStyle.SmallCaps -> sb.setSpan(RelativeSizeSpan(0.85f), s, e, f)
            is InlineStyle.Link -> sb.setSpan(LinkSpan(style.href, colorHolder), s, e, f)
        }
    }

    companion object {
        private const val SAMPLE = "abcdefghijklmnopqrstuvwxyz ABCDEFG eaosrnidlc"

        // Las constantes Layout.BREAK_STRATEGY_*/JUSTIFICATION_MODE_* tienen el mismo valor que
        // las de LineBreaker (que lint prefiere pero son de API 29); aqui son correctas y validas
        // desde minSdk 26 (setJustificationMode existe desde API 26).
        @Suppress("WrongConstant")
        internal fun buildLayout(text: CharSequence, paint: TextPaint, width: Int, s: LayoutSettings): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, s.lineSpacing)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(
                    if (s.hyphenation) Layout.HYPHENATION_FREQUENCY_NORMAL else Layout.HYPHENATION_FREQUENCY_NONE,
                )
                .setJustificationMode(
                    if (s.justify) Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE,
                )
                .build()

        /**
         * Corta [layout] en paginas de [pageHeight] pixeles por lineas enteras. Evita dejar
         * la primera linea de un parrafo sola al pie (huerfana) y la ultima sola en cabeza
         * (viuda) cuando la pagina tiene lineas de sobra. Siempre avanza al menos una linea.
         */
        fun cutPages(layout: Layout, pageHeight: Int): PageMap {
            val lineCount = layout.lineCount
            val text = layout.text
            val firstLines = ArrayList<Int>()
            var line = 0
            while (line < lineCount) {
                firstLines += line
                val top = layout.getLineTop(line)
                var end = line + 1 // exclusiva
                while (end < lineCount && layout.getLineBottom(end) - top <= pageHeight) end++
                if (end < lineCount && end - line >= 4) {
                    val last = end - 1
                    val lastStartsPara = startsParagraph(text, layout.getLineStart(last))
                    val lastEndsPara = endsParagraph(text, layout.getLineEnd(last))
                    if (lastStartsPara && !lastEndsPara) {
                        end-- // huerfana: llevar la primera linea del parrafo a la pagina siguiente
                    } else if (endsParagraph(text, layout.getLineEnd(end)) && !startsParagraph(text, layout.getLineStart(end)) &&
                        !lastEndsPara
                    ) {
                        end-- // viuda: llevar una linea mas para que la ultima no quede sola
                    }
                }
                line = end
            }
            if (firstLines.isEmpty()) firstLines += 0
            val n = firstLines.size
            val lines = IntArray(n + 1)
            val offsets = IntArray(n + 1)
            for (i in 0 until n) {
                lines[i] = firstLines[i]
                offsets[i] = layout.getLineStart(firstLines[i])
            }
            lines[n] = lineCount
            offsets[n] = text.length
            return PageMap(lines, offsets)
        }

        private fun startsParagraph(text: CharSequence, offset: Int): Boolean =
            offset == 0 || text[offset - 1] == '\n'

        private fun endsParagraph(text: CharSequence, lineEnd: Int): Boolean =
            lineEnd >= text.length || (lineEnd > 0 && text[lineEnd - 1] == '\n')
    }
}
