package com.lectorvoz.core.data.repos

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.data.bookmarks.RoomBookmarkRepository
import com.lectorvoz.core.data.collections.RoomCollectionRepository
import com.lectorvoz.core.data.db.BookEntity
import com.lectorvoz.core.data.db.LectorVozDatabase
import com.lectorvoz.core.data.pronunciation.PronunciationEntry
import com.lectorvoz.core.data.pronunciation.RoomPronunciationRepository
import com.lectorvoz.core.data.stats.RoomReadingStatsRepository
import com.lectorvoz.core.data.stats.SessionKind
import com.lectorvoz.core.model.BookFormat
import com.lectorvoz.core.model.Locator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.TimeZone

/** Marcadores, diccionario, colecciones/series y estadisticas. */
@RunWith(AndroidJUnit4::class)
class ReposTest {
    private lateinit var db: LectorVozDatabase

    @Before
    fun abre() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LectorVozDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun cierra() = db.close()

    private suspend fun libro(hash: String, finished: Boolean = false): Long = db.bookDao().insert(
        BookEntity(
            uri = "content://x/$hash", localPath = null, format = BookFormat.PDF, title = hash, authors = "",
            language = null, coverPath = null, sizeBytes = 1, quickHash = hash, addedAt = 0, lastOpenedAt = null,
            finished = finished,
        ),
    )

    @Test
    fun marcadoresSinDuplicarPosicion() = runTest {
        val id = libro("a")
        val repo = RoomBookmarkRepository(db.bookmarkDao()) { 5L }
        val m1 = repo.add(id, Locator(2, 10), "  Frase marcada  ")
        val m2 = repo.add(id, Locator(2, 10), "otra")
        assertThat(m2).isEqualTo(m1)
        repo.add(id, Locator(0, 3), "antes")
        val list = repo.observe(id).first()
        assertThat(list.map { it.locator }).containsExactly(Locator(0, 3), Locator(2, 10)).inOrder()
        assertThat(list[1].excerpt).isEqualTo("Frase marcada")
        repo.updateNote(list[1], "nota")
        assertThat(repo.observe(id).first()[1].note).isEqualTo("nota")
        repo.delete(m1)
        assertThat(repo.observe(id).first()).hasSize(1)
    }

    @Test
    fun diccionarioGlobalYPorLibro() = runTest {
        val id = libro("b")
        val repo = RoomPronunciationRepository(db.pronunciationRuleDao())
        repo.save(PronunciationEntry(pattern = "SQL", replacement = "ese cu ele"))
        val porLibro = repo.save(PronunciationEntry(pattern = "Hermione", replacement = "Jermaioni", bookId = id))
        assertThat(repo.global.first().map { it.pattern }).containsExactly("SQL")
        assertThat(repo.forBook(id).first().map { it.pattern }).containsExactly("Hermione")
        // Las del libro van primero.
        assertThat(repo.effective(id).first().map { it.pattern }).containsExactly("Hermione", "SQL").inOrder()
        val e = repo.forBook(id).first().single()
        repo.save(e.copy(enabled = false))
        assertThat(repo.forBook(id).first().single().enabled).isFalse()
        repo.delete(porLibro)
        assertThat(repo.forBook(id).first()).isEmpty()
    }

    @Test
    fun seriesConOrdenYSiguienteLibro() = runTest {
        val a = libro("tomo1"); val b = libro("tomo2"); val c = libro("tomo3")
        val repo = RoomCollectionRepository(db) { 0L }
        val serie = repo.create("Trilogia", isSeries = true)
        val coleccion = repo.create("Favoritos", isSeries = false)
        repo.addBook(serie, a); repo.addBook(serie, b); repo.addBook(serie, c)
        repo.addBook(serie, b) // repetido: no cambia nada
        repo.addBook(coleccion, a)
        assertThat(repo.collections.first().first { it.id == serie }.bookIds).containsExactly(a, b, c).inOrder()
        assertThat(repo.nextInSeries(a)?.id).isEqualTo(b)
        assertThat(repo.nextInSeries(c)).isNull()
        repo.move(serie, c, 0)
        assertThat(repo.collections.first().first { it.id == serie }.bookIds).containsExactly(c, a, b).inOrder()
        assertThat(repo.nextInSeries(c)?.id).isEqualTo(a)
        repo.removeBook(serie, a)
        assertThat(repo.nextInSeries(c)?.id).isEqualTo(b)
        repo.delete(serie)
        assertThat(repo.collections.first().map { it.name }).containsExactly("Favoritos")
    }

    @Test
    fun estadisticasDeLecturaYEscucha() = runTest {
        val id = libro("c")
        libro("d", finished = true)
        val now = 10L * 24 * 3600 * 1000 + 12L * 3600 * 1000
        val repo = RoomReadingStatsRepository(db.readingSessionDao(), db.bookDao(), clock = { now }, zone = TimeZone.getTimeZone("UTC"))
        repo.record(id, SessionKind.READ, now - 1000, 120_000, 2_000)
        repo.record(id, SessionKind.LISTEN, now - 5000, 60_000, 900)
        repo.record(id, SessionKind.READ, now, 1_000, 10) // demasiado corta: se descarta
        val s = repo.summary.first()
        assertThat(s.readMs).isEqualTo(120_000)
        assertThat(s.listenMs).isEqualTo(60_000)
        assertThat(s.readCharsPerMinute).isEqualTo(1000)
        assertThat(s.listenCharsPerMinute).isEqualTo(900)
        assertThat(s.finishedBooks).isEqualTo(1)
        assertThat(s.sessions).isEqualTo(2)
        assertThat(s.lastDays).hasSize(7)
        assertThat(s.lastDays.last().readMs).isEqualTo(120_000)
    }
}
