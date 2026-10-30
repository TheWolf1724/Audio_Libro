package com.lectorvoz.tts.playback

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.lectorvoz.tts.neural.PreloadPolicy
import com.lectorvoz.tts.neural.PreloadStatus
import com.lectorvoz.tts.neural.PreloadTiers

/**
 * Nivel de precarga de la voz neuronal segun el terminal (peticion del usuario, 30-10-2026:
 * "si el movil tiene capacidad, que cargue la pagina siguiente; si no, la frase o el parrafo").
 * Mira la RAM total y si Android lo marca como de poca memoria (una vez), y el ahorro de bateria
 * y el estado termico (como mucho cada [RECHECK_MS]); el motor aporta su velocidad de sintesis
 * medida. La decision es [PreloadTiers.choose].
 */
class DevicePreload(context: Context, private val hub: PlaybackHub? = null) {
    private val app = context.applicationContext
    private val power = app.getSystemService(PowerManager::class.java)
    private val totalMem: Long
    private val lowRam: Boolean

    init {
        val am = app.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        totalMem = info.totalMem
        lowRam = am?.isLowRamDevice == true
    }

    @Volatile private var checkedAt = Long.MIN_VALUE
    @Volatile private var powerSave = false
    @Volatile private var thermal = -1
    @Volatile private var last: PreloadPolicy? = null

    /** Politica para la velocidad de sintesis [rtf] medida (nula = aun sin medir). */
    fun policy(rtf: Float?): PreloadPolicy {
        val now = SystemClock.elapsedRealtime()
        if (now - checkedAt >= RECHECK_MS) {
            checkedAt = now
            powerSave = power?.isPowerSaveMode == true
            thermal = if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus ?: -1 else -1
        }
        val p = PreloadTiers.choose(totalMem, lowRam, powerSave, thermal, rtf)
        if (p != last) {
            last = p
            Log.i(TAG, "Precarga ${p.level}: RAM ${totalMem / (1024 * 1024)} MB, poca memoria=$lowRam, ahorro=$powerSave, termico=$thermal, rtf=$rtf")
        }
        publish(p, rtf)
        return p
    }

    /** Estimacion sin leer (para Ajustes): mismos datos del terminal, sin velocidad medida. */
    fun estimate(): PreloadStatus {
        val save = power?.isPowerSaveMode == true
        val th = if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus ?: -1 else -1
        return PreloadStatus(PreloadTiers.choose(totalMem, lowRam, save, th, null), totalMem, lowRam, save, th, null, live = false)
    }

    // Al hub solo si cambia algo que se ve (nivel, ahorro, temperatura o la velocidad en 0,05):
    // el productor consulta la politica muy a menudo.
    private fun publish(p: PreloadPolicy, rtf: Float?) {
        val h = hub ?: return
        val old = h.mutablePreload.value
        val rtfMoved = (old?.rtf == null) != (rtf == null) || (old?.rtf != null && rtf != null && kotlin.math.abs(old.rtf - rtf) >= 0.05f)
        if (old == null || old.policy != p || old.powerSave != powerSave || old.thermal != thermal || rtfMoved) {
            h.mutablePreload.value = PreloadStatus(p, totalMem, lowRam, powerSave, thermal, rtf, live = true)
        }
    }

    private companion object {
        const val TAG = "DevicePreload"
        const val RECHECK_MS = 30_000L
    }
}
