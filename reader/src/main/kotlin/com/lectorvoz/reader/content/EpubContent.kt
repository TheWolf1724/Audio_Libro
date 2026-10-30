package com.lectorvoz.reader.content

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.graphics.Canvas
import com.lectorvoz.core.model.ChapterDocument
import com.lectorvoz.core.model.DocumentSource
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.LocatorRange
import com.lectorvoz.reader.FontProvider
import com.lectorvoz.reader.cache.BitmapLru
import com.lectorvoz.reader.layout.ChapterLayout
import com.lectorvoz.reader.layout.DocumentImageLoader
import com.lectorvoz.reader.layout.EpubPaginator
import com.lectorvoz.reader.layout.ImageBoxSpan
import com.lectorvoz.reader.layout.ImageLoader
import com.lectorvoz.reader.layout.ImageSize
import com.lectorvoz.reader.render.HighlightLayer
import com.lectorvoz.reader.render.EpubPageRenderer
import com.lectorvoz.reader.render.Highlights
import com.lectorvoz.reader.view.PageKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Contenido EPUB: pagina capitulos bajo demanda con [EpubPaginator] (cache LRU de
 * [CACHED_LAYOUTS] capitulos para la configuracion actual) y los dibuja con
 * [EpubPageRenderer]. Las imagenes se decodifican al dibujar cada pagina, al tamano exacto
 * del hueco, en una LRU propia limitada por bytes.
 */
class EpubContent(
    private val source: DocumentSource,
    fonts: FontProvider = FontProvider.Default,
    images: ImageLoader? = DocumentImageLoader(source),
    imageCacheBytes: Long = defaultImageCacheBytes(),
) : PagedContent {

    private val loader = images
    private val imageSizes = HashMap<String, ImageSize?>()

    /** Cargador que memoriza el tamano de cada imagen (se pide en cada maquetacion). */
    private val sizedLoader: ImageLoader? = images?.let { inner ->
        object : ImageLoader {
            override suspend fun size(href: String): ImageSize? {
                synchronized(imageSizes) { if (imageSizes.containsKey(href)) return imageSizes[href] }
                val s = inner.size(href)
                synchronized(imageSizes) { imageSizes[href] = s }
                return s
            }

            override suspend fun load(href: String, width: Int, height: Int): Bitmap? = inner.load(href, width, height)
        }
    }

    private val paginator = EpubPaginator(fonts, sizedLoader)
    private val imageCache = BitmapLru<String>(imageCacheBytes)

    /** Se sustituye en el hilo principal y se lee en el de render: volatil y leido una vez. */
    @Volatile
    private var renderer: EpubPageRenderer? = null
    private val mutex = Mutex()

    @Volatile
    private var config: RenderConfig? = null

    /** Generacion de la configuracion de maquetacion; cambia al repaginar. */
    @Volatile
    private var generation = 0

    private val layouts = object : LinkedHashMap<Int, ChapterLayout>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ChapterLayout>) = size > CACHED_LAYOUTS
    }
    private val docs = object : LinkedHashMap<Int, ChapterDocument>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ChapterDocument>) = size > CACHED_DOCS
    }
    private val pageCounts = IntArray(source.chapterCount) { -1 }
    private val charCounts = IntArray(source.chapterCount) { -1 }

    override val chapterCount: Int get() = source.chapterCount

    override val title: String? get() = source.metadata.title

    override fun configure(config: RenderConfig): Boolean {
        val old = this.config
        this.config = config
        if (renderer == null || old?.viewport?.density != config.viewport.density) {
            renderer = EpubPageRenderer(config.viewport.density)
        }
        val relayout = old == null || old.layout != config.layout || old.viewport != config.viewport
        if (relayout) {
            synchronized(layouts) {
                generation++
                layouts.clear()
                pageCounts.fill(-1)
            }
            imageCache.clear()
        }
        return relayout
    }

    private fun requireConfig(): RenderConfig = config ?: error("EpubContent sin configurar (falta tamano de vista)")

    private fun noteChars(chapter: Int, doc: ChapterDocument) {
        synchronized(layouts) { charCounts[chapter] = doc.plainText.length }
    }

    private suspend fun document(chapter: Int): ChapterDocument {
        synchronized(docs) { docs[chapter] }?.let { return it }
        val doc = source.chapter(chapter)
        synchronized(docs) { docs[chapter] = doc }
        noteChars(chapter, doc)
        return doc
    }

    /** Maquetacion del capitulo [chapter] (cacheada). */
    suspend fun layout(chapter: Int): ChapterLayout {
        require(chapter in 0 until chapterCount) { "Capitulo fuera de rango: $chapter" }
        synchronized(layouts) { layouts[chapter] }?.let { return it }
        return mutex.withLock {
            synchronized(layouts) { layouts[chapter] }?.let { return@withLock it }
            val cfg = requireConfig()
            val gen = generation
            val cl = paginator.paginate(document(chapter), cfg.layout, cfg.viewport)
            synchronized(layouts) {
                if (gen == generation) {
                    layouts[chapter] = cl
                    pageCounts[chapter] = cl.pageCount
                }
            }
            cl
        }
    }

    override fun knownPageCount(chapter: Int): Int? =
        synchronized(layouts) { pageCounts.getOrNull(chapter)?.takeIf { it >= 0 } }

    override suspend fun pageCount(chapter: Int): Int = knownPageCount(chapter) ?: layout(chapter).pageCount

    /**
     * Cuenta en [dispatcher] las paginas de los capitulos que falten para conocer el total
     * exacto. No toma el candado de maquetacion (la navegacion y el seguimiento de la voz no
     * esperan), no mete nada en las caches de capitulos ni de maquetaciones y no decodifica
     * imagenes (solo su tamano, que se memoriza). Usa la misma maquetacion que al mostrar, asi
     * que el numero es exacto. Cancelable entre capitulos y entre fases.
     */
    suspend fun computeAllPageCounts(dispatcher: CoroutineDispatcher = Dispatchers.Default) {
        for (ch in 0 until chapterCount) {
            if (knownPageCount(ch) != null) continue
            val cfg = config ?: return
            val gen = synchronized(layouts) { generation }
            val count = synchronized(layouts) { layouts[ch] }?.pageCount ?: run {
                val doc = synchronized(docs) { docs[ch] } ?: source.chapter(ch).also { noteChars(ch, it) }
                currentCoroutineContext().ensureActive()
                paginator.paginate(doc, cfg.layout, cfg.viewport, dispatcher).pageCount
            }
            synchronized(layouts) { if (gen == generation && pageCounts[ch] < 0) pageCounts[ch] = count }
            currentCoroutineContext().ensureActive()
        }
    }

    override suspend fun keyFor(locator: Locator): PageKey {
        val ch = locator.chapter.coerceIn(0, chapterCount - 1)
        val cl = layout(ch)
        return PageKey(ch, cl.pageForOffset(locator.offset))
    }

    override suspend fun startOf(key: PageKey): Locator {
        val cl = layout(key.chapter)
        val p = key.page.coerceIn(0, cl.pageCount - 1)
        return Locator(key.chapter, cl.pages.startOffset(p))
    }

    override suspend fun offsetsOf(key: PageKey): IntRange {
        val cl = layout(key.chapter)
        val p = key.page.coerceIn(0, cl.pageCount - 1)
        return cl.pages.startOffset(p) until cl.pages.endOffset(p)
    }

    override fun bitmapSize(key: PageKey): Pair<Int, Int> {
        val v = requireConfig().viewport
        return v.widthPx to v.heightPx
    }

    override suspend fun render(key: PageKey, bitmap: Bitmap, highlights: Highlights, footer: String?) {
        val cfg = requireConfig()
        val r = renderer ?: return
        val cl = layout(key.chapter)
        val page = key.page.coerceIn(0, cl.pageCount - 1)
        val header = if (cfg.layout.chrome.showHeader) chapterTitle(key.chapter) else null
        val boxes = cl.imagesOn(page)
        for (b in boxes) b.bitmap = image(b)
        try {
            r.draw(
                Canvas(bitmap), cl, page, cfg.colors, highlights,
                header = header,
                footer = if (cfg.layout.chrome.showFooter) footer else null,
            )
        } finally {
            for (b in boxes) b.bitmap = null
        }
    }

    override suspend fun voiceLayer(key: PageKey, sentence: LocatorRange?, word: LocatorRange?): HighlightLayer? {
        val cfg = requireConfig()
        val r = renderer ?: return null
        val cl = layout(key.chapter)
        return r.voiceLayer(cl, key.page.coerceIn(0, cl.pageCount - 1), cfg.colors, sentence, word)
    }

    /** Imagen de [span] al tamano de su hueco (LRU propia). */
    private suspend fun image(span: ImageBoxSpan): Bitmap? {
        val l = loader ?: return null
        val key = "${span.width}x${span.height}:${span.href}"
        imageCache[key]?.let { return it }
        val bmp = try {
            l.load(span.href, span.width, span.height)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        imageCache.put(key, bmp)
        return bmp
    }

    override suspend fun hitTest(key: PageKey, x: Float, y: Float): Locator? {
        val cl = layout(key.chapter)
        val p = key.page.coerceIn(0, cl.pageCount - 1)
        val g = cl.geometry
        val first = cl.pages.firstLine(p)
        val last = cl.pages.endLine(p) - 1
        val ly = y - g.contentTop + cl.layout.getLineTop(first)
        val line = cl.layout.getLineForVertical(ly.toInt()).coerceIn(first, last)
        val lx = x - g.columnLeft
        val off = cl.layout.getOffsetForHorizontal(line, lx)
        return Locator(key.chapter, off.coerceIn(0, cl.plainText.length))
    }

    override suspend fun pageText(key: PageKey): String {
        val cl = layout(key.chapter)
        return cl.pageText(key.page.coerceIn(0, cl.pageCount - 1))
    }

    override suspend fun textOf(range: LocatorRange): String? {
        if (range.isEmpty) return null
        val first = range.start.chapter.coerceIn(0, chapterCount - 1)
        val last = range.endExclusive.chapter.coerceIn(first, chapterCount - 1)
        val sb = StringBuilder()
        for (ch in first..last) {
            val text = document(ch).plainText
            val s = if (ch == range.start.chapter) range.start.offset.coerceIn(0, text.length) else 0
            val e = if (ch == range.endExclusive.chapter) range.endExclusive.offset.coerceIn(s, text.length) else text.length
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(text, s, e)
        }
        return sb.toString().replace(ChapterDocument.OBJECT_CHAR, ' ').replace('\n', ' ').trim()
    }

    override fun chapterTitle(chapter: Int): String? =
        synchronized(docs) { docs[chapter]?.title } ?: source.toc.firstOrNull { it.target.chapter == chapter }?.title
            ?: source.metadata.title

    override fun totalPages(): Pair<Int, Boolean> = synchronized(layouts) {
        var known = 0
        var knownChapters = 0
        var knownChars = 0L
        var unknownChars = 0L
        var unknownNoChars = 0
        for (i in pageCounts.indices) {
            if (pageCounts[i] >= 0) {
                known += pageCounts[i]; knownChapters++
                if (charCounts[i] >= 0) knownChars += charCounts[i]
            } else if (charCounts[i] >= 0) {
                unknownChars += charCounts[i]
            } else {
                unknownNoChars++
            }
        }
        if (knownChapters == pageCounts.size) return@synchronized known to true
        val perChapter = if (knownChapters > 0) known.toFloat() / knownChapters else 1f
        val perChar = if (knownChars > 0) known.toFloat() / knownChars else 0f
        val estimate = known + (unknownChars * perChar).toInt() + (unknownNoChars * perChapter).toInt()
        estimate.coerceAtLeast(known + (pageCounts.size - knownChapters)) to false
    }

    override fun globalIndex(key: PageKey): Int? = synchronized(layouts) {
        var sum = 0
        for (i in 0 until key.chapter) {
            if (pageCounts[i] < 0) return@synchronized null
            sum += pageCounts[i]
        }
        sum + key.page
    }

    override fun knownCharCount(chapter: Int): Int? =
        synchronized(layouts) { charCounts.getOrNull(chapter)?.takeIf { it >= 0 } }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            imageCache.clear()
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            // Conservar solo la maquetacion usada mas recientemente (la de la pagina actual).
            synchronized(layouts) {
                val keep = layouts.entries.lastOrNull()
                layouts.clear()
                keep?.let { layouts[it.key] = it.value }
            }
            synchronized(docs) { docs.clear() }
        }
    }

    /** Cierra el libro. El controlador lo llama cuando ya no queda maquetacion ni render en curso. */
    override fun close() {
        synchronized(layouts) { layouts.clear() }
        synchronized(docs) { docs.clear() }
        imageCache.clear()
        source.close()
    }

    companion object {
        private const val CACHED_LAYOUTS = 3
        private const val CACHED_DOCS = 4

        /** Presupuesto de imagenes decodificadas: 1/16 del heap, como mucho 48 MB. */
        fun defaultImageCacheBytes(): Long = minOf(Runtime.getRuntime().maxMemory() / 16, 48L * 1024 * 1024)
    }
}
