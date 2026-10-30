package com.lectorvoz.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.BookFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Pruebas de los DAO sobre una base de datos en memoria. */
@RunWith(AndroidJUnit4::class)
class DaoTest {
    private lateinit var db: LectorVozDatabase

    @Before
    fun abre() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LectorVozDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun cierra() = db.close()

    private fun libro(hash: String, titulo: String = "Libro $hash") = BookEntity(
        uri = "content://x/$hash",
        localPath = null,
        format = BookFormat.EPUB,
        title = titulo,
        authors = "Autora",
        language = "es",
        coverPath = null,
        sizeBytes = 10,
        quickHash = hash,
        addedAt = 1,
        lastOpenedAt = null,
    )

    @Test
    fun insertaBuscaPorHashYProgreso() = runTest {
        val dao = db.bookDao()
        val id = dao.insert(libro("a"))
        assertThat(dao.findByHash("a")?.id).isEqualTo(id)
        assertThat(dao.observeContinueReading().first()).isNull()
        dao.updateProgress(id, 0.5f, 3, 120, openedAt = 99, finished = false)
        val leido = dao.observeContinueReading().first()
        assertThat(leido?.progress).isEqualTo(0.5f)
        assertThat(leido?.locatorChapter).isEqualTo(3)
        assertThat(leido?.locatorOffset).isEqualTo(120)
    }

    @Test
    fun borrarLibroBorraSusMarcadoresYRelaciones() = runTest {
        val id = db.bookDao().insert(libro("b"))
        db.bookmarkDao().insert(BookmarkEntity(bookId = id, locatorChapter = 0, locatorOffset = 5, excerpt = "x", note = null, createdAt = 1))
        val col = db.collectionDao().insert(CollectionEntity(name = "Novela", createdAt = 1))
        db.collectionDao().addBook(BookCollectionCrossRef(id, col))
        assertThat(db.collectionDao().observeBooks(col).first()).hasSize(1)
        db.bookDao().delete(id)
        assertThat(db.bookmarkDao().observeForBook(id).first()).isEmpty()
        assertThat(db.collectionDao().observeBooks(col).first()).isEmpty()
    }

    @Test
    fun reglasActivasPorIdioma() = runTest {
        val dao = db.pronunciationRuleDao()
        dao.insert(PronunciationRuleEntity(pattern = "a", replacement = "b", language = null, position = 1))
        dao.insert(PronunciationRuleEntity(pattern = "c", replacement = "d", language = "en", position = 0))
        dao.insert(PronunciationRuleEntity(pattern = "e", replacement = "f", language = "es", enabled = false))
        assertThat(dao.activeFor("es").map { it.pattern }).containsExactly("a")
        assertThat(dao.activeFor("en").map { it.pattern }).containsExactly("c", "a").inOrder()
    }
}
