package com.lectorvoz.tts.neural

import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.lectorvoz.tts.engine.SpeechEngineException
import com.lectorvoz.tts.voice.ModelFiles
import com.lectorvoz.tts.voice.VoiceCatalog
import com.lectorvoz.tts.voice.VoiceInfo
import com.lectorvoz.tts.voice.VoiceLocation
import com.lectorvoz.tts.voice.VoiceRef
import com.lectorvoz.tts.voice.VoiceStore
import java.io.File

/**
 * [NeuralSynthesizer] sobre sherpa-onnx 2.0.1 (`OfflineTts`). API verificada con `javap`
 * sobre el `classes.jar` del AAR y `kotlin-api/Tts.kt` del tag v2.0.1: locutor, velocidad,
 * pasos (`numSteps`) e idioma (`extra["lang"]`) van en [GenerationConfig] por generacion.
 */
class SherpaSynthesizer private constructor(
    private val tts: OfflineTts,
    val voice: VoiceInfo,
) : NeuralSynthesizer {

    override val sampleRate: Int = tts.sampleRate()

    /** Numero de locutores que declara el modelo. */
    val numSpeakers: Int = tts.numSpeakers()

    override fun synthesize(text: String, params: SynthesisParams, isCancelled: () -> Boolean): FloatArray? {
        val lang = params.lang?.takeIf { it in voice.languages } ?: voice.languages.first()
        val config = GenerationConfig(
            speed = params.speed,
            sid = params.sid.coerceIn(0, (numSpeakers - 1).coerceAtLeast(0)),
            numSteps = params.numSteps.coerceIn(1, 64),
            extra = mapOf("lang" to lang),
        )
        val callback = CancelCallback(isCancelled)
        val audio = tts.generateWithConfigAndCallback(text, config, callback)
        // Solo se descarta si se aborto a mitad. Si la cancelacion llega con la frase ya hecha,
        // el audio se devuelve (va a la cache): al reanudar o volver a esa frase no se repite el
        // trabajo (antes cada pausa/salto tiraba la frase en curso y luego la sintetizaba otra vez).
        if (callback.cancelled) return null
        return audio.samples
    }

    override fun release() = tts.release()

    /**
     * Callback de sintesis: recibe cada bloque generado; devolver 0 aborta.
     *
     * Debe ser una clase explicita, no una lambda: el JNI de sherpa busca el metodo
     * `invoke([F)Ljava/lang/Integer;` y las lambdas de Kotlin 2.x (invokedynamic, y su
     * desazucarado en D8) solo tienen `invoke(Object)Object` -> NoSuchMethodError
     * (comprobado en SherpaJvmTest con la libreria nativa real).
     */
    internal class CancelCallback(private val isCancelled: () -> Boolean) : (FloatArray) -> Int {
        @Volatile var cancelled = false

        override fun invoke(samples: FloatArray): Int {
            if (isCancelled()) {
                cancelled = true
                return 0
            }
            return 1
        }
    }

    companion object {
        /**
         * Opciones de ONNX Runtime: hilos SIN espera activa. Por defecto los hilos de ORT
         * "giran" (busy-wait) entre operaciones para arrancar antes: en un movil es CPU, calor
         * y bateria tirados. Medido (29-10-2026, mismo texto): 0,70 -> 0,59 s de CPU por segundo
         * de audio (-15 %) con la misma velocidad y el mismo audio. sherpa lo lee de un fichero
         * ("cpu:<ruta>"; las claves "SessionConfig." pasan a AddConfigEntry).
         */
        private const val ORT_OPTIONS =
            "SessionConfig.session.intra_op.allow_spinning=0\n" +
                "SessionConfig.session.inter_op.allow_spinning=0\n"

        /**
         * Proveedor de sherpa con [ORT_OPTIONS] (fichero en el directorio temporal: en Android
         * la cache de la app). Si no se puede escribir, "cpu" tal cual (funciona igual, gasta mas).
         */
        fun cpuProvider(dir: File = File(System.getProperty("java.io.tmpdir") ?: ".")): String = try {
            val f = File(dir, "lectorvoz-ort.cfg")
            if (!f.isFile || f.readText() != ORT_OPTIONS) f.writeText(ORT_OPTIONS)
            "cpu:" + f.absolutePath
        } catch (e: Exception) {
            "cpu"
        }

        /** Construye la configuracion de sherpa para [location]. */
        fun config(location: VoiceLocation, numThreads: Int, provider: String = "cpu"): OfflineTtsConfig {
            val model = when (val f = location.voice.files) {
                is ModelFiles.Supertonic -> OfflineTtsModelConfig(
                    supertonic = OfflineTtsSupertonicModelConfig(
                        durationPredictor = location.path(f.durationPredictor),
                        textEncoder = location.path(f.textEncoder),
                        vectorEstimator = location.path(f.vectorEstimator),
                        vocoder = location.path(f.vocoder),
                        ttsJson = location.path(f.ttsJson),
                        unicodeIndexer = location.path(f.unicodeIndexer),
                        voiceStyle = location.path(f.voiceStyle),
                    ),
                    numThreads = numThreads,
                    provider = provider,
                )
            }
            // maxNumSentences = 1: la app ya entrega frase a frase.
            return OfflineTtsConfig(model = model, maxNumSentences = 1)
        }

        /** Carga la voz (bloqueante). */
        fun load(location: VoiceLocation, numThreads: Int): SherpaSynthesizer {
            val tts = try {
                OfflineTts(assetManager = location.assetManager, config = config(location, numThreads, cpuProvider()))
            } catch (e: Throwable) {
                throw SpeechEngineException("No se pudo cargar la voz ${location.voice.displayName}", e)
            }
            return SherpaSynthesizer(tts, location.voice)
        }
    }
}

/** Cargador real: catalogo + [VoiceStore] + sherpa. */
class SherpaSynthesizerLoader(private val store: VoiceStore) : NeuralSynthesizerLoader {
    override fun load(ref: VoiceRef, numThreads: Int): NeuralSynthesizer {
        val voice = VoiceCatalog.find(ref.voiceId)
            ?: throw SpeechEngineException("Voz desconocida: ${ref.voiceId}")
        val location = try {
            store.locate(voice)
        } catch (e: Exception) {
            throw SpeechEngineException("Voz no instalada: ${voice.displayName}", e)
        }
        return SherpaSynthesizer.load(location, numThreads)
    }
}
