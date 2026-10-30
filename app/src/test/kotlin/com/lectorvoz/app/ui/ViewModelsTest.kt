package com.lectorvoz.app.ui

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lectorvoz.app.fakes.FakeCollectionRepository
import com.lectorvoz.app.fakes.FakeLibraryRepository
import com.lectorvoz.app.fakes.FakePlaybackPort
import com.lectorvoz.app.fakes.FakeSettingsRepository
import com.lectorvoz.app.fakes.FakeVoices
import com.lectorvoz.app.fakes.book
import com.lectorvoz.app.ui.library.LibraryFilter
import com.lectorvoz.app.ui.library.LibraryViewModel
import com.lectorvoz.app.ui.settings.SettingsViewModel
import com.lectorvoz.core.data.settings.BookOverrides
import com.lectorvoz.core.data.settings.LibrarySort
import com.lectorvoz.core.model.BookFormat
import com.lectorvoz.core.model.PageAnimation
import com.lectorvoz.core.model.ReaderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** ViewModels con repositorios falsos. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ViewModelsTest {
    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() = Dispatchers.resetMain()

    private val libros = listOf(
        book(1, "Cien años de soledad", "García Márquez", progress = 0.5f, opened = 50, added = 1),
        book(2, "El Quijote", "Cervantes", format = BookFormat.PDF, opened = 10, added = 3, finished = true),
        book(3, "Ánimo", "Árbol", favorite = true, added = 2),
    )

    @Test
    fun filtraOrdenaYBuscaSinTildes() {
        val f = LibraryViewModel.Companion
        assertThat(f.filterBooks(libros, "anos", LibraryFilter.All, LibrarySort.TITLE, emptyList()).map { it.id }).containsExactly(1L)
        assertThat(f.filterBooks(libros, "ARBOL", LibraryFilter.All, LibrarySort.TITLE, emptyList()).map { it.id }).containsExactly(3L)
        assertThat(f.filterBooks(libros, "", LibraryFilter.All, LibrarySort.TITLE, emptyList()).map { it.id }).containsExactly(3L, 1L, 2L).inOrder()
        assertThat(f.filterBooks(libros, "", LibraryFilter.All, LibrarySort.RECENT, emptyList()).map { it.id }).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(f.filterBooks(libros, "", LibraryFilter.Pdf, LibrarySort.TITLE, emptyList()).map { it.id }).containsExactly(2L)
        assertThat(f.filterBooks(libros, "", LibraryFilter.Unfinished, LibrarySort.ADDED, emptyList()).map { it.id }).containsExactly(3L, 1L).inOrder()
        assertThat(f.filterBooks(libros, "", LibraryFilter.Favorites, LibrarySort.TITLE, emptyList()).map { it.id }).containsExactly(3L)
        assertThat(f.filterBooks(libros, "", LibraryFilter.All, LibrarySort.PROGRESS, emptyList()).first().id).isEqualTo(1L)
    }

    @Test
    fun importacionMultipleConResumen() = runTest {
        val repo = FakeLibraryRepository(libros)
        val bad = Uri.parse("content://x/roto.txt")
        repo.results[bad.toString()] = FakeLibraryRepository.failed("roto.txt")
        val settings = FakeSettingsRepository()
        settings.appState.value = settings.appState.value.copy(copyOnImport = false)
        val vm = LibraryViewModel(repo, FakeCollectionRepository(), settings)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.import(listOf(Uri.parse("content://x/nuevo.epub"), bad))
        val s = vm.state.value
        assertThat(s.totalCount).isEqualTo(4)
        assertThat(s.importing).isEqualTo(0)
        assertThat(s.message).contains("1 libro importado")
        assertThat(s.message).contains("roto.txt")
        assertThat(repo.imported.map { it.second }).containsExactly(false, false)
        vm.consumeMessage()
        assertThat(vm.state.value.message).isNull()
    }

    @Test
    fun seguirLeyendoBorrarYColecciones() = runTest {
        val repo = FakeLibraryRepository(libros)
        val cols = FakeCollectionRepository()
        val settings = FakeSettingsRepository()
        settings.overridesState.value = mapOf(1L to BookOverrides(fontSizeSp = 30f))
        val vm = LibraryViewModel(repo, cols, settings)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertThat(vm.state.value.continueReading?.id).isEqualTo(1L)

        vm.createCollection("Saga", series = true, addBook = libros[1])
        val c = vm.state.value.collections.single()
        vm.addToCollection(c, libros[0])
        vm.setFilter(LibraryFilter.Collection(c.id))
        assertThat(vm.state.value.books.map { it.id }).containsExactly(2L, 1L).inOrder()
        vm.moveInCollection(vm.state.value.collections.single(), libros[0], -1)
        assertThat(vm.state.value.books.map { it.id }).containsExactly(1L, 2L).inOrder()

        vm.delete(libros[0])
        assertThat(vm.state.value.totalCount).isEqualTo(2)
        assertThat(settings.overrides(1L).first().isEmpty).isTrue()
        assertThat(vm.state.value.message).contains("no se ha borrado")
    }

    @Test
    fun preferenciasDeVista() = runTest {
        val settings = FakeSettingsRepository()
        val vm = LibraryViewModel(FakeLibraryRepository(), FakeCollectionRepository(), settings)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertThat(vm.state.value.totalCount).isEqualTo(0)
        vm.setGrid(false)
        vm.setSort(LibrarySort.AUTHOR)
        assertThat(vm.state.value.grid).isFalse()
        assertThat(vm.state.value.sort).isEqualTo(LibrarySort.AUTHOR)
    }

    @Test
    fun ajustesAccesibilidadYPruebaDeVoz() = runTest {
        val settings = FakeSettingsRepository()
        val playback = FakePlaybackPort()
        val vm = SettingsViewModel(settings, FakeVoices(), playback)
        vm.setLargeText(true)
        assertThat(settings.readerState.value.fontSizeSp).isEqualTo(24f)
        assertThat(settings.readerState.value.lineSpacing).isEqualTo(1.6f)
        assertThat(settings.appState.value.largeText).isTrue()
        vm.setLargeText(false)
        assertThat(settings.readerState.value.fontSizeSp).isEqualTo(ReaderSettings().fontSizeSp)
        vm.setDyslexia(true)
        assertThat(settings.readerState.value.fontFamily).isEqualTo("opendyslexic")
        assertThat(settings.readerState.value.letterSpacingEm).isEqualTo(0.05f)
        vm.setReduceMotion(true)
        assertThat(settings.readerState.value.pageAnimation).isEqualTo(PageAnimation.FADE)
        vm.voice { it.copy(speed = 9f) }
        assertThat(settings.voiceState.value.speed).isEqualTo(3f)
        vm.testVoice("supertonic-3-int8#1")
        assertThat(playback.calls.single()).startsWith("preview:supertonic-3-int8#1:")
    }
}
