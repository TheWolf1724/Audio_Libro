package com.lectorvoz.tts.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.PlaybackEvent
import com.lectorvoz.core.model.VoiceSettings
import com.lectorvoz.tts.BookInfo
import com.lectorvoz.tts.PlaybackState
import com.lectorvoz.tts.SleepTimerMode
import com.lectorvoz.tts.UtteranceProvider
import com.lectorvoz.tts.engine.SpeechEngineException
import com.lectorvoz.tts.export.AudioExporter
import com.lectorvoz.tts.export.ExportProgress
import com.lectorvoz.tts.export.ExportResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Lado UI de la lectura en voz alta (ver la decision en [PlaybackHub]):
 * [state] y [events] se leen en memoria; los mandos van por un [MediaController] conectado
 * a [PlaybackService]. Usar desde el hilo principal; [connect] antes de mandar ordenes y
 * [close] al destruir la pantalla (la lectura sigue en el servicio).
 */
@UnstableApi
class PlaybackConnection(
    context: Context,
    private val hub: PlaybackHub = PlaybackHub.default,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    /** Estado de la lectura. */
    val state: StateFlow<PlaybackState> get() = hub.state

    /** Eventos de resaltado, capitulo y error. */
    val events: SharedFlow<PlaybackEvent> get() = hub.events

    /** Peticiones de marcador desde la notificacion (un unico recolector en :app). */
    val bookmarkRequests: Flow<Locator> get() = hub.bookmarkRequests

    /**
     * Ajustes de voz vigentes. Cambian tambien desde fuera de la app (velocidad o tono desde
     * un mando Media3: reloj, Android Auto): `:app` debe recogerlos y guardarlos.
     */
    val settings: StateFlow<VoiceSettings> get() = hub.settings

    /** Conecta (y arranca) el servicio. Idempotente. */
    suspend fun connect(): MediaController {
        controller?.let { return it }
        val f = future ?: MediaController.Builder(
            appContext,
            SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java)),
        ).buildAsync().also { future = it }
        val c = suspendCancellableCoroutine { cont ->
            f.addListener({
                try {
                    cont.resume(f.get())
                } catch (e: ExecutionException) {
                    cont.resumeWithException(e.cause ?: e)
                }
            }, ContextCompat.getMainExecutor(appContext))
        }
        controller = c
        return c
    }

    /**
     * Abre [book] en el servicio desde [start] y, si [autoPlay], empieza a leer. Debe
     * llamarse por una accion del usuario (Android 17: arranque de audio en primer plano).
     */
    suspend fun openBook(book: BookInfo, provider: UtteranceProvider, start: Locator, autoPlay: Boolean) {
        hub.pendingBook = BookRequest(book, provider, start)
        val c = connect()
        c.sendCustomCommand(TtsCommands.OPEN_BOOK, Bundle.EMPTY)
        if (autoPlay) c.play()
    }

    fun play() { controller?.play() }

    fun pause() { controller?.pause() }

    /**
     * Para la lectura, olvida el libro y limpia la notificacion y el estado (`bookId` a null).
     * Para usar al borrar de la biblioteca el libro que suena.
     */
    fun stop() = send(TtsCommands.STOP, Bundle.EMPTY)

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.playWhenReady) c.pause() else c.play()
    }

    fun nextSentence() { controller?.seekToNext() }

    fun previousSentence() { controller?.seekToPrevious() }

    fun nextChapter() = send(TtsCommands.NEXT_CHAPTER, Bundle.EMPTY)

    fun previousChapter() = send(TtsCommands.PREVIOUS_CHAPTER, Bundle.EMPTY)

    /** Lee desde [locator] (por ejemplo, al tocar una frase). */
    fun seekTo(locator: Locator) = send(TtsCommands.SEEK_TO, TtsCommands.seekArgs(locator))

    /** Temporizador de apagado; nulo lo quita. */
    fun setSleepTimer(mode: SleepTimerMode?) = send(TtsCommands.SLEEP_TIMER, TtsCommands.timerArgs(mode))

    /** Ajustes de voz (motor, voz, velocidad, tono, pausas); se aplican al momento. */
    fun updateSettings(settings: VoiceSettings) {
        hub.settings.value = settings
    }

    /**
     * Exporta el capitulo [chapter] a WAV 16 bits en [uri] (elegido con
     * ACTION_CREATE_DOCUMENT) con la voz neuronal del servicio. Cancelable; [onProgress]
     * llega desde hilos de fondo.
     */
    suspend fun exportChapter(
        uri: Uri,
        provider: UtteranceProvider,
        chapter: Int,
        settings: VoiceSettings,
        onProgress: (ExportProgress) -> Unit = {},
    ): ExportResult {
        connect()
        val renderer = hub.renderer ?: throw SpeechEngineException("Servicio de voz no disponible")
        return AudioExporter(renderer).exportChapterWav(appContext, uri, provider, chapter, settings, onProgress)
    }

    private fun send(command: SessionCommand, args: Bundle) {
        controller?.sendCustomCommand(command, args)
    }

    /** Suelta el controlador (no para la lectura). */
    override fun close() {
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
    }
}
