package com.lectorvoz.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lectorvoz.app.MainActivity
import com.lectorvoz.app.books.CoverLoader
import com.lectorvoz.app.fakes.FakeVoices
import com.lectorvoz.app.fakes.book
import com.lectorvoz.app.ui.history.HistoryContent
import com.lectorvoz.app.ui.history.HistoryUiState
import com.lectorvoz.app.ui.library.LibraryActions
import com.lectorvoz.app.ui.library.LibraryFilter
import com.lectorvoz.app.ui.library.LibraryScreen
import com.lectorvoz.app.ui.library.LibraryUiState
import com.lectorvoz.app.ui.settings.VoiceSettingsContent
import com.lectorvoz.app.ui.settings.entrySaver
import com.lectorvoz.core.data.collections.BookCollection
import com.lectorvoz.core.data.history.HistoryEntry
import com.lectorvoz.core.data.library.Book
import com.lectorvoz.core.data.pronunciation.PronunciationEntry
import com.lectorvoz.core.data.settings.AppPrefs
import com.lectorvoz.core.data.settings.LibrarySort
import com.lectorvoz.core.model.BookFormat
import com.lectorvoz.core.model.VoiceSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Arreglos de la revision de UI: dialogos que sobreviven al giro, confirmaciones y accesibilidad. */
@RunWith(AndroidJUnit4::class)
class ArreglosUiTest {
    @get:Rule val rule = createComposeRule()

    private val covers = CoverLoader(4 * 1024 * 1024)

    private class Acciones : LibraryActions {
        val calls = ArrayList<String>()
        override fun open(book: Book) {}
        override fun import() {}
        override fun settings() {}
        override fun query(q: String) {}
        override fun filter(f: LibraryFilter) {}
        override fun sort(s: LibrarySort) {}
        override fun grid(grid: Boolean) {}
        override fun favorite(book: Book) {}
        override fun finished(book: Book, finished: Boolean) {}
        override fun delete(book: Book) {}
        override fun createCollection(name: String, series: Boolean, addBook: Book?) { calls += "crear:$name" }
        override fun deleteCollection(c: BookCollection) { calls += "eliminar:${c.id}" }
        override fun addTo(c: BookCollection, book: Book) {}
        override fun removeFrom(c: BookCollection, book: Book) {}
        override fun move(c: BookCollection, book: Book, delta: Int) {}
        override fun messageShown() {}
    }

    private val libros = listOf(book(1, "Primero"), book(2, "Segundo"))

    @Test
    fun eliminarColeccionPideConfirmacion() {
        val acciones = Acciones()
        val c = BookCollection(7, "Saga", isSeries = true, bookIds = listOf(1L))
        val state = LibraryUiState(loading = false, books = libros.take(1), totalCount = 2, filter = LibraryFilter.Collection(7), collections = listOf(c))
        rule.setContent { MaterialTheme { LibraryScreen(state, covers, acciones) } }
        rule.onNodeWithTag("coleccion-eliminar").performClick()
        assertThat(acciones.calls).isEmpty()
        rule.onNodeWithText("Cancelar").performClick()
        assertThat(acciones.calls).isEmpty()
        rule.onNodeWithTag("coleccion-eliminar").performClick()
        rule.onNodeWithTag("coleccion-eliminar-si").performClick()
        assertThat(acciones.calls).containsExactly("eliminar:7")
    }

    @Test
    fun nombreDeColeccionNuevaSobreviveAlGiro() {
        val acciones = Acciones()
        val state = LibraryUiState(loading = false, books = libros, totalCount = 2)
        val restore = StateRestorationTester(rule)
        restore.setContent { MaterialTheme { LibraryScreen(state, covers, acciones) } }
        rule.onNodeWithTag("filtros").performScrollToNode(hasText("Nueva coleccion"))
        rule.onNodeWithText("Nueva coleccion").performClick()
        rule.onNodeWithTag("coleccion-nombre").performTextReplacement("Lecturas de verano")
        // Girar la pantalla o cambiar el modo oscuro recrea la actividad.
        restore.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("coleccion-nombre").assertIsDisplayed()
        rule.onNodeWithText("Lecturas de verano").assertIsDisplayed()
        rule.onNodeWithText("Crear").performClick()
        assertThat(acciones.calls).containsExactly("crear:Lecturas de verano")
    }

    @Test
    fun reglaDelDiccionarioSeGuardaYRestaura() {
        val e = PronunciationEntry(id = 3, pattern = "Sr.", replacement = "senor", isRegex = true, language = "es", position = 2, bookId = 9)
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saver = entrySaver<PronunciationEntry?>()
        val saved = with(saver) { scope.save(e) }
        assertThat(saver.restore(saved!!)).isEqualTo(e)
        assertThat(with(saver) { scope.save(null) }).isNull()
    }

    @Test
    fun historialDeLibroQuitadoAbreOpcionesYEtiquetaLaPulsacionLarga() {
        val quitado = HistoryEntry(1, null, "Quitado", listOf("Ana"), BookFormat.EPUB, null, 0L, null, 0.4f, 2L)
        val opened = ArrayList<Long>()
        rule.setContent {
            MaterialTheme {
                HistoryContent(HistoryUiState(listOf(quitado), AppPrefs(), loading = false), covers, onBack = {}, onOpen = { opened += it }, onDelete = {})
            }
        }
        val node = rule.onNodeWithTag("historial-1").fetchSemanticsNode()
        assertThat(node.config[SemanticsActions.OnLongClick].label).isEqualTo("Opciones")
        assertThat(node.config[SemanticsActions.OnClick].label).isEqualTo("Opciones")
        rule.onNodeWithTag("historial-1").performClick()
        assertThat(opened).isEmpty()
        rule.onNodeWithTag("historial-quitar").assertIsDisplayed()
    }

    @Test
    fun preajustesDeVelocidadSeGuardanAlPerderElFocoYAlSalir() {
        var v by mutableStateOf(VoiceSettings())
        var show by mutableStateOf(true)
        lateinit var focus: FocusManager
        val voices = FakeVoices()
        rule.setContent {
            focus = LocalFocusManager.current
            MaterialTheme {
                if (show) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        VoiceSettingsContent(v, voices.neuralVoices(), emptyList(), voices.defaultVoiceId, { v = it(v) }, {}, {})
                    }
                }
            }
        }
        rule.onNodeWithTag("voz-speedPresets").performScrollTo().performClick()
        rule.onNodeWithTag("voz-speedPresets").performTextReplacement("0,8; 1,7")
        rule.runOnIdle { focus.clearFocus() }
        rule.runOnIdle { assertThat(v.speedPresets).containsExactly(0.8f, 1.7f).inOrder() }
        // Sin pulsar Hecho ni quitar el foco: salir de la pantalla tambien lo guarda.
        rule.onNodeWithTag("voz-speedPresets").performScrollTo().performClick()
        rule.onNodeWithTag("voz-speedPresets").performTextReplacement("1,25")
        show = false
        rule.runOnIdle { assertThat(v.speedPresets).containsExactly(1.25f) }
    }

    @Test
    fun intentRelanzadoDesdeRecientesSeIgnora() {
        val normal = Intent(MainActivity.ACTION_LISTEN).putExtra(MainActivity.EXTRA_BOOK, 1L)
        assertThat(MainActivity.launchedFromHistory(normal)).isFalse()
        val reciente = Intent(normal).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertThat(MainActivity.launchedFromHistory(reciente)).isTrue()
    }
}
