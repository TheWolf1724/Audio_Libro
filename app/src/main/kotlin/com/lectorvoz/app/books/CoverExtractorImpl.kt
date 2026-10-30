package com.lectorvoz.app.books

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.lectorvoz.core.data.library.CoverExtractor
import com.lectorvoz.core.data.library.ExtractedInfo
import com.lectorvoz.core.model.BookFormat
import com.lectorvoz.format.epub.EpubBook
import com.lectorvoz.format.pdf.PdfBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Metadatos y portada al importar: EPUB con su portada declarada; PDF con la primera
 * pagina renderizada. Las portadas se guardan como JPEG de [maxSide] px como maximo.
 */
class CoverExtractorImpl(
    private val context: Context,
    private val maxSide: Int = 480,
) : CoverExtractor {

    override suspend fun extract(file: File, format: BookFormat, coverOut: File): ExtractedInfo = withContext(Dispatchers.IO) {
        when (format) {
            BookFormat.EPUB -> epub(file, coverOut)
            BookFormat.PDF -> pdf(file, coverOut)
        }
    }

    private suspend fun epub(file: File, coverOut: File): ExtractedInfo {
        val book = EpubBook.open(file)
        try {
            val m = book.metadata
            val written = m.coverHref?.let { href ->
                try {
                    book.resource(href)?.let { writeScaled(it, coverOut) } ?: false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            } ?: false
            return ExtractedInfo(m.title, m.authors, m.language, written)
        } finally {
            book.close()
        }
    }

    private suspend fun pdf(file: File, coverOut: File): ExtractedInfo {
        val book = PdfBook.open(context, file)
        try {
            val written = try {
                if (book.pageCount > 0) renderFirstPage(book, coverOut) else false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            val m = book.metadata
            return ExtractedInfo(m.title, m.authors, m.language, written)
        } finally {
            book.close()
        }
    }

    private suspend fun renderFirstPage(book: PdfBook, out: File): Boolean {
        val size = book.pageSize(0)
        val scale = maxSide / maxOf(size.width, size.height).coerceAtLeast(1f)
        val w = (size.width * scale).toInt().coerceAtLeast(1)
        val h = (size.height * scale).toInt().coerceAtLeast(1)
        val bmp = createBitmap(w, h)
        try {
            bmp.eraseColor(Color.WHITE)
            book.renderPage(0, bmp, scale)
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            return true
        } finally {
            bmp.recycle()
        }
    }

    /** Decodifica [bytes] reducido (inSampleSize) y lo guarda como JPEG. */
    private fun writeScaled(bytes: ByteArray, out: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        val opts = BitmapFactory.Options().apply {
            inSampleSize = CoverLoader.sampleSize(bounds.outWidth, bounds.outHeight, maxSide, maxSide)
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return false
        try {
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            return true
        } finally {
            bmp.recycle()
        }
    }
}
