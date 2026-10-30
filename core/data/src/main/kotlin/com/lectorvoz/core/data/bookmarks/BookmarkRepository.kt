package com.lectorvoz.core.data.bookmarks

import com.lectorvoz.core.data.db.BookmarkDao
import com.lectorvoz.core.data.db.BookmarkEntity
import com.lectorvoz.core.model.Locator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Marcador de un libro. */
data class Bookmark(
    val id: Long,
    val bookId: Long,
    val locator: Locator,
    val excerpt: String,
    val note: String?,
    val createdAt: Long,
)

/** Marcadores por libro. */
interface BookmarkRepository {
    fun observe(bookId: Long): Flow<List<Bookmark>>

    /** Anade un marcador en [locator]; si ya existe en esa posicion, devuelve el existente. */
    suspend fun add(bookId: Long, locator: Locator, excerpt: String, note: String? = null): Long

    suspend fun updateNote(bookmark: Bookmark, note: String?)

    suspend fun delete(id: Long)
}

/** [BookmarkRepository] sobre Room. */
class RoomBookmarkRepository(
    private val dao: BookmarkDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : BookmarkRepository {
    override fun observe(bookId: Long): Flow<List<Bookmark>> = dao.observeForBook(bookId).map { list ->
        list.map { Bookmark(it.id, it.bookId, Locator(it.locatorChapter, it.locatorOffset), it.excerpt, it.note, it.createdAt) }
    }

    override suspend fun add(bookId: Long, locator: Locator, excerpt: String, note: String?): Long {
        dao.find(bookId, locator.chapter, locator.offset)?.let { return it.id }
        return dao.insert(
            BookmarkEntity(
                bookId = bookId,
                locatorChapter = locator.chapter,
                locatorOffset = locator.offset,
                excerpt = excerpt.trim().take(MAX_EXCERPT),
                note = note?.takeIf { it.isNotBlank() },
                createdAt = clock(),
            ),
        )
    }

    override suspend fun updateNote(bookmark: Bookmark, note: String?) = dao.update(
        BookmarkEntity(
            id = bookmark.id,
            bookId = bookmark.bookId,
            locatorChapter = bookmark.locator.chapter,
            locatorOffset = bookmark.locator.offset,
            excerpt = bookmark.excerpt,
            note = note?.takeIf { it.isNotBlank() },
            createdAt = bookmark.createdAt,
        ),
    )

    override suspend fun delete(id: Long) = dao.delete(id)

    private companion object {
        const val MAX_EXCERPT = 200
    }
}
