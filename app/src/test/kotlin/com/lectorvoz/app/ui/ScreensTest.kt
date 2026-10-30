package com.lectorvoz.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lectorvoz.app.books.CoverLoader
import com.lectorvoz.app.fakes.FakeVoices
import com.lectorvoz.app.fakes.book
import com.lectorvoz.app.ui.library.LibraryActions
import com.lectorvoz.app.ui.library.LibraryFilter
import com.lectorvoz.app.ui.library.LibraryScreen
import com.lectorvoz.app.ui.library.LibraryUiState
import com.lectorvoz.app.ui.components.SliderRow
import com.lectorvoz.app.ui.reader.AppearancePanel
import com.lectorvoz.app.ui.settings.ReadingSettingsContent
import com.lectorvoz.app.ui.settings.VoiceSettingsContent
import com.lectorvoz.core.data.collections.BookCollection
import com.lectorvoz.core.data.settings.AppPrefs
import com.lectorvoz.core.data.settings.BookOverrides
import com.lectorvoz.core.data.settings.LibrarySort
import com.lectorvoz.core.data.library.Book
import com.lectorvoz.core.model.ReaderSettings
import com.lectorvoz.core.model.ReadingMode
import com.lectorvoz.core.model.ThemeMode
import com.lectorvoz.core.model.VoiceSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Modifier as JModifier
import kotlin.math.roundToInt

/** Pruebas de UI con Compose sobre Robolectric. */
@RunWith(AndroidJUnit4::class)
class ScreensTest {
    @get:Rule val rule = createComposeRule()

    private val covers = CoverLoader(4 * 1024 * 1024)

    private class RecordingActions : LibraryActions {
        val calls = ArrayList<String>()
        override fun open(book: Book) { calls += "open:${book.id}" }
        override fun import() { calls += "import" }
        override fun settings() { calls += "settings" }
        override fun query(q: String) { calls += "query:$q" }
        override fun filter(f: LibraryFilter) { calls += "filter:$f" }
        override fun sort(s: LibrarySort) { calls += "sort:$s" }
        override fun grid(grid: Boolean) { calls += "grid:$grid" }
        override fun favorite(book: Book) {}
        override fun finished(book: Book, finished: Boolean) {}
        override fun delete(book: Book) {}
        override fun createCollection(name: String, series: Boolean, addBook: Book?) {}
        override fun deleteCollection(c: BookCollection) {}
        override fun addTo(c: BookCollection, book: Book) {}
        override fun removeFrom(c: BookCollection, book: Book) {}
        override fun move(c: BookCollection, book: Book, delta: Int) {}
        override fun messageShown() { calls += "message" }
    }

    /** Nombres de los campos de un data class (para comprobar que la UI los expone todos). */
    private fun fields(c: Class<*>): List<String> =
        c.declaredFields.filter { !JModifier.isStatic(it.modifiers) }.map { it.name }

    @Test
    fun bibliotecaVaciaInvitaAImportar() {
        val actions = RecordingActions()
        rule.setContent { MaterialTheme { LibraryScreen(LibraryUiState(loading = false), covers, actions) } }
        rule.onNodeWithTag("biblioteca-vacia").assertIsDisplayed()
        rule.onNodeWithText("Tu biblioteca esta vacia").assertIsDisplayed()
        rule.onNodeWithText("Importar libros").performClick()
        rule.onNodeWithTag("boton-importar").performClick()
        assertThat(actions.calls).containsExactly("import", "import")
    }

    @Test
    fun importacionSimuladaMuestraProgresoYLuegoLosLibros() {
        val actions = RecordingActions()
        var state by mutableStateOf(LibraryUiState(loading = false, importing = 2))
        rule.setContent { MaterialTheme { LibraryScreen(state, covers, actions) } }
        rule.onNodeWithTag("importando").assertIsDisplayed()
        val libros = listOf(book(1, "Primero", "Ana", progress = 0.3f, opened = 5), book(2, "Segundo"))
        state = LibraryUiState(loading = false, books = libros, totalCount = 2, continueReading = libros[0], message = "2 libros importados")
        rule.waitForIdle()
        rule.onNodeWithTag("importando").assertDoesNotExist()
        rule.onNodeWithTag("seguir-leyendo").assertIsDisplayed()
        // Accion de clic semantica (como TalkBack): el FAB puede tapar el centro en pantallas pequenas.
        rule.onNodeWithTag("libro-2").assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("2 libros importados").assertIsDisplayed()
        assertThat(actions.calls).contains("open:2")
    }

    @Test
    fun panelDeAparienciaAplicaCambiosEnVivo() {
        var settings by mutableStateOf(ReaderSettings())
        var overrides by mutableStateOf(BookOverrides.NONE)
        rule.setContent {
            MaterialTheme {
                // Igual que en el lector: ajustes globales con los del libro encima.
                val effective = overrides.applyTo(settings)
                AppearancePanel(effective, overrides, systemDark = false, onReader = { settings = it(settings) }, onOverrides = { overrides = it(overrides) })
            }
        }
        rule.onNodeWithTag("tema-EYE_CARE").performScrollTo().performClick()
        assertThat(settings.theme).isEqualTo(ThemeMode.EYE_CARE)
        rule.onNodeWithTag("fuente-atkinson").performScrollTo().performClick()
        assertThat(settings.fontFamily).isEqualTo("atkinson")
        rule.onNodeWithText("Desplazamiento").performScrollTo().performClick()
        assertThat(settings.readingMode).isEqualTo(ReadingMode.SCROLL)
        // La animacion solo aparece en modo paginas: tras pasar a scroll ya no esta.
        rule.onNodeWithTag("apariencia-animacion").assertDoesNotExist()
        // Solo para este libro: los cambios van a los ajustes del libro.
        rule.onNodeWithTag("apariencia-por-libro").performScrollTo().performClick()
        assertThat(overrides.fontFamily).isEqualTo("atkinson")
        rule.onNodeWithTag("fuente-opendyslexic").performScrollTo().performClick()
        assertThat(overrides.fontFamily).isEqualTo("opendyslexic")
        assertThat(settings.fontFamily).isEqualTo("atkinson")
        rule.onNodeWithTag("apariencia-brillo-sistema").performScrollTo().performClick()
        assertThat(settings.brightness).isEqualTo(0.5f)
        rule.onNodeWithTag("apariencia-brillo").assertExists()
    }

    @Test
    fun ajustesDeVozMuestranTodosLosCampos() {
        var v by mutableStateOf(VoiceSettings())
        val voices = FakeVoices()
        rule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    VoiceSettingsContent(v, voices.neuralVoices(), emptyList(), voices.defaultVoiceId, { v = it(v) }, {}, {})
                }
            }
        }
        val campos = fields(VoiceSettings::class.java)
        assertThat(campos).hasSize(31)
        for (c in campos) rule.onNodeWithTag("voz-$c").assertExists()
        rule.onNodeWithTag("voz-probar").assertExists()
        rule.onNodeWithTag("voz-nightMode").performScrollTo().performClick()
        assertThat(v.nightMode).isTrue()
    }

    /** Ajustes > Voz muestra el nivel de precarga de este movil (solo con la voz natural). */
    @Test
    fun ajustesDeVozMuestranElNivelDePrecarga() {
        var v by mutableStateOf(VoiceSettings())
        val voices = FakeVoices()
        val status = com.lectorvoz.tts.neural.PreloadStatus(
            com.lectorvoz.tts.neural.PreloadTiers.HIGH, totalMemBytes = 8L * 1024 * 1024 * 1024,
            lowRamDevice = false, powerSave = false, thermal = 0, rtf = 0.31f, live = true,
        )
        rule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    VoiceSettingsContent(v, voices.neuralVoices(), emptyList(), voices.defaultVoiceId, { v = it(v) }, {}, {}, preload = status)
                }
            }
        }
        rule.onNodeWithTag("voz-precarga").performScrollTo().assertExists()
        rule.onNodeWithText("Precarga de audio: Alta").assertExists()
        rule.onNodeWithText("pagina siguiente", substring = true).assertExists()
        rule.onNodeWithText("Sintesis: 0", substring = true).assertExists() // 0,31 o 0.31 segun el idioma
        // Con la voz del sistema no aplica.
        v = v.copy(engine = com.lectorvoz.core.model.TtsEngineType.SYSTEM)
        rule.waitForIdle()
        rule.onNodeWithTag("voz-precarga").assertDoesNotExist()
    }

    @Test
    fun ajustesDeLecturaMuestranTodosLosCampos() {
        rule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ReadingSettingsContent(ReaderSettings(), AppPrefs(), {}, {})
                }
            }
        }
        for (c in fields(ReaderSettings::class.java)) rule.onNodeWithTag("lectura-$c").assertExists()
    }

    @Test
    fun deslizadorNoSaltaAunqueElValorCambieDesdeFueraDuranteElArrastre() {
        var value by mutableStateOf(0.5f)
        rule.setContent {
            MaterialTheme {
                SliderRow(
                    label = "Velocidad",
                    value = value,
                    range = 0f..1f,
                    step = 0.1f,
                    format = { "V${(it * 10).roundToInt()}" },
                    onChange = { value = it },
                    tag = "sl",
                )
            }
        }
        val slider = rule.onNodeWithContentDescription("Velocidad")
        // Empieza un arrastre hacia la derecha y NO suelta (dragging activo).
        slider.performTouchInput { down(centerLeft); moveTo(center); moveTo(centerRight) }
        rule.waitForIdle()
        // El valor cambia desde fuera (como si la vista previa lo realimentara) a 0.
        value = 0f
        rule.waitForIdle()
        // Con el fallo (borrador reiniciado por el value) el pulgar saltaria a V0; con el
        // arreglo el borrador ignora el cambio externo mientras se arrastra.
        rule.onAllNodesWithText("V0").assertCountEquals(0)
        slider.performTouchInput { up() }
    }
}
