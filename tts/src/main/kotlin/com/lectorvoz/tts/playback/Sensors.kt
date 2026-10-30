package com.lectorvoz.tts.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/** Estado de la bateria: [percent] 0..100 y si esta cargando. */
data class BatteryStatus(val percent: Int, val charging: Boolean) {
    /** Bateria baja sin cargar (umbral del ajuste `lowBattery`). */
    val isLow: Boolean get() = percent in 0 until LOW_PERCENT && !charging

    /**
     * Regimen de bateria baja con histeresis para no oscilar cuando el nivel ronda el umbral:
     * si [wasLow], sigue bajo hasta recuperar [RECOVER_PERCENT]; si no, entra en bajo por
     * debajo de [LOW_PERCENT]. Cargar siempre sale del regimen bajo.
     */
    fun isLowWithHysteresis(wasLow: Boolean): Boolean {
        if (charging) return false
        val threshold = if (wasLow) RECOVER_PERCENT else LOW_PERCENT
        return percent in 0 until threshold
    }

    companion object {
        /** Umbral para entrar en bateria baja. */
        const val LOW_PERCENT = 15

        /** Umbral para salir de bateria baja (histeresis: evita el parpadeo en torno al 15 %). */
        const val RECOVER_PERCENT = 20

        /** Valor inicial desconocido (no baja, no cargando). */
        val UNKNOWN = BatteryStatus(100, false)
    }
}

/** Fuente del estado de la bateria (abstraida para pruebas). */
interface BatteryMonitor {
    val status: StateFlow<BatteryStatus>

    /** Empieza a escuchar cambios. */
    fun start()

    /** Deja de escuchar. */
    fun stop()
}

/**
 * Bateria con `ACTION_BATTERY_CHANGED`: el intent fijo da el estado al registrarse y el
 * sistema avisa de cada cambio; no hay sondeo activo.
 */
class AndroidBatteryMonitor(context: Context) : BatteryMonitor {
    private val appContext = context.applicationContext
    private val state = MutableStateFlow(BatteryStatus.UNKNOWN)
    override val status: StateFlow<BatteryStatus> = state.asStateFlow()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            parse(intent)?.let { state.value = it }
        }
    }

    override fun start() {
        if (registered) return
        val sticky = ContextCompat.registerReceiver(
            appContext, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
        sticky?.let { parse(it) }?.let { state.value = it }
    }

    override fun stop() {
        if (!registered) return
        appContext.unregisterReceiver(receiver)
        registered = false
    }

    private fun parse(intent: Intent): BatteryStatus? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging = plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return BatteryStatus(level * 100 / scale, charging)
    }
}

/** Detector del gesto de agitar (abstraido para pruebas). */
interface ShakeSensor {
    /** Empieza a escuchar; [onShake] se llama en el hilo principal. */
    fun start(onShake: () -> Unit)

    /** Deja de escuchar (libera el sensor). */
    fun stop()
}

/**
 * Logica del gesto: dos picos de aceleracion por encima de [thresholdG] g en menos de
 * [windowMs], y luego [cooldownMs] sin volver a disparar. Sin asignaciones por muestra.
 */
class ShakeLogic(
    private val thresholdG: Float = 2.5f,
    private val windowMs: Long = 800,
    private val cooldownMs: Long = 1_500,
) {
    private var firstPeak = -1L
    private var lastFire = Long.MIN_VALUE / 2
    private var above = false

    /** Muestra del acelerometro en m/s2; true si completa un gesto de agitar. */
    fun onSample(x: Float, y: Float, z: Float, timeMs: Long): Boolean {
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        val peak = g >= thresholdG
        val rising = peak && !above
        above = peak
        if (!rising || timeMs - lastFire < cooldownMs) return false
        if (firstPeak >= 0 && timeMs - firstPeak <= windowMs) {
            firstPeak = -1
            lastFire = timeMs
            return true
        }
        firstPeak = timeMs
        return false
    }
}

/** Acelerometro registrado solo entre [start] y [stop] (ultimos 30 s del temporizador). */
class AccelerometerShakeSensor(context: Context) : ShakeSensor {
    private val sensorManager = context.applicationContext.getSystemService(SensorManager::class.java)
    private var listener: SensorEventListener? = null

    override fun start(onShake: () -> Unit) {
        if (listener != null) return
        val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val logic = ShakeLogic()
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val v = event.values
                if (logic.onSample(v[0], v[1], v[2], event.timestamp / 1_000_000)) onShake()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        // Sin Handler: los eventos llegan al hilo principal (el que registra).
        sensorManager.registerListener(l, accel, SensorManager.SENSOR_DELAY_UI)
        listener = l
    }

    override fun stop() {
        listener?.let { sensorManager?.unregisterListener(it) }
        listener = null
    }
}

/**
 * Distingue toque simple y doble del boton del auricular: el simple se confirma tras
 * [windowMs] sin segundo toque. Confinado al hilo de [scope].
 */
class DoubleTapDetector(
    private val scope: CoroutineScope,
    private val onSingle: () -> Unit,
    private val onDouble: () -> Unit,
    private val windowMs: Long = 400,
) {
    private var pending: Job? = null

    /** Llamar en cada pulsacion (ACTION_DOWN sin repeticion). */
    fun onTap() {
        val p = pending
        if (p != null && p.isActive) {
            p.cancel()
            pending = null
            onDouble()
        } else {
            pending = scope.launch {
                delay(windowMs)
                pending = null
                onSingle()
            }
        }
    }
}

/**
 * Velocidad efectiva de lectura en caracteres por segundo: media movil de las ultimas
 * [window] frases (caracteres de la frase / tiempo desde su inicio al de la siguiente,
 * pausas incluidas). Seguro entre hilos.
 */
class RateMeter(private val window: Int = 30) {
    private val chars = IntArray(window)
    private val millis = LongArray(window)
    private var count = 0
    private var next = 0

    /** Anade una medida; se descartan tiempos absurdos (<= 0 o > 2 min). */
    @Synchronized
    fun add(charCount: Int, elapsedMs: Long) {
        if (charCount <= 0 || elapsedMs <= 0 || elapsedMs > 120_000) return
        chars[next] = charCount
        millis[next] = elapsedMs
        next = (next + 1) % window
        if (count < window) count++
    }

    /** Caracteres por segundo o nulo sin medidas. */
    @Synchronized
    fun charsPerSecond(): Float? {
        if (count == 0) return null
        var c = 0L
        var ms = 0L
        for (i in 0 until count) {
            c += chars[i]
            ms += millis[i]
        }
        return if (ms <= 0) null else c * 1000f / ms
    }

    /** Olvida las medidas (cambio de velocidad o de voz). */
    @Synchronized
    fun reset() {
        count = 0
        next = 0
    }
}
