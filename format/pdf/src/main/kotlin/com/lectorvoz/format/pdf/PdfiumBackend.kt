package com.lectorvoz.format.pdf

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPage
import io.legere.pdfiumandroid.PdfiumCore
import io.legere.pdfiumandroid.api.Bookmark
import io.legere.pdfiumandroid.api.PdfPasswordException
import java.io.IOException

/**
 * [PdfBackend] sobre io.legere:pdfiumandroid 2.0.3 (API plana, no suspend: el hilo lo pone
 * [PdfiumDispatcher]). Mantiene abiertas las ultimas paginas usadas, porque abrir una
 * pagina en pdfium es caro.
 */
internal class PdfiumBackend private constructor(
    private val document: PdfDocument,
    private val fd: ParcelFileDescriptor,
) : PdfBackend {

    private val openPages = object : LinkedHashMap<Int, PdfPage>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PdfPage>): Boolean {
            if (size > MAX_OPEN_PAGES) {
                runCatching { eldest.value.close() }
                return true
            }
            return false
        }
    }
    private var closed = false

    override val pageCount: Int = document.getPageCount()

    private fun page(index: Int): PdfPage {
        if (closed) throw PdfException.Closed()
        require(index in 0 until pageCount) { "Pagina fuera de rango: $index" }
        openPages[index]?.let { return it }
        val p = document.openPage(index) ?: throw PdfException.Corrupt("no se pudo abrir la pagina ${index + 1}")
        openPages[index] = p
        return p
    }

    override fun pageSizePoints(index: Int): PageSize {
        val p = page(index)
        return PageSize(p.getPageWidthPoint().toFloat(), p.getPageHeightPoint().toFloat())
    }

    override fun meta(): PdfMeta {
        val m = document.getDocumentMeta()
        return PdfMeta(
            title = m.title?.takeIf { it.isNotBlank() },
            author = m.author?.takeIf { it.isNotBlank() },
            subject = m.subject?.takeIf { it.isNotBlank() },
        )
    }

    override fun bookmarks(): List<PdfBookmark> = document.getTableOfContents().map { it.toModel() }

    private fun Bookmark.toModel(): PdfBookmark = PdfBookmark(
        title = title.orEmpty(),
        pageIndex = pageIdx.toInt().coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
        children = children.map { it.toModel() },
    )

    override fun render(index: Int, bitmap: Bitmap, startX: Int, startY: Int, drawWidth: Int, drawHeight: Int) {
        page(index).renderPageBitmap(
            bitmap, startX, startY, drawWidth, drawHeight,
            true, // renderAnnot
            false, // textMask
            WHITE, // canvasColor
            WHITE, // pageBackgroundColor
        )
    }

    override fun pageText(index: Int): RawPageText {
        val p = page(index)
        val wPt = p.getPageWidthPoint()
        val hPt = p.getPageHeightPoint()
        // Transformacion afin pagina PDF (y hacia arriba) -> puntos con origen arriba a la
        // izquierda, obtenida de pdfium con 3 puntos a escala alta (respeta rotacion y
        // CropBox).
        val sx = (wPt * SCALE).coerceAtLeast(1)
        val sy = (hPt * SCALE).coerceAtLeast(1)
        val o = p.mapPageCoordsToDevice(0, 0, sx, sy, 0, 0.0, 0.0)
        val ax = p.mapPageCoordsToDevice(0, 0, sx, sy, 0, UNIT, 0.0)
        val ay = p.mapPageCoordsToDevice(0, 0, sx, sy, 0, 0.0, UNIT)
        val k = (1.0 / (UNIT * SCALE)).toFloat()
        val a = (ax.x - o.x) * k; val c = (ay.x - o.x) * k; val e = o.x / SCALE.toFloat()
        val b = (ax.y - o.y) * k; val d = (ay.y - o.y) * k; val f = o.y / SCALE.toFloat()

        p.openTextPage().use { tp ->
            val n = tp.textPageCountChars().coerceAtLeast(0)
            var text = if (n > 0) tp.textPageGetText(0, n).orEmpty() else ""
            if (text.length != n) {
                val sb = StringBuilder(n)
                for (i in 0 until n) sb.append(tp.textPageGetUnicode(i))
                text = sb.toString()
            }
            val boxes = FloatArray(n * 4)
            for (i in 0 until n) {
                val r = tp.textPageGetCharBox(i) ?: continue
                if (r.left == 0f && r.right == 0f && r.top == 0f && r.bottom == 0f) continue
                // r.top es la y PDF mayor (pdfium devuelve left, right, bottom, top).
                val x0 = a * r.left + c * r.top + e
                val y0 = b * r.left + d * r.top + f
                val x1 = a * r.right + c * r.bottom + e
                val y1 = b * r.right + d * r.bottom + f
                boxes[i * 4] = minOf(x0, x1)
                boxes[i * 4 + 1] = minOf(y0, y1)
                boxes[i * 4 + 2] = maxOf(x0, x1)
                boxes[i * 4 + 3] = maxOf(y0, y1)
            }
            return RawPageText(text, boxes)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        openPages.values.forEach { runCatching { it.close() } }
        openPages.clear()
        runCatching { document.close() }
        runCatching { fd.close() }
    }

    companion object {
        private const val MAX_OPEN_PAGES = 4
        private const val SCALE = 16
        private const val UNIT = 1000.0
        private const val WHITE = -0x1 // 0xFFFFFFFF

        @Volatile
        private var core: PdfiumCore? = null

        private fun core(context: Context): PdfiumCore =
            core ?: synchronized(this) {
                core ?: PdfiumCore(context.applicationContext).also { core = it }
            }

        /** Abre el documento; traduce los errores de pdfium a [PdfException]. */
        fun open(context: Context, fd: ParcelFileDescriptor, password: String?): PdfiumBackend {
            val pdfium = try {
                core(context)
            } catch (t: Throwable) {
                runCatching { fd.close() }
                if (t is LinkageError || t is RuntimeException) throw PdfException.NativeUnavailable(t)
                throw t
            }
            val doc = try {
                if (password == null) pdfium.newDocument(fd) else pdfium.newDocument(fd, password)
            } catch (e: PdfPasswordException) {
                runCatching { fd.close() }
                throw if (password == null) PdfException.PasswordRequired(e) else PdfException.WrongPassword(e)
            } catch (e: IOException) {
                runCatching { fd.close() }
                throw PdfException.Corrupt(e.message, e)
            } catch (t: LinkageError) {
                runCatching { fd.close() }
                throw PdfException.NativeUnavailable(t)
            } catch (e: RuntimeException) {
                runCatching { fd.close() }
                throw PdfException.Corrupt(e.message, e)
            }
            return try {
                PdfiumBackend(doc, fd)
            } catch (e: RuntimeException) {
                runCatching { doc.close() }
                runCatching { fd.close() }
                throw PdfException.Corrupt(e.message, e)
            }
        }
    }
}
