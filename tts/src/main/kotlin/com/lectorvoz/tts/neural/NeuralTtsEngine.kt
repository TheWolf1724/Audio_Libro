package com.lectorvoz.tts.neural

import android.os.Process
import com.lectorvoz.core.model.TtsEngineType
import com.lectorvoz.core.model.UtteranceKind
import com.lectorvoz.core.model.PauseKind
import com.lectorvoz.core.model.Utterance
import com.lectorvoz.core.model.VoiceRole
import com.lectorvoz.core.model.VoiceSettings
import com.lectorvoz.tts.audio.PauseSplitter
import com.lectorvoz.tts.audio.ProcessingOptions
import com.lectorvoz.tts.audio.TimeStretch
import com.lectorvoz.tts.audio.TruncationDetector
import com.lectorvoz.tts.audio.VoiceProcessor
import com.lectorvoz.tts.cache.AudioDiskCache
import com.lectorvoz.tts.engine.AdaptivePace
import com.lectorvoz.tts.engine.Pauses
import com.lectorvoz.tts.engine.Pregenerator
import com.lectorvoz.tts.engine.RenderedAudio
import com.lectorvoz.tts.engine.SpeechEngine
import com.lectorvoz.tts.engine.SpeechEngineException
import com.lectorvoz.tts.engine.SpeechLanguage
import com.lectorvoz.tts.engine.SpeechListener
import com.lectorvoz.tts.engine.UtteranceRenderer
import com.lectorvoz.tts.voice.VoiceCatalog
import com.lectorvoz.tts.voice.VoiceInfo
import com.lectorvoz.tts.voice.VoiceRef
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Motor neuronal (sherpa-onnx, Supertonic 3) con tuberia productor/consumidor:
 *
 * - Productor: por cada frase decide locutor (narrador o dialogo), idioma, velocidad (base
 *   de la voz x usuario x ritmo adaptativo) y pasos; obtiene el audio crudo (cache en
 *   memoria, cache en disco o modelo) en el hilo de sintesis (prioridad
 *   `THREAD_PRIORITY_AUDIO`) y lo pasa por [VoiceProcessor] (recorte, EQ, compresor,
 *   normalizacion, fundidos). Va por delante de la que suena hasta tener [bufferAheadMs] de
 *   audio listo (al menos [lookahead] frases y como mucho [maxLookahead]): una racha de frases
 *   cortas (dialogos) no vacia la precarga.
 * - Consumidor (hilo de audio): escribe PCM float en un [PcmSink] (AudioTrack) en bloques
 *   cortos, mas la pausa calibrada de cada frase ([Pauses]).
 * - Resaltado: un temporizador lee la cabeza de reproduccion real y emite inicio de frase,
 *   palabra (interpolada, [WordTimings]) y fin de frase en el instante en que suenan.
 *
 * Prioridad: la lectura manda sobre exportar ([render]) y preparar en disco
 * ([pregenerate]). Estos solo usan el hilo de sintesis cuando la lectura no lo necesita y
 * abandonan la frase a medias (se reintenta despues) en cuanto la lectura pide sintetizar.
 *
 * El modelo se libera tras [idleReleaseMs] sin ningun uso activo (lectura, exportacion,
 * preparacion; ahorro de RAM: Supertonic ocupa ~300 MB) y se recarga en el siguiente uso.
 * Cambiar de locutor, idioma, velocidad o calidad no recarga el modelo (son parametros por
 * generacion).
 *
 * Hilos: por defecto los de sintesis y audio son de proceso (compartidos entre motores) y
 * terminan solos tras un rato sin trabajo; [release] cancela ademas las tareas propias del
 * motor, asi que recrear el servicio no deja hilos ni corrutinas huerfanas.
 */
class NeuralTtsEngine(
    private val loader: NeuralSynthesizerLoader,
    private val sinkFactory: () -> PcmSink = { AudioTrackSink() },
    private val synthDispatcher: CoroutineDispatcher = sharedSynthDispatcher,
    private val audioDispatcher: CoroutineDispatcher = sharedAudioDispatcher,
    private val tickDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Hilos de inferencia de ONNX Runtime (2 = no saturar todos los nucleos). */
    private val numThreads: Int = 2,
    /** Frases sintetizadas por delante de la que suena como minimo (margen frente a picos de CPU). */
    private val lookahead: Int = 3,
    /**
     * Audio listo por delante de lo que suena hasta el que se rellena (ms). Con frases cortas
     * (dialogos) 3 frases son ~4 s de margen y un tramo lento del movil lo vaciaba (huecos entre
     * parrafos); por tiempo el margen es el mismo sea cual sea la longitud de las frases. Con
     * histeresis: lleno, el productor descansa hasta que quede menos de [refillBelowMs] y vuelve
     * a rellenar de una vez (rafagas de CPU con reposo entre medias, no un goteo frase a frase).
     * No cuesta energia: es el mismo trabajo, solo que antes. 0 = solo [lookahead] frases.
     */
    private val bufferAheadMs: Long = 30_000L,
    /** Por debajo de este margen (ms) el productor vuelve a rellenar. */
    private val refillBelowMs: Long = bufferAheadMs / 2,
    /** Tope de frases por delante (memoria acotada aunque sean muy cortas). */
    private val maxLookahead: Int = 120,
    /** Tope de audio listo por delante (ms): acota la memoria con parrafos muy largos (~16 MB). */
    private val maxAheadMs: Long = 90_000L,
    /**
     * Precarga por parrafos: no se descansa mientras el parrafo SIGUIENTE al que suena no este
     * entero (ademas del margen por tiempo). Al cambiar de parrafo ya esta todo listo.
     */
    private val preloadNextParagraph: Boolean = false,
    /**
     * Politica de precarga dinamica (nivel segun el terminal, ver [PreloadTiers]); recibe la
     * velocidad de sintesis medida. Nula = la fija de los parametros de arriba.
     */
    private val preloadPolicy: ((rtf: Float?) -> PreloadPolicy)? = null,
    /**
     * Frases cortas seguidas del mismo parrafo (misma voz, idioma y velocidad) se sintetizan en
     * una sola llamada hasta este numero de caracteres y luego se parten por la pausa natural
     * ([PauseSplitter]). El coste de una llamada al modelo es casi fijo (relee todos los pesos en
     * cada paso de difusion), asi que un dialogo de frases cortas cuesta mucho menos. 0 = no.
     */
    private val groupMaxChars: Int = 0,
    private val idleReleaseMs: Long = 5 * 60_000L,
    private val tickMs: Long = 20L,
    /** Cache de audio en disco (preparar mientras carga); nula = sin cache. */
    private val diskCache: AudioDiskCache? = null,
) : SpeechEngine, UtteranceRenderer, Pregenerator {

    override val type: TtsEngineType = TtsEngineType.NEURAL

    @Volatile private var settings = VoiceSettings(engine = TtsEngineType.NEURAL)
    @Volatile private var volume = 1f
    @Volatile private var currentSink: PcmSink? = null
    @Volatile private var bookId: String? = null

    /** Si es true, todo lo que se sintetiza tambien se guarda en la cache de disco. */
    @Volatile private var writeThrough: Boolean = false

    /** La cache de disco no tiene sitio ni desalojando otros libros (se rearma al cambiar de libro). */
    @Volatile private var cacheFull: Boolean = false

    override fun setWriteThrough(on: Boolean) {
        writeThrough = on
    }

    // Confinados al hilo de sintesis.
    private var synth: NeuralSynthesizer? = null
    private var loadedId: String? = null
    private val processor = VoiceProcessor()
    // Audio crudo reciente (LRU acotada por muestras, no por frases). El modelo genera siempre a
    // la misma velocidad (SAFE_MODEL_SPEED), asi que sirve aunque cambien la velocidad del usuario,
    // el tono o el procesado, y tras pausar, retroceder o reiniciar: no se repite la sintesis de
    // lo que ya estaba precargado.
    private val memory = LinkedHashMap<RawKey, FloatArray>(32, 0.75f, true)

    // Politica fija (parametros del constructor) si no hay una dinamica.
    private val staticPolicy = PreloadPolicy(bufferAheadMs, refillBelowMs, maxAheadMs, if (preloadNextParagraph) 1 else 0, "fija")

    private fun policy(): PreloadPolicy = preloadPolicy?.invoke(synthesisRtf) ?: staticPolicy

    // Velocidad de sintesis medida (segundos de calculo por segundo de audio), media con olvido:
    // las frases servidas desde la cache cuentan con su audio y tiempo casi nulo, asi que un grupo
    // de frases sintetizado de una vez se reparte bien entre ellas.
    private val rtfLock = Any()
    private var rtfSynthSec = 0.0
    private var rtfAudioSec = 0.0

    /** Velocidad de sintesis medida en este terminal; nula hasta tener unos segundos de audio. */
    val synthesisRtf: Float?
        get() = synchronized(rtfLock) { if (rtfAudioSec < 5.0) null else (rtfSynthSec / rtfAudioSec).toFloat() }

    private fun recordSynthesis(seconds: Double, audioSeconds: Double) = synchronized(rtfLock) {
        rtfSynthSec = rtfSynthSec * RTF_DECAY + seconds
        rtfAudioSec = rtfAudioSec * RTF_DECAY + audioSeconds
    }
    private var memorySamples = 0L

    private fun remember(key: RawKey, pcm: FloatArray) {
        memory.put(key, pcm)?.let { memorySamples -= it.size }
        memorySamples += pcm.size
        val it = memory.entries.iterator()
        while (memorySamples > MEMORY_CACHE_SAMPLES && memory.size > 1 && it.hasNext()) {
            val e = it.next()
            memorySamples -= e.value.size
            it.remove()
        }
    }

    private val ownerScope = CoroutineScope(SupervisorJob() + tickDispatcher)
    private val idleLock = Any()
    private var idleJob: Job? = null

    /** Usos activos del modelo (lecturas, exportaciones, preparaciones, carga). */
    private val users = AtomicInteger(0)

    /**
     * Lecturas que necesitan el hilo de sintesis: desde que empiezan hasta que se acaban,
     * salvo mientras esperan con la cola llena. Los trabajos de fondo solo corren a 0.
     */
    private val readerDemand = MutableStateFlow(0)

    /** Lecturas en curso (aunque no sinteticen ahora): fijan el modelo cargado. */
    private val readers = MutableStateFlow(0)

    /** true si hay un modelo cargado en memoria (para pruebas y diagnostico). */
    val isModelLoaded: Boolean get() = synth != null

    override suspend fun prepare(settings: VoiceSettings) {
        this.settings = settings
        inUse { asReader { withContext(synthDispatcher) { ensureLoaded(settings) } } }
    }

    override fun updateSettings(settings: VoiceSettings) {
        val old = this.settings
        this.settings = settings
        if (old.pitch != settings.pitch) currentSink?.setPitch(settings.pitch)
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        currentSink?.setVolume(this.volume)
    }

    override fun setBook(bookId: String?) {
        if (bookId != this.bookId) cacheFull = false
        this.bookId = bookId
    }

    override suspend fun speak(
        start: Utterance,
        next: suspend (Utterance) -> Utterance?,
        listener: SpeechListener,
    ): Unit = inUse {
        readers.update { it + 1 }
        try {
            read(start, next, listener)
        } finally {
            readers.update { it - 1 }
        }
    }

    private suspend fun read(start: Utterance, next: suspend (Utterance) -> Utterance?, listener: SpeechListener) {
        asReader { demand ->
            coroutineScope {
                val active = withContext(synthDispatcher) { ensureLoaded(settings) }
                val sampleRate = active.sampleRate
                // Sin capacidad fija: el productor se limita solo (ver Ahead).
                val queue = Channel<Chunk>(capacity = Channel.UNLIMITED)
                val ahead = Ahead(sampleRate)
                launch(tickDispatcher) {
                    try {
                        produce(active, start, next, sampleRate, queue, demand, ahead)
                    } finally {
                        demand.set(false) // ya no hay nada que sintetizar: el fondo puede seguir
                        queue.close()
                    }
                }
                withContext(audioDispatcher) { consume(queue, sampleRate, listener, ahead) }
            }
        }
    }

    override suspend fun render(u: Utterance, settings: VoiceSettings): RenderedAudio = inUse {
        background { preempted ->
            val active = loadForBackground(settings)
            val job = coroutineContext.job
            val pcm = voice(active, u, settings, { !job.isActive || preempted() }, preempted)
            val silence = Pauses.samples(Pauses.afterMs(u, settings), active.sampleRate)
            RenderedAudio(pcm, silence, active.sampleRate)
        }
    }

    override val canPregenerate: Boolean
        get() = diskCache != null && bookId != null && !cacheFull

    override suspend fun pregenerate(u: Utterance, settings: VoiceSettings): Boolean {
        val cache = diskCache ?: return false
        val book = bookId ?: return false
        if (u.spokenText.isBlank()) return false
        return inUse {
            background { preempted ->
                if (!ensureRoom(cache, book)) {
                    cacheFull = true
                    return@background false
                }
                val active = loadForBackground(settings)
                val ref = refFor(u, settings)
                val params = paramsFor(u, settings, ref)
                val chapter = u.range.start.chapter
                if (cache.contains(book, chapter, ref.voiceId, params, u.spokenText)) return@background false
                val job = coroutineContext.job
                val pcm = synthesizeComplete(active, u.spokenText, params) { !job.isActive || preempted() }
                    ?: run {
                        coroutineContext.ensureActive()
                        if (preempted()) throw Preempted()
                        return@background false
                    }
                cache.put(book, chapter, ref.voiceId, params, u.spokenText, pcm, active.sampleRate)
                true
            }
        }
    }

    /**
     * Libera el modelo y cancela las tareas propias del motor (liberacion por inactividad).
     * Los hilos por defecto son de proceso y se cierran solos al quedar sin trabajo.
     */
    override fun release() {
        cancelIdleRelease()
        ownerScope.coroutineContext.cancelChildren()
        ownerScope.launch(synthDispatcher + NonCancellable) { unload() }
    }

    // --- Usos y prioridad ---

    /** Cuenta [block] como uso activo del modelo: no se libera por inactividad mientras dure. */
    private inline fun <T> inUse(block: () -> T): T {
        users.incrementAndGet()
        cancelIdleRelease()
        try {
            return block()
        } finally {
            if (users.decrementAndGet() == 0) scheduleIdleRelease()
        }
    }

    /** Demanda de una lectura sobre el hilo de sintesis (se activa y desactiva sin contar dos veces). */
    private inner class Demand {
        private var on = false

        @Synchronized
        fun set(value: Boolean) {
            if (value == on) return
            on = value
            readerDemand.update { if (value) it + 1 else it - 1 }
        }
    }

    /** Ejecuta [block] como lectura: los trabajos de fondo le ceden el hilo de sintesis. */
    private inline fun <T> asReader(block: (Demand) -> T): T {
        val demand = Demand()
        demand.set(true)
        try {
            return block(demand)
        } finally {
            demand.set(false)
        }
    }

    /**
     * Trabajo de fondo (exportar, preparar): espera a que la lectura no necesite el hilo de
     * sintesis y, si la lectura lo pide a mitad ([block] recibe la comprobacion y lanza
     * [Preempted]), se abandona y se reintenta entero despues.
     */
    private suspend fun <T> background(block: suspend CoroutineScope.(preempted: () -> Boolean) -> T): T {
        val preempted: () -> Boolean = { readerDemand.value > 0 }
        var waitReaders = false
        while (true) {
            if (waitReaders) readers.first { it == 0 } else readerDemand.first { it == 0 }
            try {
                return withContext(synthDispatcher) {
                    if (preempted()) throw Preempted()
                    block(preempted)
                }
            } catch (e: Preempted) {
                // La lectura necesita el hilo (o su modelo): se reintenta cuando lo suelte.
                waitReaders = e.untilNoReaders
            }
        }
    }

    /**
     * Modelo para un trabajo de fondo (hilo de sintesis). Si pide otra voz mientras hay una
     * lectura en curso, espera a que acabe en vez de cambiar el modelo bajo la lectura.
     */
    private fun loadForBackground(s: VoiceSettings): NeuralSynthesizer {
        val wanted = VoiceRef.parse(s.voiceId).voiceId
        if (readers.value > 0 && synth != null && loadedId != wanted) throw Preempted(untilNoReaders = true)
        return ensureLoaded(s)
    }

    /** Deja sitio en la cache de disco desalojando otros libros (LRU); false si no hay manera. */
    private fun ensureRoom(cache: AudioDiskCache, book: String): Boolean {
        val high = cache.maxBytes / 10 * 9
        if (cache.sizeBytes() < high) return true
        cache.trimTo(cache.maxBytes / 10 * 7, keepBookId = book)
        return cache.sizeBytes() < high
    }

    // --- Decision por frase (hilo de sintesis) ---

    /** Voz y locutor de [u]: el de dialogos si procede y es del mismo modelo. */
    private fun refFor(u: Utterance, s: VoiceSettings): VoiceRef {
        val narrator = VoiceRef.parse(s.voiceId)
        if (u.role != VoiceRole.DIALOG || s.dialogVoiceId == null) return narrator
        val dialog = VoiceRef.parse(s.dialogVoiceId)
        // Solo cambia el locutor: otro modelo exigiria recargar (no se hace a mitad de lectura).
        return if (dialog.voiceId == narrator.voiceId) dialog else narrator
    }

    /** Velocidad total pedida para [u]: usuario x base de la voz x ritmo adaptativo. */
    private fun wantedSpeed(u: Utterance, s: VoiceSettings, ref: VoiceRef): Float {
        val voice: VoiceInfo? = VoiceCatalog.find(ref.voiceId)
        val base = voice?.baseSpeed ?: 1f
        return s.speed * base * AdaptivePace.factor(u, s)
    }

    private fun paramsFor(u: Utterance, s: VoiceSettings, ref: VoiceRef): SynthesisParams =
        SynthesisParams(
            speed = modelSpeed(wantedSpeed(u, s, ref)),
            sid = ref.sid,
            lang = SpeechLanguage.of(u, s),
            numSteps = s.quality.steps,
        )

    /**
     * Voz procesada de [u] (vacia si no hay texto): audio crudo del modelo a la velocidad que
     * reproduce entera ([modelSpeed]), acelerado si hace falta hasta la pedida ([TimeStretch])
     * y pasado por la cadena de audio.
     */
    private suspend fun voice(
        active: NeuralSynthesizer,
        u: Utterance,
        s: VoiceSettings,
        isCancelled: () -> Boolean,
        preempted: () -> Boolean = NEVER,
    ): FloatArray {
        if (u.spokenText.isBlank()) return FloatArray(0)
        val ref = refFor(u, s)
        val params = paramsFor(u, s, ref)
        val raw = rawAudio(active, u, ref, params, isCancelled, preempted)
        val wanted = wantedSpeed(u, s, ref)
        var timed = TimeStretch.stretchKeepingEnd(raw, active.sampleRate, wanted / params.speed)
        // Red de seguridad sobre lo que de verdad va a sonar: si aun asi acaba cortado (muy raro,
        // el modelo es estocastico), se genera mas lento y se acelera de nuevo.
        if (TruncationDetector.isTruncated(timed, active.sampleRate)) {
            // Por la cache (memoria y disco) con su propia clave: no se repite al volver a la frase.
            val slow = params.copy(speed = params.speed * LAST_RESORT_SLOWDOWN)
            val slowRaw = rawAudio(active, u, ref, slow, isCancelled, preempted)
            timed = TimeStretch.stretchKeepingEnd(slowRaw, active.sampleRate, wanted / slow.speed)
        }
        return processor.process(timed, active.sampleRate, ProcessingOptions.from(s))
    }

    /**
     * Sintesis que no se come el final. El modelo es estocastico: incluso a [SAFE_MODEL_SPEED]
     * un ~4 % de las frases muy cortas acaba cortado en seco. Si [TruncationDetector] lo ve, se
     * repite (otra semilla) y, si persiste, se genera mas lento ([RETRY_SLOWDOWNS]) y se
     * devuelve ya acelerado a la velocidad de [params], para que lo que se guarda en cache
     * signifique siempre lo mismo. Cada intento se comprueba; si todos salen cortados (no
     * visto en las pruebas) se queda el ultimo. Nulo si se cancela.
     */
    private fun synthesizeComplete(
        active: NeuralSynthesizer,
        text: String,
        params: SynthesisParams,
        isCancelled: () -> Boolean,
    ): FloatArray? {
        val fs = active.sampleRate
        var pcm: FloatArray? = null
        for (slowdown in RETRY_SLOWDOWNS) {
            val raw = active.synthesize(text, params.copy(speed = params.speed * slowdown), isCancelled) ?: return null
            pcm = if (slowdown == 1f) raw else TimeStretch.stretchKeepingEnd(raw, fs, 1f / slowdown)
            if (!TruncationDetector.isTruncated(raw, fs, RAW_MAX_FALL_MS)) return pcm
            // Cancelado: no gastar mas intentos; si se vuelve a pedir se reintentara entonces.
            if (isCancelled()) return null
        }
        return pcm
    }

    /** Audio crudo: memoria -> disco -> modelo (y se guarda segun [writeThrough]). */
    private suspend fun rawAudio(
        active: NeuralSynthesizer,
        u: Utterance,
        ref: VoiceRef,
        params: SynthesisParams,
        isCancelled: () -> Boolean,
        preempted: () -> Boolean,
    ): FloatArray {
        val key = RawKey(ref.voiceId, params, u.spokenText)
        memory[key]?.let { return it }
        val book = bookId
        val chapter = u.range.start.chapter
        val fromDisk = if (diskCache != null && book != null) {
            diskCache.get(book, chapter, ref.voiceId, params, u.spokenText)
                ?.takeIf { it.sampleRate == active.sampleRate }?.pcm
        } else {
            null
        }
        val pcm = fromDisk ?: synthesizeComplete(active, u.spokenText, params, isCancelled)
            ?: run {
                coroutineContext.ensureActive()
                if (preempted()) throw Preempted()
                throw SpeechEngineException("Sintesis cancelada")
            }
        if (fromDisk == null && writeThrough && diskCache != null && book != null) {
            diskCache.put(book, chapter, ref.voiceId, params, u.spokenText, pcm, active.sampleRate)
        }
        remember(key, pcm)
        return pcm
    }

    /**
     * Cuanto va la sintesis por delante de lo que suena, para que el productor se limite solo:
     * sigue mientras haya menos de [lookahead] frases sin empezar a escribir, o menos de
     * [bufferAheadMs] de audio sin sonar (y menos de [maxLookahead] frases).
     */
    private inner class Ahead(sampleRate: Int) {
        private val rate = sampleRate.toLong()
        private val producedChunks = AtomicLong()
        private val dequeuedChunks = AtomicLong()
        private val producedFrames = AtomicLong()
        private val playedFrames = AtomicLong()

        /** Parrafos ya sintetizados enteros (desde el inicio de la sesion). */
        private val paragraphsDone = AtomicLong()

        /** Parrafo de la ultima frase que paso a la salida (la que suena o esta a punto). */
        private val playingParagraph = AtomicLong()
        private val changes = MutableStateFlow(0L)

        /** Lleno y esperando a bajar de [refillFrames] (solo lo toca el productor). */
        @Volatile private var resting = false

        fun produced(frames: Long, endsParagraph: Boolean) {
            producedChunks.incrementAndGet()
            producedFrames.addAndGet(frames)
            if (endsParagraph) paragraphsDone.incrementAndGet()
        }

        fun dequeued(paragraph: Long) {
            dequeuedChunks.incrementAndGet()
            playingParagraph.set(paragraph)
            changes.update { it + 1 }
        }

        /** Del reloj de reproduccion: solo despierta al productor cuando le toca volver a trabajar. */
        fun played(frames: Long) {
            playedFrames.set(frames)
            if (resting && producedFrames.get() - frames < policy().refillBelowMs * rate / 1000) changes.update { it + 1 }
        }

        fun full(): Boolean {
            val chunks = producedChunks.get() - dequeuedChunks.get()
            if (chunks < lookahead) return false.also { resting = false }
            val ahead = producedFrames.get() - playedFrames.get()
            val p = policy()
            if (chunks >= maxLookahead || ahead >= p.maxAheadMs * rate / 1000) return true
            // El parrafo que suena y los siguientes que pida la politica, enteros, antes de descansar.
            if (p.paragraphsAhead > 0 && paragraphsDone.get() < playingParagraph.get() + 1 + p.paragraphsAhead) return false.also { resting = false }
            if (ahead >= p.bufferAheadMs * rate / 1000) return true.also { resting = true }
            if (resting && ahead >= p.refillBelowMs * rate / 1000) return true
            resting = false
            return false
        }

        suspend fun awaitRoom() {
            while (true) {
                val seen = changes.value
                if (!full()) return
                changes.first { it != seen }
            }
        }
    }

    // --- Productor (hilo de sintesis) ---

    private suspend fun produce(
        active: NeuralSynthesizer,
        start: Utterance,
        next: suspend (Utterance) -> Utterance?,
        sampleRate: Int,
        queue: Channel<Chunk>,
        demand: Demand,
        ahead: Ahead,
    ) {
        val job = coroutineContext.job
        var u: Utterance? = start
        var paragraph = 0L
        while (u != null) {
            coroutineContext.ensureActive()
            if (ahead.full()) {
                // Precarga completa: la lectura no necesita el hilo (el trabajo de fondo puede usarlo).
                demand.set(false)
                try {
                    ahead.awaitRoom()
                } finally {
                    demand.set(true)
                }
            }
            val s = settings
            val cur: Utterance = u
            val t0 = System.nanoTime()
            if (groupMaxChars > 0) {
                val group = groupFrom(cur, s, next)
                if (group.size > 1) {
                    withContext(synthDispatcher) {
                        if (synth !== active) throw SpeechEngineException("El modelo de voz se libero durante la lectura")
                        synthesizeGroup(active, group, s, { !job.isActive })
                    }
                }
            }
            val pcm = withContext(synthDispatcher) {
                // El modelo se fija al empezar; un cambio de modelo exige reiniciar la lectura.
                if (synth !== active) throw SpeechEngineException("El modelo de voz se libero durante la lectura")
                voice(active, cur, s, { !job.isActive })
            }
            if (pcm.isNotEmpty()) recordSynthesis((System.nanoTime() - t0) / 1e9, pcm.size.toDouble() / sampleRate)
            val silence = Pauses.samples(Pauses.afterMs(cur, s), sampleRate)
            val chunk = Chunk(cur, pcm, WordTimings.estimate(cur.spokenText, pcm), silence, paragraph)
            val endsParagraph = cur.pauseAfter >= PauseKind.PARAGRAPH
            ahead.produced(pcm.size.toLong() + silence, endsParagraph)
            if (endsParagraph) paragraph++
            queue.send(chunk)
            u = next(cur)
        }
    }

    // --- Frases agrupadas ---

    /**
     * [cur] y las siguientes que se pueden sintetizar junto a ella en una sola llamada: mismo
     * parrafo (sin pausa de parrafo, verso ni seccion entre medias), mismo capitulo, mismos
     * parametros del modelo (voz, locutor, idioma, velocidad, pasos), frases normales, aun no
     * sintetizadas, y en total hasta [groupMaxChars]. Solo [cur] si no hay con quien juntarla.
     */
    private suspend fun groupFrom(cur: Utterance, s: VoiceSettings, next: suspend (Utterance) -> Utterance?): List<Utterance> {
        if (!groupable(cur) || cur.spokenText.length >= groupMaxChars) return listOf(cur)
        val ref = refFor(cur, s)
        val params = paramsFor(cur, s, ref)
        if (withContext(synthDispatcher) { memory.containsKey(RawKey(ref.voiceId, params, cur.spokenText)) }) return listOf(cur)
        val out = arrayListOf(cur)
        var total = cur.spokenText.length
        var last = cur
        while (out.size < GROUP_MAX_SIZE && last.pauseAfter <= PauseKind.SENTENCE) {
            val n = next(last) ?: break
            if (!groupable(n) || n.range.start.chapter != cur.range.start.chapter) break
            val r = refFor(n, s)
            if (r != ref || paramsFor(n, s, r) != params) break
            total += 1 + n.spokenText.length
            if (total > groupMaxChars) break
            out += n
            last = n
        }
        return out
    }

    private fun groupable(u: Utterance): Boolean =
        u.spokenText.isNotBlank() && (u.kind == UtteranceKind.SENTENCE || u.kind == UtteranceKind.LIST_ITEM)

    /**
     * Sintetiza [group] de una vez y guarda en la cache el audio crudo de cada frase, partido por
     * las pausas naturales. Si no se puede partir con seguridad, o se cancela, no guarda nada: las
     * frases se sintetizaran una a una (nunca peor que sin agrupar).
     */
    private fun synthesizeGroup(active: NeuralSynthesizer, group: List<Utterance>, s: VoiceSettings, isCancelled: () -> Boolean) {
        val ref = refFor(group.first(), s)
        val params = paramsFor(group.first(), s, ref)
        val starts = IntArray(group.size)
        val text = StringBuilder()
        group.forEachIndexed { i, u ->
            if (i > 0) text.append(' ')
            starts[i] = text.length
            text.append(u.spokenText)
        }
        val raw = synthesizeComplete(active, text.toString(), params, isCancelled) ?: return
        val parts = PauseSplitter.split(raw, active.sampleRate, starts, text.length) ?: return
        val book = bookId
        group.forEachIndexed { i, u ->
            val piece = raw.copyOfRange(parts[i].first, parts[i].last + 1)
            remember(RawKey(ref.voiceId, params, u.spokenText), piece)
            if (writeThrough && diskCache != null && book != null) {
                diskCache.put(book, u.range.start.chapter, ref.voiceId, params, u.spokenText, piece, active.sampleRate)
            }
        }
    }

    // --- Consumidor (hilo de audio) ---

    private suspend fun consume(queue: Channel<Chunk>, sampleRate: Int, listener: SpeechListener, ahead: Ahead) = coroutineScope {
        val sink = sinkFactory()
        sink.open(sampleRate)
        sink.setVolume(volume)
        if (settings.pitch != 1f) sink.setPitch(settings.pitch)
        currentSink = sink
        val tracker = PlaybackTracker(listener)
        // Pausa/salto inmediatos: al cancelar, desde otro hilo se calla y vacia la salida (eso
        // desbloquea la escritura en curso, que con bloques grandes podria tardar ~0,2 s).
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val stopper = launch(tickDispatcher) {
            try {
                awaitCancellation()
            } finally {
                if (!finished.get()) sink.interrupt()
            }
        }
        val ticker = launch(tickDispatcher) {
            while (isActive) {
                val played = sink.playedFrames()
                tracker.update(played)
                ahead.played(played)
                delay(tickMs)
            }
        }
        try {
            var written = 0L
            for (chunk in queue) {
                ahead.dequeued(chunk.paragraph)
                val start = written
                val voiceEnd = start + chunk.pcm.size
                tracker.add(Segment(chunk.utterance, start, voiceEnd + chunk.silenceFrames, chunk.timings))
                written += writeAll(sink, chunk.pcm)
                written += writeSilence(sink, chunk.silenceFrames)
            }
            drain(sink, written)
            finished.set(true)
            ticker.cancel()
            tracker.update(written)
        } finally {
            stopper.cancel()
            ticker.cancel()
            currentSink = null
            sink.release()
        }
    }

    private suspend fun writeAll(sink: PcmSink, data: FloatArray): Long {
        var off = 0
        while (off < data.size) {
            coroutineContext.ensureActive()
            val n = sink.write(data, off, minOf(BLOCK, data.size - off))
            if (n < 0) throw SpeechEngineException("Error de AudioTrack: $n")
            if (n == 0) delay(5)
            off += n
        }
        return data.size.toLong()
    }

    private suspend fun writeSilence(sink: PcmSink, frames: Int): Long {
        var left = frames
        while (left > 0) {
            coroutineContext.ensureActive()
            val n = sink.write(SILENCE, 0, minOf(BLOCK, left))
            if (n < 0) throw SpeechEngineException("Error de AudioTrack: $n")
            if (n == 0) delay(5)
            left -= n
        }
        return frames.toLong()
    }

    /** Espera a que suene todo lo escrito; corta si la cabeza no avanza en 2 s. */
    private suspend fun drain(sink: PcmSink, written: Long) {
        var last = -1L
        var stalledMs = 0L
        while (true) {
            val played = sink.playedFrames()
            if (played >= written) return
            if (played == last) {
                stalledMs += tickMs
                if (stalledMs >= 2_000) return
            } else {
                stalledMs = 0
                last = played
            }
            delay(tickMs)
        }
    }

    // --- Modelo ---

    private fun ensureLoaded(s: VoiceSettings): NeuralSynthesizer {
        val ref = VoiceRef.parse(s.voiceId)
        val current = synth
        if (current != null && loadedId == ref.voiceId) return current
        unload()
        val loaded = loader.load(ref, numThreads)
        synth = loaded
        loadedId = ref.voiceId
        return loaded
    }

    private fun unload() {
        memory.clear()
        memorySamples = 0
        synth?.release()
        synth = null
        loadedId = null
    }

    /**
     * Libera el modelo YA si no hay ningun uso activo (lectura/preparacion/exportacion), sin
     * esperar a la liberacion perezosa. Pensado para responder a la presion de memoria del
     * sistema (`onTrimMemory`): si se esta leyendo (`users > 0`) no hace nada; el modelo se
     * recarga solo al volver a usarse.
     */
    fun releaseIfIdle() {
        if (users.get() != 0) return
        cancelIdleRelease()
        ownerScope.launch { withContext(synthDispatcher) { if (users.get() == 0) unload() } }
    }

    private fun scheduleIdleRelease() = synchronized(idleLock) {
        idleJob?.cancel()
        idleJob = ownerScope.launch {
            delay(idleReleaseMs)
            // Se vuelve a comprobar en el hilo de sintesis: un uso pudo empezar entre medias.
            withContext(synthDispatcher) { if (users.get() == 0) unload() }
        }
    }

    private fun cancelIdleRelease() = synchronized(idleLock) {
        idleJob?.cancel()
        idleJob = null
    }

    /** El trabajo de fondo cede el hilo de sintesis a la lectura (no es una cancelacion). */
    private class Preempted(val untilNoReaders: Boolean = false) : RuntimeException(null, null, false, false)

    private data class RawKey(val voiceId: String, val params: SynthesisParams, val text: String)

    internal class Chunk(
        val utterance: Utterance,
        val pcm: FloatArray,
        val timings: List<WordTiming>,
        val silenceFrames: Int,
        /** Parrafo de la sesion al que pertenece (0, 1, 2...). */
        val paragraph: Long = 0,
    )

    internal companion object {
        /**
         * Velocidad maxima que se le pide a Supertonic. Por encima de ~1,1 habla algo menos
         * deprisa de lo pedido y, al llegar al final que habia planificado, DESCARTA lo que
         * falta (deja silencio detras): se come la ultima silaba. Es estocastico (depende de la
         * semilla) y pasa en todas las longitudes. Medido el 29-10-2026 (F5, 8 pasos, 109
         * frases de todo tipo): a 1,0 el 4 % (solo frases de menos de 20 caracteres); a 1,15
         * el 16 %; a 1,4375 el 40 % (de ellas, 8 de 24 con mas de 40 caracteres). Lo que falte
         * hasta la velocidad pedida lo pone [TimeStretch] sin cambiar el tono: mismo ritmo,
         * ultima palabra completa. Cuesta generar ~37 % mas de audio en la sintesis (medido:
         * 107 s frente a 78 s de CPU por el mismo texto), inevitable: hay que generar las
         * silabas que el modelo se comia.
         */
        const val SAFE_MODEL_SPEED = 1.0f

        /** Velocidad a pedir al modelo cuando se quiere [wanted]. */
        fun modelSpeed(wanted: Float): Float = minOf(wanted, SAFE_MODEL_SPEED)

        /**
         * Intentos de sintesis de una frase (factor sobre la velocidad del modelo): dos a la
         * velocidad segura y, si ambos salen cortados, mas lentos (a 0,85 no se vio ningun
         * corte en las pruebas). Solo se pasa del primero en ~4 % de las frases muy cortas.
         */
        val RETRY_SLOWDOWNS = floatArrayOf(1f, 1f, 0.85f, 0.7f)

        /** Ultimo recurso si la frase ya acelerada aun sale cortada (ver `voice`). */
        const val LAST_RESORT_SLOWDOWN = 0.8f

        /**
         * Caida mas rapida que se acepta en el audio crudo: la de [TruncationDetector] (15 ms)
         * por la mayor compresion de tiempo habitual (x1,4375) mas una ventana de margen. Asi
         * un final que el modelo deja "casi cortado" tambien se regenera y nunca llega cortado
         * al oido.
         */
        const val RAW_MAX_FALL_MS = 25

        /**
         * Bloque de escritura: ~186 ms a 44,1 kHz. Escrituras grandes = menos despertares del
         * hilo de audio; la pausa no depende de esto (se libera la salida con flush).
         */
        const val BLOCK = 8192
        /** Olvido de la media de velocidad de sintesis por frase (~las ultimas 10-20 frases). */
        const val RTF_DECAY = 0.9

        /** Frases como mucho en un grupo (ver `groupMaxChars`). */
        const val GROUP_MAX_SIZE = 8

        /** Muestras de audio crudo en la cache de memoria (~12 MB en float: unas 24 frases). */
        const val MEMORY_CACHE_SAMPLES = 3_000_000L
        private val SILENCE = FloatArray(BLOCK)
        private val NEVER: () -> Boolean = { false }

        /** Segundos sin trabajo tras los que termina el hilo de un dispatcher de proceso. */
        private const val THREAD_KEEP_ALIVE_S = 30L

        /** Hilo de sintesis del proceso (compartido por todos los motores). */
        /**
         * Hilo de sintesis del proceso: prioridad NORMAL. La inferencia es trabajo pesado y largo;
         * con prioridad de audio competia con el mezclador y la interfaz sin ganar nada (hay ~30 s
         * de precarga) y los hilos de ONNX Runtime, creados desde este, la heredaban.
         */
        val sharedSynthDispatcher: CoroutineDispatcher by lazy { singleThreadDispatcher("tts-sintesis", Process.THREAD_PRIORITY_DEFAULT) }

        /** Hilo de audio del proceso (compartido por todos los motores). */
        val sharedAudioDispatcher: CoroutineDispatcher by lazy { singleThreadDispatcher("tts-audio", Process.THREAD_PRIORITY_AUDIO) }

        /**
         * Dispatcher de un hilo con prioridad [priority] (`Process.THREAD_PRIORITY_*`). El hilo
         * termina tras [THREAD_KEEP_ALIVE_S] s sin trabajo y se recrea al volver a usarlo (las
         * tareas siguen ejecutandose de una en una y en orden).
         */
        fun singleThreadDispatcher(name: String, priority: Int): CoroutineDispatcher {
            val factory = ThreadFactory { r ->
                Thread({
                    try {
                        Process.setThreadPriority(priority)
                    } catch (_: Throwable) {
                        // JVM de pruebas sin Android: prioridad por defecto.
                    }
                    r.run()
                }, name).apply { isDaemon = true }
            }
            val executor = ThreadPoolExecutor(1, 1, THREAD_KEEP_ALIVE_S, TimeUnit.SECONDS, LinkedBlockingQueue(), factory)
            executor.allowCoreThreadTimeOut(true)
            return executor.asCoroutineDispatcher()
        }
    }
}

/** Tramo de audio de una frase: [startFrame, endFrame) incluye el silencio final. */
internal class Segment(
    val utterance: Utterance,
    val startFrame: Long,
    val endFrame: Long,
    val words: List<WordTiming>,
) {
    var started = false
    var nextWord = 0
}

/**
 * Convierte la cabeza de reproduccion en eventos. Si en un tic pasan varias palabras solo
 * se notifica la ultima (no inundar la UI).
 */
internal class PlaybackTracker(private val listener: SpeechListener) {
    private val segments = ArrayDeque<Segment>()

    @Synchronized
    fun add(segment: Segment) {
        segments.addLast(segment)
    }

    @Synchronized
    fun update(played: Long) {
        while (segments.isNotEmpty()) {
            val s = segments.first()
            if (played < s.startFrame) return
            if (!s.started) {
                s.started = true
                listener.onUtteranceStart(s.utterance)
            }
            var word = -1
            while (s.nextWord < s.words.size && played >= s.startFrame + s.words[s.nextWord].startFrame) {
                word = s.nextWord++
            }
            if (word >= 0) {
                val w = s.words[word]
                listener.onWord(s.utterance, w.start, w.end)
            }
            if (played >= s.endFrame) {
                listener.onUtteranceDone(s.utterance)
                segments.removeFirst()
            } else {
                return
            }
        }
    }
}
