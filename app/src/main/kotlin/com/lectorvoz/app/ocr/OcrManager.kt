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
import com.lectorvoz.core.text.MissingTextDetector
import com.lectorvoz.core.text.PageTextMerger
import com.lectorvoz.format.pdf.MixedTextProvider
import com.lectorvoz.format.pdf.PageTextFallback
import com.lectorvoz.format.pdf.PdfBook
import com.lectorvoz.format.pdf.RawPageText
import com.lectorvoz.format.pdf.ocr.OcrEngine
import com.lectorvoz.format.pdf.ocr.OcrRegionResult
import com.lectorvoz.format.pdf.ocr.OcrStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.debounce
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
 *   con la app en pantalla o la voz sonando; se detiene con el movil caliente y (si esta
 *   activado, por defecto) con ahorro de bateria o bateria baja sin cargar; sin cargador deja
 *   descansos entre paginas.
 * - **Al momento**: si la voz llega a una pagina aun sin reconocer, se reconoce esa con
 *   prioridad (la voz trabaja por delante de lo que suena, asi que no hay corte). Al pasar
 *   paginas no se espera: el trabajo de fondo salta a la pagina a la que se llega.
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
    /** Libera el modelo y su copia en disco (OCR desactivado). */
    private val discardModels: () -> Unit = { OcrEngine.discardModels(context) },
) : LibraryListener {
    private val enabled = MutableStateFlow(true)
    /** Modo MIXTO (recuperar palabras que faltan) activado; ajuste recoverMissingText. */
    private val recoverEnabled = MutableStateFlow(true)
    /** (libro shl 24)|pagina de las paginas donde el mixto ya vio que NO hay nada que recuperar. */
    private val noRecover = ConcurrentHashMap.newKeySet<Long>()
    @Volatile private var saveBattery = true
    private val readerBook = MutableStateFlow<Long?>(null)
    private val voiceBook = MutableStateFlow<Long?>(null)
    private val appVisible = MutableStateFlow(false)
    private val voicePlaying = MutableStateFlow(false)
    private val pending = MutableStateFlow<List<Long>>(emptyList())
    private val inFlight = ConcurrentHashMap<Long, Deferred<RawPageText?>>()

    /** Claves de los reconocimientos FORZADOS por libro en vuelo (no se cancelan al apagar el OCR global). */
    private val forcedKeys = ConcurrentHashMap.newKeySet<Long>()
    private val pageCounts = ConcurrentHashMap<Long, Int>()
    private val mutableStatus = MutableStateFlow(OcrStatus())

    val status: StateFlow<OcrStatus> = mutableStatus

    private val mutableRecognizing = MutableStateFlow<Set<Long>>(emptySet())

    /** Paginas reconociendose ahora ((libro shl 24) or pagina): el lector lo avisa en la suya. */
    val recognizing: StateFlow<Set<Long>> = mutableRecognizing

    /** true si la pagina [index] de [bookId] se esta reconociendo. */
    fun isRecognizing(set: Set<Long>, bookId: Long, index: Int): Boolean = ((bookId shl 24) or index.toLong()) in set

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

    fun setEnabled(on: Boolean) {
        val was = enabled.value
        enabled.value = on
        mutableStatus.value = mutableStatus.value.copy(enabled = on)
        if (was && !on) {
            // Cancela los reconocimientos AUTOMATICOS en vuelo antes de soltar el modelo (si no,
            // uno a medio hacer volveria a instalar ~40 MB y a cargar los modelos recien
            // borrados, M4). Los FORZADOS por libro (decision explicita del usuario, S2-1) NO se
            // cancelan: deben funcionar aunque el OCR global este apagado, y la voz podria
            // estarlos esperando. Si queda alguno en vuelo no se descarta el modelo (lo usa).
            inFlight.forEach { (key, job) -> if (key !in forcedKeys) job.cancel() }
            if (forcedKeys.isEmpty()) discardModels()
        }
    }

    /** Activa o desactiva el modo MIXTO (recuperar palabras que faltan). */
    fun setRecoverMissingText(on: Boolean) {
        recoverEnabled.value = on
        if (!on) noRecover.clear()
    }

    /**
     * Proveedor del modo MIXTO para [book]: dado el texto NATIVO de una pagina, recupera con OCR
     * solo las palabras que el PDF no expone (detector "tinta menos cajas") y las fusiona en orden
     * de lectura. Null si no procede. El seam de [PdfBook] ya garantiza que solo se llama en
     * paginas con texto propio y sin OCR forzado; aqui se decide con el AJUSTE, el OCR global y la
     * CONFIANZA de cada region reconocida.
     */
    fun mixedFor(book: Book): MixedTextProvider = MixedTextProvider { pdf, index, native, wait ->
        if (!recoverEnabled.value || !enabled.value) return@MixedTextProvider null
        val key = (book.id shl 24) or index.toLong()
        if (key in noRecover) return@MixedTextProvider null
        val nativeBoxes = native.copyBoxes()
        var detectRan = false
        var regionCount = 0
        val results = OcrEngine.get(context).recoverMissingText(pdf, index, OcrEngine.languageFor(book.language), urgent = wait) { gray, w, h, scale ->
            detectRan = true
            val regions = MissingTextDetector.detect(MissingTextDetector.GrayPage(gray, w, h, scale), nativeBoxes)
            regionCount = regions.size
            regions.map { floatArrayOf(it.left, it.top, it.right, it.bottom) }
        }
        // Filtro de CONFIANZA: solo se inserta lo que el OCR reconocio como texto fiable; las
        // zonas que no son texto (figuras, casillas, lineas) vuelven vacias/con baja confianza y
        // se descartan (asi los falsos positivos del detector son inofensivos).
        val lines = results.filter { confident(it) }.map { r ->
            PageTextMerger.OcrLine(r.text, r.charBoxes, MissingTextDetector.Region(r.region[0], r.region[1], r.region[2], r.region[3]))
        }
        if (lines.isEmpty()) {
            // El detector corrio y no encontro nada que recuperar: no volver a renderizar/detectar
            // esta pagina. Si el modelo no estaba listo (detectRan=false) se reintentara luego.
            if (detectRan && regionCount == 0) noRecover.add(key)
            return@MixedTextProvider null
        }
        val merged = PageTextMerger.merge(native.rawText, nativeBoxes, lines)
        RawPageText(merged.text, merged.boxes)
    }

    /** Pausar con ahorro de bateria o bateria baja (Ajustes, activado por defecto). */
    fun setSaveBattery(on: Boolean) { saveBattery = on }
    fun setReaderBook(id: Long?) { readerBook.value = id }
    fun setVoice(bookId: Long?, playing: Boolean) { voiceBook.value = bookId; voicePlaying.value = playing }
    fun setAppVisible(visible: Boolean) { appVisible.value = visible }

    /**
     * Texto de las paginas escaneadas de [book] para su [PdfBook]. Si la voz lo espera y el
     * reconocimiento falla, error (la voz lo muestra) en vez de una pagina vacia que se saltaria:
     * saltar todas las de un libro escaneado lo daria por terminado y borraria lo guardado.
     */
    fun fallbackFor(book: Book): PageTextFallback = PageTextFallback { pdf, index, wait ->
        withContext(Dispatchers.IO) { store.read(book.id, index) } ?: run {
            // El interruptor global ([enabled]) gobierna el OCR AUTOMATICO de los PDF escaneados.
            // El forzado por libro ([PdfBook.forceFallback]) es una decision explicita del usuario:
            // su lectura a demanda (wait) reconoce aunque el OCR global este apagado (S2-1).
            val forced = pdf.forceFallback
            // Pasando paginas (sin esperar): texto provisional, no se reconoce ni se cachea.
            if (!wait) return@run null
            // La voz/busqueda espera texto de una pagina escaneada pero el OCR global esta apagado
            // y el libro no lo fuerza: fallo VISIBLE (la voz avisa) en vez de una pagina vacia que
            // se saltaria en silencio y daria el libro por terminado (borrando lo guardado).
            if (!enabled.value && !forced) throw java.io.IOException("Activa \"Reconocer el texto (OCR)\" en Ajustes para leer este PDF escaneado")
            val r = page(book, pdf, index, urgent = true, force = forced) ?: throw java.io.IOException("No se pudo reconocer el texto de la pagina ${index + 1}")
            // La voz va pagina a pagina: la siguiente se adelanta de fondo.
            if (index + 1 < pdf.pageCount) scope.launch { runCatching { page(book, pdf, index + 1, urgent = false, force = forced) } }
            r
        }
    }

    /**
     * Pagina reconocida (del disco o reconociendola); sin repetir trabajo en curso. El
     * reconocimiento no depende de quien lo pide: si este se cancela (pausa, salto, la app pasa
     * a segundo plano) la pagina se termina y se guarda igual, sin tirar el trabajo hecho.
     */
    private suspend fun page(book: Book, pdf: PdfBook, index: Int, urgent: Boolean, force: Boolean = false): RawPageText? {
        withContext(Dispatchers.IO) { store.read(book.id, index) }?.let { return it }
        val key = (book.id shl 24) or index.toLong()
        // Forzado por libro: marcar la clave para que apagar el OCR global no lo cancele (S2-1).
        if (force) forcedKeys.add(key)
        val job = inFlight.computeIfAbsent(key) {
            scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try {
                    // Si se desactivo el OCR entre el encolado y el arranque, no se instala ni usa el
                    // modelo; salvo forzado por libro ([force]), peticion explicita del usuario (S2-1).
                    if (!enabled.value && !force) return@async null
                    mutableRecognizing.update { it + key }
                    val r = recognizer.recognize(pdf, index, OcrEngine.languageFor(book.language), urgent)
                    if (r != null) {
                        store.write(book.id, index, r)
                        pdf.invalidateText(index)
                        // Quitado de la biblioteca mientras se reconocia: no dejar restos.
                        if (library.get(book.id) == null) store.deleteBook(book.id)
                    }
                    r
                } finally {
                    inFlight.remove(key)
                    forcedKeys.remove(key)
                    mutableRecognizing.update { it - key }
                }
            }
        }
        job.start()
        return job.await()
    }

    /**
     * Procesa [bookId]. Si no es el que se lee o escucha, al acabar sale de la cola; si lo es,
     * espera a que su posicion cambie y se asiente (pasar paginas deprisa no lanza trabajo en
     * cada una) para seguir desde alli. Se cancela al cambiar de objetivo. El PDF se abre solo
     * si hace falta y una vez por seguimiento, no en cada pagina.
     */
    private suspend fun follow(bookId: Long, focused: Boolean) {
        // Los EPUB no necesitan OCR: ni se vigila su posicion.
        if (library.get(bookId)?.format != BookFormat.PDF) {
            pending.value = pending.value - bookId
            return
        }
        var lease: Pair<PdfBook?, () -> Unit>? = null
        try {
            while (true) {
                try {
                    process(bookId, focused) { b -> (lease ?: openPdf(b).also { lease = it }).first }
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
                library.observe(bookId).map { it?.locator?.chapter }.distinctUntilChanged().debounce(SETTLE_MS).first { it != from }
            }
        } finally {
            lease?.second?.invoke()
            refreshSize()
        }
    }

    /**
     * Recorre [bookId] por delante de su posicion: los escaneados hasta el final (o una ventana
     * si ya esta terminado); los que traen texto, solo unas paginas por delante (por si mezclan
     * paginas escaneadas). Lo ya sabido (paginas con texto y reconocidas) no vuelve a mirarse.
     */
    private suspend fun process(bookId: Long, focused: Boolean, pdfOf: suspend (Book) -> PdfBook?) {
        var book = library.get(bookId) ?: return
        if (book.format != BookFormat.PDF || !shouldProcess(book, focused)) return
        // TODO(S2-2, futura "pre-OCR"): si el libro quedo marcado "textual" (y sus paginas como
        // native) en una pasada anterior, al forzar OCR por libro el seguimiento de fondo lo sigue
        // viendo como textual (ventana corta por delante) y salta las paginas native sin
        // reconocerlas; la voz obtiene el OCR a demanda ([fallbackFor] con wait), con posible
        // latencia entre paginas. Pendiente: al forzar OCR, ignorar o invalidar esas marcas para
        // preleer por delante. No se implementa aqui.
        val textual = withContext(Dispatchers.IO) { store.isTextual(bookId) }
        val native = withContext(Dispatchers.IO) { store.nativePages(bookId) }.toHashSet()
        val stored = withContext(Dispatchers.IO) { store.recognizedPages(bookId) }.toHashSet()
        val newNative = ArrayList<Int>()
        var count = pageCounts[bookId]
        var pos = position(book, count)
        var i = pos
        var end = endPage(book, textual, pos, count)
        var sinceCheck = 0
        try {
            mutableStatus.value = mutableStatus.value.copy(working = true)
            if (!textual && !sampled(bookId, native, stored, pdfOf(book) ?: return)) {
                // Resulto ser un PDF con texto: solo la ventana por delante.
                return process(bookId, focused, pdfOf)
            }
            while (i < end) {
                // Si el usuario salta a otra parte (o termina el libro), se sigue desde alli.
                if (++sinceCheck >= RECHECK) {
                    sinceCheck = 0
                    book = library.get(bookId) ?: return
                    if (!shouldProcess(book, focused)) return
                    val now = position(book, count)
                    if (now != pos) {
                        pos = now
                        if (now > i || now < i - BACK_JUMP) i = now
                    }
                    end = endPage(book, textual, pos, count)
                    if (i >= end) break
                }
                if (i in native || i in stored) { i++; continue }
                val pdf = pdfOf(book) ?: return
                if (count == null) {
                    count = pdf.pageCount.also { pageCounts[bookId] = it }
                    end = endPage(book, textual, pos, count)
                    if (i >= end) break
                }
                if (pdf.hasOwnText(i)) {
                    native += i
                    newNative += i
                    if (newNative.size >= NATIVE_FLUSH) flushNative(bookId, newNative)
                    i++
                    continue
                }
                waitForGoodConditions()
                val t0 = System.nanoTime()
                if (page(book, pdf, i, urgent = false) != null) stored += i
                // Sin cargador: descanso igual al trabajo (la CPU nunca va al limite).
                if (!conditions.charging()) delay(((System.nanoTime() - t0) / 1_000_000).coerceIn(MIN_REST_MS, MAX_REST_MS))
                i++
                sinceCheck = RECHECK // tras cada pagina reconocida, se mira la posicion
            }
        } finally {
            withContext(NonCancellable) { flushNative(bookId, newNative) }
            mutableStatus.value = mutableStatus.value.copy(working = false)
        }
    }

    /** Terminado: los ya leidos solo se procesan si se estan leyendo o escuchando. */
    private fun shouldProcess(book: Book, focused: Boolean): Boolean = !book.finished || focused

    private fun position(book: Book, count: Int?): Int {
        val p = book.locator?.chapter ?: 0
        return if (count == null) p.coerceAtLeast(0) else p.coerceIn(0, (count - 1).coerceAtLeast(0))
    }

    private fun endPage(book: Book, textual: Boolean, start: Int, count: Int?): Int {
        val limit = when {
            textual -> start + TEXTUAL_WINDOW
            book.finished -> start + FINISHED_WINDOW
            else -> Int.MAX_VALUE
        }
        return if (count == null) limit else minOf(count, limit)
    }

    /**
     * Muestra de paginas repartidas por el libro (solo la primera vez: lo que se sabe de cada
     * pagina queda guardado). Si todas traen texto, el PDF no es escaneado y se recuerda;
     * devuelve false en ese caso.
     */
    private suspend fun sampled(bookId: Long, native: MutableSet<Int>, stored: Set<Int>, pdf: PdfBook): Boolean {
        val n = pdf.pageCount
        pageCounts[bookId] = n
        if (n == 0) {
            // Sin paginas: nada que reconocer (y se recuerda, para no volver a intentarlo).
            withContext(Dispatchers.IO) { store.markTextual(bookId) }
            return false
        }
        val sample = (0 until SAMPLE).map { (it.toLong() * (n - 1) / (SAMPLE - 1).coerceAtLeast(1)).toInt() }.distinct()
        if (sample.any { it in stored }) return true
        val unknown = sample.filter { it !in native }
        val withText = unknown.filter { pdf.hasOwnText(it) }
        if (withText.isNotEmpty()) {
            native += withText
            withContext(Dispatchers.IO) { store.markNative(bookId, withText) }
        }
        if (sample.all { it in native }) {
            withContext(Dispatchers.IO) { store.markTextual(bookId) }
            return false
        }
        return true
    }

    private suspend fun flushNative(bookId: Long, pages: MutableList<Int>) {
        if (pages.isEmpty()) return
        val copy = pages.toList()
        pages.clear()
        withContext(Dispatchers.IO) { store.markNative(bookId, copy) }
    }

    private suspend fun waitForGoodConditions() {
        while (!conditions.good(saveBattery)) delay(conditionsPollMs)
    }

    /** Quitados de la biblioteca o ya terminados (lo de una relectura): fuera. */
    private suspend fun removeOrphans() {
        val keep = library.books.first().filter { !it.finished }.map { it.id }.toSet()
        for (id in store.books()) if (id !in keep) store.deleteBook(id)
        store.removePartials()
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

    /** Libera lo guardado de [bookId]: texto reconocido, audio y el recuento de paginas en memoria. */
    private suspend fun release(bookId: Long) = withContext(Dispatchers.IO) {
        store.deleteBook(bookId)
        removeAudio(bookId)
        pageCounts.remove(bookId) // B10: no dejar un Int por libro procesado durante la vida del proceso.
        refreshSize()
    }

    /** Filtro de confianza: una region OCR se inserta solo si parece texto fiable. */
    private fun confident(r: OcrRegionResult): Boolean {
        if (r.text.isBlank() || r.wordCount == 0) return false
        if (r.meanConfidence < MIN_REGION_CONFIDENCE) return false
        val letters = r.text.count { it.isLetterOrDigit() }
        val nonSpace = r.text.count { !it.isWhitespace() }
        // Al menos 2 alfanumericos y que no sea mayoria de simbolos (basura de figuras/lineas).
        return letters >= 2 && letters * 2 >= nonSpace
    }

    companion object {
        private const val TAG = "OcrManager"
        /** Confianza media minima (0..100) de una region para insertar su texto en el mixto. */
        private const val MIN_REGION_CONFIDENCE = 60f
        private const val SAMPLE = 8
        private const val FINISHED_WINDOW = 10
        private const val BACK_JUMP = 3
        private const val TEXTUAL_WINDOW = 5
        private const val RECHECK = 16
        private const val SETTLE_MS = 800L
        private const val NATIVE_FLUSH = 20
        private const val MIN_REST_MS = 300L
        private const val MAX_REST_MS = 5_000L
        private const val CONDITIONS_POLL_MS = 60_000L
    }
}

/** Estado del movil para el trabajo de fondo. */
interface DeviceConditions {
    fun charging(): Boolean

    /**
     * true si se puede trabajar de fondo: el movil no esta caliente y, con [saveBattery], sin
     * ahorro de bateria y con bateria suficiente (o cargando).
     */
    fun good(saveBattery: Boolean): Boolean
}

class AndroidConditions(private val context: Context) : DeviceConditions {
    private fun battery(): Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    override fun charging(): Boolean {
        val s = battery()?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: return false
        return s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL
    }

    override fun good(saveBattery: Boolean): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        if (Build.VERSION.SDK_INT >= 29 && pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) return false
        if (!saveBattery) return true
        if (pm.isPowerSaveMode) return false
        val b = battery() ?: return true
        val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        return charging() || level < 0 || level * 100 / scale >= MIN_BATTERY
    }

    private companion object {
        const val MIN_BATTERY = 20
    }
}
