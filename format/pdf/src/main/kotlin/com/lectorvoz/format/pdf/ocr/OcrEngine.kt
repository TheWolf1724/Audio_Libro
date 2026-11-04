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
        val book: PdfBook,
        val index: Int,
        val language: String,
        val result: CompletableDeferred<RawPageText?>,
    ) : Comparable<Job> {
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
        val job = Job(if (urgent) URGENT else BACKGROUND, seq.incrementAndGet(), book, index, language, d)
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
            val job = queue.poll(IDLE_RELEASE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (job == null) {
                releaseModel()
                continue
            }
            if (job.result.isCancelled) continue
            val started = System.nanoTime()
            val out = try {
                run(job)
            } catch (e: Throwable) {
                Log.w(TAG, "OCR fallido en la pagina ${job.index}", e)
                if (e is OutOfMemoryError) releaseModel()
                null
            }
            lastPageMs = (System.nanoTime() - started) / 1_000_000
            job.result.complete(out)
        }
    }

    private fun run(job: Job): RawPageText? {
        val tess = model(job.language) ?: return null
        val size: PageSize = kotlinx.coroutines.runBlocking { job.book.pageSize(job.index) }
        val scale = scaleFor(size)
        val w = (size.width * scale).roundToInt().coerceAtLeast(1)
        val h = (size.height * scale).roundToInt().coerceAtLeast(1)
        val bmp = bitmapFor(w, h)
        kotlinx.coroutines.runBlocking { job.book.renderPage(job.index, bmp, scale) }
        val gray = toGray(bmp, w, h)
        tess.setImage(gray, w, h, 1, w)
        return try {
            words(tess, scale)
        } finally {
            tess.clear()
        }
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

    /** Escala (pixeles por punto): ~300 ppp, sin pasar de [MAX_SIDE_PX] en el lado largo. */
    private fun scaleFor(size: PageSize): Float {
        val longSide = max(size.width, size.height).coerceAtLeast(1f)
        return min(MAX_SCALE, MAX_SIDE_PX / longSide).coerceAtLeast(MIN_SCALE)
    }

    /** Bitmap de trabajo reutilizado mientras el tamano no cambie. */
    private fun bitmapFor(w: Int, h: Int): Bitmap {
        bitmap?.let { if (it.width == w && it.height == h) return it; it.recycle() }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap = it }
    }

    /** Gris de 8 bits por franjas (no copia la pagina entera en color). */
    private fun toGray(bmp: Bitmap, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        val band = BAND_ROWS
        val row = IntArray(w * band)
        var y = 0
        while (y < h) {
            val rows = min(band, h - y)
            bmp.getPixels(row, 0, w, 0, y, w, rows)
            for (i in 0 until w * rows) {
                val c = row[i]
                val g = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
                out[y * w + i] = g.toByte()
            }
            y += rows
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
    private fun installModels(): File? {
        val base = File(context.noBackupFilesDir, "ocr-modelos")
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
        private const val MIN_CONFIDENCE = 35f
        private const val IDLE_RELEASE_MS = 60_000L

        @Volatile private var instance: OcrEngine? = null

        fun get(context: Context): OcrEngine =
            instance ?: synchronized(this) { instance ?: OcrEngine(context.applicationContext).also { instance = it } }

        /** Codigo de modelo para el idioma de un libro ("es" -> spa, "en" -> eng). */
        fun languageFor(bookLanguage: String?): String = when (bookLanguage?.lowercase()?.take(2)) {
            "en" -> "eng"
            else -> DEFAULT_LANGUAGE
        }
    }
}
