package com.lectorvoz.tts.playback

import com.lectorvoz.tts.SleepTimerMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Cuenta atras del temporizador de apagado por minutos, con fundido de volumen en los
 * ultimos [fadeMs]. Usa `delay` (tiempo virtual en pruebas). Vive en el ambito del
 * servicio (confinado al hilo principal): sigue contando con la pantalla apagada mientras
 * el servicio exista.
 *
 * En los ultimos [windowMs] avisa por [onWindow] (para escuchar el gesto de agitar solo
 * entonces) y [extend] alarga la cuenta (agitar = +10 min). [pause] y [resume] congelan la
 * cuenta mientras la lectura esta en pausa.
 */
class SleepTimer(
    private val scope: CoroutineScope,
    /** Restante en ms; se llama cada segundo (y en cada paso del fundido). */
    private val onTick: (remainingMs: Long) -> Unit,
    /** Factor de volumen 0..1 del fundido (1 = sin fundido). */
    private val onFade: (factor: Float) -> Unit,
    /** Vencio: pausar. */
    private val onExpire: () -> Unit,
    /** Entra (true) o sale (false) de los ultimos [windowMs]. */
    private val onWindow: (Boolean) -> Unit = {},
    private val fadeMs: Long = FADE_MS,
    private val windowMs: Long = WINDOW_MS,
) {
    private var job: Job? = null
    private var remaining = 0L
    private var inWindow = false

    /** Cuenta congelada por [pause] (se reanuda con [resume]). */
    var isPaused: Boolean = false
        private set

    /** true si hay una cuenta atras en marcha. */
    val isRunning: Boolean get() = job?.isActive == true

    /** Milisegundos restantes (0 si no hay cuenta atras). */
    val remainingMs: Long get() = if (isRunning) remaining else 0L

    /** Empieza (o reinicia) la cuenta atras de [durationMs]. */
    fun start(durationMs: Long) {
        require(durationMs > 0) { "durationMs debe ser positivo" }
        cancel()
        launchCountdown(durationMs)
    }

    /** Congela la cuenta (lectura en pausa) y restaura el volumen; [resume] la continua. */
    fun pause() {
        if (!isRunning || remaining <= 0) return
        job?.cancel()
        job = null
        isPaused = true
        setWindow(false)
        onFade(1f)
    }

    /** Continua una cuenta congelada con [pause]. */
    fun resume() {
        if (!isPaused) return
        isPaused = false
        if (remaining > 0) launchCountdown(remaining)
    }

    private fun launchCountdown(durationMs: Long) {
        remaining = durationMs
        job = scope.launch {
            onTick(remaining)
            updateWindow()
            while (remaining > 0) {
                val inFade = remaining <= fadeMs
                val step = minOf(if (inFade) FADE_STEP_MS else TICK_MS, remaining)
                // Al entrar en la zona de fundido, el paso se ajusta para caer justo en ella.
                val untilFade = remaining - fadeMs
                val realStep = if (!inFade && untilFade in 1 until step) untilFade else step
                delay(realStep)
                remaining -= realStep
                onTick(remaining)
                updateWindow()
                if (remaining <= fadeMs) onFade(remaining.toFloat() / fadeMs)
            }
            setWindow(false)
            onExpire()
            onFade(1f)
        }
    }

    /** Alarga la cuenta en [ms] y quita el fundido. No hace nada si no hay cuenta. */
    fun extend(ms: Long) {
        if (!isRunning) return
        remaining += ms
        onFade(1f)
        onTick(remaining)
        updateWindow()
    }

    /** Cancela la cuenta atras y restaura el volumen. */
    fun cancel() {
        val wasRunning = job?.isActive == true
        job?.cancel()
        job = null
        isPaused = false
        setWindow(false)
        if (wasRunning) onFade(1f)
    }

    private fun updateWindow() = setWindow(remaining in 1..windowMs)

    private fun setWindow(on: Boolean) {
        if (on == inWindow) return
        inWindow = on
        onWindow(on)
    }

    companion object {
        /** Ciclo del boton de la notificacion: apagado -> 15 -> 30 -> 60 min -> fin de capitulo. */
        fun nextInCycle(current: SleepTimerMode?): SleepTimerMode? = when (current) {
            null -> SleepTimerMode.Minutes(15)
            is SleepTimerMode.Minutes -> when {
                current.minutes < 30 -> SleepTimerMode.Minutes(30)
                current.minutes < 60 -> SleepTimerMode.Minutes(60)
                else -> SleepTimerMode.EndOfChapter
            }
            SleepTimerMode.EndOfChapter -> null
        }

        /** Duracion del fundido final. */
        const val FADE_MS = 10_000L

        /** Ultimos 30 s: se escucha el gesto de agitar. */
        const val WINDOW_MS = 30_000L

        /** Lo que alarga agitar el movil. */
        const val SHAKE_EXTENSION_MS = 10 * 60_000L

        private const val TICK_MS = 1_000L
        private const val FADE_STEP_MS = 200L
    }
}
