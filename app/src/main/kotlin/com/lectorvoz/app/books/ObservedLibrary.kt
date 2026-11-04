package com.lectorvoz.app.books

import android.net.Uri
import android.util.Log
import com.lectorvoz.core.data.library.Book
import com.lectorvoz.core.data.library.ImportResult
import com.lectorvoz.core.data.library.LibraryRepository
import com.lectorvoz.core.model.Locator
import kotlinx.coroutines.CancellationException

/**
 * Avisos de los momentos clave de un libro: importado, abierto, terminado (o desmarcado) y
 * quitado de la biblioteca. Los usan el historial de lecturas y la limpieza del texto
 * reconocido y del audio guardado.
 */
interface LibraryListener {
    suspend fun imported(book: Book) {}
    suspend fun opened(book: Book) {}
    suspend fun finished(book: Book, finished: Boolean) {}
    suspend fun deleted(book: Book) {}
}

/**
 * [LibraryRepository] que avisa a [listeners]. Solo anade una lectura por clave primaria en
 * los cambios que pueden terminar un libro; guardar la posicion normal no cuesta nada mas.
 * Un fallo de un receptor se registra y no afecta a la biblioteca.
 */
class ObservedLibrary(
    private val delegate: LibraryRepository,
    private val listeners: List<LibraryListener>,
) : LibraryRepository by delegate {

    override suspend fun import(uri: Uri, copy: Boolean): ImportResult =
        delegate.import(uri, copy).also { r -> if (r is ImportResult.Imported) notify { imported(r.book) } }

    override suspend fun markOpened(id: Long) {
        delegate.markOpened(id)
        delegate.get(id)?.let { b -> notify { opened(b) } }
    }

    override suspend fun updateProgress(id: Long, locator: Locator, progress: Float, finished: Boolean?) {
        val before = if (finished != null) delegate.get(id)?.finished else null
        delegate.updateProgress(id, locator, progress, finished)
        if (finished != null && before != null && before != finished) {
            delegate.get(id)?.let { b -> notify { finished(b, finished) } }
        }
    }

    override suspend fun setFinished(id: Long, finished: Boolean) {
        val before = delegate.get(id)?.finished
        delegate.setFinished(id, finished)
        if (before != null && before != finished) delegate.get(id)?.let { b -> notify { finished(b, finished) } }
    }

    override suspend fun delete(id: Long) {
        val b = delegate.get(id)
        delegate.delete(id)
        if (b != null) notify { deleted(b) }
    }

    private suspend fun notify(event: suspend LibraryListener.() -> Unit) {
        for (l in listeners) {
            try {
                l.event()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ObservedLibrary", "Aviso de biblioteca fallido", e)
            }
        }
    }
}
