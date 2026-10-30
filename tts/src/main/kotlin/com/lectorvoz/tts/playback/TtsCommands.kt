package com.lectorvoz.tts.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand
import com.lectorvoz.core.model.Locator
import com.lectorvoz.tts.SleepTimerMode

/** Comandos de sesion propios (MediaController.sendCustomCommand y botones). */
object TtsCommands {
    const val ACTION_OPEN_BOOK = "com.lectorvoz.tts.ABRIR_LIBRO"
    const val ACTION_NEXT_CHAPTER = "com.lectorvoz.tts.CAPITULO_SIGUIENTE"
    const val ACTION_PREVIOUS_CHAPTER = "com.lectorvoz.tts.CAPITULO_ANTERIOR"
    const val ACTION_SEEK_TO = "com.lectorvoz.tts.IR_A"
    const val ACTION_SLEEP_TIMER = "com.lectorvoz.tts.TEMPORIZADOR"

    /** Sin argumentos rota apagado -> 15 -> 30 -> 60 min -> fin de capitulo -> apagado. */
    const val ACTION_SLEEP_TIMER_CYCLE = "com.lectorvoz.tts.TEMPORIZADOR_CICLO"

    /** Marcador en la frase actual (llega a :app por `PlaybackHub.bookmarkRequests`). */
    const val ACTION_BOOKMARK = "com.lectorvoz.tts.MARCAR"

    /** Para la lectura, olvida el libro y limpia la notificacion (al borrar el libro que suena). */
    const val ACTION_STOP = "com.lectorvoz.tts.PARAR"

    const val EXTRA_CHAPTER = "capitulo"
    const val EXTRA_OFFSET = "desplazamiento"
    const val EXTRA_MINUTES = "minutos"
    const val EXTRA_END_OF_CHAPTER = "fin_capitulo"

    val OPEN_BOOK = SessionCommand(ACTION_OPEN_BOOK, Bundle.EMPTY)
    val NEXT_CHAPTER = SessionCommand(ACTION_NEXT_CHAPTER, Bundle.EMPTY)
    val PREVIOUS_CHAPTER = SessionCommand(ACTION_PREVIOUS_CHAPTER, Bundle.EMPTY)
    val SEEK_TO = SessionCommand(ACTION_SEEK_TO, Bundle.EMPTY)
    val SLEEP_TIMER = SessionCommand(ACTION_SLEEP_TIMER, Bundle.EMPTY)
    val SLEEP_TIMER_CYCLE = SessionCommand(ACTION_SLEEP_TIMER_CYCLE, Bundle.EMPTY)
    val BOOKMARK = SessionCommand(ACTION_BOOKMARK, Bundle.EMPTY)
    val STOP = SessionCommand(ACTION_STOP, Bundle.EMPTY)

    /** Todos los comandos propios. */
    val ALL = listOf(OPEN_BOOK, NEXT_CHAPTER, PREVIOUS_CHAPTER, SEEK_TO, SLEEP_TIMER, SLEEP_TIMER_CYCLE, BOOKMARK, STOP)

    /** Argumentos de [ACTION_SEEK_TO]. */
    fun seekArgs(locator: Locator) = Bundle().apply {
        putInt(EXTRA_CHAPTER, locator.chapter)
        putInt(EXTRA_OFFSET, locator.offset)
    }

    /** Lee [seekArgs]; nulo si faltan. */
    fun readSeek(args: Bundle): Locator? {
        val ch = args.getInt(EXTRA_CHAPTER, -1)
        val off = args.getInt(EXTRA_OFFSET, -1)
        return if (ch >= 0 && off >= 0) Locator(ch, off) else null
    }

    /** Argumentos de [ACTION_SLEEP_TIMER]; nulo = quitar temporizador. */
    fun timerArgs(mode: SleepTimerMode?) = Bundle().apply {
        when (mode) {
            is SleepTimerMode.Minutes -> putInt(EXTRA_MINUTES, mode.minutes)
            SleepTimerMode.EndOfChapter -> putBoolean(EXTRA_END_OF_CHAPTER, true)
            null -> Unit
        }
    }

    /** Lee [timerArgs]. */
    fun readTimer(args: Bundle): SleepTimerMode? = when {
        args.getBoolean(EXTRA_END_OF_CHAPTER, false) -> SleepTimerMode.EndOfChapter
        args.getInt(EXTRA_MINUTES, 0) > 0 -> SleepTimerMode.Minutes(args.getInt(EXTRA_MINUTES))
        else -> null
    }
}

/** Quien puede usar los comandos propios de [TtsCommands] (sin Android: se prueba en JVM). */
object ControllerTrust {
    /**
     * true si un controlador puede usar los comandos propios: la propia app
     * ([ownPackage]), el de la notificacion multimedia, uno de confianza del sistema
     * (`ControllerInfo.isTrusted`: SystemUI, Bluetooth, apps con MEDIA_CONTENT_CONTROL) o el
     * de Android Auto / Automotive.
     */
    fun isTrusted(
        ownPackage: String,
        controllerPackage: String,
        isNotification: Boolean,
        isSystemTrusted: Boolean,
        isCar: Boolean,
    ): Boolean = controllerPackage == ownPackage || isNotification || isSystemTrusted || isCar
}
