package com.lectorvoz.app.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lectorvoz.app.fakes.FakeLibraryRepository
import com.lectorvoz.app.fakes.FakePlaybackPort
import com.lectorvoz.app.fakes.book
import com.lectorvoz.app.integration.VoiceUiState
import com.lectorvoz.core.data.library.Book
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/** Widget "seguir escuchando": que libro muestra y que hace el boton. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ListenWidgetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun muestraElUltimoLibroYElQueSuena() = runTest(UnconfinedTestDispatcher()) {
        val library = FakeLibraryRepository(listOf(book(1, "Uno", "Ana", progress = 0.426f, opened = 5), book(2, "Dos", progress = 0.1f)))
        val playback = FakePlaybackPort()
        val w = ListenWidgetUpdater(context, this, library, playback) {}

        val first = w.content().first()
        assertThat(first).isEqualTo(WidgetContent(1, "Uno", "Ana", null, 43, loaded = false, playing = false))

        // Suena otro libro: el widget muestra ese, cargado y sonando.
        playback.mutable.value = VoiceUiState(bookId = 2, playWhenReady = true)
        val now = w.content().first()
        assertThat(now.bookId).isEqualTo(2)
        assertThat(now.loaded).isTrue()
        assertThat(now.playing).isTrue()
    }

    @Test
    fun soloRepintaSiCambiaLoQueSeVe() = runTest(UnconfinedTestDispatcher()) {
        val library = FakeLibraryRepository(listOf(book(1, "Uno", progress = 0.100f, opened = 5)))
        val playback = FakePlaybackPort()
        val w = ListenWidgetUpdater(context, this, library, playback) {}
        val seen = ArrayList<WidgetContent>()
        val job = launch { w.content().take(2).toList(seen) }
        // Progreso que no cambia el % entero: no emite.
        library.updateProgress(1, com.lectorvoz.core.model.Locator.START, 0.1004f)
        assertThat(seen).hasSize(1)
        library.updateProgress(1, com.lectorvoz.core.model.Locator.START, 0.12f)
        job.join()
        assertThat(seen.map { it.percent }).containsExactly(10, 12).inOrder()
    }

    @Test
    fun bibliotecaSinLibroAbiertoMuestraElAvisoVacio() = runTest(UnconfinedTestDispatcher()) {
        val w = ListenWidgetUpdater(context, this, FakeLibraryRepository(listOf(book(1))), FakePlaybackPort()) {}
        assertThat(w.content().first()).isEqualTo(WidgetContent.EMPTY)
    }

    @Test
    fun elBotonConmutaOEmpiezaAEscuchar() = runTest(UnconfinedTestDispatcher()) {
        val library = FakeLibraryRepository(listOf(book(1, "Uno", opened = 5)))
        val playback = FakePlaybackPort()
        val started = ArrayList<Book>()
        val w = ListenWidgetUpdater(context, this, library, playback) { started += it }
        var done = 0

        // Proceso reiniciado (la voz no tiene libro): empieza a escuchar el ultimo.
        w.toggle { done++ }
        assertThat(started.map { it.id }).containsExactly(1L)
        assertThat(playback.calls).doesNotContain("toggle")

        // Con el libro cargado: conmuta.
        playback.mutable.value = VoiceUiState(bookId = 1, playWhenReady = true)
        w.toggle { done++ }
        assertThat(playback.calls).contains("toggle")
        assertThat(done).isEqualTo(2)
    }
}
