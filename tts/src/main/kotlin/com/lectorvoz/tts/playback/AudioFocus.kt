package com.lectorvoz.tts.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** Cambio de foco de audio ya interpretado. */
enum class FocusChange {
    /** Recuperamos el foco. */
    GAIN,

    /** Perdida definitiva (otra app reproduce): pausar y no reanudar solos. */
    LOSS,

    /** Perdida temporal (llamada, asistente): pausar y reanudar al recuperar. */
    LOSS_TRANSIENT,

    /** Perdida temporal que permite seguir bajito (navegador GPS, notificacion). */
    DUCK,
}

/** Foco de audio (abstraido para pruebas). */
interface AudioFocus {
    /** Pide el foco; [onChange] recibe los cambios posteriores. false si se deniega. */
    fun request(onChange: (FocusChange) -> Unit): Boolean

    /** Suelta el foco. */
    fun abandon()
}

/**
 * Foco con [AudioFocusRequest] (API 26). `setWillPauseWhenDucked(true)`: el sistema no
 * atenua por nosotros (contenido hablado) y nos avisa para que bajemos el volumen a mano.
 * Con targetSdk 37 la peticion solo se concede con servicio en primer plano o app visible
 * (Android 17, "background audio hardening").
 */
class AndroidAudioFocus(context: Context) : AudioFocus {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var request: AudioFocusRequest? = null

    override fun request(onChange: (FocusChange) -> Unit): Boolean {
        abandon()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setWillPauseWhenDucked(true)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> onChange(FocusChange.GAIN)
                    AudioManager.AUDIOFOCUS_LOSS -> onChange(FocusChange.LOSS)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onChange(FocusChange.LOSS_TRANSIENT)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onChange(FocusChange.DUCK)
                }
            }, handler)
            .build()
        val granted = audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) request = req
        return granted
    }

    override fun abandon() {
        request?.let { audioManager.abandonAudioFocusRequest(it) }
        request = null
    }
}
