package com.lectorvoz.app.playback

import android.util.Log
import com.lectorvoz.app.integration.PlaybackPort
import com.lectorvoz.core.data.pronunciation.PronunciationEntry
import com.lectorvoz.core.data.pronunciation.PronunciationRepository
import com.lectorvoz.core.data.settings.SettingsRanges
import com.lectorvoz.core.data.settings.SettingsRepository
import com.lectorvoz.core.model.VoiceSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Ajustes de voz entre DataStore y el servicio, en los dos sentidos:
 *
 * - DataStore -> servicio: los ajustes efectivos del libro que suena (globales + propios del
 *   libro) y su diccionario ([applyToBook] reconstruye el troceo si hace falta).
 * - Servicio -> DataStore: la velocidad o el tono cambiados desde fuera de la app (mando
 *   Media3: reloj, Android Auto) se guardan (en el libro si tiene velocidad propia).
 *
 * Sin bucles: se recuerda lo ultimo que se envio al servicio; un valor del servicio igual a
 * ese no se guarda, y tras guardar se toma como enviado.
 */
class VoiceSettingsSync(
    private val scope: CoroutineScope,
    private val playback: PlaybackPort,
    private val settings: SettingsRepository,
    private val pronunciation: PronunciationRepository,
    private val applyToBook: suspend (VoiceSettings, List<PronunciationEntry>) -> Unit,
) {
    @Volatile private var lastPushed: VoiceSettings? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        scope.launch {
            playback.state.map { it.bookId }.distinctUntilChanged().flatMapLatest { id ->
                if (id == null) settings.voice.map { it to null }
                else combine(settings.voiceFor(id), pronunciation.effective(id)) { v, d -> v to d }
            }.collect { (voice, dict) ->
                lastPushed = voice
                playback.updateSettings(voice)
                if (dict != null) {
                    try {
                        applyToBook(voice, dict)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "No se pudieron aplicar los ajustes al libro", e)
                    }
                }
            }
        }
        scope.launch {
            playback.activeSettings.collect { onServiceSettings(it) }
        }
    }

    /** Ajustes publicados por el servicio (publico para pruebas). */
    suspend fun onServiceSettings(active: VoiceSettings) {
        val pushed = lastPushed ?: return
        if (active.speed == pushed.speed && active.pitch == pushed.pitch) return
        val clean = SettingsRanges.sanitize(pushed.copy(speed = active.speed, pitch = active.pitch))
        lastPushed = clean
        try {
            val book = playback.state.value.bookId
            val ownSpeed = book != null && settings.overrides(book).first().speed != null
            if (ownSpeed && book != null) {
                settings.updateOverrides(book) { it.copy(speed = clean.speed) }
                if (clean.pitch != pushed.pitch) settings.updateVoice { it.copy(pitch = clean.pitch) }
            } else {
                settings.updateVoice { it.copy(speed = clean.speed, pitch = clean.pitch) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo guardar la velocidad cambiada desde fuera", e)
        }
    }

    private companion object {
        const val TAG = "VoiceSettingsSync"
    }
}
