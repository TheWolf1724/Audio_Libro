package com.lectorvoz.tts.playback

import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.LowBatteryAction
import com.lectorvoz.core.model.PauseKind
import com.lectorvoz.core.model.PlaybackEvent
import com.lectorvoz.core.model.TtsEngineType
import com.lectorvoz.core.model.Utterance
import com.lectorvoz.core.model.VoiceSettings
import com.lectorvoz.tts.BookInfo
import com.lectorvoz.tts.PlaybackState
import com.lectorvoz.tts.SleepTimerMode
import com.lectorvoz.tts.UtteranceProvider
import com.lectorvoz.tts.engine.Pregenerator
import com.lectorvoz.tts.engine.SpeechEngine
import com.lectorvoz.tts.engine.SpeechEngineException
import com.lectorvoz.tts.engine.SpeechListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Maquina de estados de la lectura en voz alta, independiente de Media3 (se prueba sin
 * Android). Vive en el servicio; [scope] debe estar confinado al hilo principal.
 *
 * Estados: sin libro -> listo (pausado) -> preparando -> leyendo -> pausado / terminado.
 * Las notificaciones del motor llegan desde hilos de audio y solo tocan flujos seguros
 * entre hilos ([PlaybackHub]) y campos volatiles; las de una lectura ya cancelada se
 * descartan por numero de sesion.
 *
 * Ademas: rebobinado inteligente al reanudar, temporizador con fundido (minutos o fin de
 * capitulo) y gesto de agitar para alargarlo, bateria baja, medida de la velocidad
 * efectiva (tiempo restante), marcadores y preparacion de audio mientras carga.
 */
class ReadAloudController(
    private val scope: CoroutineScope,
    private val engineFactory: (TtsEngineType) -> SpeechEngine,
    private val focus: AudioFocus,
    private val hub: PlaybackHub = PlaybackHub(),
    /** Reloj monotono en ms (tiempo virtual en pruebas). */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Gesto de agitar (solo se registra en los ultimos 30 s del temporizador). */
    private val shake: ShakeSensor? = null,
) {
    val state: StateFlow<PlaybackState> = hub.state
    val events: SharedFlow<PlaybackEvent> = hub.events

    /** Libro abierto (para los metadatos de la sesion). */
    var book: BookInfo? = null
        private set

    private var provider: UtteranceProvider? = null
    private var startLocator: Locator = Locator.START
    @Volatile private var current: Utterance? = null
    @Volatile private var settings: VoiceSettings = hub.settings.value
    private val engines = HashMap<TtsEngineType, SpeechEngine>()
    private var activeEngine: SpeechEngine? = null
    private var speakJob: Job? = null
    @Volatile private var session = 0L

    /** Candado de [session] y [current]: el oyente del motor no pisa una navegacion. */
    private val sessionLock = Any()
    private var duck = 1f
    private var fade = 1f
    private var chapterFade = 1f
    private val nav = Mutex()

    // Fin de capitulo: frase -> capitulo que termina con ella.
    private val boundaries = ConcurrentHashMap<Long, Int>()
    @Volatile private var stopAtChapterEnd = false

    /** Capitulo cuyo final para la lectura (el que suena al programar o tras navegar). */
    @Volatile private var timerChapter = 0

    /**
     * Primera frase tras la frontera en la que se corto la sintesis por el temporizador de
     * fin de capitulo. La decision de parar se toma cuando termina de SONAR el capitulo
     * ([onSpeakFinished]): si entre medias se quito o cambio el temporizador, se sigue.
     */
    @Volatile private var pendingAfterStop: Utterance? = null
    @Volatile private var pendingChapter = -1
    private var chapterFadeJob: Job? = null

    // Rebobinado inteligente: instante de la ultima pausa con audio sonando.
    private var pausedAtMs: Long? = null

    // Velocidad efectiva.
    private val rate = RateMeter()
    @Volatile private var lastStarted: Utterance? = null
    @Volatile private var lastStartedAt = 0L
    @Volatile private var lastStartedSession = -1L
    @Volatile private var lengthChapter = -1

    // Bateria y preparacion en disco.
    private var battery = BatteryStatus.UNKNOWN
    // Regimen de bateria baja con histeresis (ver BatteryStatus.isLowWithHysteresis).
    private var batteryLow = false
    private var prepareJob: Job? = null

    private val sleepTimer = SleepTimer(
        scope = scope,
        onTick = { ms -> hub.mutableState.update { it.copy(sleepTimerRemainingMs = ms) } },
        onFade = { f ->
            fade = f
            applyVolume()
        },
        onExpire = {
            pause()
            hub.mutableState.update { it.copy(sleepTimer = null, sleepTimerRemainingMs = null) }
        },
        onWindow = ::setShakeListening,
    )

    /** Ajustes vigentes. */
    val currentSettings: VoiceSettings get() = settings

    /** Abre [book] para leer desde [start]; para la lectura anterior. */
    fun open(book: BookInfo, provider: UtteranceProvider, start: Locator) {
        pause() // tambien suelta el foco de audio
        pausedAtMs = null
        this.book = book
        this.provider = provider
        startLocator = start
        current = null
        pendingAfterStop = null
        boundaries.clear()
        lengthChapter = -1
        engines.values.forEach { it.setBook(book.id) }
        hub.mutableState.update {
            PlaybackState(
                bookId = book.id,
                position = start,
                engine = settings.engine,
                voiceId = settings.voiceId,
                sleepTimer = it.sleepTimer,
                sleepTimerRemainingMs = it.sleepTimerRemainingMs,
                charsPerSecond = rate.charsPerSecond(),
            )
        }
        fetchChapterLength(start.chapter)
        prepareJob?.cancel()
        prepareJob = null
        updatePreparer()
    }

    /** Empieza o reanuda desde la frase recordada. */
    fun play() {
        if (provider == null) return
        if (speakJob?.isActive == true) return
        // Foco perdido temporalmente (llamada): ya se reanudara sola al recuperarlo.
        if (state.value.focusSuppressed) return
        if (!focus.request(::onFocusChange)) {
            fail("No se pudo obtener el foco de audio", null)
            return
        }
        if (state.value.ended) {
            current = null
            startLocator = Locator.START
            pausedAtMs = null
        }
        startSpeaking()
    }

    /** Pausa (corta el audio; al reanudar se repite la frase actual desde su inicio). */
    fun pause() {
        stopSpeaking()
        focus.abandon()
    }

    /** Para y libera el foco; el libro y la posicion se conservan. */
    fun stop() {
        pause()
        setSleepTimer(null)
    }

    /**
     * Para, olvida el libro y deja el estado vacio (al borrar de la biblioteca el libro que
     * suena): sin fuente ni posicion, `bookId` a null y notificacion limpia. El servicio ya
     * libera el primer plano por su cuenta.
     */
    fun clear() {
        stop()
        book = null
        provider = null
        current = null
        startLocator = Locator.START
        pendingAfterStop = null
        pendingChapter = -1
        boundaries.clear()
        lengthChapter = -1
        pausedAtMs = null
        prepareJob?.cancel()
        prepareJob = null
        hub.pendingBook = null
        hub.mutableState.value = PlaybackState()
    }

    /** Alterna reproducir/pausa (toque simple del auricular). */
    fun togglePlayPause() {
        if (state.value.playWhenReady) pause() else play()
    }

    fun nextSentence() = navigate { p, cur -> cur?.let { p.next(it) } }

    fun previousSentence() = navigate { p, cur -> cur?.let { p.previous(it) } }

    fun nextChapter() = navigate { p, cur ->
        val ch = cur?.range?.start?.chapter ?: startLocator.chapter
        if (ch + 1 < p.chapterCount) p.at(Locator(ch + 1, 0)) else null
    }

    /** Al principio del capitulo actual o, si ya se esta en el, al anterior. */
    fun previousChapter() = navigate { p, cur ->
        val ch = cur?.range?.start?.chapter ?: startLocator.chapter
        val chapterStart = p.at(Locator(ch, 0))
        // Ya en el inicio: la misma frase (id estable) o una que no empieza despues.
        val atStart = cur == null || chapterStart == null || chapterStart.id == cur.id ||
            cur.range.start <= chapterStart.range.start
        if (!atStart) {
            chapterStart
        } else if (ch > 0) {
            p.at(Locator(ch - 1, 0))
        } else {
            chapterStart
        }
    }

    /** Salta a la frase de [locator] (tocar el texto en el lector). */
    fun seekTo(locator: Locator) = navigate { p, _ -> p.at(locator) }

    /** Pide un marcador en la frase actual (boton "Marcar"); lo guarda `:app`. */
    fun requestBookmark() {
        val loc = current?.range?.start ?: state.value.position ?: return
        hub.requestBookmark(loc)
    }

    /** Aplica ajustes; si cambia algo audible mientras suena, se reinicia la frase actual. */
    fun updateSettings(new: VoiceSettings) {
        val old = settings
        if (old == new) return
        settings = new
        activeEngine?.updateSettings(new)
        hub.mutableState.update { it.copy(engine = new.engine, voiceId = new.voiceId) }
        if (old.speed != new.speed || old.voiceId != new.voiceId || old.engine != new.engine) {
            rate.reset()
            lastStarted = null
            hub.mutableState.update { it.copy(charsPerSecond = null) }
        }
        val audible = audibleKey(old) != audibleKey(new) ||
            (old.pitch != new.pitch && activeEngine?.type == TtsEngineType.SYSTEM)
        if (audible && speakJob?.isActive == true) startSpeaking()
        updatePreparer()
    }

    /**
     * Velocidad y tono pedidos desde Media3 (mando externo, reloj, Android Auto): se aplican
     * y se escriben en `PlaybackHub.settings`, para que la UI los vea y `:app` los guarde.
     */
    fun setPlaybackParameters(speed: Float, pitch: Float) {
        hub.settings.update { it.copy(speed = speed, pitch = pitch) }
        updateSettings(settings.copy(speed = speed, pitch = pitch))
    }

    /**
     * Programa (o quita con nulo) el temporizador de apagado. Quitarlo o cambiarlo anula una
     * parada de fin de capitulo pendiente: la lectura sigue sin cortes.
     */
    fun setSleepTimer(mode: SleepTimerMode?) {
        sleepTimer.cancel()
        stopAtChapterEnd = false
        cancelChapterFade()
        when (mode) {
            null -> Unit
            is SleepTimerMode.Minutes -> {
                sleepTimer.start(mode.minutes * 60_000L)
                // Sin lectura en marcha la cuenta espera a que se reanude.
                if (!state.value.playWhenReady) sleepTimer.pause()
            }
            SleepTimerMode.EndOfChapter -> {
                timerChapter = current?.range?.start?.chapter ?: startLocator.chapter
                stopAtChapterEnd = true
            }
        }
        hub.mutableState.update {
            it.copy(
                sleepTimer = mode,
                sleepTimerRemainingMs = (mode as? SleepTimerMode.Minutes)?.let { m -> m.minutes * 60_000L },
            )
        }
    }

    /**
     * Gesto de agitar: alarga el temporizador por minutos 10 min; durante el fundido de
     * "fin de capitulo", lo cambia por 10 min y sigue leyendo.
     */
    fun onShake() {
        if (!settings.shakeToExtendTimer) return
        when {
            sleepTimer.isRunning -> sleepTimer.extend(SleepTimer.SHAKE_EXTENSION_MS)
            // Al cambiar el temporizador, la parada pendiente queda anulada (ver onSpeakFinished).
            chapterFadeJob?.isActive == true ->
                setSleepTimer(SleepTimerMode.Minutes((SleepTimer.SHAKE_EXTENSION_MS / 60_000).toInt()))
        }
    }

    /**
     * Cambio de foco de audio (lo llama [AudioFocus]). La perdida temporal (llamada) es una
     * supresion, no una pausa: se para el motor pero `playWhenReady` sigue a true y el estado
     * marca `focusSuppressed`; asi Media3 mantiene el servicio en primer plano aunque la
     * llamada dure mas de 10 min y al recuperar el foco se reanuda sin arrancar desde
     * segundo plano.
     */
    fun onFocusChange(change: FocusChange) {
        when (change) {
            FocusChange.LOSS -> pause()
            FocusChange.LOSS_TRANSIENT -> {
                val s = state.value
                if (s.playWhenReady && !s.focusSuppressed) {
                    stopSpeaking(suppress = true)
                }
            }
            FocusChange.DUCK -> {
                duck = DUCK_VOLUME
                applyVolume()
            }
            FocusChange.GAIN -> {
                duck = 1f
                applyVolume()
                if (state.value.focusSuppressed) {
                    hub.mutableState.update { it.copy(focusSuppressed = false) }
                    startSpeaking()
                }
            }
        }
    }

    /**
     * Nuevo estado de bateria (lo llama el servicio desde [BatteryMonitor]). Con bateria
     * baja y voz neuronal sonando: aviso o paso a la voz del sistema segun `lowBattery`.
     * Cargar activa o para la preparacion de audio en disco.
     */
    fun onBatteryChanged(status: BatteryStatus) {
        val old = battery
        battery = status
        val low = status.isLowWithHysteresis(batteryLow)
        batteryLow = low
        val s = state.value
        if (low && settings.engine == TtsEngineType.NEURAL && settings.lowBattery != LowBatteryAction.NONE) {
            val switch = settings.lowBattery == LowBatteryAction.SWITCH_TO_SYSTEM
            hub.mutableState.update { it.copy(lowBattery = true, switchedToSystemForBattery = switch) }
            if (switch && s.playWhenReady && !s.focusSuppressed && activeEngine?.type == TtsEngineType.NEURAL) startSpeaking()
        } else if (!low && (s.lowBattery || s.switchedToSystemForBattery)) {
            // Al recuperar la bateria (p. ej. al enchufar), si se habia cambiado a la voz del
            // sistema por bateria y el ajuste pide neuronal, se vuelve a la voz neuronal en la
            // frase actual; si no, el estado diria "sin aviso" mientras se sigue oyendo la del
            // sistema (simetrico con la rama de bateria baja).
            val backToNeural = s.switchedToSystemForBattery && settings.engine == TtsEngineType.NEURAL &&
                s.playWhenReady && !s.focusSuppressed && activeEngine?.type == TtsEngineType.SYSTEM
            hub.mutableState.update { it.copy(lowBattery = false, switchedToSystemForBattery = false) }
            if (backToNeural) startSpeaking()
        }
        if (old.charging != status.charging) updatePreparer()
    }

    /** Libera motores y temporizador (al destruir el servicio). */
    fun release() {
        stop()
        prepareJob?.cancel()
        shake?.stop()
        engines.values.forEach { it.release() }
        engines.clear()
        activeEngine = null
    }

    // --- Interno ---

    private fun startSpeaking() {
        val p = provider ?: return
        val previous = speakJob
        previous?.cancel()
        val mySession = synchronized(sessionLock) { ++session }
        pendingAfterStop = null
        sleepTimer.resume()
        hub.mutableState.update {
            it.copy(playWhenReady = true, isPreparing = true, ended = false, error = null, focusSuppressed = false)
        }
        speakJob = scope.launch {
            previous?.cancelAndJoin()
            try {
                val remembered = current ?: p.at(startLocator)
                if (remembered == null) {
                    onBookEnded()
                    return@launch
                }
                val start = smartRewind(p, remembered)
                current = start
                publishPosition(start)
                val engine = prepareEngine()
                engine.setVolume(volume())
                hub.mutableState.update {
                    it.copy(isPreparing = false, isPlaying = true, engine = engine.type)
                }
                engine.speak(start, nextFn(p, mySession), Listener(mySession))
                if (session == mySession) onSpeakFinished()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (session == mySession) {
                    fail(e.message ?: "Error de voz", e)
                    focus.abandon()
                }
            }
        }
    }

    /**
     * Rebobinado inteligente al reanudar tras una pausa: > 2 min repite 1 frase, > 30 min
     * 2 frases, > 3 h vuelve al inicio del parrafo.
     */
    private suspend fun smartRewind(p: UtteranceProvider, u: Utterance): Utterance {
        val paused = pausedAtMs ?: return u
        pausedAtMs = null
        if (!settings.smartRewind) return u
        val elapsed = clock() - paused
        return when {
            elapsed > REWIND_PARAGRAPH_MS -> paragraphStart(p, u)
            elapsed > REWIND_TWO_MS -> back(p, u, 2)
            elapsed > REWIND_ONE_MS -> back(p, u, 1)
            else -> u
        }
    }

    private suspend fun back(p: UtteranceProvider, u: Utterance, n: Int): Utterance {
        var cur = u
        repeat(n) { cur = p.previous(cur) ?: return cur }
        return cur
    }

    /** Primera frase del parrafo de [u] (tras una pausa de parrafo o mas, sin cambiar de capitulo). */
    private suspend fun paragraphStart(p: UtteranceProvider, u: Utterance): Utterance {
        var cur = u
        repeat(MAX_PARAGRAPH_SENTENCES) {
            val prev = p.previous(cur) ?: return cur
            if (prev.pauseAfter >= PauseKind.PARAGRAPH || prev.range.start.chapter != cur.range.start.chapter) return cur
            cur = prev
        }
        return cur
    }

    /** Prepara el motor pedido; si la voz neuronal falla o la bateria lo pide, la del sistema. */
    private suspend fun prepareEngine(): SpeechEngine {
        val s = settings
        val lowSwitch = s.engine == TtsEngineType.NEURAL && batteryLow &&
            s.lowBattery == LowBatteryAction.SWITCH_TO_SYSTEM
        val wanted = if (lowSwitch) TtsEngineType.SYSTEM else s.engine
        if (lowSwitch) hub.mutableState.update { it.copy(lowBattery = true, switchedToSystemForBattery = true) }
        val engine = engineFor(wanted)
        return try {
            engine.prepare(if (lowSwitch) s.copy(engine = TtsEngineType.SYSTEM, voiceId = null) else s)
            engine
        } catch (e: SpeechEngineException) {
            if (wanted != TtsEngineType.NEURAL) throw e
            hub.mutableEvents.tryEmit(PlaybackEvent.Error("${e.message}. Se usa la voz del sistema.", null, e))
            val fallback = engineFor(TtsEngineType.SYSTEM)
            fallback.prepare(s.copy(engine = TtsEngineType.SYSTEM, voiceId = null))
            fallback
        }.also { activeEngine = it }
    }

    private fun engineFor(type: TtsEngineType): SpeechEngine = engines.getOrPut(type) {
        engineFactory(type).also { it.setBook(book?.id) }
    }

    /**
     * Para el motor. Con [suppress] (perdida temporal del foco) `playWhenReady` sigue a true
     * y se marca `focusSuppressed`; si no, queda en pausa.
     */
    private fun stopSpeaking(suppress: Boolean = false) {
        val s = state.value
        if (s.playWhenReady && !s.focusSuppressed) pausedAtMs = clock()
        synchronized(sessionLock) { session++ }
        speakJob?.cancel()
        lastStarted = null
        pendingAfterStop = null
        cancelChapterFade()
        // La cuenta del temporizador no corre si no suena: tambien durante una llamada (foco
        // transitorio, suppress=true); al recuperar el foco, startSpeaking la reanuda.
        sleepTimer.pause()
        hub.mutableState.update {
            if (suppress) {
                it.copy(isPlaying = false, isPreparing = false, focusSuppressed = true)
            } else {
                it.copy(playWhenReady = false, isPlaying = false, isPreparing = false, focusSuppressed = false)
            }
        }
    }

    private fun navigate(target: suspend (UtteranceProvider, Utterance?) -> Utterance?) {
        val p = provider ?: return
        scope.launch {
            nav.withLock {
                val cur = current ?: p.at(startLocator)
                val t = target(p, cur) ?: return@withLock
                val st = state.value
                val playing = st.playWhenReady && !st.focusSuppressed
                synchronized(sessionLock) {
                    if (playing) session++
                    current = t
                }
                if (playing) speakJob?.cancel()
                pendingAfterStop = null
                // "Fin de capitulo" pasa a ser el final del capitulo al que se salta.
                if (stopAtChapterEnd) timerChapter = t.range.start.chapter
                pausedAtMs = null
                lastStarted = null
                publishPosition(t)
                hub.mutableEvents.tryEmit(PlaybackEvent.UtteranceStarted(t.id, t.range))
                if (playing) startSpeaking()
            }
        }
    }

    /**
     * `next` que marca fronteras de capitulo. Con temporizador de fin de capitulo deja de
     * sintetizar en la frontera (no se encola audio del capitulo siguiente); si se para o no
     * se decide al terminar de sonar ([onSpeakFinished]).
     */
    private fun nextFn(p: UtteranceProvider, mySession: Long): suspend (Utterance) -> Utterance? = { u ->
        val ch = u.range.start.chapter
        val n = p.next(u)
        when {
            n == null -> {
                boundaries[u.id] = ch
                null
            }
            n.range.start.chapter != ch -> {
                boundaries[u.id] = ch
                if (stopAtChapterEnd && ch >= timerChapter && session == mySession) {
                    pendingChapter = ch
                    pendingAfterStop = n
                    null
                } else {
                    n
                }
            }
            else -> n
        }
    }

    /** Termino de sonar todo lo encolado: fin del libro o frontera del temporizador. */
    private fun onSpeakFinished() {
        val after = pendingAfterStop ?: return onBookEnded()
        pendingAfterStop = null
        synchronized(sessionLock) { current = after }
        publishPosition(after)
        cancelChapterFade()
        if (!stopAtChapterEnd || pendingChapter < timerChapter) {
            // Se quito o cambio el temporizador (o se agito el movil) mientras sonaba el final
            // del capitulo: se sigue leyendo.
            startSpeaking()
            return
        }
        // Temporizador "fin de capitulo": queda pausado al principio del siguiente.
        stopAtChapterEnd = false
        hub.mutableState.update {
            it.copy(playWhenReady = false, isPlaying = false, sleepTimer = null, sleepTimerRemainingMs = null)
        }
        focus.abandon()
    }

    private fun onBookEnded() {
        hub.mutableState.update { it.copy(playWhenReady = false, isPlaying = false, isPreparing = false, ended = true) }
        focus.abandon()
    }

    private fun publishPosition(u: Utterance) {
        hub.mutableState.update { it.copy(position = u.range.start, utteranceId = u.id) }
        fetchChapterLength(u.range.start.chapter)
    }

    private fun fetchChapterLength(chapter: Int) {
        if (chapter == lengthChapter) return
        lengthChapter = chapter
        val p = provider ?: return
        scope.launch {
            val len = try {
                p.chapterLength(chapter)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (lengthChapter == chapter) hub.mutableState.update { it.copy(chapterLength = len) }
        }
    }

    private fun fail(message: String, cause: Throwable?) {
        hub.mutableEvents.tryEmit(PlaybackEvent.Error(message, current?.id, cause))
        hub.mutableState.update {
            it.copy(playWhenReady = false, isPlaying = false, isPreparing = false, error = message)
        }
    }

    private fun volume() = duck * fade * chapterFade

    private fun applyVolume() {
        activeEngine?.setVolume(volume())
    }

    // --- Temporizador: agitar y fundido de fin de capitulo ---

    private fun setShakeListening(on: Boolean) {
        if (on && settings.shakeToExtendTimer) shake?.start(::onShake) else shake?.stop()
    }

    /** Si [u] es la ultima frase del capitulo del temporizador, funde el volumen (hilo principal). */
    private suspend fun maybeStartChapterFade(u: Utterance, mySession: Long) {
        if (!stopAtChapterEnd || u.range.start.chapter < timerChapter) return
        val p = provider ?: return
        val n = p.next(u)
        if (session != mySession || !stopAtChapterEnd) return
        if (n != null && n.range.start.chapter == u.range.start.chapter) return
        val cps = rate.charsPerSecond() ?: DEFAULT_CPS
        val durationMs = (u.displayText.length / cps * 1000).toLong().coerceIn(MIN_CHAPTER_FADE_MS, SleepTimer.FADE_MS)
        cancelChapterFade()
        setShakeListening(true)
        chapterFadeJob = scope.launch {
            var elapsed = 0L
            while (isActive && elapsed < durationMs) {
                delay(FADE_STEP_MS)
                elapsed += FADE_STEP_MS
                chapterFade = 1f - (1f - CHAPTER_FADE_FLOOR) * (elapsed.toFloat() / durationMs).coerceAtMost(1f)
                applyVolume()
            }
        }
    }

    private fun cancelChapterFade() {
        val wasFading = chapterFadeJob != null
        chapterFadeJob?.cancel()
        chapterFadeJob = null
        if (chapterFade != 1f) {
            chapterFade = 1f
            applyVolume()
        }
        if (wasFading && !sleepTimer.isRunning) shake?.stop()
    }

    // --- Preparar audio mientras carga ---

    private fun updatePreparer() {
        val p = provider
        val want = settings.prepareWhileCharging && battery.charging && p != null &&
            settings.engine == TtsEngineType.NEURAL
        if (!want) {
            prepareJob?.cancel()
            prepareJob = null
            return
        }
        if (prepareJob?.isActive == true) return
        val engine = engineFor(TtsEngineType.NEURAL) as? Pregenerator ?: return
        prepareJob = scope.launch {
            hub.mutableState.update { it.copy(preparingCache = true) }
            engine.setWriteThrough(true)
            try {
                var u = current ?: p.at(startLocator) ?: return@launch
                val lastChapter = u.range.start.chapter + PREPARE_CHAPTERS
                while (isActive && engine.canPregenerate) {
                    engine.pregenerate(u, settings)
                    u = p.next(u) ?: break
                    if (u.range.start.chapter > lastChapter) break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Sin modelo o sin espacio: se deja para la proxima vez que cargue.
            } finally {
                engine.setWriteThrough(false)
                hub.mutableState.update { it.copy(preparingCache = false) }
            }
        }
    }

    /** Traduce notificaciones del motor; ignora las de sesiones anteriores. */
    private inner class Listener(private val mySession: Long) : SpeechListener {
        override fun onUtteranceStart(utterance: Utterance) {
            synchronized(sessionLock) {
                if (session != mySession) return
                current = utterance
            }
            measure(utterance)
            publishPosition(utterance)
            hub.mutableEvents.tryEmit(PlaybackEvent.UtteranceStarted(utterance.id, utterance.range))
            if (stopAtChapterEnd && utterance.range.start.chapter > timerChapter) {
                // La frontera se pidio antes de programar el temporizador: parar aqui.
                scope.launch {
                    if (session != mySession) return@launch
                    pause()
                    pausedAtMs = null // sin rebobinado al capitulo anterior
                    hub.mutableState.update { it.copy(sleepTimer = null, sleepTimerRemainingMs = null) }
                    stopAtChapterEnd = false
                }
            } else if (stopAtChapterEnd) {
                scope.launch { maybeStartChapterFade(utterance, mySession) }
            }
        }

        override fun onWord(utterance: Utterance, spokenStart: Int, spokenEnd: Int) {
            if (session != mySession) return
            val (s, e) = displayRange(utterance, spokenStart, spokenEnd)
            hub.mutableEvents.tryEmit(PlaybackEvent.WordRange(utterance.id, s, e))
        }

        override fun onUtteranceDone(utterance: Utterance) {
            if (session != mySession) return
            hub.mutableEvents.tryEmit(PlaybackEvent.UtteranceDone(utterance.id))
            boundaries.remove(utterance.id)?.let { hub.mutableEvents.tryEmit(PlaybackEvent.ChapterFinished(it)) }
        }

        /** Mide caracteres por segundo de inicio a inicio de frases seguidas. */
        private fun measure(u: Utterance) {
            val now = clock()
            val prev = lastStarted
            if (prev != null && lastStartedSession == mySession && prev.id != u.id) {
                rate.add(prev.displayText.length, now - lastStartedAt)
                val cps = rate.charsPerSecond()
                // Solo si cambia de verdad (> 5 %): cada cambio de estado rehace el estado de
                // Media3 y su notificacion (IPC con SystemUI), tambien con la pantalla apagada.
                val old = hub.mutableState.value.charsPerSecond
                if (cps != null && (old == null || kotlin.math.abs(cps - old) > old * CPS_PUBLISH_DELTA)) {
                    hub.mutableState.update { it.copy(charsPerSecond = cps) }
                }
            }
            lastStarted = u
            lastStartedAt = now
            lastStartedSession = mySession
        }
    }

    companion object {
        /** Cambio relativo minimo de la velocidad medida para publicarla (ver `measure`). */
        private const val CPS_PUBLISH_DELTA = 0.05f

        /** Volumen al ceder el foco con atenuacion. */
        const val DUCK_VOLUME = 0.2f

        const val REWIND_ONE_MS = 2 * 60_000L
        const val REWIND_TWO_MS = 30 * 60_000L
        const val REWIND_PARAGRAPH_MS = 3 * 60 * 60_000L
        private const val MAX_PARAGRAPH_SENTENCES = 200

        /** Capitulos por delante que se preparan en disco mientras carga. */
        const val PREPARE_CHAPTERS = 2

        private const val DEFAULT_CPS = 14f
        private const val MIN_CHAPTER_FADE_MS = 2_000L
        private const val FADE_STEP_MS = 200L
        private const val CHAPTER_FADE_FLOOR = 0.15f

        /** Ajustes que cambian lo que se oye (reiniciar la frase si cambian sonando). */
        private fun audibleKey(s: VoiceSettings): List<Any?> = listOf(
            s.engine, s.voiceId, s.dialogVoiceId, s.speed, s.quality, s.readingLanguage,
            s.punctuationPause, s.pauseClauseMs, s.pauseSentenceMs, s.pauseVerseMs,
            s.pauseParagraphMs, s.pauseSectionMs, s.pauseChapterMs, s.adaptivePace,
            s.loudnessNormalize, s.voiceEnhance, s.nightMode,
        )

        /** Convierte un rango de `spokenText` a `displayText` con `spokenToDisplay`. */
        fun displayRange(u: Utterance, spokenStart: Int, spokenEnd: Int): Pair<Int, Int> {
            val map = u.spokenToDisplay
            val len = u.displayText.length
            if (map.isEmpty()) return spokenStart.coerceIn(0, len) to spokenEnd.coerceIn(0, len)
            val s = map.getOrElse(spokenStart.coerceIn(0, map.size - 1)) { 0 }.coerceIn(0, len)
            val lastSpoken = (spokenEnd - 1).coerceIn(0, map.size - 1)
            val e = (map[lastSpoken] + 1).coerceIn(s, len)
            return s to e
        }
    }
}
