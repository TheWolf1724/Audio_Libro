package com.lectorvoz.core.data.repos

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.data.annotations.AnnotationExport
import com.lectorvoz.core.data.annotations.RoomAnnotationRepository
import com.lectorvoz.core.data.db.BookEntity
import com.lectorvoz.core.data.db.LectorVozDatabase
import com.lectorvoz.core.model.BookFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Subrayados y notas: persistencia, orden, edicion, borrado en cascada y exportacion. */
@RunWith(AndroidJUnit4::class)
class AnnotationsTest {
    private lateinit var db: LectorVozDatabase
    private lateinit var repo: RoomAnnotationRepository

    @Before
    fun abre() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LectorVozDatabase::class.java).allowMainThreadQueries().build()
        repo = RoomAnnotationRepository(db.annotationDao(), clock = { 42L })
    }

    @After
    fun cierra() = db.close()

    private suspend fun libro(hash: String): Long = db.bookDao().insert(
        BookEntity(
            uri = "content://x/$hash", localPath = null, format = BookFormat.EPUB, title = "Libro $hash", authors = "",
            language = null, coverPath = null, sizeBytes = 1, quickHash = hash, addedAt = 0, lastOpenedAt = null,
        ),
    )

    @Test
    fun seGuardanOrdenadosEditanYBorran() = runTest {
        val id = libro("a")
        repo.add(id, 2, 10, 20, 1, "  segundo  ")
        val first = repo.add(id, 0, 5, 9, 0, "primero", note = "buena frase")
        repo.add(id, 0, 1, 3, 9, "color fuera de rango")
        val list = repo.observe(id).first()
        assertThat(list.map { it.chapter to it.start }).containsExactly(0 to 1, 0 to 5, 2 to 10).inOrder()
        assertThat(list.first { it.id == first }.note).isEqualTo("buena frase")
        assertThat(list.first { it.chapter == 2 }.quote).isEqualTo("segundo")
        assertThat(list.first { it.start == 1 }.color).isEqualTo(3) // recortado al ultimo color
        repo.update(list.first { it.id == first }, color = 2, note = "  ")
        val upd = repo.observe(id).first().first { it.id == first }
        assertThat(upd.color).isEqualTo(2)
        assertThat(upd.note).isNull()
        repo.delete(first)
        assertThat(repo.observe(id).first().map { it.id }).doesNotContain(first)
    }

    @Test
    fun noSeMezclanLibrosYBorrarElLibroBorraSusSubrayados() = runTest {
        val a = libro("a")
        val b = libro("b")
        repo.add(a, 0, 0, 4, 0, "uno")
        repo.add(b, 0, 0, 4, 0, "dos")
        assertThat(repo.observe(a).first()).hasSize(1)
        db.bookDao().delete(a)
        assertThat(repo.observe(a).first()).isEmpty()
        assertThat(repo.observe(b).first()).hasSize(1)
    }

    @Test
    fun unSubrayadoVacioSeRechaza() = runTest {
        val id = libro("a")
        val r = runCatching { repo.add(id, 0, 5, 5, 0, "x") }
        assertThat(r.isFailure).isTrue()
    }

    @Test
    fun exportaMarkdownAgrupadoPorCapitulo() = runTest {
        val id = libro("a")
        repo.add(id, 1, 4, 9, 1, "Segunda cita\ncon salto", note = "mi nota")
        repo.add(id, 0, 0, 5, 0, "Primera cita")
        val md = AnnotationExport.markdown("El libro", repo.observe(id).first()) { if (it == 0) "Prologo" else null }
        assertThat(md).isEqualTo(
            "# El libro\n\n## Prologo\n\n> Primera cita\n\n(amarillo)\n\n## Capitulo 2\n\n> Segunda cita\n> con salto\n\n(verde) Nota: mi nota\n",
        )
        val txt = AnnotationExport.text("El libro", repo.observe(id).first()) { null }
        assertThat(txt).contains("Nota: mi nota")
    }
}
