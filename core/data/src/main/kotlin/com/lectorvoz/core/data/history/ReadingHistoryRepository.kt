package com.lectorvoz.core.data.history

import com.lectorvoz.core.data.db.ReadingHistoryDao
import com.lectorvoz.core.data.db.ReadingHistoryEntity
import com.lectorvoz.core.data.library.Book
import com.lectorvoz.core.model.BookFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Entrada del historial de lecturas (sobrevive a quitar el libro de la biblioteca). */
data class HistoryEntry(
    val id: Long,
    /** Libro de la biblioteca, si sigue en ella. */
    val bookId: Long?,
    val title: String,
    val authors: List<String>,
    val format: BookFormat,
    /** Miniatura propia del historial. */
    val coverPath: String?,
    val startedAt: Long,
    val finishedAt: Long?,
    val progress: Float,
    val updatedAt: Long,
) {
    val author: String? get() = authors.firstOrNull()
    val finished: Boolean get() = finishedAt != null
}

/**
 * Historial de lecturas: cuando se empezo y se termino cada libro. Se escribe solo en los
 * momentos clave (abrir, terminar, quitar de la biblioteca), nunca en cada pagina.
 */
interface ReadingHistoryRepository {
    val entries: Flow<List<HistoryEntry>>

    /** Libro abierto: crea su entrada (inicio = ahora) o la enlaza y actualiza. */
    suspend fun opened(book: Book)

    /** Libro terminado (o desmarcado si [finished] es false). */
    suspend fun finished(book: Book, finished: Boolean)

    /** El libro sale de la biblioteca: la entrada se queda con su ultimo progreso. */
    suspend fun detached(book: Book)

    /** Entradas enlazadas a un libro sin miniatura todavia (para crearlas). */
    suspend fun withoutCover(): List<HistoryEntry>

    suspend fun setCover(id: Long, path: String?)

    suspend fun delete(id: Long)

    suspend fun clear()

    /**
     * Mantenimiento al arrancar: suelta las entradas cuyo libro ya no existe (borrado a medias)
     * y borra miniaturas que ya no son de ninguna entrada.
     */
    suspend fun tidy() {}
}

class RoomReadingHistoryRepository(
    private val dao: ReadingHistoryDao,
    /** Carpeta de las miniaturas (se borran con su entrada). */
    private val coversDir: File?,
    private val clock: () -> Long = System::currentTimeMillis,
) : ReadingHistoryRepository {
    override val entries: Flow<List<HistoryEntry>> = dao.observeAll().map { l -> l.map { it.toEntry() } }.distinctUntilChanged()

    /**
     * Cada cambio lee la fila y la reescribe: abrir, terminar y poner la miniatura pueden
     * coincidir (la voz termina el libro mientras se abre el lector) y no deben pisarse.
     */
    private val lock = Mutex()

    override suspend fun opened(book: Book): Unit = lock.withLock {
        val now = clock()
        val cur = dao.byHash(book.quickHash)
        if (cur == null) {
            dao.insert(
                ReadingHistoryEntity(
                    bookId = book.id,
                    quickHash = book.quickHash,
                    title = book.title,
                    authors = book.authors.joinToString("\n"),
                    format = book.format,
                    coverPath = null,
                    // Un libro ya terminado (entrada borrada o historial reactivado): inicio <= fin.
                    startedAt = minOf(now, book.finishedAt ?: now),
                    finishedAt = book.finishedAt,
                    progress = book.progress,
                    updatedAt = now,
                ),
            )
        } else {
            dao.update(
                cur.copy(
                    bookId = book.id,
                    title = book.title,
                    authors = book.authors.joinToString("\n"),
                    progress = book.progress,
                    // Terminado o desmarcado con el historial desactivado: se pone al dia.
                    finishedAt = when {
                        !book.finished -> null
                        cur.finishedAt != null -> cur.finishedAt
                        else -> book.finishedAt ?: now
                    },
                    updatedAt = now,
                ),
            )
        }
    }

    override suspend fun finished(book: Book, finished: Boolean): Unit = lock.withLock {
        val now = clock()
        val cur = dao.byHash(book.quickHash)
        if (cur == null) {
            if (!finished) return@withLock
            // Terminado sin haberlo abierto nunca en la app (marcado a mano): inicio = fin.
            dao.insert(
                ReadingHistoryEntity(
                    bookId = book.id, quickHash = book.quickHash, title = book.title,
                    authors = book.authors.joinToString("\n"), format = book.format, coverPath = null,
                    startedAt = now, finishedAt = now, progress = 1f, updatedAt = now,
                ),
            )
            return@withLock
        }
        dao.update(
            cur.copy(
                bookId = book.id,
                // Releido o reimportado y vuelto a terminar: se conserva la primera fecha de fin.
                finishedAt = if (finished) cur.finishedAt ?: now else null,
                progress = if (finished) 1f else book.progress,
                updatedAt = now,
            ),
        )
    }

    override suspend fun detached(book: Book): Unit = lock.withLock { dao.detach(book.id, book.progress, clock()) }

    override suspend fun withoutCover(): List<HistoryEntry> = dao.withoutCover().map { it.toEntry() }

    override suspend fun setCover(id: Long, path: String?): Unit = lock.withLock {
        // La entrada se borro mientras se hacia la miniatura: fuera el fichero.
        if (dao.setCover(id, path) == 0) path?.let { ownCover(it)?.delete() }
    }

    override suspend fun delete(id: Long): Unit = lock.withLock {
        dao.coverOf(id)?.let { ownCover(it)?.delete() }
        dao.delete(id)
    }

    override suspend fun clear(): Unit = lock.withLock {
        dao.allCovers().forEach { ownCover(it)?.delete() }
        dao.clear()
    }

    override suspend fun tidy(): Unit = lock.withLock {
        dao.detachMissingBooks()
        val dir = coversDir ?: return@withLock
        val used = dao.allCovers().mapNotNull { runCatching { File(it).canonicalPath }.getOrNull() }.toHashSet()
        dir.listFiles()?.forEach { if (it.canonicalPath !in used) it.delete() }
    }

    /** Solo se borran miniaturas de la carpeta propia del historial. */
    private fun ownCover(path: String): File? {
        val dir = coversDir ?: return null
        val f = File(path)
        return f.takeIf { it.parentFile?.canonicalPath == dir.canonicalPath }
    }

    private fun ReadingHistoryEntity.toEntry() = HistoryEntry(
        id = id,
        bookId = bookId,
        title = title,
        authors = authors.split('\n').map { it.trim() }.filter { it.isNotEmpty() },
        format = format,
        coverPath = coverPath,
        startedAt = startedAt,
        finishedAt = finishedAt,
        progress = progress,
        updatedAt = updatedAt,
    )
}

/** Historial en memoria (pruebas y grafo sin base de datos). */
class InMemoryReadingHistory(private val clock: () -> Long = System::currentTimeMillis) : ReadingHistoryRepository {
    private val state = kotlinx.coroutines.flow.MutableStateFlow<List<HistoryEntry>>(emptyList())
    private val hashes = HashMap<Long, String>()
    private var next = 1L

    override val entries: Flow<List<HistoryEntry>> = state.map { l -> l.sortedByDescending { it.updatedAt } }

    private fun byHash(hash: String) = state.value.firstOrNull { hashes[it.id] == hash }

    private fun put(e: HistoryEntry) {
        state.value = state.value.filterNot { it.id == e.id } + e
    }

    override suspend fun opened(book: Book) {
        val now = clock()
        val cur = byHash(book.quickHash)
        if (cur == null) {
            val id = next++
            hashes[id] = book.quickHash
            put(HistoryEntry(id, book.id, book.title, book.authors, book.format, null, minOf(now, book.finishedAt ?: now), book.finishedAt, book.progress, now))
        } else {
            val fin = if (!book.finished) null else cur.finishedAt ?: book.finishedAt ?: now
            put(cur.copy(bookId = book.id, title = book.title, authors = book.authors, progress = book.progress, finishedAt = fin, updatedAt = now))
        }
    }

    override suspend fun finished(book: Book, finished: Boolean) {
        val now = clock()
        val cur = byHash(book.quickHash)
        if (cur == null) {
            if (!finished) return
            val id = next++
            hashes[id] = book.quickHash
            put(HistoryEntry(id, book.id, book.title, book.authors, book.format, null, now, now, 1f, now))
        } else {
            put(cur.copy(bookId = book.id, finishedAt = if (finished) cur.finishedAt ?: now else null, progress = if (finished) 1f else book.progress, updatedAt = now))
        }
    }

    override suspend fun detached(book: Book) {
        state.value.filter { it.bookId == book.id }.forEach { put(it.copy(bookId = null, progress = book.progress, updatedAt = clock())) }
    }

    override suspend fun withoutCover(): List<HistoryEntry> = state.value.filter { it.coverPath == null && it.bookId != null }

    override suspend fun setCover(id: Long, path: String?) {
        state.value.firstOrNull { it.id == id }?.let { put(it.copy(coverPath = path)) }
    }

    override suspend fun delete(id: Long) {
        state.value = state.value.filterNot { it.id == id }
    }

    override suspend fun clear() {
        state.value = emptyList()
    }
}
