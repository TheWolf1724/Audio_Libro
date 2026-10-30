package com.lectorvoz.core.data.collections

import androidx.room.withTransaction
import com.lectorvoz.core.data.db.BookCollectionCrossRef
import com.lectorvoz.core.data.db.CollectionEntity
import com.lectorvoz.core.data.db.LectorVozDatabase
import com.lectorvoz.core.data.library.Book
import com.lectorvoz.core.data.library.toBook
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Coleccion o serie con sus libros en orden. */
data class BookCollection(
    val id: Long,
    val name: String,
    val isSeries: Boolean,
    /** Ids de los libros en orden de la coleccion. */
    val bookIds: List<Long>,
)

/** Colecciones y series ("continuar con el siguiente libro"). */
interface CollectionRepository {
    val collections: Flow<List<BookCollection>>

    suspend fun create(name: String, isSeries: Boolean): Long

    suspend fun update(id: Long, name: String, isSeries: Boolean)

    suspend fun delete(id: Long)

    /** Anade [bookId] al final de la coleccion. */
    suspend fun addBook(collectionId: Long, bookId: Long)

    suspend fun removeBook(collectionId: Long, bookId: Long)

    /** Mueve [bookId] a la posicion [newIndex] (0..) dentro de la coleccion. */
    suspend fun move(collectionId: Long, bookId: Long, newIndex: Int)

    /** Siguiente libro de la primera serie que contiene [bookId]; nulo si es el ultimo. */
    suspend fun nextInSeries(bookId: Long): Book?
}

/** [CollectionRepository] sobre Room. */
class RoomCollectionRepository(
    private val db: LectorVozDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : CollectionRepository {
    private val dao = db.collectionDao()

    override val collections: Flow<List<BookCollection>> = combine(dao.observeAll(), dao.observeRefs()) { cols, refs ->
        val byCol = refs.groupBy { it.collectionId }
        cols.map { c ->
            BookCollection(c.id, c.name, c.isSeries, byCol[c.id].orEmpty().sortedBy { it.position }.map { it.bookId })
        }
    }

    override suspend fun create(name: String, isSeries: Boolean): Long {
        val n = name.trim()
        require(n.isNotEmpty()) { "Nombre vacio" }
        return dao.insert(CollectionEntity(name = n, createdAt = clock(), isSeries = isSeries))
    }

    override suspend fun update(id: Long, name: String, isSeries: Boolean) = dao.update(id, name.trim(), isSeries)

    override suspend fun delete(id: Long) = dao.deleteById(id)

    override suspend fun addBook(collectionId: Long, bookId: Long) {
        db.withTransaction {
            if (dao.refsForCollection(collectionId).any { it.bookId == bookId }) return@withTransaction
            dao.addBook(BookCollectionCrossRef(bookId, collectionId, dao.nextPosition(collectionId)))
        }
    }

    override suspend fun removeBook(collectionId: Long, bookId: Long) =
        dao.removeBook(BookCollectionCrossRef(bookId, collectionId))

    override suspend fun move(collectionId: Long, bookId: Long, newIndex: Int) {
        db.withTransaction {
            val ids = dao.refsForCollection(collectionId).map { it.bookId }.toMutableList()
            if (!ids.remove(bookId)) return@withTransaction
            ids.add(newIndex.coerceIn(0, ids.size), bookId)
            ids.forEachIndexed { i, id -> dao.setPosition(id, collectionId, i) }
        }
    }

    override suspend fun nextInSeries(bookId: Long): Book? {
        for (ref in dao.refsForBook(bookId)) {
            val col = dao.get(ref.collectionId) ?: continue
            if (!col.isSeries) continue
            val ordered = dao.refsForCollection(col.id)
            val i = ordered.indexOfFirst { it.bookId == bookId }
            val next = ordered.getOrNull(i + 1) ?: continue
            return db.bookDao().get(next.bookId)?.toBook()
        }
        return null
    }
}
