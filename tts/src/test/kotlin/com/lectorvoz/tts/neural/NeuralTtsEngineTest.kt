package com.lectorvoz.tts.neural

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.TtsEngineType
import com.lectorvoz.core.model.VoiceSettings
import com.lectorvoz.core.model.PauseKind
import com.lectorvoz.core.model.ReadingLanguage
import com.lectorvoz.core.model.UtteranceKind
import com.lectorvoz.core.model.VoiceQuality
import com.lectorvoz.core.model.VoiceRole
import com.lectorvoz.tts.FakeUtteranceProvider
import com.lectorvoz.tts.cache.AudioDiskCache
import com.lectorvoz.tts.engine.AdaptivePace
import com.lectorvoz.tts.utt
import com.lectorvoz.tts.RecordingListener
import com.lectorvoz.tts.voice.VoiceCatalog
import com.lectorvoz.tts.voice.VoiceRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.math.roundToInt

/** Cola productor/consumidor del motor neuronal con sintetizador y salida falsos. */
@OptIn(ExperimentalCoroutinesApi::class)
class NeuralTtsEngineTest {

    /**
     * 1000 Hz; 10 muestras (10 ms) por caracter; registra adelanto respecto a lo que suena
     * y los parametros. [shape] permite devolver otra senal.
     */
    private class FakeSynth(
        private val shape: (String) -> FloatArray = { t -> FloatArray(t.length * 10) { 0.5f } },
        private val playing: () -> Int,
    ) : NeuralSynthesizer {
        override val sampleRate = 1000
        val calls = ArrayList<String>()
        val params = ArrayList<SynthesisParams>()
        val lead = ArrayList<Int>()
        var released = false
        override fun synthesize(text: String, params: SynthesisParams, isCancelled: () -> Boolean): FloatArray? {
            calls += text
            this.params += params
            lead += calls.size - playing()
            return shape(text)
        }
        override fun release() { released = true }
    }

    @get:org.junit.Rule val tmp = org.junit.rules.TemporaryFolder()

    private companion object {
        /** Velocidad de usuario que deja la total en 1,0: el modelo va directo y nada se estira. */
        val SIN_ESTIRAR = 1f / VoiceCatalog.SUPERTONIC_BASE_SPEED

        /** Frase larga (72 caracteres). */
        const val LARGA = "Aquella manana de otono la niebla cubria el puerto y las barcas dormian."
    }

    /**
     * Salida que "reproduce" en tiempo virtual (cabeza = ms transcurridos * sampleRate/1000)
     * con un bufer de [capacity] muestras: si esta lleno, write devuelve 0 (como un
     * AudioTrack bloqueante, el motor reintenta tras un retardo).
     */
    private class FakeSink(
        private val scheduler: TestCoroutineScheduler,
        private val capacity: Long = 30,
    ) : PcmSink {
        var opened = 0L
        var rate = 0
        var written = 0L
        var released = false
        var lastVolume = 1f
        override fun open(sampleRate: Int) { rate = sampleRate; opened = scheduler.currentTime }
        override fun write(data: FloatArray, offset: Int, length: Int): Int {
            val free = (capacity - (written - playedFrames())).coerceAtLeast(0)
            val n = minOf(free, length.toLong()).toInt()
            written += n
            return n
        }
        override fun playedFrames(): Long =
            minOf(written, (scheduler.currentTime - opened) * rate / 1000)
        override fun setVolume(volume: Float) { lastVolume = volume }
        override fun setPitch(pitch: Float) = true
        override fun release() { released = true }
    }

    private val book = FakeUtteranceProvider(
        listOf(listOf("Uno dos.", "Tres cuatro cinco.", "Seis.", "Siete ocho.", "Nueve diez once doce.")),
    )

    @Test
    fun leeEnOrdenConInicioPalabrasYFin() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val listener = RecordingListener()
        var started = 0
        val synth = FakeSynth { started }
        val sink = FakeSink(testScheduler)
        val engine = NeuralTtsEngine(
            loader = { _, _ -> synth }, sinkFactory = { sink },
            synthDispatcher = d, audioDispatcher = d, tickDispatcher = d, tickMs = 5,
        )
        engine.prepare(VoiceSettings(engine = TtsEngineType.NEURAL, speed = SIN_ESTIRAR, punctuationPause = 0f))
        engine.speak(book.all.first(), book::next, object : com.lectorvoz.tts.engine.SpeechListener by listener {
            override fun onUtteranceStart(utterance: com.lectorvoz.core.model.Utterance) {
                started++
                listener.onUtteranceStart(utterance)
            }
        })
        val log = listener.snapshot()
        val starts = log.filter { it.startsWith("start") }
        val dones = log.filter { it.startsWith("done") }
        assertThat(starts).containsExactly("start:1", "start:2", "start:3", "start:4", "start:5").inOrder()
        assertThat(dones).containsExactly("done:1", "done:2", "done:3", "done:4", "done:5").inOrder()
        // Cada inicio va antes de su fin y del inicio de la siguiente.
        assertThat(log.indexOf("done:1")).isLessThan(log.indexOf("start:2"))
        assertThat(log.any { it.startsWith("word:5") }).isTrue()
        assertThat(sink.released).isTrue()
        // Sin pausas: todo lo escrito es la voz (10 muestras por caracter).
        assertThat(sink.written).isEqualTo(book.all.sumOf { it.spokenText.length * 10L })
    }

    @Test
    fun sintetizaComoMaximoDosFrasesPorDelante() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        var started = 0
        val synth = FakeSynth { started }
        val engine = NeuralTtsEngine(
            loader = { _, _ -> synth }, sinkFactory = { FakeSink(testScheduler) },
            synthDispatcher = d, audioDispatcher = d, tickDispatcher = d, tickMs = 5, lookahead = 2,
            bufferAheadMs = 0,
        )
        val listener = object : com.lectorvoz.tts.engine.SpeechListener {
            override fun onUtteranceStart(utterance: com.lectorvoz.core.model.Utterance) { started++ }
            override fun onWord(utterance: com.lectorvoz.core.model.Utterance, spokenStart: Int, spokenEnd: Int) {}
            override fun onUtteranceDone(utterance: com.lectorvoz.core.model.Utterance) {}
        }
        engine.speak(book.all.first(), book::next, listener)
        assertThat(synth.calls).hasSize(5)
        // Adelanto = frases sintetizadas - frases empezadas: nunca mas de actual + 2.
        assertThat(synth.lead.max()).isAtMost(3)
        assertThat(synth.lead).contains(3)
    }

    /**
     * Precarga por tiempo: con frases cortas va muchas frases por delante (hasta tener
     * bufferAheadMs de audio listo); con frases largas, pocas. Nunca pasa de maxLookahead.
     */
    @Test
    fun precargaPorTiempoYNoPorNumeroDeFrases() = runTest {
        suspend fun leadWith(frase: String, frases: Int): Int {
            val d = StandardTestDispatcher(testScheduler)
            var started = 0
            // 1000 Hz: cada caracter son 10 ms de voz (FakeSynth: 10 muestras por caracter).
            val synth = FakeSynth { started }
            val engine = NeuralTtsEngine(
                loader = { _, _ -> synth }, sinkFactory = { FakeSink(testScheduler) },
                synthDispatcher = d, audioDispatcher = d, tickDispatcher = d, tickMs = 5,
                lookahead = 3, bufferAheadMs = 2_000, maxLookahead = 12,
            )
            // Frases distintas (si fueran iguales las serviria la cache de audio sin sintetizar).
            val texts = FakeUtteranceProvider(listOf(List(frases) { i -> frase.dropLast(3) + "%02d.".format(i) }))
            val listener = object : com.lectorvoz.tts.engine.SpeechListener {
                override fun onUtteranceStart(utterance: com.lectorvoz.core.model.Utterance) { started++ }
                override fun onWord(utterance: com.lectorvoz.core.model.Utterance, spokenStart: Int, spokenEnd: Int) {}
                override fun onUtteranceDone(utterance: com.lectorvoz.core.model.Utterance) {}
            }
            engine.prepare(VoiceSettings(engine = TtsEngineType.NEURAL, speed = SIN_ESTIRAR, punctuationPause = 0f))
            launch { engine.speak(texts.all.first(), texts::next, listener) }
            advanceUntilIdle()
            return synth.lead.max()
        }
        // "Si00." = 50 ms de voz: 2 s de margen serian ~40 frases, pero el tope es 12 (+1 en curso).
        assertThat(leadWith("Si...", 30)).isEqualTo(13)
        // Frase de 50 caracteres = 500 ms: con 2 s de margen van ~5 por delante (no 40).
        val larga = "Una frase bastante larga que dura medio segundo.."
        assertThat(leadWith(larga, 30)).isIn(4..7)
    }

    /**
     * Voz falsa realista para frases agrupadas: 10 muestras por caracter; tras cada punto la voz
     * se apaga en 100 ms (como la real) y deja 80 ms de silencio (la pausa natural del modelo);
     * al final, apagado y cola de silencio.
     */
    private fun vozConPausas(text: String): FloatArray {
        val out = ArrayList<Float>()
        fun apagado() = repeat(100) { k -> out += 0.5f * Math.pow(10.0, -3.0 * k / 100).toFloat() }
        for ((i, c) in text.withIndex()) {
            if (c == ' ' && i > 0 && text[i - 1] == '.') { apagado(); repeat(80) { out += 0f } } else repeat(10) { out += 0.5f }
        }
        apagado()
        repeat(150) { out += 0f }
        return out.toFloatArray()
    }

    @Test
    fun frasesCortasSeSintetizanJuntasYCadaUnaSuenaEnSuSitio() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val synth = FakeSynth(shape = ::vozConPausas) { 0 }
        val engine = NeuralTtsEngine(
            loader = { _, _ -> synth }, sinkFactory = { FakeSink(testScheduler, capacity = 100_000) },
            synthDispatcher = d, audioDispatcher = d, tickDispatcher = d, tickMs = 5, groupMaxChars = 180,
        )
        val texts = FakeUtteranceProvider(listOf(listOf("Hola.", "Que tal estas hoy.", "Muy bien.")))
        engine.prepare(VoiceSettings(engine = TtsEngineType.NEURAL, speed = SIN_ESTIRAR, punctuationPause = 0f))
        val listener = RecordingListener()
        engine.speak(texts.all.first(), texts::next, listener)
        // Una sola llamada al modelo para las tres frases.
        assertThat(synth.calls).containsExactly("Hola. Que tal estas hoy. Muy bien.")
        val log = listener.snapshot().filter { !it.startsWith("word") }
        assertThat(log).containsExactly("start:1", "done:1", "start:2", "done:2", "start:3", "done:3").inOrder()
    }

    @Test
    fun sinPausaClaraLasFrasesSeSintetizanUnaAUna() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        // Voz continua sin pausas: no se puede partir con seguridad -> una a una.
        val synth = FakeSynth { 0 }
        val engine = NeuralTtsEngine(
            loader = { _, _ -> synth }, sinkFactory = { FakeSink(testScheduler, capacity = 100_000) },
            synthDispatcher = d, audioDispatcher = d, tickDispatcher = d, tickMs = 5, groupMaxChars = 180,
        )
        val texts = FakeUtteranceProvider(listOf(listOf("Hola.", "Que tal.")))
        engine.prepare(VoiceSettings(engine = TtsEngineType.NEURAL, speed = SIN_ESTIRAR, punctuationPause = 0f))
        val listener = RecordingListener()
        engine.speak(texts.all.first(), texts::next, listener)
        assertThat(synth.calls).containsExactly("Hola. Que tal.", "Hola.", "Que tal.").inOrder()
        assertThat(listener.snapshot().filter { it.startsWith("done") }).containsExactly("done:1", "done:2").inOrder()
    }

    /** Con la precarga por parrafos, antes de que suene la primera frase ya estan enteros el parrafo actual y el siguiente. */
    @Test
    fun precargaElParrafoSiguienteEntero() = runTest {
        suspend fun synthesizedAtFirstStart(preload: Boolean): Int {
            val d = StandardTestDispatcher(testScheduler)
            var calls = -1
            val synth = FakeSynth { 0 }
            val engine = NeuralTtsEngine(
                loader = { _, _ -> synth }, sinkFactory = { FakeSink(testScheduler, capacity = 100_000) },
                synthDispatcher = d, audioDispatcher = d, tickDispatcher = d, tickMs = 5,
                lookahead = 1, bufferAheadMs = 10, preloadNextParagraph = preload,
            )
            // Parrafos: [1-3], [4-6], [7-9].
            val texts = FakeUtteranceProvider(
                listOf(List(9) { i -> "Frase numero $i." }),
                pauses = mapOf(3L to PauseKind.PARAGRAPH, 6L to PauseKind.PARAGRAPH, 9L to PauseKind.PARAGRAPH),
            )
            val listener = object : com.lectorvoz.tts.engine.SpeechListener {
                override fun onUtteranceStart(utterance: com.lectorvoz.core.model.Utterance) { if (calls < 0) calls = synth.calls.size }
                override fun onWord(utterance: com.lectorvoz.core.model.Utterance, spokenStart: Int, spokenEnd: Int) {}
                override fun onUtteranceDone(utterance: com.lectorvoz.core.model.Utterance) {}
            }
            engine.prepare(VoiceSettings(engine = TtsEngineType.NEURAL, speed = SIN_ESTIRAR, punctuationPause = 0f))
            engine.speak(texts.all.first(), texts::next, listener)
            return calls
        }
        assertThat(synthesizedAtFirstStart(preload = true)).isAtLeast(6)
        assertThat(synthesizedAtFirstStart(preload = false)).isAtMost(3)
    }

    /** Politica dinamica: con nivel alto (2 parrafos por delante) se preparan el actual y los dos siguientes. */
    @Test
    fun politicaDinamicaPrecargaLosParrafosQuePide() = runTest {
        suspend fun synthesizedAtFirstStart(policy: PreloadPolicy): Int {
            val d = StandardTestDispatcher(testScheduler)
            var calls = -1
            val synth = FakeSynth { 0 }
            val engine = NeuralTtsEngine(
                loader = { _, _ -> synth }, sinkFactory = { FakeSink(testScheduler, capacity = 100_000) },
                synthDispatcher = d, audioDispatcher = d, tickDispatcher = d, tickMs = 5, lookahead = 1,
                preloadPolicy = { policy },
            )
            // Parrafos: [1-3], [4-6], [7-9], [10-12].
            val texts = FakeUtteranceProvider(
                listOf(List(12) { i -> "Frase numero $i." }),
                pauses = mapOf(3L to PauseKind.PARAGRAPH, 6L to PauseKind.PARAGRAPH, 9L to PauseKind.PARAGRAPH, 12L to PauseKind.PARAGRAPH),
            )
            val listener = object : com.lectorvoz.tts.engine.SpeechListener {
                override fun onUtteranceStart(utterance: com.lectorvoz.core.model.Utterance) { if (calls < 0) calls = synth.calls.size }
                override fun onWord(utterance: com.lectorvoz.core.model.Utterance, spokenStart: Int, spokenEnd: Int) {}
                override fun onUtteranceDone(utterance: com.lectorvoz.core.model.Utterance) {}
            }
            engine.prepare(VoiceSettings(engine = TtsEngineType.NEURAL, speed = SIN_ESTIRAR, punctuationPause = 0f))
            engine.speak(texts.all.first(), texts::next, listener)
            return calls
        }
        val tiny = PreloadPolicy(bufferAheadMs = 10, refillBelowMs = 5, maxAheadMs = 600_000, paragraphsAhead = 0)
        assertThat(synthesizedAtFirstStart(tiny.copy(paragraphsAhead = 2))).isAtLeast(9)
        assertThat(synthesizedAtFirstStart(tiny.copy(paragraphsAhead = 1))).isIn(6..8)
        assertThat(synthesizedAtFirstStart(tiny)).isAtMost(3)
    }

    /** El motor mide su velocidad de sintesis (y no la da hasta tener unos segundos de audio). */
    @Test
    fun mideLaVelocidadDeSintesis() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        // 1000 Hz y 2000 muestras por frase: 2 s de audio cada una.
        val synth = FakeSynth(shape = { FloatArray(2000) { i -> if (i < 1800) 0.4f * Math.pow(10.0, -3.0 * maxOf(0, i - 1700) / 100).toFloat() else 0f } }) { 0 }
        val engine = NeuralTtsEngine(
            loader = { _, _ -> synth }, sinkFactory = { FakeSink(testScheduler, capacity = 100_000) },
            synthDispatcher = d, audioDispatcher = d, tickDispatcher = d, tickMs = 5,
        )
        assertThat(engine.synthesisRtf).isNull()
        val texts = FakeUtteranceProvider(listOf(List(6) { i -> "Frase $i." }))
        engine.prepare(VoiceSettings(engine = TtsEngineType.NEURAL, speed = SIN_ESTIRAR, punctuationPause = 0f))
        engine.speak(texts.all.first(), texts::next, RecordingListener())
        assertThat(engine.synthesisRtf).isNotNull()
        assertThat(engine.synthesisRtf!!).isAtLeast(0f)
    }

    @Test
    fun anadeSilencioPorPuntuacion() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val sink = FakeSink(testScheduler)
        val engine = NeuralTtsEngine(
            loader = { _, _ -> FakeSynth { 0 } }, sinkFactory = { sink },
            synthDispatcher = d, audioDispatcher = d, tickDispatcher = d, tickMs = 5,
        )
        engine.prepare(VoiceSettings(engine = TtsEngineType.NEURAL, speed = SIN_ESTIRAR, punctuationPause = 1f))
        val single = FakeUtteranceProvider(listOf(listOf("Hola.")))
        engine.speak(single.all.first(), single::next, RecordingListener())
        // 5 caracteres * 10 + pausa de frase (320 ms) a 1000 Hz.
        assertThat(sink.written).isEqualTo(50L + 320L)
    }

    @Test
    fun cancelarCortaElAudioYNoEmiteMas() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val sink = FakeSink(testScheduler)
        val listener = RecordingListener()
        val engine = NeuralTtsEngine(
            loader = { _, _ -> FakeSynth { 0 } }, sinkFactory = { sink },
            synthDispatcher = d, audioDispatcher = d, tickDispatcher = d, tickMs = 5,
        )
        engine.prepare(VoiceSettings(engine = TtsEngineType.NEURAL, speed = SIN_ESTIRAR, punctuationPause = 0f))
        val job = launch { engine.speak(book.all.first(), book::next, listener) }
        advanceTimeBy(120) // Dentro de la segunda frase (la primera dura 80 ms).
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        val log = listener.snapshot()
        assertThat(log).contains("start:2")
        assertThat(log).doesNotContain("start:3")
        assertThat(sink.released).isTrue()
    }

    @Test
    fun liberaElModeloTrasInactividadYLoRecarga() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val loaded = ArrayList<FakeSynth>()
        val engine = NeuralTtsEngine(
            loader = { _: VoiceRef, _: Int -> FakeSynth { 0 }.also { loaded += it } },
            sinkFactory = { FakeSink(testScheduler) },
            synthDispatcher = d, audioDispatcher = d, tickDispatcher = d, tickMs = 5,
            idleReleaseMs = 5 * 60_000L,
        )
        engine.prepare(VoiceSettings(engine = TtsEngineType.NEURAL, speed = SIN_ESTIRAR))
        assertThat(engine.isModelLoaded).isTrue()
        advanceTimeBy(4 * 60_000L)
        runCurrent()
        assertThat(engine.isModelLoaded).isTrue()
        advanceTimeBy(61_000L)
        runCurrent()
        assertThat(engine.isModelLoaded).isFalse()
        assertThat(loaded.single().released).isTrue()
        engine.speak(book.all.last(), book::next, RecordingListener())
        assertThat(loaded).hasSize(2)
    }

    @Test
    fun reutilizaLaFraseSintetizadaAlReanudar() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val synth = FakeSynth { 0 }
        val engine = NeuralTtsEngine(
            loader = { _, _ -> synth }, sinkFactory = { FakeSink(testScheduler) },
            synthDispatcher = d, audioDispatcher = d, tickDispatcher = d, tickMs = 5,
        )
        val one = FakeUtteranceProvider(listOf(listOf("Una frase larga de prueba.")))
        engine.speak(one.all.first(), one::next, RecordingListener())
        engine.speak(one.all.first(), one::next, RecordingListener())
        assertThat(synth.calls).hasSize(1)
    }

    @Test
    fun volumenSeAplicaALaSalida() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val sink = FakeSink(testScheduler)
        val engine = NeuralTtsEngine(
            loader = { _, _ -> FakeSynth { 0 } }, sinkFactory = { sink },
            synthDispatcher = d, audioDispatcher = d, tickDispatcher = d, tickMs = 5,
        )
        engine.setVolume(0.2f)
        engine.speak(book.all.first(), { null }, RecordingListener())
        assertThat(sink.lastVolume).isEqualTo(0.2f)
    }

    @Test
    fun pausaCalibradaSegunElTipoYElFactor() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val sink = FakeSink(testScheduler)
        val engine = NeuralTtsEngine(
            loader = { _, _ -> FakeSynth { 0 } }, sinkFactory = { sink },
            synthDispatcher = d, audioDispatcher = d, tickDispatcher = d, tickMs = 5,
        )
        engine.prepare(VoiceSettings(engine = TtsEngineType.NEURAL, speed = SIN_ESTIRAR, punctuationPause = 2f))
        val list = listOf(
            utt(1, 0, 0, "Hola.", pauseAfter = PauseKind.PARAGRAPH),
            utt(2, 0, 6, "Si.", pauseAfter = PauseKind.NONE),
        )
        engine.speak(list[0], { u -> list.getOrNull(list.indexOf(u) + 1) }, RecordingListener())
        // Voz 50 + 30; pausas: parrafo 650 ms x 2 y ninguna.
        assertThat(sink.written).isEqualTo(50L + 1300L + 30L)
    }

    @Test
    fun recortaElSilencioDelModeloAntesDeLaPausa() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val sink = FakeSink(testScheduler, capacity = 10_000)
        // 300 ms de silencio, 100 ms de voz que se apaga exponencialmente hasta -60 dB (un final
        // real: si parara en seco el detector de cortes la haria repetir), 400 ms de silencio a
        // 1000 Hz.
        val synth = FakeSynth(shape = { FloatArray(800) { i -> if (i in 300 until 400) 0.4f * Math.pow(10.0, -3.0 * (i - 300) / 100).toFloat() else 0f } }) { 0 }
        val engine = NeuralTtsEngine(
            loader = { _, _ -> synth }, sinkFactory = { sink },
            synthDispatcher = d, audioDispatcher = d, tickDispatcher = d, tickMs = 5,
        )
        engine.prepare(VoiceSettings(engine = TtsEngineType.NEURAL, speed = SIN_ESTIRAR, punctuationPause = 0f))
        val one = FakeUtteranceProvider(listOf(listOf("Hola.")))
        engine.speak(one.all.first(), one::next, RecordingListener())
        // Se conservan 30 ms antes y 100 ms despues de la voz (cola natural); la voz por encima
        // del umbral (1,2 % del pico) dura 65 ms por el apagado final.
        assertThat(sink.written).isEqualTo(30L + 65L + 100L)
    }

    @Test
    fun parametrosPorFraseIdiomaLocutorDialogoRitmoYPasos() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val synth = FakeSynth { 0 }
        val engine = NeuralTtsEngine(
            loader = { _, _ -> synth }, sinkFactory = { FakeSink(testScheduler) },
            synthDispatcher = d, audioDispatcher = d, tickDispatcher = d, tickMs = 5,
        )
        val list = listOf(
            utt(1, 0, 0, "Hola.", language = "es"),
            utt(2, 0, 6, "I will be back.", language = "en-GB", role = VoiceRole.DIALOG),
            utt(3, 0, 22, "Capitulo dos", kind = UtteranceKind.HEADING),
            utt(4, 0, 35, LARGA),
        )
        val next: suspend (com.lectorvoz.core.model.Utterance) -> com.lectorvoz.core.model.Utterance? =
            { u -> list.getOrNull(list.indexOf(u) + 1) }
        val settings = VoiceSettings(
            engine = TtsEngineType.NEURAL,
            quality = VoiceQuality.HIGH,
            dialogVoiceId = "supertonic-3-int8#1",
        )
        engine.prepare(settings)
        engine.speak(list[0], next, RecordingListener())
        // Al modelo nunca se le pide mas de la velocidad segura (1,0): lo que falta hasta la
        // pedida (1,4375, o 1,4375 x ritmo adaptativo en el titulo) lo pone la compresion de tiempo.
        assertThat(synth.params).containsExactly(
            SynthesisParams(speed = 1f, sid = 4, lang = "es", numSteps = 12),
            SynthesisParams(speed = 1f, sid = 1, lang = "en", numSteps = 12),
            SynthesisParams(speed = 1f, sid = 4, lang = "es", numSteps = 12),
            SynthesisParams(speed = 1f, sid = 4, lang = "es", numSteps = 12),
        ).inOrder()
        // Idioma forzado y sin ritmo adaptativo; dialogo de otro modelo = narrador.
        synth.params.clear()
        engine.updateSettings(
            settings.copy(
                readingLanguage = ReadingLanguage.ENGLISH, adaptivePace = false, speed = 2f,
                dialogVoiceId = "otro-modelo#3", quality = VoiceQuality.NORMAL,
            ),
        )
        engine.speak(list[0], next, RecordingListener())
        assertThat(synth.params.map { it.lang }).containsExactly("en", "en", "en", "en")
        assertThat(synth.params.map { it.sid }).containsExactly(4, 4, 4, 4)
        assertThat(synth.params.map { it.speed }).containsExactly(1f, 1f, 1f, 1f)
        assertThat(synth.params.map { it.numSteps }).containsExactly(8, 8, 8, 8)
    }

    @Test
    fun cacheDeDiscoPregenerarYReutilizar() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val cache = AudioDiskCache(tmp.newFolder("cache"))
        val first = FakeSynth { 0 }
        val engine = NeuralTtsEngine(
            loader = { _, _ -> first }, sinkFactory = { FakeSink(testScheduler) },
            synthDispatcher = d, audioDispatcher = d, tickDispatcher = d, tickMs = 5, diskCache = cache,
        )
        val settings = VoiceSettings(engine = TtsEngineType.NEURAL, speed = SIN_ESTIRAR)
        assertThat(engine.pregenerate(book.all[0], settings)).isFalse() // Sin libro: no hay clave.
        engine.setBook("libro")
        assertThat(engine.canPregenerate).isTrue()
        assertThat(engine.pregenerate(book.all[0], settings)).isTrue()
        assertThat(engine.pregenerate(book.all[0], settings)).isFalse()
        assertThat(first.calls).hasSize(1)
        // Otro motor (memoria vacia) con la misma cache: la frase 1 sale del disco.
        val second = FakeSynth { 0 }
        val other = NeuralTtsEngine(
            loader = { _, _ -> second }, sinkFactory = { FakeSink(testScheduler) },
            synthDispatcher = d, audioDispatcher = d, tickDispatcher = d, tickMs = 5, diskCache = cache,
        )
        other.setBook("libro")
        other.speak(book.all[0], { null }, RecordingListener())
        assertThat(second.calls).isEmpty()
        // Con escritura directa, lo que se lee tambien se guarda.
        other.setWriteThrough(true)
        other.speak(book.all[1], { null }, RecordingListener())
        assertThat(second.calls).hasSize(1)
        assertThat(engine.pregenerate(book.all[1], settings)).isFalse()
    }

    /**
     * Toda frase se sintetiza a la velocidad segura y se comprime en el tiempo hasta la pedida
     * (longitud exacta = muestras / velocidad). Con velocidad pedida <= 1 el modelo va directo
     * y nada se estira.
     */
    @Test
    fun seSintetizaALaVelocidadSeguraYSeAceleraHastaLaPedida() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val synth = FakeSynth(shape = { t -> FloatArray(t.length * 100) { 0.5f } }) { 0 }
        val engine = NeuralTtsEngine(
            loader = { _, _ -> synth }, sinkFactory = { FakeSink(testScheduler) },
            synthDispatcher = d, audioDispatcher = d, tickDispatcher = d, tickMs = 5,
        )
        val corta = engine.render(utt(1, 0, 0, "Hola."), VoiceSettings())
        assertThat(synth.params.last().speed).isEqualTo(NeuralTtsEngine.SAFE_MODEL_SPEED)
        // Acelerada salvo el apagado final (sin comprimir): ~500/1,4375 + unos ms.
        assertThat(corta.pcm.size).isIn((500 / 1.4375f).roundToInt()..(500 / 1.4375f).roundToInt() + 60)
        val larga = engine.render(utt(2, 0, 0, LARGA), VoiceSettings())
        assertThat(synth.params.last().speed).isEqualTo(1f)
        val l = (LARGA.length * 100 / 1.4375f).roundToInt()
        assertThat(larga.pcm.size).isIn(l..l + 60)
        val lenta = engine.render(utt(3, 0, 0, "Hola."), VoiceSettings(speed = SIN_ESTIRAR))
        assertThat(synth.params.last().speed).isEqualTo(1f)
        assertThat(lenta.pcm.size).isEqualTo(500)
        assertThat(NeuralTtsEngine.modelSpeed(1.2f)).isEqualTo(1f)
        assertThat(NeuralTtsEngine.modelSpeed(0.9f)).isEqualTo(0.9f)
    }

    @Test
    fun renderDevuelveVozProcesadaYPausa() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val engine = NeuralTtsEngine(
            loader = { _, _ -> FakeSynth { 0 } }, sinkFactory = { FakeSink(testScheduler) },
            synthDispatcher = d, audioDispatcher = d, tickDispatcher = d, tickMs = 5,
        )
        val audio = engine.render(utt(1, 0, 0, "Hola.", pauseAfter = PauseKind.CHAPTER), VoiceSettings(speed = SIN_ESTIRAR))
        assertThat(audio.sampleRate).isEqualTo(1000)
        assertThat(audio.pcm.size).isEqualTo(50)
        assertThat(audio.silenceFrames).isEqualTo(1600)
        // Fundido: empieza y acaba en silencio.
        assertThat(audio.pcm.first()).isEqualTo(0f)
        assertThat(audio.pcm.last()).isEqualTo(0f)
    }
}
