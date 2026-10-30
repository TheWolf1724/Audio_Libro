package com.lectorvoz.tts.neural

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.ChapterText
import com.lectorvoz.core.model.PauseKind
import com.lectorvoz.core.model.TtsEngineType
import com.lectorvoz.core.model.Utterance
import com.lectorvoz.core.model.UtteranceKind
import com.lectorvoz.core.model.VoiceRole
import com.lectorvoz.core.model.VoiceSettings
import com.lectorvoz.core.text.EnglishNormalizer
import com.lectorvoz.core.text.SpanishNormalizer
import com.lectorvoz.core.text.UtteranceBuilder
import com.lectorvoz.tts.RecordingListener
import com.lectorvoz.tts.UtteranceProvider
import com.lectorvoz.tts.export.WavWriter
import com.lectorvoz.tts.voice.VoiceCatalog
import com.lectorvoz.tts.voice.VoiceLocation
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Carga real de Supertonic 3 con la libreria nativa de sherpa-onnx 2.0.1 para Linux x86_64
 * y las clases Kotlin del AAR de Android (mismo JNI). Se omite si faltan la libreria
 * (tools/descargar-dependencias.sh) o la voz (tools/descargar-voces.sh).
 * Las .so x86_64 del AAR no sirven aqui: enlazan con bionic (libc de Android).
 */
class SherpaJvmTest {
    private val libDir = File(System.getProperty("lectorvoz.sherpa.libdir").orEmpty())
    private val voces = File(System.getProperty("lectorvoz.voces.dir").orEmpty())

    private fun location(): VoiceLocation {
        assumeTrue("Falta la libreria nativa de Linux", File(libDir, "libsherpa-onnx-jni.so").isFile)
        val dir = File(voces, VoiceCatalog.SUPERTONIC_ID)
        assumeTrue("Falta la voz Supertonic", dir.isDirectory)
        return VoiceLocation(VoiceCatalog.find(VoiceCatalog.SUPERTONIC_ID)!!, null, dir.absolutePath)
    }

    @Test
    fun supertonicSintetizaEspanolEInglesConLocutorYPasos() {
        val synth = SherpaSynthesizer.load(location(), numThreads = 2)
        try {
            assertThat(synth.sampleRate).isEqualTo(44_100)
            assertThat(synth.numSpeakers).isEqualTo(10)
            val es = synth.synthesize("Buenas tardes, esto es una prueba.", SynthesisParams(sid = 4, lang = "es", numSteps = 4)) { false }!!
            assertThat(es.size / 44_100f).isGreaterThan(0.8f)
            assertThat(es.maxOf { abs(it) }).isGreaterThan(0.05f)
            // Idioma por generacion: "en" funciona sin recargar el modelo.
            val en = synth.synthesize("I will be back tomorrow morning.", SynthesisParams(sid = 4, lang = "en", numSteps = 4)) { false }!!
            assertThat(en.size / 44_100f).isGreaterThan(0.8f)
            assertThat(en.maxOf { abs(it) }).isGreaterThan(0.05f)
            // Mas velocidad = audio mas corto.
            val fast = synth.synthesize("Buenas tardes, esto es una prueba.", SynthesisParams(sid = 4, speed = 1.6f, numSteps = 4)) { false }!!
            assertThat(fast.size).isLessThan(es.size)
            // Cancelacion: el callback devuelve 0 y no hay audio.
            assertThat(synth.synthesize("Otra frase.", SynthesisParams(numSteps = 4)) { true }).isNull()
        } finally {
            synth.release()
        }
    }

    /** Tuberia completa con la voz real: hilos reales, salida que acepta al instante. */
    @Test
    fun motorNeuronalCompletoConSupertonic() = runBlocking {
        val loc = location()
        val sink = RecordingSink()
        val engine = NeuralTtsEngine(loader = { _, threads -> SherpaSynthesizer.load(loc, threads) }, sinkFactory = { sink })
        val book = ListProvider(
            listOf(
                com.lectorvoz.tts.utt(1, 0, 0, "Primera frase."),
                com.lectorvoz.tts.utt(2, 0, 15, "Segunda frase, algo mas larga."),
            ),
        )
        val listener = RecordingListener()
        val settings = VoiceSettings(engine = TtsEngineType.NEURAL)
        engine.prepare(settings)
        engine.speak(book.list.first(), book::next, listener)
        engine.release()
        val log = listener.snapshot()
        assertThat(log.filter { !it.startsWith("word") })
            .containsExactly("start:1", "done:1", "start:2", "done:2").inOrder()
        // Voz + 2 pausas de frase (320 ms) a 44,1 kHz: mas de 2 s en total.
        assertThat(sink.frames()).isGreaterThan(2L * 44_100)
        // Empalmes sin clics: la primera muestra escrita es casi silencio (fundido).
        assertThat(abs(sink.samples().first())).isLessThan(0.01f)
    }

    /**
     * Muestra de ~30 s con F5 y TODA la cadena (idioma por frase, pausas calibradas,
     * recorte, fundidos, normalizacion). Solo si se pide: -Plectorvoz.muestra=/ruta.wav
     */
    @Test
    fun muestraCadenaCompletaF5() = runBlocking {
        val out = System.getProperty("lectorvoz.muestra")
        assumeTrue("Muestra no pedida", !out.isNullOrBlank())
        val loc = location()
        // Las pruebas se ejecutan con el modulo (tts/) como directorio de trabajo.
        val text = File("src/test/resources/texto30.txt").readText().trim()
        val utterances = sampleUtterances(text)
        val sink = RecordingSink()
        val engine = NeuralTtsEngine(loader = { _, threads -> SherpaSynthesizer.load(loc, threads) }, sinkFactory = { sink })
        val settings = VoiceSettings(engine = TtsEngineType.NEURAL) // F5, 1,4375, 8 pasos, normalizar.
        val provider = ListProvider(utterances)
        val t0 = System.nanoTime()
        engine.prepare(settings)
        engine.speak(utterances.first(), provider::next, RecordingListener())
        engine.release()
        val secs = sink.frames() / 44_100.0
        println("Muestra: ${utterances.size} frases, %.1f s de audio en %.1f s".format(secs, (System.nanoTime() - t0) / 1e9))
        File(out).apply { parentFile?.mkdirs() }.outputStream().buffered().use { WavWriter.write(it, sink.samples(), 44_100) }
        assertThat(secs).isGreaterThan(15.0)
    }

    /** Frases del texto de muestra con tipo, pausa, idioma y papel, y una frase en ingles. */
    private fun sampleUtterances(text: String): List<Utterance> {
        val english = "He said: I will be back tomorrow morning."
        val anchor = "Llevaba tres semanas esperándola."
        val es = UtteranceBuilder(SpanishNormalizer()).build(ChapterText(0, text, null, "es"), 0, 1)
        val en = UtteranceBuilder(EnglishNormalizer()).build(ChapterText(0, english, null, "en"), 0, 1000)
        val out = ArrayList<Utterance>()
        var id = 1L
        for (u in es) {
            val dialog = u.displayText.trimStart().startsWith("—")
            out += when {
                u.displayText.startsWith("Capítulo") ->
                    u.copy(id = id++, kind = UtteranceKind.CHAPTER_ANNOUNCE, pauseAfter = PauseKind.SECTION)
                else -> u.copy(id = id++, role = if (dialog) VoiceRole.DIALOG else VoiceRole.NARRATOR)
            }
            if (u.displayText.trim() == anchor) {
                // Fin de parrafo y una frase en ingles intercalada.
                out[out.size - 1] = out.last().copy(pauseAfter = PauseKind.PARAGRAPH)
                en.forEach { e -> out += e.copy(id = id++, language = "en", pauseAfter = PauseKind.PARAGRAPH) }
            }
        }
        return out
    }

    /** Proveedor sobre una lista fija. */
    private class ListProvider(val list: List<Utterance>) : UtteranceProvider {
        override val chapterCount = 1
        override suspend fun at(locator: com.lectorvoz.core.model.Locator) = list.firstOrNull()
        override suspend fun next(current: Utterance) = list.getOrNull(list.indexOfFirst { it.id == current.id } + 1)
        override suspend fun previous(current: Utterance) = list.getOrNull(list.indexOfFirst { it.id == current.id } - 1)
    }

    /** Salida que acepta al instante y guarda todo lo escrito. */
    private class RecordingSink : PcmSink {
        private var buf = FloatArray(44_100 * 10)
        private val n = AtomicLong()
        override fun open(sampleRate: Int) {}

        @Synchronized
        override fun write(data: FloatArray, offset: Int, length: Int): Int {
            val size = n.get().toInt()
            if (size + length > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + length))
            System.arraycopy(data, offset, buf, size, length)
            n.addAndGet(length.toLong())
            return length
        }

        override fun playedFrames(): Long = n.get()
        override fun setVolume(volume: Float) {}
        override fun setPitch(pitch: Float) = true
        override fun release() {}
        fun frames() = n.get()

        @Synchronized
        fun samples(): FloatArray = buf.copyOf(n.get().toInt())
    }
}
