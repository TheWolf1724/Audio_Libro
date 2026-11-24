package com.lectorvoz.core.data.repos

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.data.db.BookEntity
import com.lectorvoz.core.data.db.LectorVozDatabase
import com.lectorvoz.core.data.stats.RoomReadingStatsRepository
import com.lectorvoz.core.data.stats.SessionKind
import com.lectorvoz.core.model.BookFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookStatsTest {
    @Test
    fun estadisticasPorLibroNoMezclanOtrosLibros() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LectorVozDatabase::class.java).allowMainThreadQueries().build()
        fun libro(h: String) = BookEntity(uri = "x/$h", localPath = null, format = BookFormat.EPUB, title = h, authors = "", language = null, coverPath = null, sizeBytes = 1, quickHash = h, addedAt = 0, lastOpenedAt = null)
        val a = db.bookDao().insert(libro("a"))
        val b = db.bookDao().insert(libro("b"))
        val repo = RoomReadingStatsRepository(db.readingSessionDao(), db.bookDao())
        repo.record(a, SessionKind.READ, 1_000, 60_000, 100)
        repo.record(a, SessionKind.LISTEN, 5_000, 120_000, 200)
        repo.record(b, SessionKind.READ, 9_000, 600_000, 900)
        val s = repo.forBook(a).first()
        assertThat(s.readMs).isEqualTo(60_000)
        assertThat(s.listenMs).isEqualTo(120_000)
        assertThat(s.sessions).isEqualTo(2)
        assertThat(s.firstAt).isEqualTo(1_000)
        assertThat(s.lastAt).isEqualTo(5_000)
        db.close()
    }
}
