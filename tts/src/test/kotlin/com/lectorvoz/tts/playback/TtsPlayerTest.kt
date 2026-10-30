package com.lectorvoz.tts.playback

import android.os.Looper
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.TtsEngineType
import com.lectorvoz.tts.BookInfo
import com.lectorvoz.tts.FakeAudioFocus
import com.lectorvoz.tts.FakeEngine
import com.lectorvoz.tts.FakeUtteranceProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** Adaptador Media3: comandos del Player -> controlador, y estado/metadatos de vuelta. */
@RunWith(AndroidJUnit4::class)
class TtsPlayerTest {
    private lateinit var scope: CoroutineScope
    private lateinit var engine: FakeEngine
    private lateinit var controller: ReadAloudController
    private lateinit var player: TtsPlayer
    private lateinit var hub: PlaybackHub
    private lateinit var focus: FakeAudioFocus
    private val book = FakeUtteranceProvider(
        listOf(listOf("Uno.", "Dos.", "Tres."), listOf("Cuatro.")),
    )

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        engine = FakeEngine(TtsEngineType.SYSTEM, msPerUtterance = 60_000)
        hub = PlaybackHub()
        focus = FakeAudioFocus()
        controller = ReadAloudController(scope, { engine }, focus, hub)
        player = TtsPlayer(Looper.getMainLooper(), controller, scope)
    }

    @After
    fun tearDown() {
        player.release()
        scope.cancel()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun sinLibroEstaInactivo() {
        idle()
        assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
        assertThat(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)).isFalse()
    }

    @Test
    fun playPausaYMetadatos() {
        controller.open(
            BookInfo("id1", "El libro", "La autora", chapterTitles = listOf("Primero", "Segundo")),
            book,
            Locator.START,
        )
        idle()
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        assertThat(player.mediaMetadata.title.toString()).isEqualTo("El libro")
        assertThat(player.mediaMetadata.artist.toString()).isEqualTo("Primero")
        assertThat(player.mediaMetadata.mediaType).isEqualTo(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)

        player.play()
        idle()
        assertThat(player.isPlaying).isTrue()
        assertThat(controller.state.value.isPlaying).isTrue()
        assertThat(engine.spoken).containsExactly(1L)

        player.pause()
        idle()
        assertThat(player.isPlaying).isFalse()
        assertThat(controller.state.value.playWhenReady).isFalse()
    }

    @Test
    fun siguienteYAnteriorSonFrases() {
        controller.open(BookInfo("id1", "Libro"), book, Locator.START)
        idle()
        player.play()
        idle()
        player.seekToNext()
        idle()
        assertThat(controller.state.value.utteranceId).isEqualTo(2L)
        player.seekToNext()
        idle()
        assertThat(controller.state.value.utteranceId).isEqualTo(3L)
        player.seekToPrevious()
        idle()
        assertThat(controller.state.value.utteranceId).isEqualTo(2L)
        assertThat(engine.spoken).containsExactly(1L, 2L, 3L, 2L).inOrder()
    }

    @Test
    fun cambioDeCapituloActualizaMetadatos() {
        controller.open(BookInfo("id1", "Libro", chapterTitles = listOf("Primero", "Segundo")), book, Locator.START)
        idle()
        controller.nextChapter()
        idle()
        assertThat(player.mediaMetadata.artist.toString()).isEqualTo("Segundo")
    }

    @Test
    fun velocidadYTonoVanALosAjustes() {
        controller.open(BookInfo("id1", "Libro"), book, Locator.START)
        idle()
        player.setPlaybackParameters(PlaybackParameters(1.5f, 0.9f))
        idle()
        assertThat(controller.currentSettings.speed).isEqualTo(1.5f)
        assertThat(controller.currentSettings.pitch).isEqualTo(0.9f)
        assertThat(player.playbackParameters.speed).isEqualTo(1.5f)
        // Revision 2, M7: tambien en los ajustes compartidos (la UI los ve y :app los guarda).
        assertThat(hub.settings.value.speed).isEqualTo(1.5f)
        assertThat(hub.settings.value.pitch).isEqualTo(0.9f)
    }

    /** Revision 2, M3: la perdida temporal de foco es una supresion, no una pausa. */
    @Test
    fun perdidaTemporalDeFocoEsSupresion() {
        controller.open(BookInfo("id1", "Libro"), book, Locator.START)
        idle()
        player.play()
        idle()
        focus.emit(FocusChange.LOSS_TRANSIENT)
        idle()
        assertThat(player.playWhenReady).isTrue()
        assertThat(player.playbackSuppressionReason)
            .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS)
        assertThat(player.isPlaying).isFalse()
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        focus.emit(FocusChange.GAIN)
        idle()
        assertThat(player.playbackSuppressionReason).isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE)
        assertThat(player.isPlaying).isTrue()
    }

    @Test
    fun duracionYPosicionEstimadasDelCapitulo() {
        // Reloj del Looper de Robolectric para que la medida de velocidad sea determinista.
        val timed = FakeEngine(TtsEngineType.SYSTEM, msPerUtterance = 1_000)
        val c = ReadAloudController(
            scope, { timed }, FakeAudioFocus(), PlaybackHub(), clock = { android.os.SystemClock.uptimeMillis() },
        )
        val p = TtsPlayer(Looper.getMainLooper(), c, scope)
        try {
            c.open(BookInfo("id1", "Libro"), book, Locator.START)
            idle()
            // Sin velocidad medida todavia: duracion desconocida.
            assertThat(p.duration).isEqualTo(androidx.media3.common.C.TIME_UNSET)
            p.play()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2_500))
            // Capitulo 0: "Uno. Dos. Tres. " = 16 caracteres; 4 caracteres por segundo medidos.
            assertThat(c.state.value.charsPerSecond).isWithin(0.01f).of(4f)
            // 16 caracteres a 4 c/s = 4 s, redondeado al minuto (no rehacer la timeline cada frase).
            assertThat(p.duration).isEqualTo(60_000L)
            assertThat(p.currentPosition).isAtLeast(2_000L)
            assertThat(p.currentPosition).isAtMost(p.duration)
        } finally {
            p.release()
        }
    }
}
