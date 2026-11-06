package com.lectorvoz.format.pdf.ocr

import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import com.lectorvoz.format.pdf.PageSize
import com.lectorvoz.format.pdf.PdfBook
import com.lectorvoz.format.pdf.RawPageText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.io.IOException
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Texto recuperado por OCR de UNA region de la pagina (modo mixto). [region] es la zona pedida
 * [left,top,right,bottom] en puntos; [text] y [charBoxes] (4 floats por caracter, puntos) van en
 * par (charBoxes.size == text.length*4); [meanConfidence] 0..100 es la media de las palabras y
 * [wordCount] cuantas se reconocieron. Quien llama decide, con la confianza, si lo inserta.
 */
data class OcrRegionResult(
    val region: FloatArray,
    val text: String,
    val charBoxes: FloatArray,
    val meanConfidence: Float,
    val wordCount: Int,
)

/**
 * Reconocimiento de texto (OCR) de paginas PDF sin capa de texto, en el propio movil y sin
 * conexion (Tesseract). Un unico hilo de prioridad baja para todo el proceso: nunca compite
 * con la voz ni con el lector, y Tesseract no es seguro entre hilos.
 *
 * Las peticiones tienen prioridad: lo que se necesita ya (la voz o el lector llegan a una
 * pagina sin reconocer) pasa por delante de lo que se prepara en segundo plano. El modelo
 * (~50-100 MB de RAM) se carga al primer uso y se libera tras un rato sin trabajo.
 *
 * La pagina se dibuja a ~200-300 ppp, se pasa a gris por franjas (sin copias completas en
 * color) y se reconocen palabras con su rectangulo; cada caracter recibe una parte de la
 * caja de su palabra, para que el resaltado y la busqueda funcionen como en un PDF con texto.
 */
class OcrEngine private constructor(private val context: Context) {

    private class Job(
        val priority: Int,
        val seq: Long,
        val book: PdfBook?,
        val index: Int,
        val language: String,
        /** Reconocimiento de PAGINA entera (OCR clasico / escaneados). */
        val pageResult: CompletableDeferred<RawPageText?>? = null,
        /**
         * Modo MIXTO: dado el render gris de la pagina (pixeles, ancho, alto, pxPerPoint),
         * devuelve las regiones (cada una [left,top,right,bottom] en PUNTOS) a reconocer; el
         * motor OCR-ea solo esas. Lo aporta :app (ejecuta el detector de :core:text).
         */
        val recover: ((ByteArray, Int, Int, Float) -> List<FloatArray>)? = null,
        val recoverResult: CompletableDeferred<List<OcrRegionResult>>? = null,
        /** Cajas nativas (puntos) a blanquear en los recortes del modo mixto. */
        val maskBoxes: FloatArray? = null,
    ) : Comparable<Job> {
        val cancelled: Boolean get() = pageResult?.isCancelled == true || recoverResult?.isCancelled == true

        // Urgentes: la mas reciente primero (si se pasan paginas deprisa, la que se esta viendo
        // u oyendo ahora). De fondo: en orden.
        override fun compareTo(other: Job): Int = when {
            priority != other.priority -> other.priority - priority
            priority == URGENT -> other.seq.compareTo(seq)
            else -> seq.compareTo(other.seq)
        }
    }

    private val queue = PriorityBlockingQueue<Job>()
    private val seq = AtomicLong()
    private var api: TessBaseAPI? = null
    private var apiLanguage: String? = null
    private var bitmap: Bitmap? = null

    @Volatile private var lastPageMs = 0L

    /** Tiempo de la ultima pagina reconocida (para repartir el trabajo de fondo). */
    val lastPageTimeMs: Long get() = lastPageMs

    private val worker = Thread({ loop() }, "lectorvoz-ocr").apply {
        isDaemon = true
        start()
    }

    /**
     * Reconoce la pagina [index] de [book]. [urgent]: se necesita ya (pasa por delante del
     * trabajo de fondo). Nulo si no se pudo (sin modelos, fallo nativo). Cancelable: si la
     * corrutina se cancela antes de empezar, la pagina no se procesa.
     */
    suspend fun recognize(book: PdfBook, index: Int, language: String, urgent: Boolean): RawPageText? {
        val d = CompletableDeferred<RawPageText?>()
        val job = Job(if (urgent) URGENT else BACKGROUND, seq.incrementAndGet(), book, index, language, pageResult = d)
        queue.put(job)
        try {
            return d.await()
        } catch (e: CancellationException) {
            queue.remove(job)
            throw e
        }
    }

    /**
     * Modo MIXTO: renderiza la pagina [index] UNA vez, pasa el gris a [detect] (que localiza las
     * zonas de texto que pdfium no extrae) y OCR-ea SOLO esas zonas, devolviendo texto + cajas +
     * confianza por region. Vacio si no hay modelos, no hay regiones o falla. Cancelable.
     * [detect] se ejecuta en el hilo del OCR (barato); devuelve cada region como
     * [left,top,right,bottom] en PUNTOS.
     */
    suspend fun recoverMissingText(
        book: PdfBook,
        index: Int,
        language: String,
        urgent: Boolean,
        /** Cajas del texto NATIVO (puntos): sus pixeles se blanquean en cada recorte antes de
         *  OCR-earlo, para que el OCR solo "vea" el texto que pdfium NO leyo y no duplique lo que
         *  ya estaba bien (lineas con parte nativa + parte sin decodificar). */
        nativeBoxes: FloatArray,
        detect: (gray: ByteArray, width: Int, height: Int, pxPerPoint: Float) -> List<FloatArray>,
    ): List<OcrRegionResult> {
        val d = CompletableDeferred<List<OcrRegionResult>>()
        val job = Job(if (urgent) URGENT else BACKGROUND, seq.incrementAndGet(), book, index, language, recover = detect, recoverResult = d, maskBoxes = nativeBoxes)
        queue.put(job)
        try {
            return d.await()
        } catch (e: CancellationException) {
            queue.remove(job)
            throw e
        }
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_LESS_FAVORABLE)
        while (true) {
            // Con el modelo cargado se espera un rato y se libera; sin el, hasta el siguiente
            // trabajo (el hilo no se despierta para nada).
            val job = if (api == null && bitmap == null) queue.take() else queue.poll(IDLE_RELEASE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (job == null) {
                releaseModel()
                continue
            }
            if (job.cancelled) continue
            if (job.book == null) {
                // OCR desactivado: fuera el modelo de la memoria y sus ficheros del disco.
                releaseModel()
                modelsDir().deleteRecursively()
                job.pageResult?.complete(null)
                continue
            }
            val started = System.nanoTime()
            if (job.recover != null) {
                val out = try {
                    runRecover(job)
                } catch (e: Throwable) {
                    Log.w(TAG, "OCR mixto fallido en la pagina ${job.index}", e)
                    if (e is OutOfMemoryError) releaseModel()
                    emptyList()
                }
                lastPageMs = (System.nanoTime() - started) / 1_000_000
                job.recoverResult?.complete(out)
            } else {
                val out = try {
                    runPage(job)
                } catch (e: Throwable) {
                    Log.w(TAG, "OCR fallido en la pagina ${job.index}", e)
                    if (e is OutOfMemoryError) releaseModel()
                    null
                }
                lastPageMs = (System.nanoTime() - started) / 1_000_000
                job.pageResult?.complete(out)
            }
        }
    }

    private fun runPage(job: Job): RawPageText? {
        val book = job.book ?: return null
        val tess = model(job.language) ?: return null
        val size: PageSize = kotlinx.coroutines.runBlocking { book.pageSize(job.index) }
        val scale = scaleFor(size)
        val w = (size.width * scale).roundToInt().coerceAtLeast(1)
        val h = (size.height * scale).roundToInt().coerceAtLeast(1)
        val gray = renderGray(book, job.index, scale, w, h)
        tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        tess.setImage(gray, w, h, 1, w)
        return try {
            words(tess, scale)
        } finally {
            tess.clear()
        }
    }

    /**
     * Modo MIXTO: render gris de la pagina (una vez), [detect] localiza las regiones y se OCR-ea
     * cada una como una linea (PSM de linea). Devuelve solo las regiones con texto reconocido.
     */
    private fun runRecover(job: Job): List<OcrRegionResult> {
        val book = job.book ?: return emptyList()
        val detect = job.recover ?: return emptyList()
        val tess = model(job.language) ?: return emptyList()
        val size: PageSize = kotlinx.coroutines.runBlocking { book.pageSize(job.index) }
        val scale = scaleFor(size)
        val w = (size.width * scale).roundToInt().coerceAtLeast(1)
        val h = (size.height * scale).roundToInt().coerceAtLeast(1)
        val gray = renderGray(book, job.index, scale, w, h)
        val regions = detect(gray, w, h, scale)
        if (regions.isEmpty()) return emptyList()
        // Cobertura del texto NATIVO: sus pixeles se blanquean en los recortes para no duplicarlo.
        val cov = coverageMask(job.maskBoxes, w, h, scale)
        tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
        val out = ArrayList<OcrRegionResult>(regions.size)
        for (region in regions) {
            recognizeCrop(tess, gray, cov, w, h, scale, region)?.let { out += it }
        }
        return out
    }

    /** OCR de UN recorte [region] (puntos) sobre el gris de la pagina; null si no hay texto. */
    private fun recognizeCrop(tess: TessBaseAPI, gray: ByteArray, cov: BooleanArray?, w: Int, h: Int, scale: Float, region: FloatArray): OcrRegionResult? {
        val bounds = cropBounds(region, w, h, scale) ?: return null
        val x0 = bounds[0]; val y0 = bounds[1]; val x1 = bounds[2]; val y1 = bounds[3]
        val cw = x1 - x0
        val ch = y1 - y0
        val crop = ByteArray(cw * ch)
        for (y in 0 until ch) {
            val srcBase = (y0 + y) * w + x0
            val dstBase = y * cw
            System.arraycopy(gray, srcBase, crop, dstBase, cw)
            if (cov != null) {
                for (x in 0 until cw) if (cov[srcBase + x]) crop[dstBase + x] = 255.toByte() // blanco
            }
        }
        tess.setImage(crop, cw, ch, 1, cw)
        return try {
            cropWords(tess, scale, x0.toFloat(), y0.toFloat(), region)
        } finally {
            tess.clear()
        }
    }

    /** Palabras del recorte -> OcrRegionResult (cajas en puntos de PAGINA, con el origen del recorte). */
    private fun cropWords(tess: TessBaseAPI, scale: Float, originXpx: Float, originYpx: Float, region: FloatArray): OcrRegionResult? {
        tess.getUTF8Text()
        val it = tess.resultIterator ?: return null
        val sb = StringBuilder()
        val boxes = FloatArrayBuilder()
        var confSum = 0f
        var words = 0
        try {
            it.begin()
            do {
                val word = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim().orEmpty()
                val conf = it.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                if (word.isEmpty() || conf < MIN_CONFIDENCE) continue
                val r = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                if (words > 0) { sb.append(' '); boxes.addEmpty() }
                val l = (originXpx + r.left) / scale
                val t = (originYpx + r.top) / scale
                val rr = (originXpx + r.right) / scale
                val b = (originYpx + r.bottom) / scale
                val step = (rr - l) / word.length
                for ((k, c) in word.withIndex()) {
                    sb.append(c)
                    boxes.add(l + step * k, t, l + step * (k + 1), b)
                }
                confSum += conf
                words++
            } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
        } finally {
            it.delete()
        }
        if (words == 0) return null
        return OcrRegionResult(region, sb.toString(), boxes.toArray(), confSum / words, words)
    }

    /** Palabras reconocidas -> texto con un caracter por caja (como pdfium), en puntos. */
    private fun words(tess: TessBaseAPI, scale: Float): RawPageText {
        // Fuerza el reconocimiento (el iterador lo da hecho).
        tess.getUTF8Text()
        val it = tess.resultIterator ?: return RawPageText("", FloatArray(0))
        val sb = StringBuilder()
        val boxes = FloatArrayBuilder()
        try {
            it.begin()
            var first = true
            do {
                val word = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim().orEmpty()
                val conf = it.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                val r = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                val newPara = it.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_PARA)
                val newLine = it.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                // Ruido de ilustraciones y manchas: palabras de confianza muy baja fuera.
                if (word.isEmpty() || conf < MIN_CONFIDENCE) continue
                if (!first) {
                    val sep = when {
                        newPara -> "\n\n"
                        newLine -> "\n"
                        else -> " "
                    }
                    for (c in sep) { sb.append(c); boxes.addEmpty() }
                }
                first = false
                val l = r.left / scale
                val t = r.top / scale
                val rr = r.right / scale
                val b = r.bottom / scale
                val step = (rr - l) / word.length
                for ((k, c) in word.withIndex()) {
                    sb.append(c)
                    boxes.add(l + step * k, t, l + step * (k + 1), b)
                }
            } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
        } finally {
            it.delete()
        }
        return RawPageText(sb.toString(), boxes.toArray())
    }

    /**
     * Escala (pixeles por punto): ~300 ppp (~150 como poco), y en todo caso sin pasar de
     * [MAX_SIDE_PX] en el lado largo (planos y carteles enormes: memoria acotada).
     */
    private fun scaleFor(size: PageSize): Float {
        val longSide = max(size.width, size.height).coerceAtLeast(1f)
        val cap = MAX_SIDE_PX / longSide
        return min(max(min(MAX_SCALE, cap), MIN_SCALE), cap)
    }

    /** Bitmap de trabajo (una franja) reutilizado mientras el tamano no cambie. */
    private fun bitmapFor(w: Int, h: Int): Bitmap {
        bitmap?.let { if (it.width == w && it.height == h) return it; it.recycle() }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap = it }
    }

    /**
     * Pagina en gris de 8 bits, dibujada por franjas de [RENDER_BAND_PX]: cada franja es una
     * llamada corta al hilo de pdfium, asi el lector puede dibujar sus paginas entre una y
     * otra en vez de esperar a la pagina entera a 300 ppp (pasar paginas sin tirones).
     */
    private fun renderGray(book: PdfBook, index: Int, scale: Float, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        val bandH = min(RENDER_BAND_PX, h)
        val bmp = bitmapFor(w, bandH)
        val row = IntArray(w * BAND_ROWS)
        var top = 0
        while (top < h) {
            val rows = min(bandH, h - top)
            kotlinx.coroutines.runBlocking { book.renderPage(index, bmp, scale, left = 0, top = top) }
            var y = 0
            while (y < rows) {
                val n = min(BAND_ROWS, rows - y)
                bmp.getPixels(row, 0, w, 0, y, w, n)
                val base = (top + y) * w
                for (i in 0 until w * n) {
                    val c = row[i]
                    out[base + i] = ((((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8).toByte()
                }
                y += n
            }
            top += rows
        }
        return out
    }

    private fun model(language: String): TessBaseAPI? {
        val lang = if (language in LANGUAGES) language else DEFAULT_LANGUAGE
        api?.let { if (apiLanguage == lang) return it }
        releaseModel()
        val dir = installModels() ?: return null
        val t = TessBaseAPI()
        if (!t.init(dir.path, lang, TessBaseAPI.OEM_LSTM_ONLY)) {
            t.recycle()
            Log.w(TAG, "No se pudo cargar el modelo de OCR $lang")
            return null
        }
        t.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        api = t
        apiLanguage = lang
        return t
    }

    private fun releaseModel() {
        api?.recycle()
        api = null
        apiLanguage = null
        bitmap?.recycle()
        bitmap = null
    }

    /**
     * Copia los modelos de los recursos de la APK a la memoria interna (Tesseract necesita
     * ficheros). Solo la primera vez o si cambiaron (se compara el tamano). Devuelve la
     * carpeta que contiene `tessdata/`, o nulo si la APK no los trae.
     */
    private fun modelsDir() = File(context.noBackupFilesDir, MODELS_DIR)

    private fun installModels(): File? {
        val base = modelsDir()
        val data = File(base, "tessdata").apply { mkdirs() }
        return try {
            val assets = context.assets
            for (lang in LANGUAGES) {
                val name = "$lang.traineddata"
                val dest = File(data, name)
                val expected = assets.openFd("ocr/tessdata/$name").use { it.length }
                if (dest.length() == expected) continue
                val tmp = File(data, "$name.part")
                assets.open("ocr/tessdata/$name").use { input -> tmp.outputStream().use { input.copyTo(it, 256 * 1024) } }
                if (!tmp.renameTo(dest)) throw IOException("No se pudo instalar $name")
            }
            base
        } catch (e: IOException) {
            Log.w(TAG, "Modelos de OCR no disponibles", e)
            null
        }
    }

    private class FloatArrayBuilder {
        private var a = FloatArray(4096)
        private var n = 0

        fun add(l: Float, t: Float, r: Float, b: Float) {
            if (n + 4 > a.size) a = a.copyOf(a.size * 2)
            a[n++] = l; a[n++] = t; a[n++] = r; a[n++] = b
        }

        fun addEmpty() = add(0f, 0f, 0f, 0f)

        fun toArray(): FloatArray = a.copyOf(n)
    }

    companion object {
        private const val TAG = "OcrEngine"
        private const val URGENT = 10
        private const val BACKGROUND = 0

        /** Idiomas con modelo incluido; el resto usa [DEFAULT_LANGUAGE]. */
        val LANGUAGES = listOf("spa", "eng")
        const val DEFAULT_LANGUAGE = "spa"

        /** ~300 ppp como mucho (4,17 px/pt) y ~150 ppp como poco. */
        private const val MAX_SCALE = 300f / 72f
        private const val MIN_SCALE = 150f / 72f

        /** Lado largo maximo de la imagen (memoria acotada: ~13 MB en color + 3 MB en gris). */
        private const val MAX_SIDE_PX = 2600f
        private const val BAND_ROWS = 64
        private const val RENDER_BAND_PX = 512
        private const val MODELS_DIR = "ocr-modelos"
        private const val MIN_CONFIDENCE = 35f
        private const val IDLE_RELEASE_MS = 60_000L

        @Volatile private var instance: OcrEngine? = null

        fun get(context: Context): OcrEngine =
            instance ?: synchronized(this) { instance ?: OcrEngine(context.applicationContext).also { instance = it } }

        /**
         * OCR desactivado: libera el modelo y borra su copia en disco (~40 MB); se vuelve a
         * copiar de la APK si se activa otra vez.
         */
        fun discardModels(context: Context) {
            // En el hilo del motor: nunca a la vez que una instalacion o un reconocimiento.
            val engine = get(context)
            engine.queue.put(Job(URGENT, engine.seq.incrementAndGet(), null, -1, "", CompletableDeferred()))
        }

        /** Codigo de modelo para el idioma de un libro ("es" -> spa, "en" -> eng). */
        fun languageFor(bookLanguage: String?): String = when (bookLanguage?.lowercase()?.take(2)) {
            "en" -> "eng"
            else -> DEFAULT_LANGUAGE
        }

        /**
         * Bordes en pixeles [x0, y0, x1, y1] del recorte de una [region] (en puntos) sobre una
         * imagen [w]x[h] a [scale] px/pt, con un margen de ~2 pt y recortados a la imagen; null si
         * el recorte queda demasiado pequeno (menos de 2 px de lado). Aritmetica pura y testable.
         */
        internal fun cropBounds(region: FloatArray, w: Int, h: Int, scale: Float): IntArray? {
            val margin = (2f * scale).roundToInt()
            val x0 = ((region[0] * scale).roundToInt() - margin).coerceIn(0, w - 1)
            val y0 = ((region[1] * scale).roundToInt() - margin).coerceIn(0, h - 1)
            val x1 = ((region[2] * scale).roundToInt() + margin).coerceIn(x0 + 1, w)
            val y1 = ((region[3] * scale).roundToInt() + margin).coerceIn(y0 + 1, h)
            if (x1 - x0 < 2 || y1 - y0 < 2) return null
            return intArrayOf(x0, y0, x1, y1)
        }

        /** Mascara (true = cubierto por una caja nativa) para blanquear la tinta nativa en los recortes. */
        internal fun coverageMask(boxes: FloatArray?, w: Int, h: Int, scale: Float): BooleanArray? {
            if (boxes == null || boxes.isEmpty()) return null
            val cov = BooleanArray(w * h)
            val pad = 1
            var i = 0
            val n = boxes.size - boxes.size % 4
            while (i < n) {
                val l = boxes[i]; val t = boxes[i + 1]; val r = boxes[i + 2]; val b = boxes[i + 3]
                i += 4
                if (r <= l || b <= t) continue
                val xa = (l * scale).toInt().coerceIn(0, w) - pad
                val xb = kotlin.math.ceil(r * scale).toInt().coerceIn(0, w) + pad
                val ya = (t * scale).toInt().coerceIn(0, h) - pad
                val yb = kotlin.math.ceil(b * scale).toInt().coerceIn(0, h) + pad
                var yy = ya.coerceAtLeast(0)
                val yEnd = yb.coerceAtMost(h)
                val xStart = xa.coerceAtLeast(0)
                val xEnd = xb.coerceAtMost(w)
                while (yy < yEnd) {
                    val base = yy * w
                    var xx = xStart
                    while (xx < xEnd) { cov[base + xx] = true; xx++ }
                    yy++
                }
            }
            return cov
        }
    }
}
