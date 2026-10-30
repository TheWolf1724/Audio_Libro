package com.lectorvoz.app.integration

import android.net.Uri
import androidx.compose.runtime.Immutable
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.PlaybackEvent
import com.lectorvoz.core.model.TtsEngineType
import com.lectorvoz.core.model.VoiceSettings
import kotlinx.coroutines.flow.Flow
import com.lectorvoz.tts.neural.PreloadStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Temporizador de apagado elegido en la UI. */
sealed interface TimerChoice {
    data class Minutes(val minutes: Int) : TimerChoice
    data object EndOfChapter : TimerChoice
}

/** Estado de la voz para la UI (copia propia: la UI no depende de `:tts`). */
@Immutable
data class VoiceUiState(
    val bookId: Long? = null,
    val position: Locator? = null,
    val playWhenReady: Boolean = false,
    val isPlaying: Boolean = false,
    val isPreparing: Boolean = false,
    val ended: Boolean = false,
    val engine: TtsEngineType = TtsEngineType.NEURAL,
    val voiceId: String? = null,
    val timer: TimerChoice? = null,
    val timerRemainingMs: Long? = null,
    val error: String? = null,
    /** Velocidad efectiva medida (caracteres visibles por segundo); nula sin medidas. */
    val charsPerSecond: Float? = null,
    /** Longitud en caracteres del capitulo actual, si el proveedor la conoce. */
    val chapterLength: Int? = null,
    val lowBattery: Boolean = false,
    val switchedToSystemForBattery: Boolean = false,
    val preparingCache: Boolean = false,
    /** En pausa por una llamada (perdida temporal del foco); se reanuda sola. */
    val focusSuppressed: Boolean = false,
)

/** Datos del libro para la notificacion. */
data class BookForVoice(
    val id: Long,
    val title: String,
    val author: String?,
    val coverPath: String?,
    val chapterTitles: List<String?>,
)

/** Resultado de exportar a audio. */
sealed interface ExportOutcome {
    data class Done(val durationMs: Long, val bytes: Long) : ExportOutcome
    data class Failed(val message: String) : ExportOutcome
}

/**
 * Puerto de la voz para la UI. Lo implementa [PlaybackFacade] sobre `PlaybackConnection`;
 * las pruebas usan uno falso. Llamar desde el hilo principal.
 */
private val NO_PRELOAD: StateFlow<PreloadStatus?> = MutableStateFlow(null)

interface PlaybackPort {
    val state: StateFlow<VoiceUiState>
    val events: Flow<PlaybackEvent>

    /** Nivel de precarga que usa ahora la voz (nulo si no ha leido aun). Ver Ajustes > Voz. */
    val preloadStatus: StateFlow<PreloadStatus?> get() = NO_PRELOAD

    /** Peticiones de "Marcar" desde la notificacion (un unico recolector: AppGraph). */
    val bookmarkRequests: Flow<Locator>

    /** Ultima fuente abierta (para el texto de los marcadores pedidos desde la notificacion). */
    val currentSource: SpeechSource?

    /**
     * Ajustes de voz vigentes en el servicio. Cambian tambien desde fuera de la app
     * (velocidad o tono desde un mando Media3): `:app` los guarda (ver `VoiceSettingsSync`).
     */
    val activeSettings: Flow<VoiceSettings>

    /**
     * Abre [book] para leer desde [start]. Debe llamarse tras un toque del usuario
     * (Android 17 exige arrancar el audio en primer plano).
     */
    suspend fun start(book: BookForVoice, source: SpeechSource, start: Locator, autoPlay: Boolean = true)

    fun play()
    fun pause()

    /**
     * Para la lectura, olvida el libro que suena y limpia la notificacion y el estado
     * (`bookId` a null). Para usar al borrar de la biblioteca el libro que suena.
     */
    fun stop()
    fun togglePlayPause()
    fun nextSentence()
    fun previousSentence()
    fun nextChapter()
    fun previousChapter()
    fun seekTo(locator: Locator)
    fun setSleepTimer(choice: TimerChoice?)
    fun updateSettings(settings: VoiceSettings)

    /**
     * Prueba la voz de [settings] con [text] (desde un toque del usuario) sin tocar el libro
     * que suena: se sintetiza aparte y se reproduce con foco de audio transitorio (el libro
     * se pausa y se reanuda solo en la misma frase). Devuelve un mensaje de error o nulo.
     */
    suspend fun preview(settings: VoiceSettings, text: String): String?

    /** Exporta el capitulo [chapter] a WAV en [uri]. Cancelable. */
    suspend fun exportChapter(uri: Uri, source: SpeechSource, chapter: Int, settings: VoiceSettings, onProgress: (Float?) -> Unit): ExportOutcome
}
