package com.lectorvoz.format.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.lectorvoz.core.model.BookMetadata
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.TocEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.roundToInt

/**
 * Documento PDF abierto. Todas las operaciones pasan por un unico hilo ([PdfiumDispatcher])
 * porque pdfium no es thread-safe. Se puede llamar desde cualquier hilo/corrutina.
 *
 * Coordenadas de pagina: puntos PDF con origen arriba a la izquierda (ver [pageSize]).
 */
class PdfBook internal constructor(
    private val backend: PdfBackend,
    private val dispatcher: CoroutineDispatcher,
    /** Metadatos (titulo del PDF o, si no tiene, el nombre del fichero). */
    val metadata: BookMetadata,
    /** Indice a partir de los marcadores; vacio si no hay. */
    val toc: List<TocEntry>,
) : AutoCloseable {

    /** Numero de paginas. */
    val pageCount: Int = backend.pageCount

    /** Id corto de esta instancia (diagnostico: distinguir el PdfBook del lector del de la voz). */
    val diagInstanceId: Int = nextInstanceId()
    private val instanceId: Int get() = diagInstanceId

    private val sizes = arrayOfNulls<PageSize>(pageCount)
    private val textCache = object : LinkedHashMap<Int, PdfPageText>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PdfPageText>) = size > TEXT_CACHE
    }

    /** Ultimas paginas con un limpiador distinto de [PageTextCleaner.Identity] (mismo candado). */
    private val cleanedCache = object : LinkedHashMap<Int, PdfPageText>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PdfPageText>) = size > TEXT_CACHE
    }

    @Volatile
    private var closed = false

    /**
     * Texto de las paginas sin texto propio (PDF escaneados): lo pone la app con el OCR.
     * Nulo = esas paginas quedan sin texto.
     */
    @Volatile
    var textFallback: PageTextFallback? = null

    /** Paginas de la cache cuyo texto vino de [textFallback] (mismo candado que la cache). */
    private val fallbackPages = HashSet<Int>()

    /** Paginas escaneadas cuyo reconocimiento fallo en la ultima peticion (mismo candado). */
    private val failedPages = HashSet<Int>()

    /**
     * Fusiona texto nativo + OCR dirigido (modo MIXTO): tercer camino de extraccion para PDF
     * cuya capa de texto esta incompleta (p. ej. versalitas que pdfium dibuja pero no lee) sin
     * degradar el texto correcto. Nulo = sin mixto (la pagina usa su texto propio tal cual).
     * Solo actua cuando la pagina SI trae texto propio y NO se fuerza el OCR de pagina entera.
     */
    @Volatile
    var mixedProvider: MixedTextProvider? = null

    /**
     * Paginas de la cache cuyo texto ya se FUSIONO (mixto nativo+OCR), paralela a [fallbackPages]
     * (mismo candado que la cache). Una entrada de texto propio que aun no esta aqui se recomputa
     * en la primera peticion con espera para fusionar el mixto (ver [shouldRemixCached]).
     */
    private val mixedPages = HashSet<Int>()

    /**
     * Fuerza el OCR ([textFallback]) aunque la pagina traiga su capa de texto: para PDF con
     * texto incompleto (p. ej. versalitas que pdfium no extrae y la voz se saltaria). Al
     * cambiar se olvida el texto ya extraido para volver a obtenerlo por OCR.
     */
    @Volatile
    var forceFallback: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            synchronized(textCache) {
                forceFallbackGen++
                textCache.clear()
                cleanedCache.clear()
                fallbackPages.clear()
                failedPages.clear()
                mixedPages.clear()
            }
        }

    /**
     * Generacion del estado de [forceFallback] (sube en cada cambio, bajo el candado de la
     * cache). [pageText] la captura al decidir el OCR y la revisa antes de cachear: si cambio
     * en medio (carrera con el setter, M1) no cachea una pagina incoherente.
     */
    @Volatile
    private var forceFallbackGen: Int = 0

    /**
     * Punto de prueba: se invoca tras decidir el OCR y antes de insertar en la cache, para
     * reproducir de forma determinista la carrera con el setter de [forceFallback] (M1). Nulo
     * en produccion (coste: una comprobacion de nulo por pagina no cacheada).
     */
    internal var onBeforeCacheForTest: (() -> Unit)? = null

    private fun checkOpen() { if (closed) throw PdfException.Closed() }

    private fun checkIndex(index: Int) {
        require(index in 0 until pageCount) { "Pagina $index fuera de 0..${pageCount - 1}" }
    }

    /** Tamano de la pagina [index] en puntos. */
    suspend fun pageSize(index: Int): PageSize {
        checkIndex(index)
        sizes[index]?.let { return it }
        return withContext(dispatcher) {
            checkOpen()
            sizes[index] ?: backend.pageSizePoints(index).also { sizes[index] = it }
        }
    }

    /**
     * Dibuja la pagina [index] en [bitmap] (ARGB_8888 o RGB_565, reutilizable) a escala
     * [scale] pixeles por punto. [left]/[top] son el origen de la region que se quiere ver,
     * en pixeles a esa escala (0,0 = esquina de la pagina): sirve para zoom y recorte.
     * El bitmap se rellena de blanco donde no hay pagina.
     */
    suspend fun renderPage(index: Int, bitmap: Bitmap, scale: Float, left: Int = 0, top: Int = 0) {
        checkIndex(index)
        require(scale > 0f) { "scale debe ser positivo" }
        val size = pageSize(index)
        withContext(dispatcher) {
            checkOpen()
            // La llamada nativa no se puede interrumpir: descartar aqui los renders obsoletos.
            currentCoroutineContext().ensureActive()
            bitmap.eraseColor(android.graphics.Color.WHITE)
            backend.render(
                index, bitmap,
                startX = -left, startY = -top,
                drawWidth = (size.width * scale).roundToInt().coerceAtLeast(1),
                drawHeight = (size.height * scale).roundToInt().coerceAtLeast(1),
            )
        }
    }

    /**
     * Texto con cajas de la pagina [index], ya filtrado con [cleaner]. Se cachean las
     * ultimas paginas (el texto fluido se calcula una vez por pagina y limpiador).
     *
     * [wait]: en una pagina escaneada aun sin reconocer, esperar al OCR (la voz, buscar,
     * seleccionar). Sin esperar (pasar paginas) se devuelve el texto propio sin cachearlo,
     * para no frenar la navegacion ni amontonar reconocimientos de paginas que solo se ojean.
     */
    suspend fun pageText(index: Int, cleaner: PageTextCleaner = PageTextCleaner.Identity, wait: Boolean = true): PdfPageText {
        checkIndex(index)
        checkOpen()
        if (cleaner !== PageTextCleaner.Identity) {
            val cached = synchronized(textCache) {
                cleanedCache[index]?.takeIf { it.usesCleaner(cleaner) && !shouldRemixCached(index, wait, it.rawText) }
            }
            if (cached != null) {
                if (PdfDiag.on) PdfDiag.log("pageText inst=$instanceId idx=$index HIT-limpio wait=$wait mixed=${synchronized(textCache) { index in mixedPages }} fb=${synchronized(textCache) { index in fallbackPages }} len=${cached.rawText.length}")
                return cached
            }
        }
        val base = run {
            // Acierto de cache ya definitivo. Un acierto de TEXTO PROPIO aun sin fusionar (no esta
            // en mixedPages) se trata como FALLO cuando procede el mixto: hay que recomputar para
            // fusionar (p. ej. tras una peticion previa sin espera que solo cacheo el texto propio).
            synchronized(textCache) {
                textCache[index]?.let {
                    if (!shouldRemixCached(index, wait, it.rawText)) {
                        if (PdfDiag.on) PdfDiag.log("pageText inst=$instanceId idx=$index HIT wait=$wait mixed=${index in mixedPages} fb=${index in fallbackPages} len=${it.rawText.length}")
                        return@run it
                    }
                }
            }
            // Texto propio: lo ya cacheado (texto propio pendiente de fusion, sin re-extraer
            // pdfium) o extraido de pdfium.
            val own = synchronized(textCache) { textCache[index] } ?: withContext(dispatcher) {
                checkOpen()
                currentCoroutineContext().ensureActive()
                synchronized(textCache) { textCache[index] } ?: run {
                    val raw = backend.pageText(index)
                    val size = sizes[index] ?: backend.pageSizePoints(index).also { sizes[index] = it }
                    PdfPageText(index, raw.text, raw.boxes, size)
                }
            }
            // Pagina escaneada: el texto lo da el OCR (fuera del hilo de pdfium, que lo usa
            // para dibujar la pagina que reconoce).
            val fb = textFallback
            // Generacion del flag al decidir el OCR: si cambia antes de cachear, no se cachea.
            val gen = forceFallbackGen
            val needsFallback = fb != null && (forceFallback || !hasReadableText(own.rawText))
            var failed = false
            val recognized = if (needsFallback) {
                try {
                    fb!!.pageText(this, index, wait)?.let { PdfPageText(index, it.text, it.boxes, own.pageSize) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed = true
                    null
                }
            } else {
                null
            }
            // Solo lo que se espero (la voz): pasar paginas sin esperar no borra el aviso de fallo
            // que la voz va a consultar justo despues.
            if (wait) synchronized(textCache) { if (failed) failedPages += index else failedPages -= index }
            // Aun sin reconocer (sin esperar, o fallo): provisional, no se cachea; se vuelve a
            // intentar en la siguiente peticion.
            if (needsFallback && recognized == null && (!wait || failed)) {
                return if (cleaner === PageTextCleaner.Identity) own else own.withCleaner(cleaner)
            }
            // TERCER CAMINO: mixto texto+OCR. Precedencia: needsFallback (OCR de pagina entera) >
            // mixto > texto propio. Solo cuando la pagina SI trae texto propio (!needsFallback),
            // hay proveedor y se ESPERA: con wait=false no se fusiona, para no frenar el paso de
            // paginas (una peticion posterior con espera lo recomputa, ver shouldRemixCached).
            // Captura del proveedor para la guarda de carrera.
            val provider = mixedProvider
            val merged = if (!needsFallback && wait && provider != null) {
                try {
                    provider.mixedPageText(this, index, own, true)
                        ?.let { PdfPageText(index, it.text, it.boxes, own.pageSize) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Cualquier fallo del mixto: tratar como "sin mixto" (usar el texto propio).
                    null
                }
            } else {
                null
            }
            val page = recognized ?: merged ?: own
            val isFallback = recognized != null
            val isMixed = merged != null
            if (PdfDiag.on) PdfDiag.log("pageText inst=$instanceId idx=$index COMPUTE wait=$wait needsFallback=$needsFallback isMixed=$isMixed ownLen=${own.rawText.length} len=${page.rawText.length}")
            onBeforeCacheForTest?.invoke()
            synchronized(textCache) {
                // Otra corrutina pudo cachear ya la version definitiva mientras se calculaba.
                textCache[index]?.let { if (!shouldRemixCached(index, wait, it.rawText)) return@run it }
                // M1: si el flag de OCR cambio tras decidir el OCR (el setter se intercalo entre
                // el calculo y esta insercion) y la pagina que ibamos a cachear no trae OCR
                // aunque ahora se exija, no se cachea: seria incoherente (quedaria sin OCR hasta
                // invalidarla). Se devuelve provisional y la siguiente peticion la recalcula.
                if (gen != forceFallbackGen && forceFallback && recognized == null && fb != null) {
                    return if (cleaner === PageTextCleaner.Identity) page else page.withCleaner(cleaner)
                }
                // Guarda de carrera del mixto (analoga a M1): si el proveedor desaparecio o cambio,
                // o forceFallback cambio, entre decidir el mixto y cachear, no se cachea una entrada
                // mixta incoherente; se devuelve provisional y la siguiente peticion la recalcula.
                if (isMixed && (mixedProvider !== provider || gen != forceFallbackGen)) {
                    return if (cleaner === PageTextCleaner.Identity) page else page.withCleaner(cleaner)
                }
                page.also {
                    textCache[index] = it
                    if (isFallback) fallbackPages += index else fallbackPages -= index
                    if (isMixed) mixedPages += index else mixedPages -= index
                    // Si el texto crudo cambio (propio <-> fusionado/OCR), su version limpia
                    // cacheada queda obsoleta.
                    if (cleanedCache[index]?.rawText != it.rawText) cleanedCache.remove(index)
                }
            }
        }
        if (cleaner === PageTextCleaner.Identity) return base
        val cleaned = base.withCleaner(cleaner)
        synchronized(textCache) { cleanedCache[index] = cleaned }
        return cleaned
    }

    /**
     * Bajo el candado de [textCache]: true si una entrada de TEXTO PROPIO cacheada debe
     * recomputarse para fusionar el mixto. Ocurre cuando se ESPERA (wait), hay [mixedProvider],
     * no se fuerza el OCR, la pagina no esta ya fusionada ([mixedPages]) ni es OCR
     * ([fallbackPages]) y trae texto propio ([hasReadableText]). Asi una peticion con espera
     * posterior a una sin espera (que solo cacheo el texto propio) vuelve a fusionar.
     */
    private fun shouldRemixCached(index: Int, wait: Boolean, rawText: String): Boolean =
        wait && mixedProvider != null && !forceFallback &&
            index !in mixedPages && index !in fallbackPages && hasReadableText(rawText)

    /** Texto ya extraido de [index] (sin llamar a pdfium), o null. */
    fun cachedPageText(index: Int, cleaner: PageTextCleaner = PageTextCleaner.Identity): PdfPageText? =
        synchronized(textCache) {
            if (cleaner === PageTextCleaner.Identity) textCache[index] else cleanedCache[index]?.takeIf { it.usesCleaner(cleaner) }
        }

    /**
     * true si la pagina [index] trae texto propio (no hace falta OCR). No usa ni llena la
     * cache: es para revisar el libro en segundo plano.
     */
    suspend fun hasOwnText(index: Int): Boolean {
        checkIndex(index)
        // Con el OCR forzado ninguna pagina cuenta como "con texto propio": todas pasan por OCR.
        if (forceFallback) return false
        synchronized(textCache) { textCache[index]?.takeIf { index !in fallbackPages } }?.let { if (hasReadableText(it.rawText)) return true }
        return withContext(dispatcher) {
            checkOpen()
            hasReadableText(backend.pageText(index).text)
        }
    }

    /** Olvida el texto cacheado de [index] (el OCR acaba de reconocerla). */
    fun invalidateText(index: Int) {
        synchronized(textCache) { textCache.remove(index); cleanedCache.remove(index); fallbackPages -= index; failedPages -= index; mixedPages -= index }
    }

    /**
     * true si [index] es escaneada y su reconocimiento fallo en la ultima peticion: su texto
     * vacio no es una pagina en blanco (la voz no debe saltarla como si lo fuera).
     */
    fun textFailed(index: Int): Boolean = synchronized(textCache) { index in failedPages }

    /** Suelta el texto cacheado (respuesta a falta de memoria). */
    fun trimMemory() {
        synchronized(textCache) { textCache.clear(); cleanedCache.clear(); fallbackPages.clear(); mixedPages.clear() }
    }

    /** Fuente de texto para la voz (una "pagina" = un capitulo). */
    fun textSource(cleaner: PageTextCleaner = PageTextCleaner.Identity): PdfTextSource =
        PdfTextSource(this, cleaner)

    /** Cierra el documento (asincrono en el hilo de pdfium). */
    override fun close() {
        if (closed) return
        closed = true
        trimMemory()
        if (dispatcher.isDispatchNeeded(EmptyCoroutineContext)) {
            dispatcher.dispatch(EmptyCoroutineContext, Runnable { backend.close() })
        } else {
            backend.close()
        }
    }

    companion object {
        private const val TEXT_CACHE = 6

        /** Contador de instancias para el id de diagnostico. */
        private val instanceCounter = java.util.concurrent.atomic.AtomicInteger(0)
        private fun nextInstanceId(): Int = instanceCounter.incrementAndGet()

        /** Minimo de letras o cifras para considerar que una pagina trae texto propio. */
        private const val MIN_OWN_CHARS = 12

        /** true si [text] tiene texto real (no solo espacios o un numero de pagina suelto). */
        fun hasReadableText(text: String): Boolean {
            var n = 0
            for (c in text) if (c.isLetterOrDigit() && ++n >= MIN_OWN_CHARS) return true
            return false
        }

        /** Abre un PDF desde un Uri de SAF o content://. */
        suspend fun open(context: Context, uri: Uri, password: String? = null): PdfBook {
            val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "PDF"
            return withContext(PdfiumDispatcher.dispatcher) {
                val fd = try {
                    context.contentResolver.openFileDescriptor(uri, "r")
                        ?: throw PdfException.Unreadable("sin descriptor para $uri")
                } catch (e: FileNotFoundException) {
                    throw PdfException.Unreadable(e.message, e)
                } catch (e: SecurityException) {
                    throw PdfException.Unreadable(e.message, e)
                }
                create(PdfiumBackend.open(context, fd, password), PdfiumDispatcher.dispatcher, name)
            }
        }

        /** Abre un PDF desde un fichero local. */
        suspend fun open(context: Context, file: File, password: String? = null): PdfBook =
            withContext(PdfiumDispatcher.dispatcher) {
                val fd = try {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                } catch (e: FileNotFoundException) {
                    throw PdfException.Unreadable(e.message, e)
                }
                create(PdfiumBackend.open(context, fd, password), PdfiumDispatcher.dispatcher, file.nameWithoutExtension)
            }

        /**
         * Crea un libro sobre un [PdfBackend] cualquiera (pruebas o motores alternativos).
         * Debe llamarse desde [dispatcher].
         */
        fun create(backend: PdfBackend, dispatcher: CoroutineDispatcher, fallbackTitle: String): PdfBook {
            try {
                val meta = runCatching { backend.meta() }.getOrNull()
                val metadata = BookMetadata(
                    title = meta?.title ?: fallbackTitle,
                    authors = meta?.author?.split(';', ',', '&')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                    description = meta?.subject,
                )
                val toc = runCatching { backend.bookmarks() }.getOrDefault(emptyList()).map { it.toToc() }
                return PdfBook(backend, dispatcher, metadata, toc)
            } catch (e: RuntimeException) {
                runCatching { backend.close() }
                throw PdfException.Corrupt(e.message, e)
            }
        }

        private fun PdfBookmark.toToc(): TocEntry = TocEntry(
            title = title.ifBlank { "Pagina ${pageIndex + 1}" },
            target = Locator(pageIndex.coerceAtLeast(0), 0),
            children = children.map { it.toToc() },
        )
    }
}

/** Texto alternativo de las paginas sin texto propio (OCR). */
fun interface PageTextFallback {
    /**
     * Texto de la pagina [index] de [book] o nulo. Con [wait] puede tardar (reconocerla);
     * sin el, solo lo ya reconocido.
     */
    suspend fun pageText(book: PdfBook, index: Int, wait: Boolean): RawPageText?
}

/**
 * Modo MIXTO texto+OCR: fusiona el texto NATIVO de una pagina (el que si leyo pdfium, [native])
 * con el OCR dirigido de las zonas con tinta que pdfium dibujo pero no decodifico (p. ej.
 * versalitas), produciendo un (texto + cajas) UNIFICADO de la pagina. Es un TERCER camino de
 * extraccion: solo se invoca cuando la pagina SI trae texto propio y NO se fuerza el OCR de
 * pagina entera (ese tiene precedencia). No degrada el texto correcto de pdfium.
 */
fun interface MixedTextProvider {
    /**
     * Texto fusionado nativo+OCR de la pagina [index] de [book], o nulo si no hay nada que
     * anadir / no procede (entonces se usa [native] tal cual). [wait] permite tardar
     * (reconocer las zonas); el seam solo lo llama con espera.
     */
    suspend fun mixedPageText(book: PdfBook, index: Int, native: PdfPageText, wait: Boolean): RawPageText?
}
