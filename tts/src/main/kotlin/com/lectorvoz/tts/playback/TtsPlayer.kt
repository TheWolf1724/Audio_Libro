package com.lectorvoz.tts.playback

import androidx.core.net.toUri
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.lectorvoz.tts.BookInfo
import com.lectorvoz.tts.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Player de Media3 que envuelve [ReadAloudController]: la sesion, la notificacion y los
 * botones de auriculares/Bluetooth lo ven como un reproductor normal.
 *
 * - Un unico MediaItem (el libro); el titulo del capitulo va en los metadatos.
 * - Siguiente/anterior (`seekToNext`/`seekToPrevious`, botones de auriculares) = frase
 *   siguiente/anterior. Capitulos y temporizador van por comandos de sesion.
 * - Velocidad y tono de `PlaybackParameters` se trasladan a los ajustes de voz (y a
 *   `PlaybackHub.settings`).
 * - Perdida temporal del foco: `playWhenReady` sigue a true con
 *   `PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS` (Media3 mantiene el primer plano).
 * - Duracion y posicion ESTIMADAS del capitulo (longitud del capitulo / velocidad efectiva
 *   medida, `PlaybackState.charsPerSecond`): la notificacion muestra una barra de progreso
 *   no desplazable; mientras suena, la posicion se extrapola desde el inicio de la frase.
 *   Sin datos (al empezar), duracion desconocida (TIME_UNSET) y sin barra.
 */
@UnstableApi
class TtsPlayer(
    looper: Looper,
    private val controller: ReadAloudController,
    scope: CoroutineScope,
) : SimpleBasePlayer(looper) {

    // getState() lee el estado del controlador directamente (se actualiza en el acto al
    // pulsar play/pausa); esta recogida solo avisa a Media3 de los cambios.
    private val collector: Job = scope.launch {
        controller.state.collect { invalidateState() }
    }

    override fun getState(): State {
        val s: PlaybackState = controller.state.value
        val book = controller.book
        val builder = State.Builder()
        if (book == null || s.bookId == null) {
            return builder
                .setAvailableCommands(Player.Commands.Builder().add(Player.COMMAND_RELEASE).build())
                .setPlaybackState(Player.STATE_IDLE)
                .build()
        }
        val chapter = s.position?.chapter
        val chapterTitle = chapter?.let { book.chapterTitle(it) }
        val metadata = metadataFor(book, chapterTitle)
        val cps = s.charsPerSecond?.takeIf { it > 0f }
        val length = s.chapterLength?.takeIf { it > 0 }
        // Duracion estimada del capitulo redondeada al minuto: un cambio de duracion es un cambio
        // de timeline y Media3 rehace la notificacion; sin redondear cambiaba en cada frase.
        val durationMs = if (cps != null && length != null) ((length / cps * 1000).toLong() / MINUTE_MS + 1) * MINUTE_MS else null
        val item = MediaItemData.Builder(book.id)
            .setMediaItem(MediaItem.Builder().setMediaId(book.id).setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .setDurationUs(durationMs?.let { it * 1000 } ?: C.TIME_UNSET)
            .setIsSeekable(false)
            .build()
        val settings = controller.currentSettings
        val playbackState = when {
            s.ended -> Player.STATE_ENDED
            s.playWhenReady && s.isPreparing -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }
        return builder
            .setAvailableCommands(COMMANDS)
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(
                if (durationMs != null && cps != null) position(s, cps, durationMs) else PositionSupplier.ZERO,
            )
            .setPlaybackState(playbackState)
            .setPlayWhenReady(s.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            // Llamada en curso: sigue "en uso" para Media3 (primer plano) pero sin sonar.
            .setPlaybackSuppressionReason(
                if (s.focusSuppressed) {
                    Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
                } else {
                    Player.PLAYBACK_SUPPRESSION_REASON_NONE
                },
            )
            .setPlaybackParameters(PlaybackParameters(settings.speed, settings.pitch))
            .setPlayerError(null)
            .build()
    }

    // Metadatos del ultimo libro/capitulo: getState() se llama cada segundo (temporizador) y
    // en cada paso de un fundido; asi no se copia la portada cada vez.
    private var metadataBook: BookInfo? = null
    private var metadataChapter: String? = null
    private var metadata: MediaMetadata? = null

    private fun metadataFor(book: BookInfo, chapterTitle: String?): MediaMetadata {
        metadata?.let { if (metadataBook === book && metadataChapter == chapterTitle) return it }
        return MediaMetadata.Builder()
            .setTitle(book.title)
            .setArtist(chapterTitle ?: book.author)
            .setAlbumTitle(book.title)
            .setAlbumArtist(book.author)
            .setSubtitle(chapterTitle)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
            .setIsPlayable(true)
            .apply {
                book.coverData?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
                if (book.coverData == null) book.coverUri?.let { setArtworkUri(it.toUri()) }
            }
            .build()
            .also {
                metadataBook = book
                metadataChapter = chapterTitle
                metadata = it
            }
    }

    // Ancla de la posicion estimada: inicio de la frase actual y cuando empezo a sonar.
    private var anchorId: Long? = null
    private var anchorPlaying = false
    private var anchorPosMs = 0L
    private var anchorAt = 0L

    /** Posicion estimada en el capitulo: inicio de la frase + tiempo sonando (sin pasar del final). */
    private fun position(s: PlaybackState, cps: Float, durationMs: Long): PositionSupplier {
        if (s.utteranceId != anchorId || s.isPlaying != anchorPlaying) {
            anchorId = s.utteranceId
            anchorPlaying = s.isPlaying
            anchorPosMs = ((s.position?.offset ?: 0) / cps * 1000).toLong()
            anchorAt = SystemClock.elapsedRealtime()
        }
        val pos = anchorPosMs.coerceAtMost(durationMs)
        if (!anchorPlaying) return PositionSupplier.getConstant(pos)
        val at = anchorAt
        return PositionSupplier { minOf(durationMs, pos + SystemClock.elapsedRealtime() - at) }
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) controller.play() else controller.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        controller.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        collector.cancel()
        controller.release()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_FORWARD -> controller.nextSentence()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK -> controller.previousSentence()
            else -> Unit // Sin duracion: no hay busqueda por tiempo.
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        // Tambien a PlaybackHub.settings: la UI lo ve y :app lo guarda (no se pierde al cambiar otro ajuste).
        controller.setPlaybackParameters(playbackParameters.speed, playbackParameters.pitch)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val MINUTE_MS = 60_000L

        val COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SET_SPEED_AND_PITCH,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_RELEASE,
            )
            .build()
    }
}
