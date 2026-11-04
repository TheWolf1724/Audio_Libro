package com.lectorvoz.app.ocr

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.lectorvoz.app.books.LibraryListener
import com.lectorvoz.core.data.library.Book
import com.lectorvoz.core.data.library.LibraryRepository
import com.lectorvoz.core.model.BookFormat
import com.lectorvoz.format.pdf.PageTextFallback
import com.lectorvoz.format.pdf.PdfBook
import com.lectorvoz.format.pdf.RawPageText
import com.lectorvoz.format.pdf.ocr.OcrEngine
import com.lectorvoz.format.pdf.ocr.OcrStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Reconocedor de una pagina (sustituible en las pruebas). */
fun interface PageRecognizer {
    suspend fun recognize(book: PdfBook, index: Int, language: String, urgent: Boolean): RawPageText?
}

/** Estado del OCR en el movil (Ajustes). */
data class OcrStatus(val enabled: Boolean = true, val storedBytes: Long = 0L, val working: Boolean = false)

/**
 * OCR de los PDF escaneados sin que el usuario espere:
 *
 * - **De fondo**: al importar un PDF y mientras se lee o se escucha, reconoce las paginas por
 *   delante de la posicion actual y las guarda (texto ligero, ~5 KB por pagina). Solo trabaja
 *   con la app en pantalla o la voz sonando; se detiene con ahorro de bateria, bateria baja
 *   (sin cargar) o el movil caliente, y sin cargador deja descansos entre paginas.
 * - **Al momento**: si la voz o el lector llegan a una pagina aun sin reconocer, se reconoce
 *   esa con prioridad (la voz trabaja por delante de lo que suena, asi que no hay corte).
 * - **Limpieza**: al terminar un libro o quitarlo de la biblioteca se borra lo guardado (y el
 *   audio guardado de ese libro). Si se vuelve a abrir uno terminado, solo se reconocen unas
 *   paginas por delante de donde se lea.
 *
 * Los PDF con texto propio no gastan nada: una muestra de paginas lo detecta y se descartan.
 */
class OcrManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val library: LibraryRepository,
    /** Abre el PDF de un libro: el documento y como soltarlo (comparte la instancia abierta). */
    private val openPdf: suspend (Book) -> Pair<PdfBook?, () -> Unit>,
    private val store: OcrStore = OcrStore(File(context.filesDir, "ocr")),
    private val recognizer: PageRecognizer = PageRecognizer { b, i, l, u -> OcrEngine.get(context).recognize(b, i, l, u) },
    /** Borra el audio guardado de un libro (terminado o quitado). */
    private val removeAudio: (bookId: Long) -> Unit = {},
    private val conditions: DeviceConditions = AndroidConditions(context),
    /** Cada cuanto se revisa el estado del movil mientras no se puede trabajar. */
    private val conditionsPollMs: Long = CONDITIONS_POLL_MS,
) : LibraryListener {
    private val enabled = MutableStateFlow(true)
    private val readerBook = MutableStateFlow<Long?>(null)
    private val voiceBook = MutableStateFlow<Long?>(null)
    private val appVisible = MutableStateFlow(false)
    private val voicePlaying = MutableStateFlow(false)
    private val pending = MutableStateFlow<List<Long>>(emptyList())
    private val inFlight = ConcurrentHashMap<Long, Deferred<RawPageText?>>()
    private val mutableStatus = MutableStateFlow(OcrStatus())

    val status: StateFlow<OcrStatus> = mutableStatus

    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) { removeOrphans() }
        scope.launch(Dispatchers.Default) {
            combine(enabled, readerBook, voiceBook, pending, active()) { on, r, v, p, act ->
                if (!on || !act) null else (r ?: v ?: p.firstOrNull())?.let { it to (r != null || v != null) }
            }
                .distinctUntilChanged()
                .collectLatest { target -> if (target != null) follow(target.first, focused = target.second) }
        }
    }

    /** Trabaja solo con la app en pantalla o la voz sonando. */
    private fun active() = combine(appVisible, voicePlaying) { a, v -> a || v }.distinctUntilChanged()

    fun setEnabled(on: Boolean) { enabled.value = on; mutableStatus.value = mutableStatus.value.copy(enabled = on) }
    fun setReaderBook(id: Long?) { readerBook.value = id }
    fun setVoice(bookId: Long?, playing: Boolean) { voiceBook.value = bookId; voicePlaying.value = playing }
    fun setAppVisible(visible: Boolean) { appVisible.value = visible }

    /** Texto de las paginas escaneadas de [book] para su [PdfBook]. */
    fun fallbackFor(book: Book): PageTextFallback = PageTextFallback { pdf, index ->
        store.read(book.id, index) ?: if (enabled.value) page(book, pdf, index, urgent = true) else null
    }

    /** Pagina reconocida (del disco o reconociendola); sin repetir trabajo en curso. */
    private suspend fun page(book: Book, pdf: PdfBook, index: Int, urgent: Boolean): RawPageText? {
        withContext(Dispatchers.IO) { store.read(book.id, index) }?.let { return it }
        val key = (book.id shl 24) or index.toLong()
        val mine = CompletableDeferred<RawPageText?>()
        val running = inFlight.putIfAbsent(key, mine)
        if (running != null) return running.await()
        try {
            val r = recognizer.recognize(pdf, index, OcrEngine.languageFor(book.language), urgent)
            if (r != null) {
                withContext(Dispatchers.IO) { store.write(book.id, index, r) }
                pdf.invalidateText(index)
            }
            mine.complete(r)
            return r
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(key, mine)
        }
    }

    /**
     * Procesa [bookId]. Si no es el que se lee o escucha, al acabar sale de la cola; si lo es,
     * espera a que cambie su posicion (un salto) para seguir desde alli. Se cancela al cambiar
     * de objetivo.
     */
    private suspend fun follow(bookId: Long, focused: Boolean) {
        // Los EPUB no necesitan OCR: ni se vigila su posicion.
        if (library.get(bookId)?.format != BookFormat.PDF) {
            pending.value = pending.value - bookId
            return
        }
        while (true) {
            try {
                process(bookId, focused)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "OCR de fondo detenido en el libro $bookId", e)
            }
            if (!focused) {
                pending.value = pending.value - bookId
                return
            }
            val from = library.get(bookId)?.locator?.chapter
            library.observe(bookId).map { it?.locator?.chapter }.distinctUntilChanged().first { it != from }
        }
    }

    /** Recorre [bookId] por delante de su posicion. */
    private suspend fun process(bookId: Long, focused: Boolean) {
        val book = library.get(bookId)
        if (book == null || book.format != BookFormat.PDF || !shouldProcess(book, focused)) return
        val (opened, release) = openPdf(book)
        try {
            val pdf = opened ?: return
            if (withContext(Dispatchers.IO) { store.isTextual(bookId) } || looksTextual(bookId, pdf)) return
            val native = withContext(Dispatchers.IO) { store.nativePages(bookId) }.toHashSet()
            val newNative = ArrayList<Int>()
            var i = startPage(bookId, pdf.pageCount)
            var end = endPage(book, i, pdf.pageCount)
            mutableStatus.value = mutableStatus.value.copy(working = true)
            while (i < end) {
                // Si el usuario salta a otra parte, se sigue desde alli.
                val now = startPage(bookId, pdf.pageCount)
                if (now > i || now < i - BACK_JUMP) { i = now; end = endPage(book, i, pdf.pageCount); continue }
                if (i in native || withContext(Dispatchers.IO) { store.has(bookId, i) }) { i++; continue }
                if (pdf.hasOwnText(i)) {
                    native += i
                    newNative += i
                    if (newNative.size >= NATIVE_FLUSH) { flushNative(bookId, newNative) }
                    i++
                    continue
                }
                waitForGoodConditions()
                val t0 = System.nanoTime()
                page(book, pdf, i, urgent = false)
                // Sin cargador: descanso igual al trabajo (la CPU nunca va al limite).
                if (!conditions.charging()) delay(((System.nanoTime() - t0) / 1_000_000).coerceIn(MIN_REST_MS, MAX_REST_MS))
                i++
            }
            flushNative(bookId, newNative)
        } finally {
            mutableStatus.value = mutableStatus.value.copy(working = false)
            release()
            refreshSize()
        }
    }

    /** Terminado: los ya leidos solo se procesan si se estan leyendo o escuchando. */
    private fun shouldProcess(book: Book, focused: Boolean): Boolean = !book.finished || focused

    private suspend fun startPage(bookId: Long, count: Int): Int =
        (library.get(bookId)?.locator?.chapter ?: 0).coerceIn(0, (count - 1).coerceAtLeast(0))

    private fun endPage(book: Book, start: Int, count: Int): Int =
        if (book.finished) minOf(count, start + FINISHED_WINDOW) else count

    /** Muestra de paginas: si todas traen texto, el PDF no es escaneado (y se recuerda). */
    private suspend fun looksTextual(bookId: Long, pdf: PdfBook): Boolean {
        val n = pdf.pageCount
        if (n == 0) return true
        val sample = (0 until SAMPLE).map { (it.toLong() * (n - 1) / (SAMPLE - 1).coerceAtLeast(1)).toInt() }.distinct()
        val withText = sample.filter { pdf.hasOwnText(it) }
        if (withText.size == sample.size) {
            withContext(Dispatchers.IO) { store.markTextual(bookId) }
            return true
        }
        withContext(Dispatchers.IO) { store.markNative(bookId, withText) }
        return false
    }

    private suspend fun flushNative(bookId: Long, pages: MutableList<Int>) {
        if (pages.isEmpty()) return
        val copy = pages.toList()
        pages.clear()
        withContext(Dispatchers.IO) { store.markNative(bookId, copy) }
    }

    private suspend fun waitForGoodConditions() {
        while (!conditions.good()) delay(conditionsPollMs)
    }

    private suspend fun removeOrphans() {
        val ids = library.books.first().map { it.id }.toSet()
        for (id in store.books()) if (id !in ids) store.deleteBook(id)
        refreshSize()
    }

    private fun refreshSize() {
        scope.launch(Dispatchers.IO) { mutableStatus.value = mutableStatus.value.copy(storedBytes = store.sizeBytes()) }
    }

    /** Borra todo el texto reconocido guardado (Ajustes). */
    fun clearAll() {
        scope.launch(Dispatchers.IO) {
            for (id in store.books()) store.deleteBook(id)
            refreshSize()
        }
    }

    // ------------------------------------------------------------------ avisos de la biblioteca

    override suspend fun imported(book: Book) {
        if (book.format == BookFormat.PDF) pending.value = pending.value + book.id
    }

    override suspend fun finished(book: Book, finished: Boolean) {
        if (finished) release(book.id)
    }

    override suspend fun deleted(book: Book) {
        pending.value = pending.value - book.id
        release(book.id)
    }

    /** Libera lo guardado de [bookId]: texto reconocido y audio. */
    private suspend fun release(bookId: Long) = withContext(Dispatchers.IO) {
        store.deleteBook(bookId)
        removeAudio(bookId)
        refreshSize()
    }

    companion object {
        private const val TAG = "OcrManager"
        private const val SAMPLE = 8
        private const val FINISHED_WINDOW = 10
        private const val BACK_JUMP = 3
        private const val NATIVE_FLUSH = 20
        private const val MIN_REST_MS = 300L
        private const val MAX_REST_MS = 5_000L
        private const val CONDITIONS_POLL_MS = 60_000L
    }
}

/** Estado del movil para el trabajo de fondo. */
interface DeviceConditions {
    fun charging(): Boolean

    /** true si se puede trabajar de fondo (sin ahorro de bateria, bateria suficiente, no caliente). */
    fun good(): Boolean
}

class AndroidConditions(private val context: Context) : DeviceConditions {
    private fun battery(): Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    override fun charging(): Boolean {
        val s = battery()?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: return false
        return s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL
    }

    override fun good(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        if (pm.isPowerSaveMode) return false
        if (Build.VERSION.SDK_INT >= 29 && pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) return false
        val b = battery() ?: return true
        val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        return charging() || level < 0 || level * 100 / scale >= MIN_BATTERY
    }

    private companion object {
        const val MIN_BATTERY = 20
    }
}
