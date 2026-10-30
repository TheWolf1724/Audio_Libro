package com.lectorvoz.tts.playback

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.TtsEngineType
import com.lectorvoz.core.model.Utterance
import com.lectorvoz.core.model.VoiceSettings
import com.lectorvoz.tts.BookInfo
import com.lectorvoz.tts.FakeAudioFocus
import com.lectorvoz.tts.FakeEngine
import com.lectorvoz.tts.FakeUtteranceProvider
import com.lectorvoz.tts.SleepTimerMode
import com.lectorvoz.tts.engine.SpeechEngine
import com.lectorvoz.tts.engine.SpeechListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Regresiones de la revision 2 en el controlador: M1 (temporizador de fin de capitulo),
 * M3 (supresion por foco), M7 (velocidad desde Media3), B3 (foco al abrir otro libro) y B8
 * (comandos propios solo para controladores de confianza). Las dos primeras de M1 vienen de
 * las reproducciones del revisor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ControladorRevision2Test {

    /** Motor falso que pide `next` 2 frases por delante (como el neuronal); 1 s por frase. */
    private class Lookahead : SpeechEngine {
        override val type = TtsEngineType.SYSTEM
        val spoken = ArrayList<Long>()
        override suspend fun prepare(settings: VoiceSettings) {}
        override suspend fun speak(start: Utterance, next: suspend (Utterance) -> Utterance?, listener: SpeechListener) {
            val q = ArrayDeque<Utterance>()
            q.add(start)
            var tail: Utterance? = start
            while (q.isNotEmpty()) {
                while (q.size < 3 && tail != null) {
                    tail = next(tail)
                    tail?.let { q.add(it) }
                }
                val u = q.removeFirst()
                listener.onUtteranceStart(u)
                spoken += u.id
                delay(1_000)
                listener.onUtteranceDone(u)
            }
        }
        override fun updateSettings(settings: VoiceSettings) {}
        override fun setVolume(volume: Float) {}
        override fun release() {}
    }

    private val twoChapters = FakeUtteranceProvider(listOf(List(5) { "Frase $it." }, List(5) { "Otra $it." }))
    private val threeChapters = FakeUtteranceProvider(
        listOf(List(5) { "Frase $it." }, List(5) { "Otra $it." }, List(5) { "Mas $it." }),
    )

    private class Env(val controller: ReadAloudController, val hub: PlaybackHub, val focus: FakeAudioFocus) {
        val state get() = controller.state.value
    }

    private fun TestScope.env(engine: SpeechEngine, book: FakeUtteranceProvider): Env {
        val hub = PlaybackHub().apply { settings.value = VoiceSettings(engine = TtsEngineType.SYSTEM) }
        val focus = FakeAudioFocus()
        val c = ReadAloudController(backgroundScope, { engine }, focus, hub, clock = { testScheduler.currentTime })
        c.open(BookInfo("b", "t"), book, Locator.START)
        runCurrent()
        return Env(c, hub, focus)
    }

    /** Revision final M1: `clear()` para, olvida el libro y deja el estado vacio (bookId null). */
    @Test
    fun clearParaYLimpiaElEstado() = runTest {
        val eng = Lookahead()
        val e = env(eng, twoChapters)
        e.controller.setSleepTimer(SleepTimerMode.Minutes(30))
        e.controller.play(); runCurrent()
        advanceTimeBy(1_500); runCurrent()
        assertThat(e.state.bookId).isEqualTo("b")
        e.hub.pendingBook = BookRequest(BookInfo("b", "t"), twoChapters, Locator.START)
        e.controller.clear(); runCurrent()
        assertThat(e.controller.book).isNull()
        assertThat(e.hub.pendingBook).isNull()
        assertThat(e.state).isEqualTo(com.lectorvoz.tts.PlaybackState())
        assertThat(e.state.bookId).isNull()
        assertThat(e.state.playWhenReady).isFalse()
        assertThat(e.state.sleepTimer).isNull()
        assertThat(e.focus.held).isFalse()
    }

    /** M1: quitar el "fin de capitulo" cuando ya se pidio la frontera no para la lectura. */
    @Test
    fun quitarTemporizadorFinDeCapituloSigueLeyendo() = runTest {
        val eng = Lookahead()
        val e = env(eng, twoChapters)
        e.controller.setSleepTimer(SleepTimerMode.EndOfChapter)
        e.controller.play(); runCurrent()
        advanceTimeBy(3_500); runCurrent() // frase 4 de 5 sonando; la frontera ya se pidio
        e.controller.setSleepTimer(null)
        advanceTimeBy(20_000); runCurrent()
        assertThat(eng.spoken).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L).inOrder()
        assertThat(e.state.ended).isTrue()
        assertThat(e.state.sleepTimer).isNull()
    }

    /** M1: cambiar "fin de capitulo" por minutos tampoco para al final del capitulo. */
    @Test
    fun cambiarFinDeCapituloPorMinutosSigueLeyendo() = runTest {
        val eng = Lookahead()
        val e = env(eng, twoChapters)
        e.controller.setSleepTimer(SleepTimerMode.EndOfChapter)
        e.controller.play(); runCurrent()
        advanceTimeBy(3_500); runCurrent()
        e.controller.setSleepTimer(SleepTimerMode.Minutes(30))
        advanceTimeBy(3_000); runCurrent() // ya en el capitulo 2 (el libro acaba a los 10 s)
        assertThat(eng.spoken).contains(7L)
        assertThat(e.state.playWhenReady).isTrue()
        assertThat(e.state.sleepTimer).isEqualTo(SleepTimerMode.Minutes(30))
    }

    /** M1: con "fin de capitulo", saltar al capitulo siguiente lee ese capitulo entero. */
    @Test
    fun capituloSiguienteConTemporizadorNoParaEnSeco() = runTest {
        val eng = FakeEngine(TtsEngineType.SYSTEM, msPerUtterance = 1_000)
        val e = env(eng, threeChapters)
        e.controller.setSleepTimer(SleepTimerMode.EndOfChapter)
        e.controller.play(); runCurrent()
        advanceTimeBy(1_500); runCurrent()
        e.controller.nextChapter()
        advanceTimeBy(20_000); runCurrent()
        assertThat(eng.spoken).containsExactly(1L, 2L, 6L, 7L, 8L, 9L, 10L).inOrder()
        assertThat(e.state.playWhenReady).isFalse()
        assertThat(e.state.position).isEqualTo(Locator(2, 0))
        assertThat(e.state.sleepTimer).isNull()
    }

    /** M1: una parada pendiente no sobrevive a una pausa (no salta atras al acabar el libro). */
    @Test
    fun pausarYQuitarTemporizadorLlegaAlFinalDelLibro() = runTest {
        val eng = Lookahead()
        val e = env(eng, twoChapters)
        e.controller.setSleepTimer(SleepTimerMode.EndOfChapter)
        e.controller.play(); runCurrent()
        advanceTimeBy(3_500); runCurrent()
        e.controller.pause(); runCurrent()
        e.controller.setSleepTimer(null)
        e.controller.play(); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertThat(eng.spoken.last()).isEqualTo(10L)
        assertThat(e.state.ended).isTrue()
        assertThat(e.state.position).isEqualTo(twoChapters.all.last().range.start)
    }

    /** M3: navegar durante una llamada mueve la posicion pero no arranca la voz. */
    @Test
    fun navegarDuranteLaSupresionNoSuena() = runTest {
        val eng = FakeEngine(TtsEngineType.SYSTEM, msPerUtterance = 1_000)
        val e = env(eng, twoChapters)
        e.controller.play(); runCurrent()
        e.focus.emit(FocusChange.LOSS_TRANSIENT); runCurrent()
        e.controller.nextSentence(); runCurrent()
        advanceTimeBy(3_000); runCurrent()
        assertThat(eng.spoken).containsExactly(1L)
        assertThat(e.state.utteranceId).isEqualTo(2L)
        assertThat(e.state.playWhenReady).isTrue()
        e.focus.emit(FocusChange.GAIN); runCurrent()
        assertThat(eng.spoken).containsExactly(1L, 2L).inOrder()
        // Pausar durante la supresion deja la lectura en pausa normal.
        e.focus.emit(FocusChange.LOSS_TRANSIENT); runCurrent()
        e.controller.pause(); runCurrent()
        assertThat(e.state.playWhenReady).isFalse()
        assertThat(e.state.focusSuppressed).isFalse()
        e.focus.emit(FocusChange.GAIN); runCurrent()
        assertThat(e.state.playWhenReady).isFalse()
    }

    /** M7: la velocidad pedida desde Media3 llega a los ajustes compartidos. */
    @Test
    fun velocidadDesdeMedia3LlegaALosAjustes() = runTest {
        val e = env(FakeEngine(TtsEngineType.SYSTEM), twoChapters)
        e.controller.setPlaybackParameters(1.4f, 0.95f)
        assertThat(e.hub.settings.value.speed).isEqualTo(1.4f)
        assertThat(e.hub.settings.value.pitch).isEqualTo(0.95f)
        assertThat(e.controller.currentSettings.speed).isEqualTo(1.4f)
    }

    /** B7: la cuenta del temporizador por minutos se congela con la lectura en pausa. */
    @Test
    fun temporizadorNoCuentaConLaLecturaEnPausa() = runTest {
        val long = FakeUtteranceProvider(listOf(List(3000) { "Frase $it." }))
        val e = env(FakeEngine(TtsEngineType.SYSTEM), long)
        e.controller.setSleepTimer(SleepTimerMode.Minutes(1))
        e.controller.play()
        advanceTimeBy(10_000); runCurrent()
        e.controller.pause(); runCurrent()
        advanceTimeBy(120_000); runCurrent()
        assertThat(e.state.sleepTimer).isEqualTo(SleepTimerMode.Minutes(1))
        assertThat(e.state.sleepTimerRemainingMs).isEqualTo(50_000L)
        e.controller.play()
        advanceTimeBy(49_000); runCurrent()
        assertThat(e.state.playWhenReady).isTrue()
        advanceTimeBy(1_100); runCurrent()
        assertThat(e.state.playWhenReady).isFalse()
        assertThat(e.state.sleepTimer).isNull()
    }

    /** B3: abrir otro libro mientras suena suelta el foco de audio. */
    @Test
    fun abrirOtroLibroSueltaElFoco() = runTest {
        val e = env(FakeEngine(TtsEngineType.SYSTEM, msPerUtterance = 1_000), twoChapters)
        e.controller.play(); runCurrent()
        assertThat(e.focus.held).isTrue()
        e.controller.open(BookInfo("otro", "Otro"), threeChapters, Locator.START); runCurrent()
        assertThat(e.focus.held).isFalse()
        assertThat(e.state.playWhenReady).isFalse()
    }

    /** B8: los comandos propios solo se conceden a controladores de confianza. */
    @Test
    fun comandosPropiosSoloParaControladoresDeConfianza() {
        fun trusted(pkg: String, notif: Boolean = false, system: Boolean = false, car: Boolean = false) =
            ControllerTrust.isTrusted("com.lectorvoz.app", pkg, notif, system, car)
        assertThat(trusted("com.lectorvoz.app")).isTrue()
        assertThat(trusted("com.android.systemui", notif = true)).isTrue()
        assertThat(trusted("com.android.bluetooth", system = true)).isTrue()
        assertThat(trusted("com.google.android.projection.gearhead", car = true)).isTrue()
        assertThat(trusted("com.otra.app")).isFalse()
    }
}
