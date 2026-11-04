package com.lectorvoz.core.data.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.data.db.LectorVozDatabase
import com.lectorvoz.core.data.library.Book
import com.lectorvoz.core.model.BookFormat
import com.lectorvoz.core.model.Locator
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Unir dos ediciones en el historial guardado (Room). */
@RunWith(AndroidJUnit4::class)
class RoomHistoryMergeTest {
    private lateinit var db: LectorVozDatabase
    private var now = 1_000L

    @Before
    fun abre() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LectorVozDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun cierra() = db.close()

    private fun book(id: Long, hash: String, title: String, finished: Boolean = false) = Book(
        id = id, uri = "content://x/$id", localPath = null, format = BookFormat.EPUB, title = title,
        authors = listOf("Autora"), language = "es", coverPath = null, sizeBytes = 1, quickHash = hash,
        addedAt = 0L, lastOpenedAt = null, progress = 0.3f, locator = Locator.START, finished = finished,
        favorite = false, finishedAt = if (finished) now else null,
    )

    @Test
    fun unirConservaFechasYLasDosHuellas() = runTest {
        val repo = RoomReadingHistoryRepository(db.readingHistoryDao(), null) { now }
        val old = book(1, "vieja", "Libro")
        val oldId = repo.opened(old)!!
        now = 2_000L
        repo.finished(old, true)
        repo.detached(old)
        now = 9_000L
        val nuevo = book(2, "nueva", "Libro (otra edicion)")
        val newId = repo.opened(nuevo)!!
        repo.merge(newId, oldId)
        val all = repo.all()
        assertThat(all).hasSize(1)
        val m = all.single()
        assertThat(m.id).isEqualTo(oldId)
        assertThat(m.bookId).isEqualTo(2L)
        assertThat(m.title).isEqualTo("Libro (otra edicion)")
        assertThat(m.startedAt).isEqualTo(1_000L)
        assertThat(m.finishedAt).isEqualTo(2_000L)
        // Volver a abrir cualquiera de las dos ediciones cae en la misma lectura (sin entrada nueva).
        assertThat(repo.opened(nuevo)).isNull()
        assertThat(repo.opened(old)).isNull()
        assertThat(repo.all()).hasSize(1)
        // Releer la otra edicion sin terminarla no borra la fecha de fin.
        assertThat(repo.all().single().finishedAt).isEqualTo(2_000L)
    }
}
