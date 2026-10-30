package com.lectorvoz.tts

import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.LocatorRange
import com.lectorvoz.core.model.PauseKind
import com.lectorvoz.core.model.TtsEngineType
import com.lectorvoz.core.model.Utterance
import com.lectorvoz.core.model.UtteranceKind
import com.lectorvoz.core.model.VoiceRole
import com.lectorvoz.core.model.VoiceSettings
import com.lectorvoz.tts.engine.SpeechEngine
import com.lectorvoz.tts.engine.SpeechEngineException
import com.lectorvoz.tts.engine.SpeechListener
import com.lectorvoz.tts.playback.AudioFocus
import com.lectorvoz.tts.playback.FocusChange
import kotlinx.coroutines.delay

/** Frase de prueba con texto hablado = texto visible. */
fun utt(
    id: Long,
    chapter: Int,
    offset: Int,
    text: String,
    language: String = "es",
    kind: UtteranceKind = UtteranceKind.SENTENCE,
    pauseAfter: PauseKind = PauseKind.SENTENCE,
    role: VoiceRole = VoiceRole.NARRATOR,
): Utterance = Utterance(
    id = id,
    range = LocatorRange(Locator(chapter, offset), Locator(chapter, offset + text.length)),
    displayText = text,
    spokenText = text,
    spokenToDisplay = IntArray(text.length) { it },
    language = language,
    kind = kind,
    pauseAfter = pauseAfter,
    role = role,
)

/**
 * Libro falso: [chapters] = frases por capitulo; ids consecutivos desde 1. [pauses] da la
 * pausa de cada frase por id (por defecto SENTENCE).
 */
class FakeUtteranceProvider(
    chapters: List<List<String>>,
    pauses: Map<Long, PauseKind> = emptyMap(),
) : UtteranceProvider {
    val all: List<Utterance>
    private val lengths = ArrayList<Int>()

    init {
        var id = 1L
        val list = ArrayList<Utterance>()
        chapters.forEachIndexed { ch, sentences ->
            var off = 0
            for (s in sentences) {
                list += utt(id, ch, off, s, pauseAfter = pauses[id] ?: PauseKind.SENTENCE)
                id++
                off += s.length + 1
            }
            lengths += off
        }
        all = list
    }

    override suspend fun chapterLength(chapter: Int): Int? = lengths.getOrNull(chapter)

    override val chapterCount: Int = chapters.size

    override suspend fun at(locator: Locator): Utterance? =
        all.firstOrNull { locator in it.range } ?: all.firstOrNull { it.range.start >= locator }

    override suspend fun next(current: Utterance): Utterance? =
        all.getOrNull(all.indexOfFirst { it.id == current.id } + 1)

    override suspend fun previous(current: Utterance): Utterance? {
        val i = all.indexOfFirst { it.id == current.id }
        return if (i > 0) all[i - 1] else null
    }
}

/** Registro de notificaciones de un motor. */
class RecordingListener : SpeechListener {
    val log = ArrayList<String>()

    @Synchronized override fun onUtteranceStart(utterance: Utterance) {
        log += "start:${utterance.id}"
    }

    @Synchronized override fun onWord(utterance: Utterance, spokenStart: Int, spokenEnd: Int) {
        log += "word:${utterance.id}:$spokenStart-$spokenEnd"
    }

    @Synchronized override fun onUtteranceDone(utterance: Utterance) {
        log += "done:${utterance.id}"
    }

    @Synchronized fun snapshot(): List<String> = log.toList()
}

/**
 * Motor falso con tiempo virtual: cada frase dura [msPerUtterance]; emite inicio, una
 * palabra y fin. Registra volumen, ajustes y llamadas.
 */
class FakeEngine(
    override val type: TtsEngineType,
    private val msPerUtterance: Long = 100,
    var failPrepare: Boolean = false,
) : SpeechEngine {
    var lastVolume = 1f
    var prepared = 0
    var released = false
    var settings: VoiceSettings? = null
    val spoken = ArrayList<Long>()
    val books = ArrayList<String?>()

    override fun setBook(bookId: String?) {
        books += bookId
    }

    override suspend fun prepare(settings: VoiceSettings) {
        if (failPrepare) throw SpeechEngineException("Voz no instalada")
        this.settings = settings
        prepared++
    }

    override suspend fun speak(start: Utterance, next: suspend (Utterance) -> Utterance?, listener: SpeechListener) {
        var u: Utterance? = start
        while (u != null) {
            listener.onUtteranceStart(u)
            spoken += u.id
            delay(msPerUtterance / 2)
            listener.onWord(u, 0, minOf(3, u.spokenText.length))
            delay(msPerUtterance / 2)
            // Como los motores reales, pide la siguiente antes de acabar la actual.
            val n = next(u)
            listener.onUtteranceDone(u)
            u = n
        }
    }

    override fun updateSettings(settings: VoiceSettings) {
        this.settings = settings
    }

    override fun setVolume(volume: Float) {
        lastVolume = volume
    }

    override fun release() {
        released = true
    }
}

/** Foco falso: concede segun [grant] y permite simular cambios. */
class FakeAudioFocus(var grant: Boolean = true) : AudioFocus {
    var requests = 0
    var abandons = 0
    var held = false
    private var listener: ((FocusChange) -> Unit)? = null

    override fun request(onChange: (FocusChange) -> Unit): Boolean {
        requests++
        listener = onChange
        held = grant
        return grant
    }

    override fun abandon() {
        abandons++
        held = false
    }

    /** Simula un cambio de foco del sistema. */
    fun emit(change: FocusChange) {
        listener?.invoke(change)
    }
}
