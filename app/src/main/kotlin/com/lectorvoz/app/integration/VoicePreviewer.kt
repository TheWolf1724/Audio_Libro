package com.lectorvoz.app.integration

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.lectorvoz.core.model.ChapterText
import com.lectorvoz.core.model.TtsEngineType
import com.lectorvoz.core.model.Utterance
import com.lectorvoz.core.model.VoiceSettings
import com.lectorvoz.core.text.TextOptions
import com.lectorvoz.core.text.UtteranceBuilder
import com.lectorvoz.tts.engine.SpeechListener
import com.lectorvoz.tts.engine.SystemTtsEngine
import com.lectorvoz.tts.engine.UtteranceRenderer
import com.lectorvoz.tts.neural.AudioTrackSink
import com.lectorvoz.tts.neural.PcmSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Foco de audio de la prueba de voz (abstraido para pruebas). */
interface PreviewFocus {
    /** Pide foco transitorio; false si se deniega. */
    fun request(): Boolean

    fun abandon()
}

/**
 * Foco transitorio (`AUDIOFOCUS_GAIN_TRANSIENT`): el servicio de lectura lo recibe como
 * perdida temporal, pausa el libro y lo reanuda solo, en la misma frase, al soltarlo.
 */
class AndroidPreviewFocus(context: Context) : PreviewFocus {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private var current: AudioFocusRequest? = null

    override fun request(): Boolean {
        abandon()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setOnAudioFocusChangeListener { }
            .build()
        val ok = audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (ok) current = req
        return ok
    }

    override fun abandon() {
        current?.let { audio.abandonAudioFocusRequest(it) }
        current = null
    }
}

/**
 * "Probar voz" sin tocar el libro que se esta escuchando.
 *
 * Decision (revision 3, A4): la prueba no pasa por el reproductor del servicio (abrir un
 * libro de muestra sustituia y perdia el libro en curso). Con la voz neuronal se sintetiza
 * con el motor del propio servicio ([UtteranceRenderer], el mismo modelo ya cargado: no se
 * duplican ~300 MB de RAM) y se reproduce en un [PcmSink] propio; con la voz del sistema se
 * usa un `TextToSpeech` efimero. En ambos casos se pide foco de audio transitorio, asi que
 * el libro que sonaba se pausa y se reanuda solo, en la misma frase, al terminar la prueba.
 * Es mas robusto que guardar y restaurar el libro y su posicion (no hay ventana en la que
 * el libro este cerrado ni carreras con el usuario pulsando reproducir durante la prueba).
 */
class VoicePreviewer(
    private val renderer: suspend () -> UtteranceRenderer?,
    private val focus: PreviewFocus,
    private val systemSpeaker: suspend (VoiceSettings, List<Utterance>) -> Unit,
    private val sinkFactory: () -> PcmSink = { AudioTrackSink() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pollMs: Long = 20L,
) {
    private val mutex = Mutex()

    /** Lee [text] con [settings]; devuelve un mensaje de error o nulo. Cancelable. */
    suspend fun preview(settings: VoiceSettings, text: String): String? = mutex.withLock {
        val list = UtteranceBuilder().build(
            ChapterText(0, text, null, "es"),
            options = TextOptions.from(settings).copy(announceChapters = false),
        ).filter { it.spokenText.isNotBlank() }
        if (list.isEmpty()) return@withLock null
        if (!focus.request()) return@withLock "No se pudo obtener el audio (otra app lo esta usando)"
        try {
            if (settings.engine == TtsEngineType.SYSTEM) {
                systemSpeaker(settings, list)
            } else {
                val r = renderer() ?: return@withLock "El servicio de voz no esta disponible"
                playRendered(r, settings, list)
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "No se pudo probar la voz"
        } finally {
            focus.abandon()
        }
    }

    private suspend fun playRendered(r: UtteranceRenderer, settings: VoiceSettings, list: List<Utterance>) {
        val sink = sinkFactory()
        var opened = false
        var written = 0L
        var rate = 0
        try {
            for (u in list) {
                coroutineContext.ensureActive()
                val audio = r.render(u, settings)
                if (!opened) {
                    rate = audio.sampleRate
                    sink.open(rate)
                    opened = true
                }
                written += withContext(io) {
                    writeAll(sink, audio.pcm) + writeAll(sink, FloatArray(audio.silenceFrames))
                }
            }
            if (!opened) return
            // Esperar a que suene lo escrito (con margen por si la salida se atasca).
            val limit = written * 1000L / rate.coerceAtLeast(1) + 2_000L
            var waited = 0L
            while (sink.playedFrames() < written && waited < limit) {
                delay(pollMs)
                waited += pollMs
            }
        } finally {
            sink.release()
        }
    }

    private suspend fun writeAll(sink: PcmSink, data: FloatArray): Long {
        var off = 0
        while (off < data.size) {
            coroutineContext.ensureActive()
            val n = sink.write(data, off, minOf(CHUNK, data.size - off))
            if (n < 0) error("Error de audio")
            if (n == 0) delay(pollMs)
            off += n
        }
        return data.size.toLong()
    }

    companion object {
        private const val CHUNK = 4096

        /** Lector de frases con un `TextToSpeech` efimero (se libera al terminar). */
        fun systemSpeaker(context: Context): suspend (VoiceSettings, List<Utterance>) -> Unit = { settings, list ->
            val engine = SystemTtsEngine(context)
            try {
                engine.prepare(settings)
                engine.speak(list.first(), { cur -> list.getOrNull(list.indexOf(cur) + 1) }, NoListener)
            } finally {
                engine.release()
            }
        }
    }

    private object NoListener : SpeechListener {
        override fun onUtteranceStart(utterance: Utterance) = Unit
        override fun onWord(utterance: Utterance, spokenStart: Int, spokenEnd: Int) = Unit
        override fun onUtteranceDone(utterance: Utterance) = Unit
    }
}
