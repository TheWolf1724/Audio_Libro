package com.lectorvoz.tts.neural

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.ChapterText
import com.lectorvoz.core.model.TtsEngineType
import com.lectorvoz.core.model.VoiceSettings
import com.lectorvoz.core.text.SpanishNormalizer
import com.lectorvoz.core.text.UtteranceBuilder
import com.lectorvoz.tts.audio.TruncationDetector
import com.lectorvoz.tts.engine.SpeechLanguage
import com.lectorvoz.tts.voice.VoiceCatalog
import com.lectorvoz.tts.voice.VoiceLocation
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Regresion con el modelo real (se omite sin la libreria nativa o la voz, como SherpaJvmTest):
 * Supertonic se come la ultima silaba cuando se le pide mas de ~1,1 de velocidad (29-10-2026,
 * medido en un corpus de 109 frases: 40 % de cortes a 1,4375). Con la velocidad segura mas la
 * compresion de tiempo del motor, ninguna frase acaba cortada y el ritmo es el pedido.
 */
class SupertonicSinCortesTest {
    private val libDir = File(System.getProperty("lectorvoz.sherpa.libdir").orEmpty())
    private val voces = File(System.getProperty("lectorvoz.voces.dir").orEmpty())

    private fun corpus(): List<com.lectorvoz.core.model.Utterance> {
        val banco = File("../core/text/src/test/resources/banco").listFiles()!!
            .filter { it.extension == "txt" && it.name != "ingles.txt" && it.name != "urls.txt" }
        val fuentes = banco.flatMap { f -> f.readLines().filter { it.startsWith("> ") }.map { it.removePrefix("> ") } }
        val extra = "La casa estaba en silencio. Nadie respondió a su llamada. ¿Dónde estabas anoche? " +
            "No me lo puedo creer. Capítulo tres. Ella cerró los ojos y respiró. Sí. No. Ven. Vale. Adiós. " +
            "¿Por qué? Claro que sí. Hasta luego. Buenos días. Gracias por todo. No fue nada. Ya lo sé."
        val text = (fuentes + File("src/test/resources/texto30.txt").readText().trim() + extra).joinToString("\n\n")
        return UtteranceBuilder(SpanishNormalizer()).build(ChapterText(0, text, null, "es"), 0, 1)
            .filter { it.spokenText.isNotBlank() && SpeechLanguage.of(it, VoiceSettings()) == "es" }
    }

    @Test
    fun ningunaFraseAcabaCortadaYElRitmoEsElPedido() = runBlocking {
        assumeTrue("Falta la libreria nativa de Linux", File(libDir, "libsherpa-onnx-jni.so").isFile)
        val dir = File(voces, VoiceCatalog.SUPERTONIC_ID)
        assumeTrue("Falta la voz Supertonic", dir.isDirectory)
        val loc = VoiceLocation(VoiceCatalog.find(VoiceCatalog.SUPERTONIC_ID)!!, null, dir.absolutePath)
        val engine = NeuralTtsEngine(loader = { _, threads -> SherpaSynthesizer.load(loc, threads) })
        val utts = corpus()
        try {
            val rapida = VoiceSettings(engine = TtsEngineType.NEURAL) // x1 = 1,4375 para el modelo
            val cortadas = ArrayList<String>()
            var frames = 0L
            for (u in utts) {
                val audio = engine.render(u, rapida)
                frames += audio.pcm.size
                if (TruncationDetector.isTruncated(audio.pcm, audio.sampleRate)) {
                    cortadas += "%s (de fuerte a silencio en %d ms)".format(u.spokenText, TruncationDetector.finalFallMs(audio.pcm, audio.sampleRate))
                }
            }
            assertThat(cortadas).isEmpty()
            // Ritmo: la voz (descontados los margenes fijos de recorte, 30 + 100 ms por frase, que
            // no se escalan) dura 1,4375 veces mas sin acelerar (velocidad total 1,0) que acelerada.
            // El mismo audio crudo sirve a ambas (misma velocidad de modelo: cache en memoria).
            // Frases largas: la caida final que se deja sin comprimir (decenas de ms) no pesa.
            val lenta = rapida.copy(speed = 1f / VoiceCatalog.SUPERTONIC_BASE_SPEED)
            val muestra = utts.filter { it.spokenText.length >= 60 }.take(10)
            var vozLenta = 0.0
            var vozRapida = 0.0
            for (u in muestra) {
                val l = engine.render(u, lenta)
                val r = engine.render(u, rapida)
                val margen = (30 + 100) * l.sampleRate / 1000.0
                vozLenta += l.pcm.size - margen
                vozRapida += r.pcm.size - margen
            }
            assertThat(vozLenta / vozRapida).isWithin(0.05).of(VoiceCatalog.SUPERTONIC_BASE_SPEED.toDouble())
            println("Sin cortes: ${utts.size} frases, %.0f s de audio".format(frames / 44_100.0))
        } finally {
            engine.release()
        }
    }
}
