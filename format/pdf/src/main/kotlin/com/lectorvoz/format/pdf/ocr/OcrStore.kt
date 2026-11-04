package com.lectorvoz.format.pdf.ocr

import com.lectorvoz.format.pdf.RawPageText
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.roundToInt

/**
 * Texto reconocido (OCR) guardado en la memoria interna de la app, una carpeta por libro y un
 * fichero comprimido por pagina: texto UTF-8 y cajas en enteros de 1/8 de punto (~3-6 KB por
 * pagina). Asi cada pagina se reconoce una sola vez. Se borra entera con [deleteBook] (libro
 * terminado o quitado de la biblioteca).
 *
 * Ademas guarda que paginas ya tienen texto propio (no necesitan OCR), para no volver a
 * mirarlas. Seguro entre hilos; operaciones de disco: fuera del hilo principal.
 */
class OcrStore(private val root: File) {
    private val lock = Any()

    private fun dir(bookId: Long) = File(root, bookId.toString())
    private fun pageFile(bookId: Long, index: Int) = File(dir(bookId), "p$index.ocr")
    private fun nativeFile(bookId: Long) = File(dir(bookId), "con-texto")
    private fun textualFile(bookId: Long) = File(dir(bookId), "pdf-con-texto")

    /** true si [bookId] es un PDF con texto propio (no necesita OCR de fondo). */
    fun isTextual(bookId: Long): Boolean = textualFile(bookId).isFile

    fun markTextual(bookId: Long) = synchronized(lock) {
        dir(bookId).mkdirs()
        textualFile(bookId).writeText("1")
    }

    fun has(bookId: Long, index: Int): Boolean = pageFile(bookId, index).isFile

    fun read(bookId: Long, index: Int): RawPageText? = synchronized(lock) {
        val f = pageFile(bookId, index)
        if (!f.isFile) return null
        try {
            DataInputStream(GZIPInputStream(f.inputStream().buffered())).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
                val bytes = ByteArray(input.readInt().also { if (it !in 0..MAX_TEXT_BYTES) throw IOException("texto invalido") })
                input.readFully(bytes)
                val text = String(bytes, Charsets.UTF_8)
                val boxes = FloatArray(text.length * 4)
                for (i in boxes.indices) boxes[i] = input.readShort().toUShortValue() / UNITS
                RawPageText(text, boxes)
            }
        } catch (e: IOException) {
            f.delete()
            null
        }
    }

    fun write(bookId: Long, index: Int, page: RawPageText) = synchronized(lock) {
        val d = dir(bookId).apply { mkdirs() }
        val tmp = File(d, "p$index.part")
        try {
            DataOutputStream(GZIPOutputStream(tmp.outputStream().buffered())).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                val bytes = page.text.toByteArray(Charsets.UTF_8)
                out.writeInt(bytes.size)
                out.write(bytes)
                for (v in page.boxes) out.writeShort((v * UNITS).roundToInt().coerceIn(0, 0xFFFF))
            }
            if (!tmp.renameTo(pageFile(bookId, index))) tmp.delete()
        } catch (e: IOException) {
            tmp.delete()
        }
    }

    /** Paginas de [bookId] que ya tienen texto propio (no necesitan OCR). */
    fun nativePages(bookId: Long): Set<Int> = synchronized(lock) {
        val f = nativeFile(bookId)
        if (!f.isFile) return emptySet()
        f.readText().split(',').mapNotNullTo(HashSet()) { it.trim().toIntOrNull() }
    }

    fun markNative(bookId: Long, pages: Collection<Int>) = synchronized(lock) {
        if (pages.isEmpty()) return
        dir(bookId).mkdirs()
        val all = nativePages(bookId) + pages
        nativeFile(bookId).writeText(all.sorted().joinToString(","))
    }

    /** Paginas ya reconocidas de [bookId]. */
    fun recognizedPages(bookId: Long): Set<Int> = synchronized(lock) {
        dir(bookId).list()?.mapNotNullTo(HashSet()) { n ->
            if (n.startsWith("p") && n.endsWith(".ocr")) n.substring(1, n.length - 4).toIntOrNull() else null
        } ?: emptySet()
    }

    /** Borra todo lo guardado de [bookId]. */
    fun deleteBook(bookId: Long) = synchronized(lock) { dir(bookId).deleteRecursively() }

    /** Bytes ocupados por [bookId] (nulo: todos los libros). */
    fun sizeBytes(bookId: Long? = null): Long = synchronized(lock) {
        val d = if (bookId == null) root else dir(bookId)
        d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Borra paginas a medio escribir (el proceso murio mientras se guardaban). */
    fun removePartials() = synchronized(lock) {
        root.listFiles()?.forEach { d -> d.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() } }
    }

    /** Libros con algo guardado. */
    fun books(): List<Long> = root.list()?.mapNotNull { it.toLongOrNull() } ?: emptyList()

    private fun Short.toUShortValue(): Int = toInt() and 0xFFFF

    companion object {
        private const val MAGIC = 0x4C564F43 // "LVOC"
        private const val VERSION = 1
        /** Cajas en 1/8 de punto: hasta 8191 pt (paginas de hasta ~2,9 m). */
        private const val UNITS = 8f
        private const val MAX_TEXT_BYTES = 4 * 1024 * 1024
    }
}
