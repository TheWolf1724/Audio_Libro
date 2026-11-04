package com.lectorvoz.app.history

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lectorvoz.app.books.ObservedLibrary
import com.lectorvoz.app.fakes.FakeLibraryRepository
import com.lectorvoz.app.fakes.book
import com.lectorvoz.core.data.history.InMemoryReadingHistory
import com.lectorvoz.core.model.Locator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/** Historial de lecturas a traves de la biblioteca observada. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class HistoryTest {
    @Test
    fun anotaInicioFinYSeConservaAlQuitarElLibro() = runTest(UnconfinedTestDispatcher()) {
        var now = 1_000L
        val history = InMemoryReadingHistory { now }
        val raw = FakeLibraryRepository(listOf(book(1, "Uno", "Ana")))
        val recorder = HistoryRecorder(this, history, raw, coversDir = null)
        val library = ObservedLibrary(raw, listOf(recorder))

        library.markOpened(1)
        var e = history.entries.first().single()
        assertThat(e.title).isEqualTo("Uno")
        assertThat(e.startedAt).isEqualTo(1_000L)
        assertThat(e.finished).isFalse()

        // Guardar la posicion normal no toca el historial.
        now = 2_000L
        library.updateProgress(1, Locator(3, 0), 0.5f)
        assertThat(history.entries.first().single().updatedAt).isEqualTo(1_000L)

        // Llega al final: terminado (solo la primera vez que pasa a terminado).
        now = 3_000L
        library.updateProgress(1, Locator(9, 0), 1f, finished = true)
        now = 4_000L
        library.updateProgress(1, Locator(9, 0), 1f, finished = true)
        e = history.entries.first().single()
        assertThat(e.finishedAt).isEqualTo(3_000L)
        assertThat(e.progress).isEqualTo(1f)

        // Quitado de la biblioteca: la entrada sigue, sin enlace al libro.
        library.delete(1)
        e = history.entries.first().single()
        assertThat(e.bookId).isNull()
        assertThat(e.finishedAt).isEqualTo(3_000L)
    }

    @Test
    fun desactivadoNoAnota() = runTest(UnconfinedTestDispatcher()) {
        val history = InMemoryReadingHistory()
        val raw = FakeLibraryRepository(listOf(book(1)))
        val recorder = HistoryRecorder(this, history, raw, coversDir = null).apply { enabled = false }
        val library = ObservedLibrary(raw, listOf(recorder))
        library.markOpened(1)
        library.setFinished(1, true)
        assertThat(history.entries.first()).isEmpty()
    }

    @Test
    fun desmarcarTerminadoQuitaLaFechaDeFin() = runTest(UnconfinedTestDispatcher()) {
        val history = InMemoryReadingHistory()
        val raw = FakeLibraryRepository(listOf(book(1)))
        val library = ObservedLibrary(raw, listOf(HistoryRecorder(this, history, raw, coversDir = null)))
        library.markOpened(1)
        library.setFinished(1, true)
        assertThat(history.entries.first().single().finished).isTrue()
        library.setFinished(1, false)
        assertThat(history.entries.first().single().finished).isFalse()
    }

    /** Terminado con el historial desactivado: al reactivarlo y abrirlo, se pone al dia. */
    @Test
    fun reactivarloPoneAlDiaElTerminadoYConservaLaPrimeraFechaDeFin() = runTest(UnconfinedTestDispatcher()) {
        var now = 1_000L
        val history = InMemoryReadingHistory { now }
        val raw = FakeLibraryRepository(listOf(book(1)))
        val recorder = HistoryRecorder(this, history, raw, coversDir = null)
        val library = ObservedLibrary(raw, listOf(recorder))
        library.markOpened(1)
        recorder.enabled = false
        now = 2_000L
        library.setFinished(1, true)
        recorder.enabled = true
        now = 3_000L
        library.markOpened(1)
        val e = history.entries.first().single()
        assertThat(e.finished).isTrue()
        assertThat(e.startedAt).isAtMost(e.finishedAt!!)
        // Releido y terminado otra vez (desmarcado por el camino no hay): la primera fecha sigue.
        val first = e.finishedAt
        now = 9_000L
        history.finished(raw.get(1)!!, true)
        assertThat(history.entries.first().single().finishedAt).isEqualTo(first)
    }

    /** Libro nuevo parecido a una lectura de un libro ya quitado: se pregunta y, si se acepta, se unen. */
    @Test
    fun otraEdicionPreguntaYUneSoloSiSeAcepta() = runTest(UnconfinedTestDispatcher()) {
        var now = 1_000L
        val history = InMemoryReadingHistory { now }
        val raw = FakeLibraryRepository(listOf(book(1, "Carl el mazmorrero", "Matt Dinniman")))
        val recorder = HistoryRecorder(this, history, raw, coversDir = null)
        val library = ObservedLibrary(raw, listOf(recorder))
        library.markOpened(1)
        library.setFinished(1, true)
        library.delete(1)
        // Otra edicion (otro fichero): se pregunta.
        now = 5_000L
        raw.list.value = raw.list.value + book(2, "Carl el Mazmorrero (Edicion de bolsillo)", "Dinniman, Matt")
        library.markOpened(2)
        val s = recorder.suggestion.value!!
        assertThat(s.candidates.map { it.title }).containsExactly("Carl el mazmorrero")
        recorder.answer(s.candidates.first())
        val merged = history.entries.first().single()
        assertThat(merged.bookId).isEqualTo(2L)
        assertThat(merged.startedAt).isEqualTo(1_000L)
        assertThat(merged.finished).isTrue()
        // "No, es otro libro": quedan dos lecturas.
        raw.list.value = raw.list.value + book(3, "Carl el mazmorrero", "Matt Dinniman")
        library.markOpened(3)
        assertThat(recorder.suggestion.value).isNull() // la otra sigue en la biblioteca: no se propone
    }
}
