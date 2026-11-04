package com.lectorvoz.tts.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reanudar tras morir el proceso: "play" desde el auricular, la notificacion o el coche con
 * el servicio recien creado y sin libro. Pide a la app el ultimo libro ([load], que es
 * `TtsConfig.resumeBook`) y lo abre en el controlador en su posicion guardada, con los
 * ajustes de voz que la app haya dejado en `PlaybackHub.settings`. El servicio lo usa desde
 * `onPlaybackResumption` (hilo principal).
 */
internal class PlaybackResumption(
    private val controller: ReadAloudController,
    private val hub: PlaybackHub,
    private val load: (suspend () -> BookRequest?)?,
    /** Tope para abrir el libro (Media3 ya puso el servicio en primer plano antes de llamar). */
    private val timeoutMs: Long = TIMEOUT_MS,
    private val onError: (Exception) -> Unit = {},
) {
    /** Abre el ultimo libro (o deja el que ya este abierto) y devuelve su id; nulo si no hay. */
    suspend fun resume(): String? {
        controller.book?.let { return it.id }
        val loader = load ?: return null
        val req = try {
            withTimeoutOrNull(timeoutMs) { loader() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
            null
        } ?: return null
        // La app pudo abrir otro libro mientras tanto (toque del usuario): manda ese.
        controller.book?.let { return it.id }
        controller.updateSettings(hub.settings.value)
        controller.open(req.book, req.provider, req.start)
        return req.book.id
    }

    companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
