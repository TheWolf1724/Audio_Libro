package com.lectorvoz.format.epub

import com.lectorvoz.core.model.Block
import com.lectorvoz.core.model.BlockKind
import com.lectorvoz.core.model.BookFormat
import com.lectorvoz.core.model.BookMetadata
import com.lectorvoz.core.model.ChapterDocument
import com.lectorvoz.core.model.ChapterText
import com.lectorvoz.core.model.DocumentSource
import com.lectorvoz.core.model.InlineStyle
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.NoteCallLabel
import com.lectorvoz.core.model.TextSource
import com.lectorvoz.core.model.TocEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Libro EPUB 2/3 abierto sobre un [java.util.zip.ZipFile] (acceso aleatorio, sin
 * descomprimir todo). Implementa [DocumentSource] para el lector y [TextSource] para la voz.
 *
 * Los capitulos se analizan bajo demanda en [dispatcher] y se guardan en una LRU de
 * [cacheSize] entradas. Es seguro usarlo desde varias corrutinas a la vez. Hay que cerrarlo
 * con [close].
 */
class EpubBook private constructor(
    private val zip: ZipContainer,
    private val pkg: PackageInfo,
    private val dispatcher: CoroutineDispatcher,
    private val cacheSize: Int,
) : DocumentSource, TextSource {

    override val format: BookFormat get() = BookFormat.EPUB
    override val metadata: BookMetadata get() = pkg.metadata
    override val chapterCount: Int get() = pkg.spine.size

    private val spineIndex: Map<String, Int> = HashMap<String, Int>().also { m ->
        pkg.spine.forEachIndexed { i, item -> m.putIfAbsent(item.path, i) }
    }

    private val cache = object : LinkedHashMap<Int, ChapterDocument>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ChapterDocument>?): Boolean = size > cacheSize
    }
    private val anchorCache = ConcurrentHashMap<Int, Map<String, Int>>()

    /** Un candado por capitulo: dos corrutinas no analizan a la vez el mismo capitulo. */
    private val chapterLocks = Array(pkg.spine.size) { Any() }
    private val parses = AtomicInteger()

    /** Numero de capitulos analizados desde la apertura (para pruebas de la cache). */
    internal val parseCount: Int get() = parses.get()

    /** Titulo del indice para cada capitulo (primera entrada que apunta a el). */
    private val tocTitles = HashMap<Int, String>()

    override val toc: List<TocEntry> = buildToc()

    /** Ruta en el contenedor del capitulo [index]. */
    fun chapterPath(index: Int): String = pkg.spine[index].path

    /** Version declarada del paquete ("2.0", "3.0"...). */
    val version: String get() = pkg.version

    override suspend fun chapter(index: Int): ChapterDocument = withContext(dispatcher) { loadChapter(index) }

    override suspend fun chapterText(chapter: Int): ChapterText {
        val doc = chapter(chapter)
        return ChapterText(doc.index, doc.plainText, doc.title, doc.language)
    }

    override suspend fun resource(href: String): ByteArray? = withContext(dispatcher) {
        val path = EpubPaths.normalize(EpubPaths.percentDecode(href.substringBefore('#')))
            ?: throw EpubException.UnsafePath(href)
        zip.readOrNull(path)
    }

    /**
     * Convierte un enlace interno ya resuelto ("OEBPS/cap2.xhtml#nota3", como en
     * `InlineStyle.Link`) en una posicion; null si no apunta a un capitulo del spine.
     */
    suspend fun locate(href: String): Locator? = withContext(dispatcher) {
        if (EpubPaths.isExternal(href)) return@withContext null
        val path = EpubPaths.normalize(EpubPaths.percentDecode(href.substringBefore('#'))) ?: return@withContext null
        val frag = EpubPaths.percentDecode(href.substringAfter('#', "")).ifEmpty { null }
        locatorFor(path, frag)
    }

    /**
     * Textos de las notas a las que llama el capitulo [index]: para cada enlace interno con
     * fragmento cuyo texto parece una llamada de nota ("1", "[2]", "*"), el texto del bloque
     * de destino (aunque este en otro capitulo), recortado a [maxChars]. La clave es el
     * `href` tal como aparece en [com.lectorvoz.core.model.InlineStyle.Link]. Pensado para
     * `TextOptions.footnoteTexts` de `:core:text` (leer notas al final del parrafo).
     *
     * Solo se acepta un destino que parece una nota: no es un titulo y tiene un enlace de
     * vuelta a este capitulo o empieza por la llamada ("3. Texto", "[3] Texto"); asi un
     * indice o una referencia cruzada ("ver II") no se lee como nota. Se miran como mucho
     * [maxTargetChapters] capitulos de destino, y los que no estaban en la cache se analizan
     * sin guardarlos (no expulsan los capitulos del lector). Las notas que no se pueden
     * resolver no aparecen.
     */
    suspend fun footnoteTexts(
        index: Int,
        maxNotes: Int = 500,
        maxChars: Int = 1_000,
        maxTargetChapters: Int = 4,
    ): Map<String, String> =
        withContext(dispatcher) {
            val doc = loadChapter(index)
            val source = chapterPath(index)
            val targets = HashMap<Int, ChapterDocument>()
            targets[index] = doc
            val out = LinkedHashMap<String, String>()
            for (b in doc.blocks) {
                if (b !is Block.Text) continue
                for (sp in b.spans) {
                    val link = sp.style as? InlineStyle.Link ?: continue
                    if (out.size >= maxNotes) return@withContext out
                    if (link.href in out || '#' !in link.href || EpubPaths.isExternal(link.href)) continue
                    val label = doc.plainText.substring(sp.start, sp.end)
                    if (!isNoteCall(label)) continue
                    val t = runCatching {
                        noteTextAt(link.href, label, source, targets, maxTargetChapters + 1, maxChars)
                    }.getOrNull() ?: continue
                    out[link.href] = t
                }
            }
            out
        }

    override fun close() = zip.close()

    // ------------------------------------------------------------------ internos

    private fun loadChapter(index: Int): ChapterDocument = loadChapter(index, store = true)

    /** Capitulo [index]; con [store] false no se guarda en la cache si no estaba. */
    private fun loadChapter(index: Int, store: Boolean): ChapterDocument {
        require(index in 0 until chapterCount) { "Capitulo fuera de rango: $index" }
        synchronized(cache) { cache[index] }?.let { return it }
        synchronized(chapterLocks[index]) {
            synchronized(cache) { cache[index] }?.let { return it }
            val item = pkg.spine[index]
            val parsed = try {
                when {
                    item.mediaType.startsWith("image/") -> XhtmlConverter(item.path).imageOnly(index, item.path)
                    item.mediaType.contains("html") || item.mediaType.contains("xml") || item.mediaType.isEmpty() -> {
                        val bytes = zip.readDocument(item.path)
                        XhtmlConverter(item.path).convert(Markup.parseXhtml(bytes), index, pkg.metadata.language)
                    }
                    else -> ChapterDocument(index, null, pkg.metadata.language, "", emptyList())
                }
            } catch (e: EpubException) {
                throw e
            } catch (e: Exception) {
                throw EpubException.Invalid("Capitulo $index ilegible: ${e.message}", e)
            } catch (e: StackOverflowError) {
                throw EpubException.Invalid("Capitulo $index demasiado anidado", null)
            }
            parses.incrementAndGet()
            val doc = tocTitles[index]?.let { parsed.copy(title = it) } ?: parsed
            anchorCache.putIfAbsent(index, doc.anchors)
            if (store) synchronized(cache) { cache[index] = doc }
            return doc
        }
    }

    private fun anchorsOf(index: Int): Map<String, Int> = anchorCache[index] ?: loadChapter(index).anchors

    /**
     * Posicion de [path]#[fragment]. Si el capitulo no se puede analizar (demasiado grande,
     * roto...) se usa su inicio: un capitulo malo no impide abrir el libro.
     */
    private fun locatorFor(path: String, fragment: String?): Locator? {
        val idx = spineIndex[path] ?: return null
        val off = if (fragment == null) {
            0
        } else {
            try {
                anchorsOf(idx)[fragment] ?: 0
            } catch (e: Exception) {
                0
            }
        }
        return Locator(idx, off)
    }

    /**
     * Texto del bloque al que apunta [href] (enlace interno ya resuelto y decodificado, como
     * en `InlineStyle.Link`) si parece una nota de la llamada [label] hecha desde [source].
     * [targets] guarda los capitulos de destino ya mirados (como mucho [maxTargets]).
     */
    private fun noteTextAt(
        href: String,
        label: String,
        source: String,
        targets: MutableMap<Int, ChapterDocument>,
        maxTargets: Int,
        maxChars: Int,
    ): String? {
        val path = EpubPaths.normalize(href.substringBefore('#')) ?: return null
        val frag = href.substringAfter('#', "").ifEmpty { return null }
        val idx = spineIndex[path] ?: return null
        val doc = targets[idx] ?: run {
            if (targets.size >= maxTargets) return null
            loadChapter(idx, store = false).also { targets[idx] = it }
        }
        val off = doc.anchors[frag] ?: return null
        val block = doc.blocks.firstOrNull { it is Block.Text && it.end > off } as? Block.Text ?: return null
        if (block.kind is BlockKind.Heading) return null
        val start = maxOf(off, block.start)
        if (!looksLikeNote(doc.plainText, block, start, label, source)) return null
        val text = doc.plainText.substring(start, block.end).trim()
        if (text.isEmpty()) return null
        return if (text.length > maxChars) text.substring(0, maxChars) else text
    }

    /** true si el bloque tiene un enlace de vuelta a [source] o empieza por la llamada [label]. */
    private fun looksLikeNote(text: String, block: Block.Text, start: Int, label: String, source: String): Boolean {
        val back = block.spans.any { sp ->
            val l = sp.style as? InlineStyle.Link
            l != null && sp.start >= start && l.href.substringBefore('#') == source
        }
        if (back) return true
        val core = label.trim().trim('[', ']', '(', ')', '.').trim()
        if (core.isEmpty()) return false
        var k = start
        while (k < block.end && (text[k].isWhitespace() || text[k] == '[' || text[k] == '(')) k++
        if (!text.startsWith(core, k)) return false
        val after = k + core.length
        return after < block.end && !text[after].isLetterOrDigit()
    }

    private fun buildToc(): List<TocEntry> {
        val raw = try {
            when {
                pkg.nav != null -> PackageParser.parseNav(zip, pkg.nav).ifEmpty {
                    pkg.ncx?.let { PackageParser.parseNcx(zip, it) }.orEmpty()
                }
                pkg.ncx != null -> PackageParser.parseNcx(zip, pkg.ncx)
                else -> emptyList()
            }
        } catch (e: Exception) {
            emptyList() // un indice roto (o demasiado grande) no impide leer el libro
        }
        val entries = convertToc(raw)
        fillTitles(entries)
        return entries
    }

    private fun convertToc(raw: List<RawTocEntry>): List<TocEntry> {
        val out = ArrayList<TocEntry>()
        for (r in raw) {
            val children = convertToc(r.children)
            val target = r.path?.let { locatorFor(it, r.fragment) }
            when {
                target != null -> out.add(TocEntry(r.title, target, children))
                // Sin destino: se usa el del primer hijo; si no hay, se suben los hijos.
                children.isNotEmpty() -> out.add(TocEntry(r.title, children.first().target, children))
            }
        }
        return out
    }

    /** Titulo de cada capitulo: la entrada del indice mas cercana a su inicio. */
    private fun fillTitles(entries: List<TocEntry>) {
        val best = HashMap<Int, Int>()
        fun visit(list: List<TocEntry>) {
            for (e in list) {
                if (e.title.isNotEmpty()) {
                    val prev = best[e.target.chapter]
                    if (prev == null || e.target.offset < prev) {
                        best[e.target.chapter] = e.target.offset
                        tocTitles[e.target.chapter] = e.title
                    }
                }
                visit(e.children)
            }
        }
        visit(entries)
        // Los capitulos ya analizados al construir el indice se rehacen con su titulo.
        synchronized(cache) { cache.clear() }
    }

    companion object {
        /** true si el texto de un enlace parece una llamada de nota ("1", "[2]", "*", "iv"). */
        internal fun isNoteCall(label: String): Boolean = NoteCallLabel.matches(label)

        /**
         * Abre [file]. Es bloqueante (lee el paquete y el indice): llamarlo fuera del hilo
         * principal. Lanza [EpubException] si el libro no es valido, tiene DRM o es peligroso.
         */
        fun open(
            file: File,
            limits: EpubLimits = EpubLimits(),
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
            cacheSize: Int = 3,
        ): EpubBook {
            require(cacheSize >= 1) { "cacheSize debe ser >= 1" }
            val zip = ZipContainer(file, limits)
            try {
                PackageParser.checkDrm(zip)
                val opf = PackageParser.opfPath(zip)
                val pkg = PackageParser.parsePackage(zip, opf)
                return EpubBook(zip, pkg, dispatcher, cacheSize)
            } catch (e: EpubException) {
                zip.close()
                throw e
            } catch (e: Exception) {
                zip.close()
                throw EpubException.Invalid("EPUB no valido: ${e.message}", e)
            } catch (e: StackOverflowError) {
                zip.close()
                throw EpubException.Invalid("EPUB no valido: estructura demasiado anidada", null)
            }
        }
    }
}
