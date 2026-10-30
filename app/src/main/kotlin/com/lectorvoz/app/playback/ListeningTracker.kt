package com.lectorvoz.app.playback

import android.os.SystemClock
import android.util.Log
import com.lectorvoz.app.integration.PlaybackPort
import com.lectorvoz.app.integration.VoiceUiState
import com.lectorvoz.core.data.library.LibraryRepository
import com.lectorvoz.core.data.stats.ReadingStatsRepository
import com.lectorvoz.core.data.stats.SessionKind
import com.lectorvoz.core.model.Locator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Guarda, en el ambito de la aplicacion, lo que hace la voz aunque el lector no este abierto
 * (revision 3, A1 y B6):
 *
 * - Posicion de escucha: con la voz en marcha se guarda como mucho cada [saveEveryMs] (solo
 *   si ha cambiado), y siempre al pausar, al terminar el libro y al cambiar de libro.
 * - Estadisticas de escucha: tramos mientras suena de verdad (`isPlaying`), con los
 *   caracteres avanzados; los tramos largos se guardan cada [segmentMaxMs] (si el proceso
 *   muere no se pierden horas).
 *
 * [progressOf] estima la fraccion del libro en una posicion (nula: no se conoce aun).
 * Los errores (libro borrado mientras sonaba) se registran y no tumban la app.
 */
class ListeningTracker(
    private val scope: CoroutineScope,
    private val playback: PlaybackPort,
    private val library: LibraryRepository,
    private val stats: ReadingStatsRepository,
    private val progressOf: (bookId: Long, Locator) -> Float?,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monotonic: () -> Long = SystemClock::elapsedRealtime,
    /**
     * Guardado periodico de la posicion mientras suena (30 s): cada guardado es un UPDATE de
     * Room que despierta a todos los que observan la biblioteca. La pausa, el fin y el cambio
     * de libro guardan siempre al momento, asi que un corte brusco pierde como mucho ~30 s.
     */
    private val saveEveryMs: Long = 30_000L,
    private val segmentMaxMs: Long = 15 * 60_000L,
) {
    private var lastBook: Long? = null
    private var lastPosition: Locator? = null
    private var lastSaved: Locator? = null
    private var lastSaveAt = Long.MIN_VALUE / 2
    private var wasPlayWhenReady = false
    private var endedSaved = false

    // Tramo de escucha en curso.
    private var segBook: Long? = null
    private var segStart = 0L
    private var segWall = 0L
    private var segChars = 0L
    private var segPos: Locator? = null

    /** Longitud en caracteres del capitulo de [segPos], si el servicio la conoce. */
    private var segChapterLen: Int? = null

    private var job: Job? = null

    /** Empieza a seguir el estado de la voz (una vez). */
    fun start() {
        if (job != null) return
        job = scope.launch { playback.state.collect { onState(it) } }
    }

    /** Procesa un estado (publico para pruebas deterministas). */
    fun onState(s: VoiceUiState) {
        val now = monotonic()
        val book = s.bookId
        if (book != lastBook) {
            // Cambio de libro (o prueba de voz / sin libro): cerrar lo del anterior.
            lastBook?.let { prev -> lastPosition?.let { save(prev, it, null) } }
            closeSegment(now)
            lastBook = book
            lastPosition = null
            lastSaved = null
            lastSaveAt = Long.MIN_VALUE / 2
            wasPlayWhenReady = false
            endedSaved = false
        }
        if (book == null) return
        val pos = s.position
        if (pos != null) {
            countChars(pos, s.chapterLength)
            lastPosition = pos
        }
        // Estadisticas: solo mientras suena.
        if (s.isPlaying && segBook == null) openSegment(book, now, pos, s.chapterLength)
        else if (!s.isPlaying && segBook != null) closeSegment(now)
        else if (segBook != null && now - segStart >= segmentMaxMs) {
            closeSegment(now)
            openSegment(book, now, pos, s.chapterLength)
        }
        // Posicion.
        val p = lastPosition
        when {
            p == null -> Unit
            s.ended -> if (!endedSaved) {
                endedSaved = true
                save(book, p, finished = true, progress = 1f)
            }
            wasPlayWhenReady && !s.playWhenReady -> save(book, p, null)
            s.playWhenReady && p != lastSaved && now - lastSaveAt >= saveEveryMs -> save(book, p, null)
        }
        if (!s.ended) endedSaved = false
        wasPlayWhenReady = s.playWhenReady
    }

    private fun save(book: Long, pos: Locator, finished: Boolean?, progress: Float? = null) {
        if (pos == lastSaved && finished == null && book == lastBook) return
        lastSaved = pos
        lastSaveAt = monotonic()
        val fraction = progress ?: progressOf(book, pos)
        scope.launch {
            try {
                val current = if (fraction == null) library.get(book) ?: return@launch else null
                library.updateProgress(book, pos, fraction ?: current?.progress ?: 0f, finished)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo guardar la posicion de escucha", e)
            }
        }
    }

    private fun openSegment(book: Long, now: Long, pos: Locator?, chapterLen: Int?) {
        segBook = book
        segStart = now
        segWall = wallClock()
        segChars = 0
        segPos = pos
        segChapterLen = chapterLen
    }

    /**
     * Suma los caracteres avanzados (hacia delante) desde la ultima posicion del tramo. Al
     * cruzar de capitulo cuenta el resto del capitulo previo (si se conoce su longitud) ademas
     * del offset en el nuevo, y un salto de varios capitulos no se pierde (antes sumaba 0).
     */
    private fun countChars(pos: Locator, chapterLen: Int?) {
        if (segBook == null) return
        val prev = segPos
        segPos = pos
        if (prev == null) {
            if (chapterLen != null) segChapterLen = chapterLen
            return
        }
        when {
            pos.chapter == prev.chapter -> {
                segChars += (pos.offset - prev.offset).coerceAtLeast(0).toLong()
                if (chapterLen != null) segChapterLen = chapterLen
            }
            pos.chapter > prev.chapter -> {
                val tail = segChapterLen?.let { (it - prev.offset).coerceAtLeast(0).toLong() } ?: 0L
                segChars += tail + pos.offset.toLong()
                // La longitud del nuevo capitulo aun no se conoce con certeza (el estado puede
                // llegar con retraso): la fijan los siguientes estados del mismo capitulo.
                segChapterLen = null
            }
            else -> {
                // Retroceso de capitulo: no se suma (solo cuenta el avance).
                segChapterLen = chapterLen
            }
        }
    }

    private fun closeSegment(now: Long) {
        val book = segBook ?: return
        segBook = null
        val dur = now - segStart
        val chars = segChars
        val started = segWall
        scope.launch {
            try {
                stats.record(book, SessionKind.LISTEN, started, dur, chars)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo guardar la sesion de escucha", e)
            }
        }
    }

    private companion object {
        const val TAG = "ListeningTracker"
    }
}
