package com.lectorvoz.app.books

import android.content.Context
import androidx.core.net.toUri
import com.lectorvoz.app.integration.BookContent
import com.lectorvoz.app.integration.PdfCleanerAdapter
import com.lectorvoz.core.data.library.Book
import com.lectorvoz.core.data.library.RoomLibraryRepository
import com.lectorvoz.core.model.BookFormat
import com.lectorvoz.core.model.TocEntry
import com.lectorvoz.format.epub.EpubBook
import com.lectorvoz.format.pdf.PdfBook
import com.lectorvoz.reader.FontProvider
import com.lectorvoz.app.ui.theme.ReadingFonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Libro abierto: contenido, indice y titulos por capitulo. */
class OpenedBook(
    val book: Book,
    val content: BookContent,
    val toc: List<TocEntry>,
) : AutoCloseable {
    /** Titulo de cada capitulo (spine/pagina) segun el indice, si lo hay. */
    val chapterTitles: List<String?> by lazy {
        val out = arrayOfNulls<String>(content.chapterCount)
        fun visit(list: List<TocEntry>) {
            for (e in list) {
                if (e.target.chapter in out.indices && out[e.target.chapter] == null) out[e.target.chapter] = e.title
                visit(e.children)
            }
        }
        visit(toc)
        out.toList()
    }

    override fun close() {
        when (val c = content) {
            is BookContent.Epub -> c.book.close()
            is BookContent.Pdf -> c.book.close()
        }
    }
}

/** Abre libros de la biblioteca con los motores de formato (fuera del hilo principal). */
open class BookOpener(private val context: Context) {
    /** Texto de las paginas escaneadas de un PDF (OCR); nulo = sin OCR. */
    @Volatile var pdfTextFallback: ((Book) -> com.lectorvoz.format.pdf.PageTextFallback?)? = null

    /** Si un PDF debe forzar el OCR aunque traiga capa de texto (ajuste por libro). */
    @Volatile var pdfForceOcr: (suspend (Book) -> Boolean)? = null

    /** Un cerrojo por libro: dos aperturas a la vez no escriben la misma copia temporal. */
    private val copyLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Abre [book]; [skipPdfHeaders] activa el filtro de cabeceras y pies del PDF.
     *
     * La apertura en si no se cancela a medias: si la corrutina se cancela mientras tanto,
     * el libro ya abierto se cierra aqui (si no, el `ZipFile` o el documento pdfium quedaria
     * abierto sin nadie que lo cierre) y se relanza la cancelacion.
     */
    open suspend fun open(book: Book, skipPdfHeaders: Boolean = true): OpenedBook {
        val epubFile = if (book.format == BookFormat.EPUB) withContext(Dispatchers.IO) { fileFor(book) } else null
        val opened = withContext(NonCancellable + Dispatchers.IO) {
            when (book.format) {
                BookFormat.EPUB -> openEpub(book, epubFile!!)
                BookFormat.PDF -> {
                    val pdf = book.localFile?.let { PdfBook.open(context, it) } ?: PdfBook.open(context, book.uri.toUri())
                    try {
                        pdf.textFallback = pdfTextFallback?.invoke(book)
                        pdf.forceFallback = pdfForceOcr?.invoke(book) ?: false
                        OpenedBook(book, BookContent.Pdf(pdf, PdfCleanerAdapter(pdf, skipPdfHeaders)), pdf.toc)
                    } catch (t: Throwable) {
                        pdf.close()
                        throw t
                    }
                }
            }
        }
        if (!currentCoroutineContext().isActive) {
            opened.close()
            currentCoroutineContext().ensureActive()
        }
        return opened
    }

    private fun openEpub(book: Book, file: File): OpenedBook {
        val epub = EpubBook.open(file)
        try {
            return OpenedBook(book, BookContent.Epub(epub), epub.toc)
        } catch (t: Throwable) {
            epub.close()
            throw t
        }
    }

    /**
     * Fichero local: la copia privada o, si solo se referencia, una copia en
     * `cacheDir/abiertos` (la borra `LibraryRepository.delete`). Se escribe en un temporal
     * de nombre unico y se renombra al final, con un cerrojo por libro.
     */
    private suspend fun fileFor(book: Book): File {
        book.localFile?.let { return it }
        val dir = File(context.cacheDir, RoomLibraryRepository.OPENED_CACHE_DIR).apply { mkdirs() }
        val f = File(dir, "${book.quickHash}.${if (book.format == BookFormat.EPUB) "epub" else "pdf"}")
        copyLocks.getOrPut(book.quickHash) { Mutex() }.withLock {
            if (f.exists() && f.length() == book.sizeBytes) return f
            val tmp = File.createTempFile(book.quickHash, ".tmp", dir)
            try {
                context.contentResolver.openInputStream(book.uri.toUri())?.use { ins ->
                    tmp.outputStream().use { ins.copyTo(it) }
                } ?: error("No se puede abrir el original")
                if (!tmp.renameTo(f)) {
                    tmp.copyTo(f, overwrite = true)
                }
            } finally {
                tmp.delete()
            }
        }
        return f
    }
}

/**
 * Libros abiertos compartidos por recuento de referencias: la voz, la busqueda del lector y
 * la exportacion usan la misma instancia (un solo `ZipFile` o documento pdfium por libro,
 * ademas del que maqueta el lector). Se cierra al soltar la ultima referencia.
 */
class SharedBooks(private val opener: BookOpener) {
    private val openLock = Mutex()
    private val open = HashMap<Long, Entry>()

    internal class Entry(val opened: OpenedBook, var refs: Int)

    /** Referencia a un libro abierto; [release] una sola vez (las siguientes no hacen nada). */
    inner class Lease internal constructor(private val entry: Entry) {
        val opened: OpenedBook get() = entry.opened
        private var released = false

        fun release() {
            val close = synchronized(open) {
                if (released) return
                released = true
                entry.refs--
                if (entry.refs == 0) {
                    if (open[entry.opened.book.id] === entry) open.remove(entry.opened.book.id)
                    true
                } else {
                    false
                }
            }
            if (close) entry.opened.close()
        }
    }

    /** Abre [book] (o reutiliza la instancia abierta). */
    suspend fun acquire(book: Book, skipPdfHeaders: Boolean): Lease {
        // La preferencia "forzar OCR" (lectura suspendida de DataStore) se lee FUERA de openLock:
        // asi una adquisicion no serializa a las demas (inicio de voz, busqueda, exportacion,
        // seguimiento del OCR) detras de esa IO (S2-3). Dentro del candado solo se aplica el flag.
        val force = opener.pdfForceOcr?.invoke(book)
        return openLock.withLock {
            val existing = synchronized(open) { open[book.id]?.also { it.refs++ } }
            if (existing != null) {
                // Al reutilizar un Entry, la preferencia "forzar OCR" del libro pudo cambiar
                // mientras seguia abierto (activar OCR con la voz fuera del libro, A1): se reaplica
                // para que la voz, la busqueda y la exportacion no lean el texto incompleto.
                if (force != null) reapplyForceOcr(existing.opened, force)
                return@withLock Lease(existing)
            }
            val e = Entry(opener.open(book, skipPdfHeaders), 1)
            synchronized(open) { open[book.id] = e }
            Lease(e)
        }
    }

    /**
     * Reaplica el valor ya resuelto de "forzar OCR" por libro a una instancia PDF ya abierta. Si
     * el flag cambia, [PdfBook.forceFallback] olvida el texto cacheado y se reinicia el limpiador
     * para no servir texto preparado sin OCR. No hace IO: el valor se leyo fuera del candado.
     */
    private fun reapplyForceOcr(opened: OpenedBook, force: Boolean) {
        val pdf = opened.content as? BookContent.Pdf ?: return
        if (pdf.book.forceFallback != force) {
            pdf.book.forceFallback = force
            pdf.cleaner.reset()
        }
    }

    /** Numero de libros abiertos (pruebas). */
    val openCount: Int get() = synchronized(open) { open.size }
}

/** Tipografias de la app para `:reader` (Literata, Atkinson, OpenDyslexic). */
class AppFontProvider(private val context: Context) : FontProvider {
    private val cache = HashMap<String, android.graphics.Typeface>()

    override fun typeface(family: String, weight: Int, italic: Boolean): android.graphics.Typeface {
        val key = "$family/$weight/$italic"
        synchronized(cache) { cache[key]?.let { return it } }
        val name = if (family == "book") ReadingFonts.LITERATA else family
        val tf = if (name in ReadingFonts.names && name != ReadingFonts.SYSTEM) {
            ReadingFonts.typeface(context, name, weight, italic)
        } else {
            FontProvider.Default.typeface(family, weight, italic)
        }
        synchronized(cache) { cache[key] = tf }
        return tf
    }
}
