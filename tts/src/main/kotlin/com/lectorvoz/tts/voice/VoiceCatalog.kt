package com.lectorvoz.tts.voice

/** Genero de una voz o locutor. */
enum class VoiceGender { FEMALE, MALE, UNKNOWN }

/** Familia de modelo de sherpa-onnx. Solo Supertonic (Piper descartado por el usuario). */
enum class NeuralModelKind { SUPERTONIC }

/**
 * Locutor de un modelo multilocutor: [sid] de sherpa, [code] del modelo ("F5") y
 * [name] visible ("Mujer 5").
 */
data class VoiceSpeaker(val sid: Int, val code: String, val name: String, val gender: VoiceGender)

/** Ficheros del modelo, relativos a la carpeta de la voz. */
sealed interface ModelFiles {
    /** Supertonic 3. */
    data class Supertonic(
        val durationPredictor: String = "duration_predictor.int8.onnx",
        val textEncoder: String = "text_encoder.int8.onnx",
        val vectorEstimator: String = "vector_estimator.int8.onnx",
        val vocoder: String = "vocoder.int8.onnx",
        val ttsJson: String = "tts.json",
        val unicodeIndexer: String = "unicode_indexer.bin",
        val voiceStyle: String = "voice.bin",
    ) : ModelFiles

    /** Lista de todos los ficheros. */
    fun all(): List<String> = when (this) {
        is Supertonic -> listOf(
            durationPredictor, textEncoder, vectorEstimator, vocoder, ttsJson, unicodeIndexer,
            voiceStyle,
        )
    }
}

/** De donde sale la voz. */
sealed interface VoiceSource {
    /** Incluida en el APK en `assets/voces/<id>/` (tools/descargar-voces.sh). */
    data object Bundled : VoiceSource

    /**
     * Descargable desde la release oficial: [url] a un `.tar.bz2` con SHA-256 [sha256];
     * [archiveRoot] es la carpeta raiz dentro del tar.
     */
    data class Download(val url: String, val sha256: String, val archiveRoot: String) : VoiceSource
}

/** Voz neuronal del catalogo. */
data class VoiceInfo(
    val id: String,
    val displayName: String,
    /** Idioma BCP-47 principal. */
    val language: String,
    val gender: VoiceGender,
    /** Bytes a descargar (comprimido) o 0 si va incluida. */
    val downloadBytes: Long,
    /** Bytes en disco una vez instalada (aprox.). */
    val installedBytes: Long,
    val license: String,
    val kind: NeuralModelKind,
    /** Locutores en el orden en que se muestran (el primero es el de por defecto). */
    val speakers: List<VoiceSpeaker>,
    val sampleRate: Int,
    val source: VoiceSource,
    val files: ModelFiles,
    /** Idiomas que el modelo acepta por frase (`extra["lang"]`); el primero es el de reserva. */
    val languages: List<String> = listOf("es"),
    /** Velocidad del modelo que corresponde a la velocidad 1,0 del usuario. */
    val baseSpeed: Float = 1f,
) {
    /** Locutor por defecto (el primero de la lista). */
    val defaultSid: Int get() = speakers.firstOrNull()?.sid ?: 0

    /** Locutor [sid] o nulo. */
    fun speaker(sid: Int): VoiceSpeaker? = speakers.firstOrNull { it.sid == sid }
}

/**
 * Referencia a voz + locutor tal como se guarda en `VoiceSettings.voiceId`:
 * `"<id>"` (locutor por defecto de la voz) o `"<id>#<sid>"`.
 */
data class VoiceRef(val voiceId: String, val sid: Int) {
    /** Forma serializada para `VoiceSettings.voiceId` (siempre con locutor). */
    fun encode(): String = "$voiceId#$sid"

    companion object {
        /** Interpreta [value]; nulo o vacio = voz y locutor por defecto del catalogo. */
        fun parse(value: String?): VoiceRef {
            if (value.isNullOrBlank()) return VoiceRef(VoiceCatalog.DEFAULT_VOICE_ID, VoiceCatalog.DEFAULT_SID)
            val i = value.lastIndexOf('#')
            if (i < 0) return VoiceRef(value, VoiceCatalog.find(value)?.defaultSid ?: 0)
            val id = value.substring(0, i)
            val sid = value.substring(i + 1).toIntOrNull()?.coerceAtLeast(0)
                ?: VoiceCatalog.find(id)?.defaultSid ?: 0
            return VoiceRef(id, sid)
        }
    }
}

/**
 * Catalogo fijo de voces neuronales (sin catalogo remoto). Decision del usuario
 * (28-10-2026): solo Supertonic 3; por defecto la locutora F5 a velocidad 1,15; F2 tambien
 * le gusta; M1 entona mal; Piper y M5 "suenan muy robot" (Piper eliminado).
 *
 * [downloadable] queda vacio, pero la estructura ([VoiceSource.Download], [VoiceDownloader])
 * se mantiene para futuras voces descargables.
 */
object VoiceCatalog {
    /** Supertonic 3 int8 (incluida en el APK). */
    const val SUPERTONIC_ID = "supertonic-3-int8"

    /** Voz por defecto. */
    const val DEFAULT_VOICE_ID = SUPERTONIC_ID

    /** Locutor por defecto: F5 ("Mujer 5"). */
    const val DEFAULT_SID = 4

    /**
     * Velocidad del modelo para la velocidad 1,0 del usuario (Supertonic habla despacio).
     * Decision del usuario (29-10-2026): el "x1" por defecto debe sonar como lo que antes era
     * el x1,25 del deslizador, es decir 1,15 (base antigua) x 1,25 = 1,4375. Asi la lectura por
     * defecto va mas ligera sin que el usuario tenga que tocar el deslizador.
     *
     * OJO: por encima de ~1,1 Supertonic se come la ultima silaba de las frases cortas (descarta
     * lo que no le cabe en el final que planifico). No se baja la base por eso: el motor pide al
     * modelo una velocidad segura en esas frases y las acelera despues sin cambiar el tono
     * (`NeuralTtsEngine.modelSpeed` + `TimeStretch`).
     */
    const val SUPERTONIC_BASE_SPEED = 1.4375f

    private fun f(n: Int) = VoiceSpeaker(n - 1, "F$n", "Mujer $n", VoiceGender.FEMALE)
    private fun m(n: Int) = VoiceSpeaker(n + 4, "M$n", "Hombre $n", VoiceGender.MALE)

    /** Orden de locutores: F5 (defecto), F2, el resto; M1 (entona mal) y M5 (robotica) al final. */
    private val supertonicSpeakers = listOf(f(5), f(2), f(1), f(3), f(4), m(2), m(3), m(4), m(1), m(5))

    val all: List<VoiceInfo> = listOf(
        VoiceInfo(
            id = SUPERTONIC_ID,
            displayName = "Supertonic 3 (alta calidad)",
            language = "es",
            gender = VoiceGender.UNKNOWN,
            downloadBytes = 0,
            installedBytes = 145_295_768,
            license = "OpenRAIL-M (pesos); MIT (codigo)",
            kind = NeuralModelKind.SUPERTONIC,
            speakers = supertonicSpeakers,
            sampleRate = 44_100,
            source = VoiceSource.Bundled,
            files = ModelFiles.Supertonic(),
            languages = listOf("es", "en"),
            baseSpeed = SUPERTONIC_BASE_SPEED,
        ),
    )

    /** Voz por [id] o nulo. */
    fun find(id: String): VoiceInfo? = all.firstOrNull { it.id == id }

    /** Voces incluidas en el APK. */
    val bundled: List<VoiceInfo> get() = all.filter { it.source == VoiceSource.Bundled }

    /** Voces descargables (vacio por ahora). */
    val downloadable: List<VoiceInfo> get() = all.filter { it.source is VoiceSource.Download }
}
