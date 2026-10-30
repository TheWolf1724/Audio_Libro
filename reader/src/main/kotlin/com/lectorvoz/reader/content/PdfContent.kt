package com.lectorvoz.reader.content

import android.graphics.Bitmap
import android.graphics.RectF
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.LocatorRange
import com.lectorvoz.format.pdf.PageSize
import com.lectorvoz.format.pdf.PageTextCleaner
import com.lectorvoz.format.pdf.PdfBook
import com.lectorvoz.format.pdf.PdfTextSource
import com.lectorvoz.reader.render.HighlightLayer
import com.lectorvoz.reader.render.Highlights
import com.lectorvoz.reader.render.PdfPageHighlights
import com.lectorvoz.reader.render.PdfPageRenderer
import com.lectorvoz.reader.render.PdfPlacement
import com.lectorvoz.reader.view.PageKey
import kotlin.math.roundToInt

/**
 * Contenido PDF: una pagina fisica por capitulo. Render con pdfium al tamano de la vista
 * (ajuste a ancho, o a pagina si no cabe en modo paginas), recoloreado opcional,
 * resaltados por rectangulos de caracteres y pie opcional ([com.lectorvoz.reader.layout.PageChrome.pdfFooter]).
 */
class PdfContent(
    private val book: PdfBook,
    private val cleaner: PageTextCleaner = PageTextCleaner.Identity,
) : PagedContent {

    val textSource: PdfTextSource = book.textSource(cleaner)

    /** Se sustituye en el hilo principal y se usa en el de render: volatil y leido una vez. */
    @Volatile
    private var renderer: PdfPageRenderer? = null

    @Volatile
    private var config: RenderConfig? = null
    private val sizes = arrayOfNulls<PageSize>(book.pageCount)
    private val charCounts = IntArray(book.pageCount) { -1 }

    override val chapterCount: Int get() = book.pageCount

    override val title: String? get() = book.metadata.title

    override fun configure(config: RenderConfig): Boolean {
        val old = this.config
        this.config = config
        if (renderer == null || old?.viewport?.density != config.viewport.density) {
            // El anterior puede estar dibujando en el hilo de render: no se libera, lo recoge el GC.
            renderer = PdfPageRenderer(config.viewport.density)
        }
        return old == null || old.viewport != config.viewport || old.scrollMode != config.scrollMode ||
            old.layout.chrome.pdfFooter != config.layout.chrome.pdfFooter
    }

    private fun requireConfig(): RenderConfig = config ?: error("PdfContent sin configurar (falta tamano de vista)")

    override fun knownPageCount(chapter: Int): Int? = if (chapter in 0 until chapterCount) 1 else null

    override suspend fun pageCount(chapter: Int): Int = 1

    override suspend fun keyFor(locator: Locator): PageKey =
        PageKey(locator.chapter.coerceIn(0, (chapterCount - 1).coerceAtLeast(0)), 0)

    override suspend fun startOf(key: PageKey): Locator = Locator(key.chapter, 0)

    override suspend fun offsetsOf(key: PageKey): IntRange = 0 until text(key.chapter).length

    /** Texto limpio de [page]; anota su longitud (en indices de pdfium, como los Locator). */
    private suspend fun text(page: Int) =
        book.pageText(page, cleaner).also { synchronized(charCounts) { charCounts[page] = it.length } }

    private suspend fun size(page: Int): PageSize =
        sizes[page] ?: book.pageSize(page).also { sizes[page] = it }

    /** Alto reservado al pie en modo paginas (0 si no hay pie). */
    private fun footerReserve(cfg: RenderConfig): Int =
        if (cfg.layout.chrome.pdfFooter && !cfg.scrollMode) (FOOTER_DP * cfg.viewport.density).roundToInt() else 0

    private fun placement(size: PageSize, cfg: RenderConfig): PdfPlacement =
        PdfPlacement.fit(
            size, cfg.viewport.widthPx,
            if (cfg.scrollMode) 0 else (cfg.viewport.heightPx - footerReserve(cfg)).coerceAtLeast(1),
        )

    override fun bitmapSize(key: PageKey): Pair<Int, Int> {
        val cfg = requireConfig()
        val v = cfg.viewport
        if (!cfg.scrollMode) return v.widthPx to v.heightPx
        val s = sizes.getOrNull(key.chapter) ?: return v.widthPx to v.heightPx
        return v.widthPx to (s.height * v.widthPx / s.width.coerceAtLeast(1f)).roundToInt().coerceAtLeast(1)
    }

    /** Alto conocido de la pagina en modo scroll (null si aun no se sabe su tamano). */
    fun knownScrollHeight(page: Int, width: Int): Int? {
        val s = sizes.getOrNull(page) ?: return null
        return (s.height * width / s.width.coerceAtLeast(1f)).roundToInt().coerceAtLeast(1)
    }

    override suspend fun render(key: PageKey, bitmap: Bitmap, highlights: Highlights, footer: String?) {
        val cfg = requireConfig()
        val r = renderer ?: return
        val page = key.chapter
        val pl = placement(size(page), cfg)
        r.render(
            book, page, bitmap, pl, cfg.colors, cfg.pdfRecolor,
            highlights = pageHighlights(page, highlights),
            footer = if (cfg.layout.chrome.pdfFooter) footer else null,
        )
    }

    override suspend fun voiceLayer(key: PageKey, sentence: LocatorRange?, word: LocatorRange?): HighlightLayer? {
        val cfg = requireConfig()
        val r = renderer ?: return null
        val page = key.chapter
        val s = rects(sentence, page)
        val w = rects(word, page)
        if (s.isEmpty() && w.isEmpty()) return null
        return r.voiceLayer(placement(size(page), cfg), cfg.colors, darkPage = cfg.pdfRecolor && cfg.colors.isDark, sentence = s, word = w)
    }

    private suspend fun rects(range: LocatorRange?, page: Int): List<RectF> {
        if (range == null || range.start.chapter > page || range.endExclusive.chapter < page) return emptyList()
        return textSource.rectsFor(range)[page].orEmpty()
    }

    private suspend fun pageHighlights(page: Int, hl: Highlights): PdfPageHighlights {
        if (hl == Highlights.NONE) return PdfPageHighlights()
        val others = hl.search.filter { it != hl.searchCurrent }
        return PdfPageHighlights(
            sentence = rects(hl.sentence, page),
            word = rects(hl.word, page),
            selection = rects(hl.selection, page),
            search = others.flatMap { rects(it, page) },
            searchCurrent = rects(hl.searchCurrent, page),
        )
    }

    override suspend fun hitTest(key: PageKey, x: Float, y: Float): Locator? {
        val cfg = requireConfig()
        val pl = placement(size(key.chapter), cfg)
        val px = (x - pl.offsetX) / pl.scale
        val py = (y - pl.offsetY) / pl.scale
        return textSource.locatorAt(key.chapter, px, py)
    }

    override suspend fun pageText(key: PageKey): String = text(key.chapter).flow.text

    override suspend fun textOf(range: LocatorRange): String? {
        if (range.isEmpty) return null
        val first = range.start.chapter.coerceIn(0, chapterCount - 1)
        val last = range.endExclusive.chapter.coerceIn(first, chapterCount - 1)
        val sb = StringBuilder()
        for (p in first..last) {
            val t = text(p)
            val s = if (p == range.start.chapter) range.start.offset else 0
            val e = if (p == range.endExclusive.chapter) range.endExclusive.offset else t.length
            val part = t.flow.textForSource(s, e)
            if (sb.isNotEmpty() && part.isNotEmpty()) sb.append(' ')
            sb.append(part)
        }
        return sb.toString().replace('\n', ' ').trim()
    }

    override fun chapterTitle(chapter: Int): String? = book.metadata.title

    override fun totalPages(): Pair<Int, Boolean> = book.pageCount to true

    override fun globalIndex(key: PageKey): Int = key.chapter

    override fun knownCharCount(chapter: Int): Int? =
        synchronized(charCounts) { charCounts.getOrNull(chapter)?.takeIf { it >= 0 } }

    override fun onTrimMemory(level: Int) {
        @Suppress("DEPRECATION")
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) book.trimMemory()
    }

    /** Cierra el libro. El controlador lo llama cuando ya no queda render en curso. */
    override fun close() {
        renderer?.release()
        book.close()
    }

    private companion object {
        const val FOOTER_DP = 20f
    }
}
