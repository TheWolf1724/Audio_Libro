package com.lectorvoz.tts

import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.TtsEngineType
import com.lectorvoz.core.model.Utterance

/**
 * Fuente de frases para la voz. La implementa `:core:text` (segmentador + normalizador);
 * aqui solo se consume. Todas las funciones pueden suspender (analisis de capitulos).
 *
 * Contrato de `Utterance.id`: unico en todo el libro y estable, es decir, la misma frase
 * tiene siempre el mismo id aunque el capitulo se construya desde otra posicion (lo cumple
 * `UtteranceBuilder` con su `idBase` por defecto). Se usa para emparejar eventos del motor,
 * fronteras de capitulo y "capitulo anterior".
 */
interface UtteranceProvider {
    /** Numero de capitulos (o paginas en PDF). */
    val chapterCount: Int

    /** Frase que contiene [locator] (o la primera posterior). Nulo si no hay mas texto. */
    suspend fun at(locator: Locator): Utterance?

    /** Frase siguiente a [current], cruzando capitulos. Nulo al final del libro. */
    suspend fun next(current: Utterance): Utterance?

    /** Frase anterior a [current], cruzando capitulos. Nulo al principio del libro. */
    suspend fun previous(current: Utterance): Utterance?

    /**
     * Longitud en caracteres del texto del capitulo [chapter] (la escala de
     * `Locator.offset`), para estimar duracion y tiempo restante. Nulo si no se conoce.
     */
    suspend fun chapterLength(chapter: Int): Int? = null
}

/** Libro abierto para leer en voz alta, con los datos de la notificacion. */
class BookInfo(
    /** Identificador estable del libro (id de la biblioteca). */
    val id: String,
    val title: String,
    val author: String? = null,
    /** Portada en bytes (JPEG/PNG pequeno); se publica como `artworkData`. */
    val coverData: ByteArray? = null,
    /** Alternativa a [coverData]: URI de contenido/fichero de la portada. */
    val coverUri: String? = null,
    /** Titulo de cada capitulo por indice; puede ser mas corto que el libro. */
    val chapterTitles: List<String?> = emptyList(),
) {
    /** Titulo del capitulo [index] si se conoce. */
    fun chapterTitle(index: Int): String? = chapterTitles.getOrNull(index)
}

/** Modo del temporizador de apagado. */
sealed interface SleepTimerMode {
    /** Parar dentro de [minutes] minutos (con fundido final de volumen). */
    data class Minutes(val minutes: Int) : SleepTimerMode {
        init { require(minutes > 0) { "minutes debe ser positivo" } }
    }

    /** Parar al terminar el capitulo actual. */
    data object EndOfChapter : SleepTimerMode
}

/** Estado observable de la lectura en voz alta. */
data class PlaybackState(
    /** Libro abierto (id), nulo si no hay ninguno. */
    val bookId: String? = null,
    /** Posicion de la frase actual (inicio de su rango). */
    val position: Locator? = null,
    /** Id de la frase actual. */
    val utteranceId: Long? = null,
    /** El usuario quiere que suene (puede estar preparando el motor). */
    val playWhenReady: Boolean = false,
    /** Suena audio ahora mismo. */
    val isPlaying: Boolean = false,
    /** Cargando el motor o la voz. */
    val isPreparing: Boolean = false,
    /** Se llego al final del libro. */
    val ended: Boolean = false,
    val engine: TtsEngineType = TtsEngineType.SYSTEM,
    /** Voz efectiva (id del catalogo o nombre de voz del sistema). */
    val voiceId: String? = null,
    val sleepTimer: SleepTimerMode? = null,
    /** Milisegundos restantes del temporizador por minutos; nulo si no aplica. */
    val sleepTimerRemainingMs: Long? = null,
    /** Ultimo error legible, si lo hubo. */
    val error: String? = null,
    /**
     * Velocidad efectiva medida en caracteres de texto visible por segundo (media movil de
     * las ultimas frases, pausas incluidas); nulo hasta tener medidas. La UI calcula el
     * tiempo restante como caracteres que faltan / [charsPerSecond].
     */
    val charsPerSecond: Float? = null,
    /** Longitud en caracteres del capitulo actual, si el proveedor la conoce. */
    val chapterLength: Int? = null,
    /** Bateria baja (<15 %) mientras suena la voz neuronal (aviso para la UI). */
    val lowBattery: Boolean = false,
    /** Se paso a la voz del sistema por bateria baja (LowBatteryAction.SWITCH_TO_SYSTEM). */
    val switchedToSystemForBattery: Boolean = false,
    /** Preparando audio en la cache de disco (prepareWhileCharging). */
    val preparingCache: Boolean = false,
    /**
     * Foco de audio perdido temporalmente (llamada): no suena, pero [playWhenReady] sigue a
     * true y se reanuda solo al recuperar el foco.
     */
    val focusSuppressed: Boolean = false,
) {
    /** Milisegundos estimados hasta el final del capitulo; nulo si faltan datos. */
    val chapterRemainingMs: Long?
        get() {
            val cps = charsPerSecond ?: return null
            val len = chapterLength ?: return null
            val pos = position?.offset ?: return null
            if (cps <= 0f) return null
            return ((len - pos).coerceAtLeast(0) / cps * 1000).toLong()
        }
}

