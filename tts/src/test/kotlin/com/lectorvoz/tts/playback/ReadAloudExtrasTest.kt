package com.lectorvoz.tts.playback

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.LowBatteryAction
import com.lectorvoz.core.model.PauseKind
import com.lectorvoz.core.model.TtsEngineType
import com.lectorvoz.core.model.Utterance
import com.lectorvoz.core.model.VoiceSettings
import com.lectorvoz.tts.BookInfo
import com.lectorvoz.tts.FakeAudioFocus
import com.lectorvoz.tts.FakeEngine
import com.lectorvoz.tts.FakeUtteranceProvider
import com.lectorvoz.tts.SleepTimerMode
import com.lectorvoz.tts.engine.Pregenerator
import com.lectorvoz.tts.engine.SpeechEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Extras del controlador con tiempo virtual: rebobinado inteligente, bateria baja, agitar,
 * fundido de fin de capitulo, marcador, velocidad efectiva y preparacion en disco.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadAloudExtrasTest {

    /** Sensor de agitar falso. */
    private class FakeShake : ShakeSensor {
        var listening = false
        private var cb: (() -> Unit)? = null
        override fun start(onShake: () -> Unit) {
            listening = true
            cb = onShake
        }
        override fun stop() {
            listening = false
        }
        fun shake() = cb?.invoke()
    }

    /** Motor neuronal falso que ademas prepara audio en "disco". */
    private class PregenEngine : SpeechEngine by FakeEngine(TtsEngineType.NEURAL), Pregenerator {
        val prepared = ArrayList<Long>()
        var writing = false
        override val canPregenerate = true
        override suspend fun pregenerate(u: Utterance, settings: VoiceSettings): Boolean {
            delay(50)
            prepared += u.id
            return true
        }
        override fun setWriteThrough(on: Boolean) {
            writing = on
        }
    }

    private class Env(
        val controller: ReadAloudController,
        val engines: Map<TtsEngineType, SpeechEngine>,
        val shake: FakeShake,
        val hub: PlaybackHub,
    ) {
        val system get() = engines.getValue(TtsEngineType.SYSTEM) as FakeEngine
        val state get() = controller.state.value
    }

    private fun TestScope.env(
        book: FakeUtteranceProvider,
        settings: VoiceSettings = VoiceSettings(engine = TtsEngineType.SYSTEM),
        msPerUtterance: Long = 100,
        neural: SpeechEngine = FakeEngine(TtsEngineType.NEURAL, msPerUtterance),
    ): Env {
        val engines = mapOf(
            TtsEngineType.SYSTEM to FakeEngine(TtsEngineType.SYSTEM, msPerUtterance),
            TtsEngineType.NEURAL to neural,
        )
        val hub = PlaybackHub().apply { this.settings.value = settings }
        val shake = FakeShake()
        val c = ReadAloudController(
            backgroundScope, { engines.getValue(it) }, FakeAudioFocus(), hub,
            clock = { testScheduler.currentTime }, shake = shake,
        )
        c.open(BookInfo("libro", "Titulo"), book, Locator.START)
        runCurrent()
        return Env(c, engines, shake, hub)
    }

    private val book = FakeUtteranceProvider(
        listOf(listOf("Uno.", "Dos.", "Tres.", "Cuatro."), listOf("Cinco.", "Seis.")),
        pauses = mapOf(1L to PauseKind.PARAGRAPH),
    )

    /** Lee hasta la frase 4, pausa, espera [awayMs] y reanuda; devuelve la frase de reanudacion. */
    private fun TestScope.resumeAfter(awayMs: Long, settings: VoiceSettings = VoiceSettings(engine = TtsEngineType.SYSTEM)): Long {
        val e = env(book, settings)
        e.controller.play()
        advanceTimeBy(350)
        runCurrent()
        assertThat(e.state.utteranceId).isEqualTo(4L)
        e.controller.pause()
        runCurrent()
        advanceTimeBy(awayMs)
        e.controller.play()
        runCurrent()
        return e.system.spoken.last()
    }

    @Test
    fun rebobinadoInteligenteSegunElTiempoEnPausa() = runTest {
        assertThat(resumeAfter(60_000)).isEqualTo(4L)
        assertThat(resumeAfter(3 * 60_000)).isEqualTo(3L)
        assertThat(resumeAfter(45 * 60_000)).isEqualTo(2L)
        // Tras horas: inicio del parrafo (la frase 1 cierra el parrafo anterior).
        assertThat(resumeAfter(4 * 60 * 60_000L)).isEqualTo(2L)
        // Desactivado: misma frase.
        assertThat(resumeAfter(4 * 60 * 60_000L, VoiceSettings(engine = TtsEngineType.SYSTEM, smartRewind = false)))
            .isEqualTo(4L)
    }

    @Test
    fun navegarTrasLaPausaAnulaElRebobinado() = runTest {
        val e = env(book)
        e.controller.play()
        advanceTimeBy(350)
        runCurrent()
        e.controller.pause()
        advanceTimeBy(60 * 60_000L)
        e.controller.seekTo(Locator(1, 0))
        runCurrent()
        e.controller.play()
        runCurrent()
        assertThat(e.system.spoken.last()).isEqualTo(5L)
    }

    @Test
    fun bateriaBajaPasaALaVozDelSistemaOAvisa() = runTest {
        val long = FakeUtteranceProvider(listOf(List(200) { "Frase $it." }))
        val e = env(long, VoiceSettings(engine = TtsEngineType.NEURAL, lowBattery = LowBatteryAction.SWITCH_TO_SYSTEM))
        e.controller.play()
        advanceTimeBy(250)
        runCurrent()
        assertThat(e.state.engine).isEqualTo(TtsEngineType.NEURAL)
        e.controller.onBatteryChanged(BatteryStatus(12, charging = false))
        runCurrent()
        assertThat(e.state.engine).isEqualTo(TtsEngineType.SYSTEM)
        assertThat(e.state.switchedToSystemForBattery).isTrue()
        assertThat(e.state.lowBattery).isTrue()
        // Repite la frase que sonaba con la voz del sistema.
        assertThat(e.system.spoken.first()).isEqualTo(3L)
        e.controller.onBatteryChanged(BatteryStatus(13, charging = true))
        assertThat(e.state.lowBattery).isFalse()
        assertThat(e.state.switchedToSystemForBattery).isFalse()

        val w = env(long, VoiceSettings(engine = TtsEngineType.NEURAL, lowBattery = LowBatteryAction.WARN))
        w.controller.play()
        runCurrent()
        w.controller.onBatteryChanged(BatteryStatus(10, charging = false))
        runCurrent()
        assertThat(w.state.lowBattery).isTrue()
        assertThat(w.state.switchedToSystemForBattery).isFalse()
        assertThat(w.state.engine).isEqualTo(TtsEngineType.NEURAL)
        assertThat(w.system.spoken).isEmpty()
    }

    @Test
    fun bateriaBajaConHisteresisNoParpadea() = runTest {
        val long = FakeUtteranceProvider(listOf(List(50) { "Frase $it." }))
        val e = env(long, VoiceSettings(engine = TtsEngineType.NEURAL, lowBattery = LowBatteryAction.WARN))
        e.controller.play()
        runCurrent()
        // Entra en bajo por debajo del 15 %.
        e.controller.onBatteryChanged(BatteryStatus(14, charging = false))
        runCurrent()
        assertThat(e.state.lowBattery).isTrue()
        // Repunte al 16 % (por encima del umbral de entrada pero por debajo del de recuperacion):
        // sigue bajo, no parpadea.
        e.controller.onBatteryChanged(BatteryStatus(16, charging = false))
        runCurrent()
        assertThat(e.state.lowBattery).isTrue()
        // Solo recupera al llegar al 20 %.
        e.controller.onBatteryChanged(BatteryStatus(20, charging = false))
        runCurrent()
        assertThat(e.state.lowBattery).isFalse()

        // Sin haber estado bajo, el 16 % no dispara el aviso.
        val w = env(long, VoiceSettings(engine = TtsEngineType.NEURAL, lowBattery = LowBatteryAction.WARN))
        w.controller.play()
        runCurrent()
        w.controller.onBatteryChanged(BatteryStatus(16, charging = false))
        runCurrent()
        assertThat(w.state.lowBattery).isFalse()
    }

    @Test
    fun bateriaRecuperadaVuelveALaVozNeuronal() = runTest {
        val long = FakeUtteranceProvider(listOf(List(200) { "Frase $it." }))
        val e = env(long, VoiceSettings(engine = TtsEngineType.NEURAL, lowBattery = LowBatteryAction.SWITCH_TO_SYSTEM))
        e.controller.play()
        advanceTimeBy(250)
        runCurrent()
        assertThat(e.state.engine).isEqualTo(TtsEngineType.NEURAL)
        // Bateria baja: pasa a la voz del sistema.
        e.controller.onBatteryChanged(BatteryStatus(12, charging = false))
        advanceTimeBy(250)
        runCurrent()
        assertThat(e.state.engine).isEqualTo(TtsEngineType.SYSTEM)
        assertThat(e.state.switchedToSystemForBattery).isTrue()
        // Al enchufar, vuelve a la voz neuronal y el estado deja de avisar (simetria).
        e.controller.onBatteryChanged(BatteryStatus(80, charging = true))
        advanceTimeBy(250)
        runCurrent()
        assertThat(e.state.lowBattery).isFalse()
        assertThat(e.state.switchedToSystemForBattery).isFalse()
        assertThat(e.state.engine).isEqualTo(TtsEngineType.NEURAL)
    }

    @Test
    fun agitarAlargaElTemporizadorSoloEnLosUltimos30s() = runTest {
        val long = FakeUtteranceProvider(listOf(List(5000) { "Frase $it." }))
        val e = env(long)
        e.controller.setSleepTimer(SleepTimerMode.Minutes(1))
        e.controller.play()
        advanceTimeBy(20_000)
        runCurrent()
        assertThat(e.shake.listening).isFalse()
        advanceTimeBy(15_000)
        runCurrent()
        assertThat(e.shake.listening).isTrue()
        e.shake.shake()
        assertThat(e.state.sleepTimerRemainingMs).isGreaterThan(10 * 60_000L)
        assertThat(e.shake.listening).isFalse()
        advanceTimeBy(5 * 60_000L)
        runCurrent()
        assertThat(e.state.playWhenReady).isTrue()
    }

    @Test
    fun finDeCapituloFundeYAgitarSigueLeyendo() = runTest {
        val e = env(book, msPerUtterance = 5_000)
        e.controller.setSleepTimer(SleepTimerMode.EndOfChapter)
        e.controller.play()
        // Frase 4 (ultima del capitulo) empieza a los 15 s; a los 17,5 s ya hay fundido.
        advanceTimeBy(17_500)
        runCurrent()
        assertThat(e.state.utteranceId).isEqualTo(4L)
        assertThat(e.system.lastVolume).isLessThan(1f)
        assertThat(e.shake.listening).isTrue()
        e.shake.shake()
        runCurrent()
        assertThat(e.system.lastVolume).isEqualTo(1f)
        assertThat(e.state.sleepTimer).isEqualTo(SleepTimerMode.Minutes(10))
        advanceTimeBy(10_000)
        runCurrent()
        assertThat(e.system.spoken).contains(5L)
        assertThat(e.state.playWhenReady).isTrue()
    }

    @Test
    fun finDeCapituloSinAgitarParaYRestauraElVolumen() = runTest {
        val e = env(book, msPerUtterance = 5_000)
        e.controller.setSleepTimer(SleepTimerMode.EndOfChapter)
        e.controller.play()
        advanceTimeBy(60_000)
        runCurrent()
        assertThat(e.state.playWhenReady).isFalse()
        assertThat(e.state.position).isEqualTo(Locator(1, 0))
        assertThat(e.system.spoken).doesNotContain(5L)
        assertThat(e.shake.listening).isFalse()
        e.controller.play()
        runCurrent()
        assertThat(e.system.lastVolume).isEqualTo(1f)
    }

    @Test
    fun marcadorEnLaFraseActual() = runTest {
        val e = env(book)
        e.controller.play()
        advanceTimeBy(150)
        runCurrent()
        e.controller.requestBookmark()
        assertThat(e.hub.bookmarkRequests.first()).isEqualTo(book.all[1].range.start)
    }

    @Test
    fun velocidadEfectivaYLongitudDelCapitulo() = runTest {
        val e = env(book)
        assertThat(e.state.chapterLength).isEqualTo(4 + 1 + 4 + 1 + 5 + 1 + 7 + 1)
        e.controller.play()
        advanceTimeBy(350)
        runCurrent()
        // Frases 1..3 medidas de inicio a inicio (100 ms cada una): 13 caracteres en 0,3 s.
        assertThat(e.state.charsPerSecond).isWithin(0.01f).of(13 / 0.3f)
        assertThat(e.state.chapterRemainingMs).isNotNull()
        // Cambiar la velocidad olvida las medidas.
        e.controller.updateSettings(VoiceSettings(engine = TtsEngineType.SYSTEM, speed = 1.5f))
        assertThat(e.state.charsPerSecond).isNull()
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(e.state.chapterLength).isEqualTo(6 + 1 + 5 + 1)
    }

    @Test
    fun preparaAudioSoloCargandoYConElAjuste() = runTest {
        val pregen = PregenEngine()
        val e = env(
            book,
            VoiceSettings(engine = TtsEngineType.NEURAL, prepareWhileCharging = true),
            neural = pregen,
        )
        e.controller.onBatteryChanged(BatteryStatus(50, charging = false))
        advanceTimeBy(1_000)
        assertThat(pregen.prepared).isEmpty()
        e.controller.onBatteryChanged(BatteryStatus(50, charging = true))
        runCurrent()
        assertThat(e.state.preparingCache).isTrue()
        assertThat(pregen.writing).isTrue()
        advanceTimeBy(120)
        runCurrent()
        // Desenchufar corta la preparacion.
        e.controller.onBatteryChanged(BatteryStatus(50, charging = false))
        runCurrent()
        val soFar = pregen.prepared.size
        assertThat(soFar).isIn(1..3)
        assertThat(e.state.preparingCache).isFalse()
        assertThat(pregen.writing).isFalse()
        advanceTimeBy(10_000)
        assertThat(pregen.prepared).hasSize(soFar)
        // Otra vez cargando: llega hasta el final (2 capitulos por delante caben enteros).
        e.controller.onBatteryChanged(BatteryStatus(60, charging = true))
        advanceTimeBy(10_000)
        runCurrent()
        assertThat(pregen.prepared.distinct()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L)
    }
}
