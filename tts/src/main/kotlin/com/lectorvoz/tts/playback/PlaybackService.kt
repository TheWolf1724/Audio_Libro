package com.lectorvoz.tts.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.lectorvoz.core.model.TtsEngineType
import com.lectorvoz.tts.R
import com.lectorvoz.tts.cache.AudioDiskCache
import com.lectorvoz.tts.engine.SpeechEngine
import com.lectorvoz.tts.engine.SystemTtsEngine
import com.lectorvoz.tts.neural.NeuralTtsEngine
import com.lectorvoz.tts.neural.SherpaSynthesizerLoader
import com.lectorvoz.tts.voice.VoiceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Configuracion que la app da al modulo de voz (antes de conectar). */
object TtsConfig {
    /**
     * Intent de la actividad lectora para el toque en la notificacion. Nulo = actividad de
     * arranque de la app.
     */
    @Volatile var sessionActivityIntent: ((Context) -> Intent)? = null

    /** Hilos de inferencia de la voz neuronal. */
    @Volatile var neuralThreads: Int = 2

    /** Limite de la cache de audio en disco (preparar mientras carga), en bytes. */
    @Volatile var audioCacheMaxBytes: Long = AudioDiskCache.DEFAULT_MAX_BYTES
}

/**
 * Servicio en primer plano (`mediaPlayback`) que lee en voz alta con la pantalla apagada.
 * Media3 publica la notificacion multimedia y gestiona el primer plano; aqui se anaden el
 * foco de audio, "auriculares desconectados", el WakeLock, los comandos propios (capitulos,
 * temporizador, "Marcar"), el doble toque del auricular, el gesto de agitar, la bateria y
 * la cache de audio en disco.
 *
 * Android 17 (targetSdk 37): la reproduccion debe arrancar desde una interaccion del
 * usuario (UI visible, notificacion o boton multimedia); no se reanuda solo desde segundo
 * plano.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val hub = PlaybackHub.default
    private lateinit var controller: ReadAloudController
    private lateinit var player: TtsPlayer
    private lateinit var wakeLock: WakeLockHolder
    private var session: MediaSession? = null
    private var noisyRegistered = false
    private lateinit var battery: AndroidBatteryMonitor
    private lateinit var headsetTaps: DoubleTapDetector
    private lateinit var neural: NeuralTtsEngine

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) controller.pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val store = VoiceStore(this)
        val diskCache = AudioDiskCache(File(cacheDir, "audio-voz"), TtsConfig.audioCacheMaxBytes)
        neural = NeuralTtsEngine(
            loader = SherpaSynthesizerLoader(store),
            numThreads = TtsConfig.neuralThreads,
            diskCache = diskCache,
            // Peticion del usuario (30-10-2026): frases cortas juntas en una llamada (menos CPU
            // en dialogos) y precarga adaptada al terminal.
            groupMaxChars = 180,
            // Precarga segun el terminal (30-10-2026): alta = ~la pagina siguiente (2 parrafos +
            // 60 s), media = el parrafo siguiente + 30 s, baja = las frases siguientes (~15 s).
            preloadPolicy = DevicePreload(this, hub)::policy,
        )
        hub.renderer = neural
        val engineFactory: (TtsEngineType) -> SpeechEngine = { type ->
            when (type) {
                TtsEngineType.SYSTEM -> SystemTtsEngine(this)
                TtsEngineType.NEURAL -> neural
            }
        }
        controller = ReadAloudController(
            scope, engineFactory, AndroidAudioFocus(this), hub,
            shake = AccelerometerShakeSensor(this),
        )
        controller.updateSettings(hub.settings.value)
        player = TtsPlayer(mainLooper, controller, scope)
        wakeLock = WakeLockHolder(this)
        headsetTaps = DoubleTapDetector(
            scope,
            onSingle = { controller.togglePlayPause() },
            onDouble = { controller.previousSentence() },
        )
        session = MediaSession.Builder(this, player)
            .setId("lectorvoz")
            .setCallback(SessionCallback())
            .setSessionActivity(sessionActivity())
            .setMediaButtonPreferences(buttons())
            .build()
        openPendingBook()
        scope.launch { hub.settings.drop(1).collect { controller.updateSettings(it) } }
        scope.launch {
            controller.state.collect { s ->
                setNoisyReceiver(s.playWhenReady)
                // Sin WakeLock durante una llamada (foco perdido temporalmente: no hay audio).
                wakeLock.set(s.playWhenReady && !s.focusSuppressed && s.engine == TtsEngineType.NEURAL)
            }
        }
        battery = AndroidBatteryMonitor(this)
        battery.start()
        scope.launch { battery.status.collect { controller.onBatteryChanged(it) } }
        // Restos de la version con Piper (espeak-ng-data copiado a disco).
        scope.launch { withContext(Dispatchers.IO) { store.cleanupLegacy() } }
    }

    /**
     * Abre el libro pendiente (si lo hay) y lo suelta para no retener su proveedor (ni el
     * texto del libro) despues. Lo consume una sola vez: al arrancar el servicio
     * (`onCreate`, primer libro) o al recibir `OPEN_BOOK` (servicio ya vivo). Asi no se abre
     * dos veces en el primer arranque ni se queda el proveedor en memoria. Devuelve si abrio.
     */
    private fun openPendingBook(): Boolean {
        val req = hub.pendingBook ?: return false
        hub.pendingBook = null
        controller.open(req.book, req.provider, req.start)
        return true
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Audiolibro: si esta sonando sigue (el usuario lo para desde la notificacion).
        if (!isPlaybackOngoing) pauseAllPlayersAndStopSelf()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Bajo presion de memoria (o con la app en segundo plano) y sin leer, se libera el
        // modelo neuronal (~150 MB) sin esperar a la liberacion perezosa; se recarga solo al
        // reanudar. Si se esta leyendo no hace nada (releaseIfIdle lo comprueba).
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) neural.releaseIfIdle()
    }

    override fun onDestroy() {
        battery.stop()
        hub.renderer = null
        setNoisyReceiver(false)
        wakeLock.set(false)
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private fun setNoisyReceiver(on: Boolean) {
        if (on == noisyRegistered) return
        if (on) {
            ContextCompat.registerReceiver(
                this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } else {
            unregisterReceiver(noisyReceiver)
        }
        noisyRegistered = on
    }

    private fun sessionActivity(): PendingIntent {
        val intent = TtsConfig.sessionActivityIntent?.invoke(this)
            ?: packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent()
        return PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Botones: frase anterior / play-pausa (automatico) / frase siguiente + menu extra. */
    private fun buttons(): List<CommandButton> = listOf(
        CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
            .setDisplayName(getString(R.string.tts_frase_anterior))
            .setSlots(CommandButton.SLOT_BACK)
            .build(),
        CommandButton.Builder(CommandButton.ICON_NEXT)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
            .setDisplayName(getString(R.string.tts_frase_siguiente))
            .setSlots(CommandButton.SLOT_FORWARD)
            .build(),
        CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
            .setSessionCommand(TtsCommands.PREVIOUS_CHAPTER)
            .setDisplayName(getString(R.string.tts_capitulo_anterior))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
            .setSessionCommand(TtsCommands.NEXT_CHAPTER)
            .setDisplayName(getString(R.string.tts_capitulo_siguiente))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(CommandButton.ICON_BOOKMARK_UNFILLED)
            .setSessionCommand(TtsCommands.BOOKMARK)
            .setDisplayName(getString(R.string.tts_marcar))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(R.drawable.tts_ic_temporizador)
            .setSessionCommand(TtsCommands.SLEEP_TIMER_CYCLE)
            .setDisplayName(getString(R.string.tts_temporizador))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
    )

    private inner class SessionCallback : MediaSession.Callback {
        /**
         * Los comandos propios (abrir libro, saltar a posicion, temporizador, marcar,
         * capitulos) solo para controladores de confianza: la propia app, la notificacion,
         * los del sistema (SystemUI, Bluetooth: `isTrusted`) y Android Auto. El resto (otra
         * app cualquiera; el servicio es exportado) solo recibe los comandos estandar.
         */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            val trusted = ControllerTrust.isTrusted(
                ownPackage = packageName,
                controllerPackage = controller.packageName,
                isNotification = session.isMediaNotificationController(controller),
                isSystemTrusted = controller.isTrusted,
                isCar = session.isAutomotiveController(controller) || session.isAutoCompanionController(controller),
            )
            if (trusted) TtsCommands.ALL.forEach { commands.add(it) }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands.build())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val c = this@PlaybackService.controller
            when (customCommand.customAction) {
                // Si no hay libro pendiente, ya lo abrio onCreate al crear el servicio (primer
                // arranque): es exito, no un error (evita abrir el libro dos veces).
                TtsCommands.ACTION_OPEN_BOOK -> openPendingBook()
                TtsCommands.ACTION_NEXT_CHAPTER -> c.nextChapter()
                TtsCommands.ACTION_PREVIOUS_CHAPTER -> c.previousChapter()
                TtsCommands.ACTION_SEEK_TO -> {
                    val loc = TtsCommands.readSeek(args)
                        ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    c.seekTo(loc)
                }
                TtsCommands.ACTION_SLEEP_TIMER -> c.setSleepTimer(TtsCommands.readTimer(args))
                TtsCommands.ACTION_SLEEP_TIMER_CYCLE ->
                    c.setSleepTimer(SleepTimer.nextInCycle(c.state.value.sleepTimer))
                TtsCommands.ACTION_BOOKMARK -> c.requestBookmark()
                TtsCommands.ACTION_STOP -> {
                    // Borrar el libro que suena: parar, olvidar el libro, limpiar el estado y
                    // soltar el primer plano (la notificacion desaparece).
                    c.clear()
                    pauseAllPlayersAndStopSelf()
                }
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /**
         * Boton del auricular: con `headphoneDoubleTapRewind`, toque simple = reproducir o
         * pausar y doble toque = frase anterior (Media3 haria "siguiente"). KEYCODE_MEDIA_PREVIOUS
         * sigue el camino normal (seekToPrevious = frase anterior).
         */
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            if (!this@PlaybackService.controller.currentSettings.headphoneDoubleTapRewind) return false
            @Suppress("DEPRECATION")
            val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
            if (key.keyCode != KeyEvent.KEYCODE_HEADSETHOOK && key.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) return false
            if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) headsetTaps.onTap()
            return true
        }
    }
}
