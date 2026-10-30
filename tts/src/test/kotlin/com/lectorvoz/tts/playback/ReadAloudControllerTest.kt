package com.lectorvoz.tts.playback

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.PlaybackEvent
import com.lectorvoz.core.model.TtsEngineType
import com.lectorvoz.core.model.VoiceSettings
import com.lectorvoz.tts.BookInfo
import com.lectorvoz.tts.FakeAudioFocus
import com.lectorvoz.tts.FakeEngine
import com.lectorvoz.tts.FakeUtteranceProvider
import com.lectorvoz.tts.SleepTimerMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Maquina de estados de la lectura con motor y foco falsos (tiempo virtual). */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadAloudControllerTest {

    private val book = FakeUtteranceProvider(
        listOf(
            listOf("Capitulo uno.", "Primera frase.", "Segunda frase."),
            listOf("Capitulo dos.", "Tercera frase."),
        ),
    )

    /** Deja correr el tiempo virtual (backgroundScope no cuenta para advanceUntilIdle). */
    private fun TestScope.settle() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    private class Env(
        val controller: ReadAloudController,
        val engines: Map<TtsEngineType, FakeEngine>,
        val focus: FakeAudioFocus,
        val events: MutableList<PlaybackEvent>,
    ) {
        val system get() = engines.getValue(TtsEngineType.SYSTEM)
        val neural get() = engines.getValue(TtsEngineType.NEURAL)
        val state get() = controller.state.value
    }

    private fun TestScope.env(settings: VoiceSettings = VoiceSettings(engine = TtsEngineType.SYSTEM)): Env {
        val engines = mapOf(
            TtsEngineType.SYSTEM to FakeEngine(TtsEngineType.SYSTEM),
            TtsEngineType.NEURAL to FakeEngine(TtsEngineType.NEURAL),
        )
        val focus = FakeAudioFocus()
        val hub = PlaybackHub().apply { this.settings.value = settings }
        val c = ReadAloudController(backgroundScope, { engines.getValue(it) }, focus, hub)
        val events = ArrayList<PlaybackEvent>()
        backgroundScope.launch { c.events.collect { events += it } }
        c.open(BookInfo("libro", "Titulo", "Autor"), book, Locator.START)
        runCurrent()
        return Env(c, engines, focus, events)
    }

    @Test
    fun leeTodoElLibroYTermina() = runTest {
        val e = env()
        e.controller.play()
        runCurrent()
        assertThat(e.state.playWhenReady).isTrue()
        assertThat(e.state.isPlaying).isTrue()
        settle()
        assertThat(e.system.spoken).containsExactly(1L, 2L, 3L, 4L, 5L).inOrder()
        assertThat(e.state.ended).isTrue()
        assertThat(e.state.playWhenReady).isFalse()
        val chapters = e.events.filterIsInstance<PlaybackEvent.ChapterFinished>().map { it.chapter }
        assertThat(chapters).containsExactly(0, 1).inOrder()
        assertThat(e.events.filterIsInstance<PlaybackEvent.WordRange>()).isNotEmpty()
        assertThat(e.focus.held).isFalse()
    }

    @Test
    fun pausaYReanudaEnLaMismaFrase() = runTest {
        val e = env()
        e.controller.play()
        advanceTimeBy(150) // Segunda frase (100 ms cada una).
        runCurrent()
        assertThat(e.state.utteranceId).isEqualTo(2L)
        e.controller.pause()
        runCurrent()
        assertThat(e.state.playWhenReady).isFalse()
        assertThat(e.state.isPlaying).isFalse()
        assertThat(e.focus.held).isFalse()
        advanceTimeBy(10_000)
        assertThat(e.system.spoken).containsExactly(1L, 2L).inOrder()
        e.controller.play()
        runCurrent()
        // Reanuda repitiendo la frase 2 desde su inicio.
        assertThat(e.system.spoken.last()).isEqualTo(2L)
        assertThat(e.state.position).isEqualTo(book.all[1].range.start)
    }

    @Test
    fun fraseSiguienteYAnteriorMientrasLee() = runTest {
        val e = env()
        e.controller.play()
        runCurrent()
        e.controller.nextSentence()
        runCurrent()
        assertThat(e.state.utteranceId).isEqualTo(2L)
        assertThat(e.system.spoken.last()).isEqualTo(2L)
        e.controller.previousSentence()
        runCurrent()
        assertThat(e.state.utteranceId).isEqualTo(1L)
        assertThat(e.state.playWhenReady).isTrue()
    }

    @Test
    fun capituloSiguienteYAnteriorEnPausa() = runTest {
        val e = env()
        e.controller.nextChapter()
        runCurrent()
        assertThat(e.state.position).isEqualTo(Locator(1, 0))
        assertThat(e.state.playWhenReady).isFalse()
        // Ya al principio del capitulo 1: "anterior" va al capitulo 0.
        e.controller.previousChapter()
        runCurrent()
        assertThat(e.state.position).isEqualTo(Locator(0, 0))
        e.controller.seekTo(book.all[4].range.start)
        runCurrent()
        assertThat(e.state.utteranceId).isEqualTo(5L)
    }

    @Test
    fun focoTemporalPausaYReanudaFocoDefinitivoSoloPausa() = runTest {
        val e = env()
        e.controller.play()
        runCurrent()
        e.focus.emit(FocusChange.LOSS_TRANSIENT)
        runCurrent()
        // Supresion (revision 2, M3): no suena, pero sigue "en uso" para Media3.
        assertThat(e.state.playWhenReady).isTrue()
        assertThat(e.state.focusSuppressed).isTrue()
        assertThat(e.state.isPlaying).isFalse()
        val spoken = e.system.spoken.size
        advanceTimeBy(5_000)
        runCurrent()
        assertThat(e.system.spoken).hasSize(spoken)
        e.focus.emit(FocusChange.GAIN)
        runCurrent()
        assertThat(e.state.playWhenReady).isTrue()
        assertThat(e.state.focusSuppressed).isFalse()
        assertThat(e.state.isPlaying).isTrue()
        e.focus.emit(FocusChange.LOSS)
        runCurrent()
        assertThat(e.state.playWhenReady).isFalse()
        e.focus.emit(FocusChange.GAIN)
        runCurrent()
        assertThat(e.state.playWhenReady).isFalse()
    }

    @Test
    fun atenuacionBajaYRestauraElVolumen() = runTest {
        val e = env()
        e.controller.play()
        runCurrent()
        e.focus.emit(FocusChange.DUCK)
        assertThat(e.system.lastVolume).isEqualTo(ReadAloudController.DUCK_VOLUME)
        e.focus.emit(FocusChange.GAIN)
        assertThat(e.system.lastVolume).isEqualTo(1f)
        assertThat(e.state.playWhenReady).isTrue()
    }

    @Test
    fun sinFocoNoSuenaYAvisa() = runTest {
        val e = env()
        e.focus.grant = false
        e.controller.play()
        runCurrent()
        assertThat(e.state.playWhenReady).isFalse()
        assertThat(e.state.error).isNotNull()
        assertThat(e.events.last()).isInstanceOf(PlaybackEvent.Error::class.java)
    }

    @Test
    fun vozNeuronalNoDisponibleCaeALaDelSistema() = runTest {
        val e = env(VoiceSettings(engine = TtsEngineType.NEURAL))
        e.neural.failPrepare = true
        e.controller.play()
        runCurrent()
        assertThat(e.state.engine).isEqualTo(TtsEngineType.SYSTEM)
        assertThat(e.system.spoken).isNotEmpty()
        assertThat(e.events.filterIsInstance<PlaybackEvent.Error>()).hasSize(1)
    }

    @Test
    fun cambiarDeMotorMientrasLeeReiniciaLaFrase() = runTest {
        val e = env()
        e.controller.play()
        advanceTimeBy(150)
        runCurrent()
        e.controller.updateSettings(VoiceSettings(engine = TtsEngineType.NEURAL))
        runCurrent()
        assertThat(e.neural.spoken.first()).isEqualTo(2L)
        assertThat(e.state.engine).isEqualTo(TtsEngineType.NEURAL)
    }

    @Test
    fun temporizadorFinDeCapituloParaAlPrincipioDelSiguiente() = runTest {
        val e = env()
        e.controller.setSleepTimer(SleepTimerMode.EndOfChapter)
        e.controller.play()
        settle()
        assertThat(e.system.spoken).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(e.state.playWhenReady).isFalse()
        assertThat(e.state.ended).isFalse()
        assertThat(e.state.position).isEqualTo(Locator(1, 0))
        assertThat(e.state.sleepTimer).isNull()
        assertThat(e.events.filterIsInstance<PlaybackEvent.ChapterFinished>().map { it.chapter }).containsExactly(0)
    }

    @Test
    fun temporizadorPorMinutosFundeYPausa() = runTest {
        // Libro largo: 2000 frases de 100 ms.
        val long = FakeUtteranceProvider(listOf(List(2000) { "Frase $it." }))
        val engine = FakeEngine(TtsEngineType.SYSTEM)
        val focus = FakeAudioFocus()
        val c = ReadAloudController(backgroundScope, { engine }, focus, PlaybackHub())
        c.open(BookInfo("l", "L"), long, Locator.START)
        c.setSleepTimer(SleepTimerMode.Minutes(1))
        c.play()
        advanceTimeBy(55_000)
        runCurrent()
        assertThat(engine.lastVolume).isWithin(0.05f).of(0.5f)
        assertThat(c.state.value.sleepTimerRemainingMs).isEqualTo(5_000L)
        advanceTimeBy(5_100)
        runCurrent()
        assertThat(c.state.value.playWhenReady).isFalse()
        assertThat(c.state.value.sleepTimer).isNull()
        val spokenAtExpiry = engine.spoken.size
        advanceTimeBy(10_000)
        assertThat(engine.spoken.size).isEqualTo(spokenAtExpiry)
    }

    @Test
    fun rangoDePalabraSeTraduceATextoVisible() {
        val u = com.lectorvoz.core.model.Utterance(
            id = 1,
            range = com.lectorvoz.core.model.LocatorRange(Locator(0, 0), Locator(0, 6)),
            displayText = "Cap. 3",
            spokenText = "Capitulo tres",
            // "Capitulo" -> "Cap." (0..3), " " -> 4, "tres" -> "3" (5).
            spokenToDisplay = intArrayOf(0, 1, 2, 3, 3, 3, 3, 3, 4, 5, 5, 5, 5),
        )
        assertThat(ReadAloudController.displayRange(u, 0, 8)).isEqualTo(0 to 4)
        assertThat(ReadAloudController.displayRange(u, 9, 13)).isEqualTo(5 to 6)
    }
}
