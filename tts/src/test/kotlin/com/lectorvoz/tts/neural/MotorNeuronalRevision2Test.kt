package com.lectorvoz.tts.neural

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.TtsEngineType
import com.lectorvoz.core.model.VoiceSettings
import com.lectorvoz.tts.FakeUtteranceProvider
import com.lectorvoz.tts.RecordingListener
import com.lectorvoz.tts.cache.AudioDiskCache
import com.lectorvoz.tts.utt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Regresiones de la revision 2 en el motor neuronal: A1 (liberacion por inactividad con
 * usos activos), M2 (cache llena), M4 (prioridad de la lectura) y M6 (hilos).
 * Las dos primeras vienen de las reproducciones del revisor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MotorNeuronalRevision2Test {
    @get:Rule val tmp = TemporaryFolder()

    private class Synth : NeuralSynthesizer {
        override val sampleRate = 1000
        val calls: MutableList<String> = Collections.synchronizedList(ArrayList())
        override fun synthesize(text: String, params: SynthesisParams, isCancelled: () -> Boolean): FloatArray {
            calls += text
            return FloatArray(text.length * 10) { 0.5f }
        }
        override fun release() {}
    }

    /** Salida que reproduce en tiempo virtual con un bufer de [cap] muestras. */
    private class Sink(private val s: TestCoroutineScheduler, private val cap: Long = 30) : PcmSink {
        var opened = 0L
        var rate = 0
        var written = 0L
        override fun open(sampleRate: Int) { rate = sampleRate; opened = s.currentTime }
        override fun write(data: FloatArray, offset: Int, length: Int): Int {
            val free = (cap - (written - playedFrames())).coerceAtLeast(0)
            val n = minOf(free, length.toLong()).toInt()
            written += n
            return n
        }
        override fun playedFrames() = minOf(written, (s.currentTime - opened) * rate / 1000)
        override fun setVolume(volume: Float) {}
        override fun setPitch(pitch: Float) = true
        override fun release() {}
    }

    private val settings = VoiceSettings(engine = TtsEngineType.NEURAL, punctuationPause = 0f)
    private val longBook = FakeUtteranceProvider(listOf(List(30) { "x".repeat(200) + " $it." }))

    /** A1: exportar mientras se lee ya no programa la liberacion del modelo en mitad de la lectura. */
    @Test
    fun exportarDuranteLaLecturaNoLiberaElModelo() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val engine = NeuralTtsEngine(
            loader = { _, _ -> Synth() }, sinkFactory = { Sink(testScheduler) },
            synthDispatcher = d, audioDispatcher = d, tickDispatcher = d, tickMs = 5, idleReleaseMs = 1_000,
        )
        engine.prepare(settings)
        val reading = async { runCatching { engine.speak(longBook.all.first(), longBook::next, RecordingListener()) } }
        advanceTimeBy(500); runCurrent()
        engine.render(longBook.all[10], settings) // exportar un capitulo mientras se lee
        advanceTimeBy(5_000); runCurrent()
        assertThat(engine.isModelLoaded).isTrue()
        val r = reading.await()
        assertThat(r.exceptionOrNull()).isNull()
        // Sin usos: ahora si se libera.
        advanceTimeBy(2_000); runCurrent()
        assertThat(engine.isModelLoaded).isFalse()
    }

    /** A1: preparar audio en disco mientras se lee tampoco corta la lectura. */
    @Test
    fun prepararDuranteLaLecturaNoLiberaElModelo() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val engine = NeuralTtsEngine(
            loader = { _, _ -> Synth() }, sinkFactory = { Sink(testScheduler) },
            synthDispatcher = d, audioDispatcher = d, tickDispatcher = d, tickMs = 5,
            idleReleaseMs = 1_000, diskCache = AudioDiskCache(tmp.newFolder()),
        )
        engine.setBook("libro")
        engine.prepare(settings)
        val reading = async { runCatching { engine.speak(longBook.all.first(), longBook::next, RecordingListener()) } }
        advanceTimeBy(500); runCurrent()
        assertThat(engine.pregenerate(longBook.all[20], settings)).isTrue()
        advanceTimeBy(5_000); runCurrent()
        assertThat(reading.await().exceptionOrNull()).isNull()
    }

    /** A1: exportar con otra voz mientras se lee espera a que acabe la lectura (no cambia el modelo debajo). */
    @Test
    fun exportarConOtraVozEsperaALaLectura() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val loads = ArrayList<String>()
        val engine = NeuralTtsEngine(
            loader = { ref, _ -> loads += ref.voiceId; Synth() }, sinkFactory = { Sink(testScheduler) },
            synthDispatcher = d, audioDispatcher = d, tickDispatcher = d, tickMs = 5,
        )
        engine.prepare(settings)
        val book = FakeUtteranceProvider(listOf(List(6) { "x".repeat(100) + " $it." }))
        val reading = async { runCatching { engine.speak(book.all.first(), book::next, RecordingListener()) } }
        advanceTimeBy(500); runCurrent()
        val export = async { engine.render(utt(99, 0, 0, "Otra voz."), settings.copy(voiceId = "otro-modelo#0")) }
        assertThat(reading.await().exceptionOrNull()).isNull()
        assertThat(export.await().pcm).isNotEmpty()
        assertThat(loads).containsExactly("supertonic-3-int8", "otro-modelo").inOrder()
    }

    /** M2: con la cache casi llena de otro libro se desaloja (LRU) y se sigue preparando. */
    @Test
    fun cacheCasiLlenaDesalojaOtrosLibrosParaPreparar() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val cache = AudioDiskCache(tmp.newFolder(), maxBytes = 10_000)
        val p = SynthesisParams(1f, 4, "es", 8)
        cache.put("otro", 0, "v", p, "Viejo.", FloatArray(4_700), 1000) // 9 424 bytes: > 90 %
        val engine = NeuralTtsEngine(
            loader = { _, _ -> Synth() }, sinkFactory = { Sink(testScheduler) },
            synthDispatcher = d, audioDispatcher = d, tickDispatcher = d, tickMs = 5, diskCache = cache,
        )
        engine.setBook("nuevo")
        assertThat(engine.canPregenerate).isTrue()
        val u = utt(1, 0, 0, "Uno dos.")
        assertThat(engine.pregenerate(u, settings)).isTrue()
        assertThat(cache.contains("otro", 0, "v", p, "Viejo.")).isFalse()
        assertThat(cache.sizeBytes()).isLessThan(9_000L)
        // Si es el propio libro el que lo llena, se deja de preparar (no se borra lo suyo).
        cache.put("nuevo", 5, "v", p, "Grande.", FloatArray(4_600), 1000)
        assertThat(engine.pregenerate(utt(2, 0, 9, "Tres cuatro."), settings)).isFalse()
        assertThat(engine.canPregenerate).isFalse()
        engine.setBook("otro-libro")
        assertThat(engine.canPregenerate).isTrue()
    }

    /** M4: exportar a la vez que empieza la lectura no adelanta a las frases de la lectura. */
    @Test
    fun laLecturaVaAntesQueExportar() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val synth = Synth()
        val engine = NeuralTtsEngine(
            loader = { _, _ -> synth }, sinkFactory = { Sink(testScheduler) },
            synthDispatcher = d, audioDispatcher = d, tickDispatcher = d, tickMs = 5, lookahead = 3,
        )
        engine.prepare(settings)
        val book = FakeUtteranceProvider(listOf(List(8) { "Frase numero $it." }))
        val reading = async { engine.speak(book.all.first(), book::next, RecordingListener()) }
        val export = async { engine.render(utt(99, 5, 0, "Exportar esto."), settings) }
        reading.await()
        export.await()
        // La lectura llena su margen antes de que el fondo use el hilo.
        assertThat(synth.calls.indexOf("Exportar esto.")).isAtLeast(3)
    }

    /** M4: un trabajo de fondo largo abandona la frase en cuanto la lectura necesita el hilo. */
    @Test
    fun exportarCedeElHiloALaLecturaAMitadDeFrase() = runBlocking {
        val backgroundStarted = CountDownLatch(1)
        val preempted = AtomicBoolean(false)
        val synth = object : NeuralSynthesizer {
            override val sampleRate = 1000
            override fun synthesize(text: String, params: SynthesisParams, isCancelled: () -> Boolean): FloatArray? {
                if (!text.startsWith("Fondo")) return FloatArray(text.length * 10) { 0.5f }
                backgroundStarted.countDown()
                val end = System.nanoTime() + 2_000_000_000L
                while (System.nanoTime() < end) {
                    if (isCancelled()) {
                        preempted.set(true)
                        return null
                    }
                    Thread.sleep(2)
                }
                return FloatArray(10) { 0.5f }
            }
            override fun release() {}
        }
        val instant = object : PcmSink {
            var written = 0L
            override fun open(sampleRate: Int) {}
            override fun write(data: FloatArray, offset: Int, length: Int): Int { written += length; return length }
            override fun playedFrames() = written
            override fun setVolume(volume: Float) {}
            override fun setPitch(pitch: Float) = true
            override fun release() {}
        }
        val engine = NeuralTtsEngine(
            loader = { _, _ -> synth }, sinkFactory = { instant },
            synthDispatcher = NeuralTtsEngine.singleThreadDispatcher("prueba-sintesis", android.os.Process.THREAD_PRIORITY_DEFAULT),
            audioDispatcher = NeuralTtsEngine.singleThreadDispatcher("prueba-audio", android.os.Process.THREAD_PRIORITY_AUDIO),
            tickDispatcher = Dispatchers.Default, tickMs = 1,
        )
        engine.prepare(settings)
        val export = async(Dispatchers.Default) { engine.render(utt(99, 0, 0, "Fondo largo."), settings) }
        assertThat(backgroundStarted.await(5, TimeUnit.SECONDS)).isTrue()
        val book = FakeUtteranceProvider(listOf(listOf("Hola.", "Adios.")))
        val t0 = System.nanoTime()
        withTimeout(10_000) { engine.speak(book.all.first(), book::next, RecordingListener()) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertThat(preempted.get()).isTrue()
        assertThat(ms).isLessThan(1_000L) // antes: esperaba a que acabase la frase de fondo (2 s)
        // La exportacion se reintenta y termina.
        assertThat(withTimeout(10_000) { export.await() }.pcm).isNotEmpty()
    }

    /** M6: los motores con los hilos por defecto no crean hilos nuevos cada vez. */
    @Test
    fun motoresSucesivosNoAcumulanHilos() = runBlocking {
        repeat(5) {
            val engine = NeuralTtsEngine(loader = { _, _ -> Synth() }, tickDispatcher = Dispatchers.Default)
            engine.render(utt(1, 0, 0, "Hola."), settings)
            engine.release()
        }
        val threads = Thread.getAllStackTraces().keys.count { it.name == "tts-sintesis" && it.isAlive }
        assertThat(threads).isAtMost(1)
    }
}
