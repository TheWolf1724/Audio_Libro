package com.lectorvoz.tts.engine

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.lectorvoz.core.model.TtsEngineType
import com.lectorvoz.core.model.Utterance
import com.lectorvoz.core.model.VoiceSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Motor con el TextToSpeech de Android.
 *
 * Cola gestionada por nosotros: se encola la frase actual y [lookahead] mas (QUEUE_ADD),
 * cada una seguida de su pausa calibrada (`playSilentUtterance`, [Pauses]), y se repone
 * una al terminar cada una: no hay huecos y cancelar solo descarta pocas frases.
 *
 * Por frase: si cambia el idioma ("es"/"en"), se cambia la voz a la mejor instalada de
 * ese idioma ([SystemVoicePicker]); la velocidad aplica el ritmo adaptativo. TextToSpeech
 * copia voz y velocidad en cada `speak`, asi que el cambio solo afecta a lo que se encola
 * despues. Palabra a palabra con `onRangeStart` (API 26). Pausar = `stop()`; reanudar =
 * volver a [speak] desde la frase recordada.
 */
class SystemTtsEngine(
    context: Context,
    /** Paquete del motor TTS (nulo = el predeterminado del sistema). */
    private val enginePackage: String? = null,
    private val lookahead: Int = 2,
    /**
     * Vigilante: si el proceso del motor TTS muere no llegan callbacks y la lectura se
     * quedaria colgada esperando para siempre. El margen de espera es este valor base mas un
     * tiempo proporcional a la longitud de la frase en curso ([WATCHDOG_MS_PER_CHAR]); asi no
     * se corta una frase larga a velocidad lenta en motores que no emiten `onRangeStart`
     * (solo `onStart`/`onDone`), pero un motor muerto (sin ningun evento) se detecta igual.
     */
    private val watchdogBaseMs: Long = 20_000L,
) : SpeechEngine {

    private val appContext = context.applicationContext

    override val type: TtsEngineType = TtsEngineType.SYSTEM

    private var tts: TextToSpeech? = null
    @Volatile private var settings = VoiceSettings()
    @Volatile private var volume = 1f
    private var voices: List<Voice> = emptyList()
    private var appliedLang: String? = null
    private var appliedUserVoice: String? = null
    private var generation = 0L

    override suspend fun prepare(settings: VoiceSettings) {
        this.settings = settings
        val t = ensureInit()
        t.setPitch(settings.pitch)
    }

    /** Voces instaladas en el motor del sistema. */
    suspend fun voices(): List<SystemVoice> = ensureInit().voices.orEmpty().map(::describe)
        .sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))

    override fun updateSettings(settings: VoiceSettings) {
        this.settings = settings
        tts?.setPitch(settings.pitch)
        if (settings.voiceId != appliedUserVoice) appliedLang = null
    }

    /** Afecta a las frases que se encolan desde ahora (limitacion de TextToSpeech). */
    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
    }

    override fun release() {
        tts?.shutdown()
        tts = null
        appliedLang = null
    }

    override suspend fun speak(
        start: Utterance,
        next: suspend (Utterance) -> Utterance?,
        listener: SpeechListener,
    ) {
        val t = ensureInit()
        t.setPitch(settings.pitch)
        // Los ids de frase son estables (la misma frase repite id al reanudar): se marcan con
        // la generacion para que un evento tardio de una lectura anterior no se confunda.
        val gen = ++generation
        fun idOf(u: Utterance) = "u${gen}_${u.id}"
        val events = Channel<Event>(Channel.UNLIMITED)
        t.setOnUtteranceProgressListener(ProgressBridge(events))
        val pending = ArrayDeque<Utterance>()
        var tail = start
        var exhausted = false
        var first = true

        fun enqueue(u: Utterance) {
            val s = settings
            applyLanguage(t, SpeechLanguage.of(u, s), s)
            t.setSpeechRate(s.speed * AdaptivePace.factor(u, s))
            val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) }
            val mode = if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            first = false
            val text = u.spokenText.take(TextToSpeech.getMaxSpeechInputLength())
            val result = if (text.isBlank()) {
                t.playSilentUtterance(1, mode, idOf(u))
            } else {
                t.speak(text, mode, params, idOf(u))
            }
            if (result != TextToSpeech.SUCCESS) throw SpeechEngineException("TextToSpeech rechazo la frase ${u.id}")
            val pauseMs = Pauses.afterMs(u, s)
            if (pauseMs > 0) t.playSilentUtterance(pauseMs.toLong(), TextToSpeech.QUEUE_ADD, SILENCE_PREFIX + idOf(u))
            pending.addLast(u)
        }

        suspend fun refill() {
            while (!exhausted && pending.size < 1 + lookahead) {
                val n = next(tail)
                if (n == null) {
                    exhausted = true
                } else {
                    enqueue(n)
                    tail = n
                }
            }
        }

        try {
            enqueue(start)
            refill()
            while (true) {
                // Espera el siguiente evento con vigilante adaptativo: como mucho el margen
                // base mas lo que puede durar de sonar la frase en curso (su longitud por un
                // ritmo lento). En motores sin onRangeStart el unico hueco grande es de onStart
                // a onDone de una frase; si el motor muere no llega ningun evento y salta igual.
                val budget = watchdogBaseMs + (pending.firstOrNull()?.spokenText?.length ?: 0).toLong() * WATCHDOG_MS_PER_CHAR
                val ev = withTimeoutOrNull(budget) { events.receiveCatching().getOrNull() }
                    ?: throw SpeechEngineException("La voz del sistema dejo de responder")
                val u = pending.firstOrNull { idOf(it) == ev.id } ?: continue
                when (ev) {
                    is Event.Start -> listener.onUtteranceStart(u)
                    is Event.Range -> listener.onWord(u, ev.start, ev.end)
                    is Event.Done -> {
                        // Las anteriores (si el motor no notifico alguna) tambien terminaron.
                        while (pending.isNotEmpty()) {
                            val head = pending.removeFirst()
                            listener.onUtteranceDone(head)
                            if (head === u) break
                        }
                        refill()
                        if (pending.isEmpty() && exhausted) return
                    }
                    is Event.Failed -> throw SpeechEngineException("Error del motor TTS (codigo ${ev.code})")
                }
            }
        } finally {
            t.stop()
            t.setOnUtteranceProgressListener(null)
            events.close()
        }
    }

    /** Cambia la voz si el idioma de la frase es otro que el de la anterior. */
    private fun applyLanguage(t: TextToSpeech, lang: String, s: VoiceSettings) {
        if (lang == appliedLang && s.voiceId == appliedUserVoice) return
        val picked = SystemVoicePicker.best(voices.map(::describe), lang, s.voiceId)
        val voice = picked?.let { p -> voices.firstOrNull { it.name == p.name } }
        if (voice != null) {
            t.voice = voice
        } else {
            t.language = SystemVoicePicker.defaultLocale(lang)
        }
        appliedLang = lang
        appliedUserVoice = s.voiceId
    }

    private suspend fun ensureInit(): TextToSpeech {
        tts?.let { return it }
        val ready = CompletableDeferred<Int>()
        val created = TextToSpeech(appContext, { status -> ready.complete(status) }, enginePackage)
        val status = withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() }
        if (status != TextToSpeech.SUCCESS) {
            created.shutdown()
            throw SpeechEngineException("No se pudo iniciar la voz del sistema (estado $status)")
        }
        created.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        voices = created.voices.orEmpty().toList()
        tts = created
        appliedLang = null
        return created
    }

    private sealed interface Event {
        val id: String

        data class Start(override val id: String) : Event
        data class Range(override val id: String, val start: Int, val end: Int) : Event
        data class Done(override val id: String) : Event
        data class Failed(override val id: String, val code: Int) : Event
    }

    /** Pasa los callbacks (hilos del binder) al canal de la corrutina de lectura. */
    private class ProgressBridge(private val events: Channel<Event>) : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            events.trySend(Event.Start(utteranceId))
        }

        override fun onDone(utteranceId: String) {
            events.trySend(Event.Done(utteranceId))
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) {
            events.trySend(Event.Failed(utteranceId, TextToSpeech.ERROR))
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            events.trySend(Event.Failed(utteranceId, errorCode))
        }

        override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
            events.trySend(Event.Range(utteranceId, start, end))
        }
    }

    private companion object {
        const val INIT_TIMEOUT_MS = 10_000L
        const val SILENCE_PREFIX = "s"

        /** Margen del vigilante por caracter del texto en curso (~6,7 car/s, ritmo muy lento). */
        const val WATCHDOG_MS_PER_CHAR = 150L

        fun describe(v: Voice) = SystemVoice(
            name = v.name,
            locale = v.locale,
            quality = v.quality,
            requiresNetwork = v.isNetworkConnectionRequired,
            latency = v.latency,
            notInstalled = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in v.features.orEmpty(),
        )
    }
}
