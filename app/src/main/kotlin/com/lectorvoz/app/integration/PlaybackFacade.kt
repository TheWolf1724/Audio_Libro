package com.lectorvoz.app.integration

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.PlaybackEvent
import com.lectorvoz.core.model.VoiceSettings
import com.lectorvoz.tts.neural.PreloadStatus
import com.lectorvoz.tts.BookInfo
import com.lectorvoz.tts.PlaybackState
import com.lectorvoz.tts.SleepTimerMode
import com.lectorvoz.tts.UtteranceProvider
import com.lectorvoz.tts.playback.PlaybackConnection
import com.lectorvoz.tts.playback.PlaybackHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * [PlaybackPort] sobre `PlaybackConnection` de `:tts`. Es el unico sitio de `:app` que
 * conoce los tipos de estado y temporizador de `:tts`.
 *
 * Si el servicio se destruye (por ejemplo tras quitar la app de recientes en pausa), el
 * `MediaController` queda desconectado; antes de cada orden se comprueba y, si hace falta,
 * se crea una conexion nueva (revision 3, B9).
 */
@UnstableApi
class PlaybackFacade(
    private val context: Context,
    private val scope: CoroutineScope,
) : PlaybackPort {
    private var connection = PlaybackConnection(context)
    private val connectLock = Mutex()
    private val hub = PlaybackHub.default

    override val state: StateFlow<VoiceUiState> =
        hub.state.map { it.toUi() }.stateIn(scope, SharingStarted.Eagerly, hub.state.value.toUi())

    override val events: Flow<PlaybackEvent> get() = hub.events

    override val preloadStatus: StateFlow<PreloadStatus?> get() = hub.preload

    override val bookmarkRequests: Flow<Locator> get() = hub.bookmarkRequests

    override val activeSettings: Flow<VoiceSettings> get() = hub.settings

    @Volatile override var currentSource: SpeechSource? = null
        private set

    private val previewer = VoicePreviewer(
        renderer = { live(); hub.renderer },
        focus = AndroidPreviewFocus(context),
        systemSpeaker = VoicePreviewer.systemSpeaker(context),
    )
    private var previewJob: Job? = null

    /** Conexion viva con el servicio (la rehace si el controlador se desconecto). */
    private suspend fun live(): PlaybackConnection = connectLock.withLock {
        val c = connection
        val ok = try {
            c.connect().isConnected
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sin conexion con el servicio de voz", e)
            false
        }
        if (ok) return@withLock c
        c.close()
        PlaybackConnection(context).also {
            connection = it
            it.connect()
        }
    }

    /** Orden al servicio por la conexion viva (sin bloquear al llamante). */
    private fun command(block: (PlaybackConnection) -> Unit) {
        scope.launch {
            val c = try {
                live()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Orden perdida: servicio no disponible", e)
                return@launch
            }
            block(c)
        }
    }

    override suspend fun start(book: BookForVoice, source: SpeechSource, start: Locator, autoPlay: Boolean) {
        val provider = source as? UtteranceProvider ?: error("Fuente de voz no compatible")
        currentSource = source
        val cover = book.coverPath?.let(::File)?.takeIf { it.exists() }
        live().openBook(
            BookInfo(
                id = book.id.toString(),
                title = book.title,
                author = book.author,
                coverUri = cover?.let { Uri.fromFile(it).toString() },
                chapterTitles = book.chapterTitles,
            ),
            provider,
            start,
            autoPlay,
        )
    }

    override fun play() = command { it.play() }
    override fun pause() = command { it.pause() }
    override fun stop() = command { it.stop() }
    override fun togglePlayPause() = command { it.togglePlayPause() }
    override fun nextSentence() = command { it.nextSentence() }
    override fun previousSentence() = command { it.previousSentence() }
    override fun nextChapter() = command { it.nextChapter() }
    override fun previousChapter() = command { it.previousChapter() }
    override fun seekTo(locator: Locator) = command { it.seekTo(locator) }

    override fun setSleepTimer(choice: TimerChoice?) = command {
        it.setSleepTimer(
            when (choice) {
                null -> null
                TimerChoice.EndOfChapter -> SleepTimerMode.EndOfChapter
                is TimerChoice.Minutes -> SleepTimerMode.Minutes(choice.minutes.coerceAtLeast(1))
            },
        )
    }

    override fun updateSettings(settings: VoiceSettings) = connection.updateSettings(settings)

    override suspend fun preview(settings: VoiceSettings, text: String): String? {
        previewJob?.cancel()
        previewJob = kotlin.coroutines.coroutineContext[Job]
        return previewer.preview(settings, text)
    }

    override suspend fun exportChapter(
        uri: Uri,
        source: SpeechSource,
        chapter: Int,
        settings: VoiceSettings,
        onProgress: (Float?) -> Unit,
    ): ExportOutcome {
        val provider = source as? UtteranceProvider ?: return ExportOutcome.Failed("Fuente de voz no compatible")
        return try {
            val r = live().exportChapter(uri, provider, chapter, settings) { onProgress(it.fraction) }
            ExportOutcome.Done(r.durationMs, r.bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ExportOutcome.Failed(e.message ?: "Error al exportar")
        }
    }

    private companion object {
        const val TAG = "PlaybackFacade"

        fun PlaybackState.toUi() = VoiceUiState(
            bookId = bookId?.toLongOrNull(),
            position = position,
            playWhenReady = playWhenReady,
            isPlaying = isPlaying,
            isPreparing = isPreparing,
            ended = ended,
            engine = engine,
            voiceId = voiceId,
            timer = when (val t = sleepTimer) {
                null -> null
                SleepTimerMode.EndOfChapter -> TimerChoice.EndOfChapter
                is SleepTimerMode.Minutes -> TimerChoice.Minutes(t.minutes)
            },
            timerRemainingMs = sleepTimerRemainingMs,
            error = error,
            charsPerSecond = charsPerSecond,
            chapterLength = chapterLength,
            lowBattery = lowBattery,
            switchedToSystemForBattery = switchedToSystemForBattery,
            preparingCache = preparingCache,
            focusSuppressed = focusSuppressed,
        )
    }
}
