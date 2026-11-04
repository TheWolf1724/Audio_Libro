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

    /**
     * Libro abierto: crea su entrada (inicio = ahora) o la enlaza y actualiza. Devuelve el id
     * de la entrada si es nueva (para proponer si es otra edicion de una lectura anterior).
     */
    suspend fun opened(book: Book): Long?

    /** Libro terminado (o desmarcado si [finished] es false). */
    suspend fun finished(book: Book, finished: Boolean)

    /** El libro sale de la biblioteca: la entrada se queda con su ultimo progreso. */
    suspend fun detached(book: Book)

    /** Entradas enlazadas a un libro sin miniatura todavia (para crearlas). */
    suspend fun withoutCover(): List<HistoryEntry>

    suspend fun setCover(id: Long, path: String?)

    suspend fun delete(id: Long)

    suspend fun clear()

    /** Todas las entradas (sin observar). */
    suspend fun all(): List<HistoryEntry>

    /**
     * Une dos lecturas del mismo libro (otra edicion): queda una sola entrada con la fecha de
     * inicio mas antigua, la primera de fin, los datos del libro que siga en la biblioteca y las
     * huellas de ambas (reimportar cualquiera de las dos vuelve a ella).
     */
    suspend fun merge(fromId: Long, intoId: Long)

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

    override suspend fun opened(book: Book): Long? = lock.withLock {
        val now = clock()
        val cur = dao.byHash(book.quickHash)
        if (cur == null) {
            return@withLock dao.insert(
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
            ).takeIf { it > 0 }
        } else {
            dao.update(
                cur.copy(
                    bookId = book.id,
                    title = book.title,
                    authors = book.authors.joinToString("\n"),
                    progress = book.progress,
                    // Terminado o desmarcado con el historial desactivado: se pone al dia. Una
                    // lectura con ediciones unidas conserva su fecha de fin (releer otra edicion).
                    finishedAt = when {
                        !book.finished -> if (cur.altHashes.isNotEmpty()) cur.finishedAt else null
                        cur.finishedAt != null -> cur.finishedAt
                        else -> book.finishedAt ?: now
                    },
                    updatedAt = now,
                ),
            )
            null
        }
    }

    override suspend fun all(): List<HistoryEntry> = dao.all().map { it.toEntry() }

    override suspend fun merge(fromId: Long, intoId: Long): Unit = lock.withLock {
        if (fromId == intoId) return@withLock
        val from = dao.byId(fromId) ?: return@withLock
        val into = dao.byId(intoId) ?: return@withLock
        // Los datos (titulo, enlace, portada, progreso) del que siga en la biblioteca.
        val live = if (from.bookId != null || into.bookId == null) from else into
        val other = if (live === from) into else from
        val cover = live.coverPath ?: other.coverPath
        listOf(live.coverPath, other.coverPath).filter { it != null && it != cover }.forEach { ownCover(it!!)?.delete() }
        val alt = (into.altHashes.split('|') + from.altHashes.split('|') + other.quickHash)
            .filter { it.isNotEmpty() && it != live.quickHash }.distinct()
        dao.replace(
            listOf(other.id, live.id),
            live.copy(
                id = into.id,
                coverPath = cover,
                startedAt = minOf(from.startedAt, into.startedAt),
                finishedAt = listOfNotNull(from.finishedAt, into.finishedAt).minOrNull(),
                updatedAt = maxOf(from.updatedAt, into.updatedAt),
                altHashes = if (alt.isEmpty()) "" else alt.joinToString("|", "|", "|"),
            ),
        )
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

    /** Huella -> entrada para las ediciones unidas. */
    private val alias = HashMap<String, Long>()

    private fun byHash(hash: String) = state.value.firstOrNull { hashes[it.id] == hash || alias[hash] == it.id }

    private fun put(e: HistoryEntry) {
        state.value = state.value.filterNot { it.id == e.id } + e
    }

    override suspend fun opened(book: Book): Long? {
        val now = clock()
        val cur = byHash(book.quickHash)
        if (cur == null) {
            val id = next++
            hashes[id] = book.quickHash
            put(HistoryEntry(id, book.id, book.title, book.authors, book.format, null, minOf(now, book.finishedAt ?: now), book.finishedAt, book.progress, now))
            return id
        }
        val merged = alias.containsValue(cur.id)
        val fin = if (!book.finished) cur.finishedAt.takeIf { merged } else cur.finishedAt ?: book.finishedAt ?: now
        put(cur.copy(bookId = book.id, title = book.title, authors = book.authors, progress = book.progress, finishedAt = fin, updatedAt = now))
        return null
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

    override suspend fun all(): List<HistoryEntry> = state.value

    override suspend fun merge(fromId: Long, intoId: Long) {
        if (fromId == intoId) return
        val from = state.value.firstOrNull { it.id == fromId } ?: return
        val into = state.value.firstOrNull { it.id == intoId } ?: return
        val live = if (from.bookId != null || into.bookId == null) from else into
        val other = if (live === from) into else from
        val merged = live.copy(
            id = into.id,
            coverPath = live.coverPath ?: other.coverPath,
            startedAt = minOf(from.startedAt, into.startedAt),
            finishedAt = listOfNotNull(from.finishedAt, into.finishedAt).minOrNull(),
            updatedAt = maxOf(from.updatedAt, into.updatedAt),
        )
        val hashes = listOfNotNull(hashes[from.id], hashes[into.id])
        state.value = state.value.filterNot { it.id == from.id || it.id == into.id } + merged
        hashes.forEach { alias[it] = into.id }
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
