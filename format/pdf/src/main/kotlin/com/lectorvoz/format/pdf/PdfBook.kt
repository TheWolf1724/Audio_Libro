package com.lectorvoz.format.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.lectorvoz.core.model.BookMetadata
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.TocEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.roundToInt

/**
 * Documento PDF abierto. Todas las operaciones pasan por un unico hilo ([PdfiumDispatcher])
 * porque pdfium no es thread-safe. Se puede llamar desde cualquier hilo/corrutina.
 *
 * Coordenadas de pagina: puntos PDF con origen arriba a la izquierda (ver [pageSize]).
 */
class PdfBook internal constructor(
    private val backend: PdfBackend,
    private val dispatcher: CoroutineDispatcher,
    /** Metadatos (titulo del PDF o, si no tiene, el nombre del fichero). */
    val metadata: BookMetadata,
    /** Indice a partir de los marcadores; vacio si no hay. */
    val toc: List<TocEntry>,
) : AutoCloseable {

    /** Numero de paginas. */
    val pageCount: Int = backend.pageCount

    private val sizes = arrayOfNulls<PageSize>(pageCount)
    private val textCache = object : LinkedHashMap<Int, PdfPageText>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PdfPageText>) = size > TEXT_CACHE
    }

    /** Ultimas paginas con un limpiador distinto de [PageTextCleaner.Identity] (mismo candado). */
    private val cleanedCache = object : LinkedHashMap<Int, PdfPageText>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PdfPageText>) = size > TEXT_CACHE
    }

    @Volatile
    private var closed = false

    private fun checkOpen() { if (closed) throw PdfException.Closed() }

    private fun checkIndex(index: Int) {
        require(index in 0 until pageCount) { "Pagina $index fuera de 0..${pageCount - 1}" }
    }

    /** Tamano de la pagina [index] en puntos. */
    suspend fun pageSize(index: Int): PageSize {
        checkIndex(index)
        sizes[index]?.let { return it }
        return withContext(dispatcher) {
            checkOpen()
            sizes[index] ?: backend.pageSizePoints(index).also { sizes[index] = it }
        }
    }

    /**
     * Dibuja la pagina [index] en [bitmap] (ARGB_8888 o RGB_565, reutilizable) a escala
     * [scale] pixeles por punto. [left]/[top] son el origen de la region que se quiere ver,
     * en pixeles a esa escala (0,0 = esquina de la pagina): sirve para zoom y recorte.
     * El bitmap se rellena de blanco donde no hay pagina.
     */
    suspend fun renderPage(index: Int, bitmap: Bitmap, scale: Float, left: Int = 0, top: Int = 0) {
        checkIndex(index)
        require(scale > 0f) { "scale debe ser positivo" }
        val size = pageSize(index)
        withContext(dispatcher) {
            checkOpen()
            // La llamada nativa no se puede interrumpir: descartar aqui los renders obsoletos.
            currentCoroutineContext().ensureActive()
            bitmap.eraseColor(android.graphics.Color.WHITE)
            backend.render(
                index, bitmap,
                startX = -left, startY = -top,
                drawWidth = (size.width * scale).roundToInt().coerceAtLeast(1),
                drawHeight = (size.height * scale).roundToInt().coerceAtLeast(1),
            )
        }
    }

    /**
     * Texto con cajas de la pagina [index], ya filtrado con [cleaner]. Se cachean las
     * ultimas paginas (el texto fluido se calcula una vez por pagina y limpiador).
     */
    suspend fun pageText(index: Int, cleaner: PageTextCleaner = PageTextCleaner.Identity): PdfPageText {
        checkIndex(index)
        checkOpen()
        if (cleaner !== PageTextCleaner.Identity) {
            synchronized(textCache) { cleanedCache[index] }?.let { if (it.usesCleaner(cleaner)) return it }
        }
        val base = synchronized(textCache) { textCache[index] } ?: withContext(dispatcher) {
            checkOpen()
            currentCoroutineContext().ensureActive()
            synchronized(textCache) { textCache[index] } ?: run {
                val raw = backend.pageText(index)
                val size = sizes[index] ?: backend.pageSizePoints(index).also { sizes[index] = it }
                PdfPageText(index, raw.text, raw.boxes, size).also {
                    synchronized(textCache) { textCache[index] = it }
                }
            }
        }
        if (cleaner === PageTextCleaner.Identity) return base
        val cleaned = base.withCleaner(cleaner)
        synchronized(textCache) { cleanedCache[index] = cleaned }
        return cleaned
    }

    /** Texto ya extraido de [index] (sin llamar a pdfium), o null. */
    fun cachedPageText(index: Int, cleaner: PageTextCleaner = PageTextCleaner.Identity): PdfPageText? =
        synchronized(textCache) {
            if (cleaner === PageTextCleaner.Identity) textCache[index] else cleanedCache[index]?.takeIf { it.usesCleaner(cleaner) }
        }

    /** Suelta el texto cacheado (respuesta a falta de memoria). */
    fun trimMemory() {
        synchronized(textCache) { textCache.clear(); cleanedCache.clear() }
    }

    /** Fuente de texto para la voz (una "pagina" = un capitulo). */
    fun textSource(cleaner: PageTextCleaner = PageTextCleaner.Identity): PdfTextSource =
        PdfTextSource(this, cleaner)

    /** Cierra el documento (asincrono en el hilo de pdfium). */
    override fun close() {
        if (closed) return
        closed = true
        trimMemory()
        if (dispatcher.isDispatchNeeded(EmptyCoroutineContext)) {
            dispatcher.dispatch(EmptyCoroutineContext, Runnable { backend.close() })
        } else {
            backend.close()
        }
    }

    companion object {
        private const val TEXT_CACHE = 6

        /** Abre un PDF desde un Uri de SAF o content://. */
        suspend fun open(context: Context, uri: Uri, password: String? = null): PdfBook {
            val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "PDF"
            return withContext(PdfiumDispatcher.dispatcher) {
                val fd = try {
                    context.contentResolver.openFileDescriptor(uri, "r")
                        ?: throw PdfException.Unreadable("sin descriptor para $uri")
                } catch (e: FileNotFoundException) {
                    throw PdfException.Unreadable(e.message, e)
                } catch (e: SecurityException) {
                    throw PdfException.Unreadable(e.message, e)
                }
                create(PdfiumBackend.open(context, fd, password), PdfiumDispatcher.dispatcher, name)
            }
        }

        /** Abre un PDF desde un fichero local. */
        suspend fun open(context: Context, file: File, password: String? = null): PdfBook =
            withContext(PdfiumDispatcher.dispatcher) {
                val fd = try {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                } catch (e: FileNotFoundException) {
                    throw PdfException.Unreadable(e.message, e)
                }
                create(PdfiumBackend.open(context, fd, password), PdfiumDispatcher.dispatcher, file.nameWithoutExtension)
            }

        /**
         * Crea un libro sobre un [PdfBackend] cualquiera (pruebas o motores alternativos).
         * Debe llamarse desde [dispatcher].
         */
        fun create(backend: PdfBackend, dispatcher: CoroutineDispatcher, fallbackTitle: String): PdfBook {
            try {
                val meta = runCatching { backend.meta() }.getOrNull()
                val metadata = BookMetadata(
                    title = meta?.title ?: fallbackTitle,
                    authors = meta?.author?.split(';', ',', '&')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                    description = meta?.subject,
                )
                val toc = runCatching { backend.bookmarks() }.getOrDefault(emptyList()).map { it.toToc() }
                return PdfBook(backend, dispatcher, metadata, toc)
            } catch (e: RuntimeException) {
                runCatching { backend.close() }
                throw PdfException.Corrupt(e.message, e)
            }
        }

        private fun PdfBookmark.toToc(): TocEntry = TocEntry(
            title = title.ifBlank { "Pagina ${pageIndex + 1}" },
            target = Locator(pageIndex.coerceAtLeast(0), 0),
            children = children.map { it.toToc() },
        )
    }
}
