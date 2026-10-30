package com.lectorvoz.tts.playback

import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.PlaybackEvent
import com.lectorvoz.core.model.VoiceSettings
import com.lectorvoz.tts.BookInfo
import com.lectorvoz.tts.PlaybackState
import com.lectorvoz.tts.UtteranceProvider
import com.lectorvoz.tts.engine.UtteranceRenderer
import com.lectorvoz.tts.neural.PreloadStatus
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** Peticion de abrir un libro para leer en voz alta. */
class BookRequest(
    val book: BookInfo,
    val provider: UtteranceProvider,
    val start: Locator,
)

/**
 * Canal local (mismo proceso) entre el servicio y la UI.
 *
 * Decision: los mandos (reproducir, pausa, frase/capitulo, temporizador) van por
 * MediaController + comandos de sesion de Media3, igual que la notificacion y los
 * auriculares: un unico camino y el servicio pasa a primer plano correctamente. En cambio
 * el estado y los eventos de resaltado (varios por segundo) y los objetos que no caben en
 * un Bundle (el [UtteranceProvider], los ajustes) se comparten por este objeto en memoria:
 * sin IPC ni serializacion por palabra y sin depender de que el binder este conectado.
 * La app y el servicio viven en el mismo proceso (no se declara `android:process`).
 */
class PlaybackHub {
    internal val mutableState = MutableStateFlow(PlaybackState())
    internal val mutableEvents = MutableSharedFlow<PlaybackEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Estado actual (posicion, reproduciendo, motor, voz, temporizador). */
    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    /**
     * Eventos de lectura (frase, palabra, fin de capitulo, errores). Sin repeticion: quien
     * se suscribe tarde recupera la frase actual de [state]; si la UI se atasca se pierden
     * los mas antiguos (solo resaltado), nunca se bloquea el audio.
     */
    val events: SharedFlow<PlaybackEvent> = mutableEvents.asSharedFlow()

    internal val mutablePreload = MutableStateFlow<PreloadStatus?>(null)

    /** Nivel de precarga que usa ahora el motor (nulo si no ha leido aun en este proceso). */
    val preload: StateFlow<PreloadStatus?> = mutablePreload.asStateFlow()

    /** Ajustes de voz vigentes; la UI los escribe y el servicio los aplica. */
    val settings = MutableStateFlow(VoiceSettings())

    /** Libro pendiente de abrir (lo consume el servicio con el comando "abrir"). */
    @Volatile internal var pendingBook: BookRequest? = null

    // Canal (no SharedFlow): un unico consumidor (:app) y las peticiones no se pierden si
    // la UI no esta recogiendo en ese momento (pantalla apagada); acotado a 16.
    private val bookmarks = Channel<Locator>(capacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Peticiones de marcador (boton "Marcar" de la notificacion): posicion de la frase que
     * sonaba. Las consume y guarda `:app` (un unico recolector, idealmente de ambito de app).
     */
    val bookmarkRequests: Flow<Locator> = bookmarks.receiveAsFlow()

    internal fun requestBookmark(locator: Locator) {
        bookmarks.trySend(locator)
    }

    /**
     * Motor neuronal del servicio para exportar audio sin cargar el modelo dos veces; nulo
     * hasta que el servicio arranca.
     */
    @Volatile var renderer: UtteranceRenderer? = null
        internal set

    companion object {
        /** Instancia del proceso. */
        val default: PlaybackHub by lazy { PlaybackHub() }
    }
}
