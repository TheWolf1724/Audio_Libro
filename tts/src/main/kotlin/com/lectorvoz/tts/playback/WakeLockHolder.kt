package com.lectorvoz.tts.playback

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock

/**
 * WakeLock parcial solo mientras la voz NEURAL lee.
 *
 * Justificacion: con servicio en primer plano y AudioTrack sonando, AudioFlinger mantiene
 * la CPU despierta, pero la sintesis va por delante del audio y si en un movil lento el
 * productor se retrasa, la pista se queda sin datos, AudioFlinger pasa a reposo y la CPU
 * podria dormirse a mitad de sintesis. El coste es nulo mientras ya suena audio. Con la voz
 * del sistema no hace falta: el audio lo reproduce el proceso del motor TTS.
 * Con tiempo limite como red de seguridad (se renueva en cada cambio de estado).
 */
class WakeLockHolder(context: Context) {
    private val lock: PowerManager.WakeLock =
        context.applicationContext.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LectorVoz:voz")
            .apply { setReferenceCounted(false) }

    private var acquiredAt = 0L

    /** Adquiere (renovando si caduca pronto) o suelta segun [needed]. Llamar a menudo. */
    fun set(needed: Boolean) {
        if (needed) {
            val now = SystemClock.elapsedRealtime()
            if (!lock.isHeld || now - acquiredAt > RENEW_MS) {
                lock.acquire(TIMEOUT_MS)
                acquiredAt = now
            }
        } else if (lock.isHeld) {
            lock.release()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30 * 60_000L
        const val RENEW_MS = 10 * 60_000L
    }
}
