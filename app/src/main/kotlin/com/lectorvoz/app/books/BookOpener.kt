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
class BookOpener(private val context: Context) {
    /** Un cerrojo por libro: dos aperturas a la vez no escriben la misma copia temporal. */
    private val copyLocks = ConcurrentHashMap<String, Mutex>()

    /** Abre [book]; [skipPdfHeaders] activa el filtro de cabeceras y pies del PDF. */
    suspend fun open(book: Book, skipPdfHeaders: Boolean = true): OpenedBook = withContext(Dispatchers.IO) {
        when (book.format) {
            BookFormat.EPUB -> {
                val epub = EpubBook.open(fileFor(book))
                OpenedBook(book, BookContent.Epub(epub), epub.toc)
            }
            BookFormat.PDF -> {
                val pdf = book.localFile?.let { PdfBook.open(context, it) } ?: PdfBook.open(context, book.uri.toUri())
                OpenedBook(book, BookContent.Pdf(pdf, PdfCleanerAdapter(pdf, skipPdfHeaders)), pdf.toc)
            }
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
    suspend fun acquire(book: Book, skipPdfHeaders: Boolean): Lease = openLock.withLock {
        synchronized(open) {
            open[book.id]?.let { e ->
                e.refs++
                return@withLock Lease(e)
            }
        }
        val e = Entry(opener.open(book, skipPdfHeaders), 1)
        synchronized(open) { open[book.id] = e }
        Lease(e)
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
