package com.lectorvoz.app.history

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.lectorvoz.app.books.LibraryListener
import com.lectorvoz.core.data.history.EditionMatch
import com.lectorvoz.core.data.history.HistoryEntry
import com.lectorvoz.core.data.history.ReadingHistoryRepository
import com.lectorvoz.core.data.library.Book
import com.lectorvoz.core.data.library.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Anota el historial de lecturas (si esta activado): al abrir un libro, al terminarlo y al
 * quitarlo de la biblioteca. La portada se guarda como miniatura propia (JPEG de ~120 px de
 * ancho, unos KB), para que el historial la conserve aunque el libro ya no este.
 */
class HistoryRecorder(
    private val scope: CoroutineScope,
    private val history: ReadingHistoryRepository,
    private val library: LibraryRepository,
    /** Carpeta de las miniaturas (nula: sin miniaturas, p. ej. en pruebas). */
    private val coversDir: File?,
) : LibraryListener {
    @Volatile var enabled: Boolean = true

    /** Una sola tanda de miniaturas a la vez (dos escribirian el mismo fichero). */
    private val coversLock = Mutex()

    fun start() {
        // Restos de un borrado a medias y miniaturas pendientes (migracion o sin portada aun).
        scope.launch(Dispatchers.IO) {
            runCatching { history.tidy() }.onFailure { Log.w(TAG, "No se pudo ordenar el historial", it) }
            fillCovers()
        }
    }

    private val mutableSuggestion = MutableStateFlow<EditionSuggestion?>(null)

    /** Propuesta pendiente: "este libro nuevo, es otra edicion de...?" (la decide el usuario). */
    val suggestion: StateFlow<EditionSuggestion?> = mutableSuggestion

    override suspend fun opened(book: Book) {
        if (!enabled) return
        val created = history.opened(book)
        if (created != null) suggest(created)
        scope.launch(Dispatchers.IO) { fillCovers() }
    }

    /**
     * Libro nuevo en el historial: si se parece a lecturas de libros que ya no estan en la
     * biblioteca, se pregunta si es el mismo en otra edicion. Sin parecido, no se pregunta.
     */
    private suspend fun suggest(id: Long) {
        val all = history.all()
        val entry = all.firstOrNull { it.id == id } ?: return
        val found = EditionMatch.candidates(entry, all.filter { it.bookId == null })
        if (found.isNotEmpty()) mutableSuggestion.value = EditionSuggestion(entry, found)
    }

    /** Respuesta a la propuesta: [same] es la lectura elegida, o nula si es otro libro. */
    fun answer(same: HistoryEntry?) {
        val s = mutableSuggestion.value ?: return
        mutableSuggestion.value = null
        if (same != null) merge(s.entry, same)
    }

    /** Une dos lecturas (la propuesta aceptada o la union a mano desde el historial). */
    fun merge(entry: HistoryEntry, into: HistoryEntry) = scope.launch {
        coversLock.withLock { history.merge(entry.id, into.id) }
        fillCovers()
    }

    override suspend fun finished(book: Book, finished: Boolean) {
        if (!enabled) return
        history.finished(book, finished)
    }

    override suspend fun deleted(book: Book) {
        // Aunque este desactivado, una entrada ya existente se queda con su ultimo dato.
        history.detached(book)
    }

    private suspend fun fillCovers() = coversLock.withLock {
        val dir = coversDir ?: return@withLock
        try {
            for (e in history.withoutCover()) {
                val id = e.bookId ?: continue
                val src = library.get(id)?.coverPath ?: continue
                val thumb = withContext(Dispatchers.IO) { thumbnail(File(src), File(dir, "h${e.id}.jpg")) } ?: continue
                history.setCover(e.id, thumb.path)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron crear las miniaturas del historial", e)
        }
    }

    private fun thumbnail(src: File, dest: File): File? {
        if (!src.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMB_WIDTH) sample *= 2
        val bmp = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scaled = if (bmp.width > THUMB_WIDTH) {
            Bitmap.createScaledBitmap(bmp, THUMB_WIDTH, (bmp.height * THUMB_WIDTH / bmp.width).coerceAtLeast(1), true).also { if (it !== bmp) bmp.recycle() }
        } else {
            bmp
        }
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part")
        tmp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        scaled.recycle()
        return if (tmp.renameTo(dest)) dest else null.also { tmp.delete() }
    }

    /**
     * Lecturas con las que se puede unir [entry] a mano: al menos una de las dos ya no esta en
     * la biblioteca (dos libros presentes son dos lecturas), las mas parecidas primero.
     */
    suspend fun mergeOptions(entry: HistoryEntry): List<HistoryEntry> {
        val others = history.all().filter { it.id != entry.id && (entry.bookId == null || it.bookId == null) }
        val close = EditionMatch.candidates(entry, others, max = others.size)
        return close + (others - close.toSet()).sortedBy { it.title.lowercase() }
    }

    /** Borra todo el historial (Ajustes). */
    fun clear() = scope.launch { history.clear() }

    companion object {
        private const val TAG = "HistoryRecorder"
        private const val THUMB_WIDTH = 120
    }
}

/** "[entry], es otra edicion de alguna de [candidates]?" */
data class EditionSuggestion(val entry: HistoryEntry, val candidates: List<HistoryEntry>)
