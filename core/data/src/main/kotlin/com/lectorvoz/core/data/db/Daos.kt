package com.lectorvoz.core.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Acceso a la tabla de libros. */
@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY added_at DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observe(id: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE quick_hash = :hash LIMIT 1")
    suspend fun findByHash(hash: String): BookEntity?

    /** Ultimo libro abierto y no terminado ("seguir leyendo"). */
    @Query("SELECT * FROM books WHERE last_opened_at IS NOT NULL AND finished = 0 ORDER BY last_opened_at DESC LIMIT 1")
    fun observeContinueReading(): Flow<BookEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Query(
        "UPDATE books SET progress = :progress, locator_chapter = :chapter, locator_offset = :offset, " +
            "last_opened_at = :openedAt, finished = :finished WHERE id = :id",
    )
    suspend fun updateProgress(id: Long, progress: Float, chapter: Int, offset: Int, openedAt: Long, finished: Boolean)

    @Query("UPDATE books SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE books SET finished = :finished WHERE id = :id")
    suspend fun setFinished(id: Long, finished: Boolean)

    @Query("UPDATE books SET finished = :finished, finished_at = :at WHERE id = :id")
    suspend fun setFinishedAt(id: Long, finished: Boolean, at: Long?)

    @Query("UPDATE books SET title = :title, authors = :authors WHERE id = :id")
    suspend fun rename(id: Long, title: String, authors: String)

    @Query("SELECT COUNT(*) FROM books WHERE finished = 1")
    fun observeFinishedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM books WHERE finished = 1 AND finished_at >= :since")
    suspend fun finishedSince(since: Long): Int

    @Query("UPDATE books SET last_opened_at = :openedAt WHERE id = :id")
    suspend fun markOpened(id: Long, openedAt: Long)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: Long)
}

/** Acceso a los marcadores. */
@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE book_id = :bookId ORDER BY locator_chapter, locator_offset")
    fun observeForBook(bookId: Long): Flow<List<BookmarkEntity>>

    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Update
    suspend fun update(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM bookmarks WHERE book_id = :bookId AND locator_chapter = :chapter AND locator_offset = :offset LIMIT 1")
    suspend fun find(bookId: Long, chapter: Int, offset: Int): BookmarkEntity?
}

/** Acceso a colecciones y su relacion con libros. */
@Dao
interface CollectionDao {
    @Query("SELECT * FROM collections ORDER BY name")
    fun observeAll(): Flow<List<CollectionEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(collection: CollectionEntity): Long

    @Delete
    suspend fun delete(collection: CollectionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addBook(ref: BookCollectionCrossRef)

    @Delete
    suspend fun removeBook(ref: BookCollectionCrossRef)

    @Query("SELECT b.* FROM books b INNER JOIN book_collections bc ON bc.book_id = b.id WHERE bc.collection_id = :collectionId ORDER BY bc.position, b.title")
    fun observeBooks(collectionId: Long): Flow<List<BookEntity>>

    @Query("SELECT * FROM book_collections ORDER BY collection_id, position")
    fun observeRefs(): Flow<List<BookCollectionCrossRef>>

    @Query("SELECT * FROM book_collections WHERE book_id = :bookId")
    suspend fun refsForBook(bookId: Long): List<BookCollectionCrossRef>

    @Query("SELECT * FROM book_collections WHERE collection_id = :collectionId ORDER BY position")
    suspend fun refsForCollection(collectionId: Long): List<BookCollectionCrossRef>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM book_collections WHERE collection_id = :collectionId")
    suspend fun nextPosition(collectionId: Long): Int

    @Query("UPDATE book_collections SET position = :position WHERE book_id = :bookId AND collection_id = :collectionId")
    suspend fun setPosition(bookId: Long, collectionId: Long, position: Int)

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun get(id: Long): CollectionEntity?

    @Query("UPDATE collections SET name = :name, is_series = :isSeries WHERE id = :id")
    suspend fun update(id: Long, name: String, isSeries: Boolean)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun deleteById(id: Long)
}

/** Acceso a las reglas de pronunciacion. */
@Dao
interface PronunciationRuleDao {
    @Query("SELECT * FROM pronunciation_rules ORDER BY position, id")
    fun observeAll(): Flow<List<PronunciationRuleEntity>>

    /** Reglas activas para [language] (incluye las de todos los idiomas). */
    @Query("SELECT * FROM pronunciation_rules WHERE enabled = 1 AND (language IS NULL OR language = :language) ORDER BY position, id")
    suspend fun activeFor(language: String?): List<PronunciationRuleEntity>

    @Query("SELECT * FROM pronunciation_rules WHERE book_id IS NULL ORDER BY position, id")
    fun observeGlobal(): Flow<List<PronunciationRuleEntity>>

    @Query("SELECT * FROM pronunciation_rules WHERE book_id = :bookId ORDER BY position, id")
    fun observeForBook(bookId: Long): Flow<List<PronunciationRuleEntity>>

    /** Reglas globales y las del libro [bookId]: primero las del libro (mas especificas). */
    @Query(
        "SELECT * FROM pronunciation_rules WHERE (book_id IS NULL OR book_id = :bookId) " +
            "ORDER BY CASE WHEN book_id IS NULL THEN 1 ELSE 0 END, position, id",
    )
    fun observeEffective(bookId: Long): Flow<List<PronunciationRuleEntity>>

    @Query("DELETE FROM pronunciation_rules WHERE book_id = :bookId")
    suspend fun deleteForBook(bookId: Long)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM pronunciation_rules")
    suspend fun nextPosition(): Int

    @Insert
    suspend fun insert(rule: PronunciationRuleEntity): Long

    @Update
    suspend fun update(rule: PronunciationRuleEntity)

    @Query("DELETE FROM pronunciation_rules WHERE id = :id")
    suspend fun delete(id: Long)
}

/** Acceso a las sesiones de lectura y escucha (estadisticas). */
@Dao
interface ReadingSessionDao {
    @Insert
    suspend fun insert(session: ReadingSessionEntity): Long

    @Query("SELECT * FROM reading_sessions WHERE started_at >= :since ORDER BY started_at")
    fun observeSince(since: Long): Flow<List<ReadingSessionEntity>>

    @Query("SELECT COALESCE(SUM(duration_ms), 0) FROM reading_sessions WHERE kind = :kind")
    fun observeTotalMs(kind: String): Flow<Long>

    @Query("SELECT COALESCE(SUM(chars), 0) FROM reading_sessions WHERE kind = :kind")
    fun observeTotalChars(kind: String): Flow<Long>

    @Query("SELECT COUNT(*) FROM reading_sessions")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM reading_sessions")
    suspend fun clear()
}
