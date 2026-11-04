package com.lectorvoz.app.history

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.lectorvoz.app.books.LibraryListener
import com.lectorvoz.core.data.history.ReadingHistoryRepository
import com.lectorvoz.core.data.library.Book
import com.lectorvoz.core.data.library.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    fun start() {
        // Miniaturas pendientes (entradas creadas por la migracion o sin portada aun).
        scope.launch(Dispatchers.IO) { fillCovers() }
    }

    override suspend fun opened(book: Book) {
        if (!enabled) return
        history.opened(book)
        scope.launch(Dispatchers.IO) { fillCovers() }
    }

    override suspend fun finished(book: Book, finished: Boolean) {
        if (!enabled) return
        history.finished(book, finished)
    }

    override suspend fun deleted(book: Book) {
        // Aunque este desactivado, una entrada ya existente se queda con su ultimo dato.
        history.detached(book)
    }

    private suspend fun fillCovers() {
        val dir = coversDir ?: return
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

    /** Borra todo el historial (Ajustes). */
    fun clear() = scope.launch { history.clear() }

    companion object {
        private const val TAG = "HistoryRecorder"
        private const val THUMB_WIDTH = 120
    }
}
