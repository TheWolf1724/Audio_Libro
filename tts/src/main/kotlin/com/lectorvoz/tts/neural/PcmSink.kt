package com.lectorvoz.tts.neural

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.util.Log

/**
 * Salida de audio PCM float mono (abstraida para probar la cola sin AudioTrack).
 * Un objeto por sesion de lectura: [open] una vez, [release] al final.
 */
interface PcmSink {
    /** Prepara la salida a [sampleRate] Hz y empieza a reproducir. */
    fun open(sampleRate: Int)

    /** Escribe (bloqueante) y devuelve las muestras aceptadas o un valor < 0 si hay error. */
    fun write(data: FloatArray, offset: Int, length: Int): Int

    /** Muestras ya reproducidas desde [open] (cabeza de reproduccion, monotona). */
    fun playedFrames(): Long

    /** Volumen 0..1. Seguro desde cualquier hilo. */
    fun setVolume(volume: Float)

    /** Tono aproximado (1 = normal) sin cambiar la velocidad. false si no se pudo. */
    fun setPitch(pitch: Float): Boolean

    /** Para en seco, descarta lo pendiente y libera. */
    fun release()

    /**
     * Calla al instante y descarta lo pendiente sin liberar (desde otro hilo, mientras el de
     * audio puede estar dentro de un [write] bloqueante: lo desbloquea). Lo usa la pausa.
     */
    fun interrupt() {}
}

/**
 * [PcmSink] con [AudioTrack] en modo stream, `ENCODING_PCM_FLOAT`, USAGE_MEDIA + SPEECH, en modo
 * de AHORRO DE ENERGIA (`PERFORMANCE_MODE_POWER_SAVING`): el sistema usa la salida de bufer
 * profundo, que despierta menos al procesador. Bufer de 1 s: la pausa no se retrasa (pausar
 * libera con pause + flush, ver [release]) y el resaltado sigue la cabeza de reproduccion real.
 */
class AudioTrackSink(
    /** Duracion del bufer interno en ms (cuanto mayor, menos despertares; ver arriba). */
    private val bufferMs: Int = 1000,
) : PcmSink {
    // write/release van en el hilo de audio; playedFrames en el del resaltado.
    @Volatile private var track: AudioTrack? = null

    // Cabeza de 32 bits sin signo con vueltas (27 h a 44,1 kHz; se cuenta por si acaso).
    private var lastHead = 0L
    private var wraps = 0L

    @Volatile private var volume = 1f

    override fun open(sampleRate: Int) {
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT,
        )
        val wantBytes = sampleRate * 4 * bufferMs / 1000
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBytes, wantBytes))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
            .build()
        t.setVolume(volume)
        t.play()
        track = t
    }

    override fun write(data: FloatArray, offset: Int, length: Int): Int =
        track?.write(data, offset, length, AudioTrack.WRITE_BLOCKING) ?: -1

    @Synchronized
    override fun playedFrames(): Long {
        val t = track ?: return (wraps shl 32) + lastHead
        val head = try {
            t.playbackHeadPosition.toLong() and 0xffffffffL
        } catch (_: IllegalStateException) {
            return (wraps shl 32) + lastHead
        }
        if (head < lastHead) wraps++
        lastHead = head
        return (wraps shl 32) + head
    }

    /** Sincronizado con [release]: llega del hilo principal mientras el de audio puede liberar. */
    @Synchronized
    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        try {
            track?.setVolume(this.volume)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Volumen no aplicable", e)
        }
    }

    @Synchronized
    override fun setPitch(pitch: Float): Boolean {
        val t = track ?: return false
        return try {
            t.playbackParams = PlaybackParams().setPitch(pitch).setSpeed(1f)
            true
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Tono no soportado: $pitch", e)
            false
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Tono no aplicable", e)
            false
        }
    }

    @Synchronized
    override fun interrupt() {
        val t = track ?: return
        try {
            t.pause()
            t.flush()
        } catch (_: IllegalStateException) {
        }
    }

    @Synchronized
    override fun release() {
        val t = track ?: return
        track = null
        try {
            t.pause()
            t.flush()
            t.stop()
        } catch (_: IllegalStateException) {
        }
        t.release()
    }

    private companion object {
        const val TAG = "AudioTrackSink"
    }
}
