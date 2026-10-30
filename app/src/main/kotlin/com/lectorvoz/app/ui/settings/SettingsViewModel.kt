package com.lectorvoz.app.ui.settings

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lectorvoz.app.AppGraph
import com.lectorvoz.app.books.ExportJobState
import com.lectorvoz.app.integration.PlaybackPort
import com.lectorvoz.app.integration.TextTools
import com.lectorvoz.app.integration.VoiceOption
import com.lectorvoz.app.integration.VoicesPort
import com.lectorvoz.core.data.library.Book
import com.lectorvoz.core.data.pronunciation.PronunciationEntry
import com.lectorvoz.core.data.pronunciation.PronunciationRepository
import com.lectorvoz.core.data.settings.AppPrefs
import com.lectorvoz.core.data.settings.SettingsRepository
import com.lectorvoz.core.data.stats.ReadingStatsRepository
import com.lectorvoz.core.data.stats.StatsSummary
import com.lectorvoz.core.model.PageAnimation
import com.lectorvoz.core.model.ReaderSettings
import com.lectorvoz.core.model.TextAlign
import com.lectorvoz.core.model.VoiceSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import com.lectorvoz.tts.neural.PreloadStatus
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Estado de todos los ajustes. */
@Immutable
data class SettingsUiState(
    val reader: ReaderSettings = ReaderSettings(),
    val voice: VoiceSettings = VoiceSettings(),
    val app: AppPrefs = AppPrefs(),
    val loaded: Boolean = false,
)

/** Ajustes (lectura, voz, accesibilidad) sobre [SettingsRepository]. */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val voices: VoicesPort,
    private val playback: PlaybackPort,
    /** Estimacion del nivel de precarga con los datos del terminal (sin leer). */
    private val preloadEstimate: () -> PreloadStatus? = { null },
) : ViewModel() {
    /**
     * Nivel de precarga para Ajustes > Voz: el que usa la voz si ya ha leido en esta sesion
     * (con la velocidad medida); si no, la estimacion con los datos del terminal.
     */
    val preload: StateFlow<PreloadStatus?> = playback.preloadStatus
        .map { it ?: preloadEstimate() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val state: StateFlow<SettingsUiState> =
        combine(settings.reader, settings.voice, settings.app) { r, v, a -> SettingsUiState(r, v, a, true) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    val neuralVoices: List<VoiceOption> = voices.neuralVoices()
    private val _systemVoices = MutableStateFlow<List<VoiceOption>>(emptyList())
    val systemVoices: StateFlow<List<VoiceOption>> = _systemVoices.asStateFlow()
    val defaultVoiceId: String get() = voices.defaultVoiceId

    fun loadSystemVoices() = viewModelScope.launch {
        if (_systemVoices.value.isEmpty()) _systemVoices.value = voices.systemVoices()
    }

    fun reader(t: (ReaderSettings) -> ReaderSettings) = viewModelScope.launch { settings.updateReader(t) }
    fun voice(t: (VoiceSettings) -> VoiceSettings) = viewModelScope.launch { settings.updateVoice(t) }
    fun app(t: (AppPrefs) -> AppPrefs) = viewModelScope.launch { settings.updateApp(t) }

    private val _message = MutableStateFlow<String?>(null)

    /** Aviso de la ultima prueba de voz (error), si lo hay. */
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * Prueba la voz actual (desde un toque del usuario). No sustituye el libro que se esta
     * escuchando: se pausa y sigue solo al acabar la prueba (A4).
     */
    fun testVoice(voiceId: String? = null) = viewModelScope.launch {
        val v = settings.voice.first().let { if (voiceId != null) it.copy(voiceId = voiceId) else it }
        _message.value = null
        _message.value = playback.preview(v, SAMPLE)
    }

    /** Texto grande (TEMAS.md seccion 6): 24 sp, interlineado 1,6, margen 16 dp, peso 450. */
    fun setLargeText(on: Boolean) = viewModelScope.launch {
        settings.updateApp { it.copy(largeText = on) }
        val d = ReaderSettings()
        settings.updateReader {
            if (on) it.copy(fontSizeSp = 24f, lineSpacing = 1.6f, marginHorizontalDp = 16, fontWeight = 450)
            else it.copy(fontSizeSp = d.fontSizeSp, lineSpacing = d.lineSpacing, marginHorizontalDp = d.marginHorizontalDp, fontWeight = d.fontWeight)
        }
    }

    /** Fuente para dislexia: OpenDyslexic + espaciado ampliado + alineacion izquierda. */
    fun setDyslexia(on: Boolean) = viewModelScope.launch {
        settings.updateApp { it.copy(dyslexiaFont = on) }
        val d = ReaderSettings()
        settings.updateReader {
            if (on) it.copy(fontFamily = "opendyslexic", letterSpacingEm = 0.05f, wordSpacingEm = 0.1f, textAlign = TextAlign.START, lineSpacing = maxOf(it.lineSpacing, 1.6f))
            else it.copy(fontFamily = d.fontFamily, letterSpacingEm = d.letterSpacingEm, wordSpacingEm = d.wordSpacingEm)
        }
    }

    /** Reducir movimiento: fundido corto en lugar de curvar o deslizar. */
    fun setReduceMotion(on: Boolean) = viewModelScope.launch {
        settings.updateApp { it.copy(reduceMotion = on) }
        settings.updateReader {
            if (on) it.copy(pageAnimation = PageAnimation.FADE, pageAnimationMillis = 150)
            else it.copy(pageAnimation = ReaderSettings().pageAnimation, pageAnimationMillis = ReaderSettings().pageAnimationMillis)
        }
    }

    companion object {
        const val SAMPLE = "Hola. Esta es la voz con la que se leeran tus libros. ¿Te gusta como suena?"
    }
}

/** Diccionario de pronunciacion (global o de un libro) con prueba en vivo. */
class DictionaryViewModel(
    private val repo: PronunciationRepository,
    private val playback: PlaybackPort,
    private val settings: SettingsRepository,
    val bookId: Long?,
) : ViewModel() {
    val entries: StateFlow<List<PronunciationEntry>> =
        (if (bookId == null) repo.global else repo.forBook(bookId)).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _sample = MutableStateFlow("")
    val sample: StateFlow<String> = _sample.asStateFlow()

    /** Resultado de aplicar las reglas a [sample] (texto que leeria la voz). */
    val preview: StateFlow<String> = combine(entries, _sample) { e, s -> TextTools.spokenPreview(e, s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val invalid: StateFlow<Set<String>> = combine(entries, _sample) { e, _ -> TextTools.invalidPatterns(e) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun setSample(s: String) { _sample.value = s }

    private val _message = MutableStateFlow<String?>(null)

    /** Aviso de la ultima prueba (error), si lo hay. */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun save(e: PronunciationEntry) = viewModelScope.launch {
        if (e.pattern.isNotBlank()) repo.save(e.copy(bookId = bookId))
    }

    fun delete(e: PronunciationEntry) = viewModelScope.launch { repo.delete(e.id) }

    fun toggle(e: PronunciationEntry) = viewModelScope.launch { repo.save(e.copy(enabled = !e.enabled)) }

    /** Escucha la prueba (desde un toque). */
    fun listen() = viewModelScope.launch {
        val s = _sample.value.ifBlank { return@launch }
        _message.value = playback.preview(settings.voice.first(), TextTools.spokenPreview(entries.value, s).ifBlank { s })
    }
}

/** Estadisticas. */
class StatsViewModel(private val stats: ReadingStatsRepository) : ViewModel() {
    val summary: StateFlow<StatsSummary> = stats.summary.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsSummary())
    fun clear() = viewModelScope.launch { stats.clear() }
}

/** Estado de la pantalla de exportar. */
@Immutable
data class ExportUiState(
    val book: Book? = null,
    val parts: List<String> = emptyList(),
    val part: Int = 0,
    val job: ExportJobState? = null,
    val error: String? = null,
) {
    /** Hay una exportacion en marcha (de este u otro libro). */
    val running: Boolean get() = job?.running == true
}

/**
 * Exportar a audio (WAV) con la voz neuronal: una parte (capitulo EPUB o tramo de paginas
 * PDF) o el libro completo en una carpeta. El trabajo vive en [com.lectorvoz.app.books.ExportManager]
 * (ambito de app): salir de esta pantalla no lo cancela.
 */
class ExportViewModel(private val graph: AppGraph, private val bookId: Long?) : ViewModel() {
    private val local = MutableStateFlow(ExportUiState())
    val state: StateFlow<ExportUiState> = combine(local, graph.exports.state) { l, j -> l.copy(job = j) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExportUiState())
    val books: StateFlow<List<Book>> = graph.library.books.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        if (bookId != null) viewModelScope.launch {
            val b = graph.library.get(bookId) ?: return@launch
            local.update { it.copy(book = b) }
            try {
                val parts = graph.exports.parts(b)
                val current = parts.indexOfFirst { b.locator.chapter in it.chapters }.coerceAtLeast(0)
                local.update { it.copy(parts = parts.map { p -> p.title }, part = current) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(error = "No se pudo abrir el libro: ${e.message}") }
            }
        }
    }

    fun setPart(i: Int) = local.update { it.copy(part = i) }

    /** Exporta la parte elegida a [uri] (ACTION_CREATE_DOCUMENT). */
    fun export(uri: Uri) {
        val b = local.value.book ?: return
        graph.exports.exportPart(b, local.value.part, uri)
    }

    /** Exporta el libro entero a la carpeta [tree] (ACTION_OPEN_DOCUMENT_TREE). */
    fun exportBook(tree: Uri) {
        val b = local.value.book ?: return
        graph.exports.exportBook(b, tree)
    }

    fun cancel() = graph.exports.cancel()

    fun clearResult() = graph.exports.clearResult()
}
