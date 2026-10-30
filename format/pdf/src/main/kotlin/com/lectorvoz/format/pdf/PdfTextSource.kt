package com.lectorvoz.format.pdf

import android.graphics.RectF
import com.lectorvoz.core.model.BookFormat
import com.lectorvoz.core.model.ChapterText
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.LocatorRange
import com.lectorvoz.core.model.TextSource

/**
 * [TextSource] de un PDF: cada "capitulo" es una pagina y el offset de [Locator] es el indice
 * de caracter de pdfium.
 *
 * [chapterText] devuelve el texto fluido proyectado a la longitud de pdfium
 * ([PdfPageText.cleanText]): las lineas de un parrafo ya van unidas por espacios (no hay un
 * salto por linea impresa) y los desplazamientos coinciden con los de pdfium, asi que un
 * consumidor generico obtiene Locators correctos sin conversion. Quien prefiera el texto
 * compacto usa [flowText] y convierte con [locatorFor]/[rangeFor].
 */
class PdfTextSource internal constructor(
    private val book: PdfBook,
    private val cleaner: PageTextCleaner,
) : TextSource {

    override val format: BookFormat get() = BookFormat.PDF
    override val chapterCount: Int get() = book.pageCount

    override suspend fun chapterText(chapter: Int): ChapterText {
        val page = book.pageText(chapter, cleaner)
        return ChapterText(chapter = chapter, text = page.cleanText, title = null, language = null)
    }

    /** Texto fluido compacto de la pagina [page] con su mapa a indices de pdfium. */
    suspend fun flowText(page: Int): PdfFlowText = book.pageText(page, cleaner).flow

    /** Locator del caracter [flowIndex] del texto fluido de la pagina [page]. */
    fun locatorFor(page: Int, flow: PdfFlowText, flowIndex: Int): Locator =
        Locator(page, flow.sourceStart(flowIndex).coerceIn(0, flow.sourceLength))

    /** Rango de Locators de [start, endExclusive) del texto fluido de la pagina [page]. */
    fun rangeFor(page: Int, flow: PdfFlowText, start: Int, endExclusive: Int): LocatorRange {
        val s = flow.sourceStart(start).coerceIn(0, flow.sourceLength)
        val e = flow.sourceEnd(endExclusive).coerceIn(s, flow.sourceLength)
        return LocatorRange(Locator(page, s), Locator(page, e))
    }

    /**
     * Rectangulos a resaltar para [range], por pagina, en puntos de pagina (origen arriba a la
     * izquierda). Las paginas sin texto no aparecen.
     */
    suspend fun rectsFor(range: LocatorRange): Map<Int, List<RectF>> {
        if (range.isEmpty) return emptyMap()
        val first = range.start.chapter
        val last = minOf(range.endExclusive.chapter, book.pageCount - 1)
        val out = LinkedHashMap<Int, List<RectF>>()
        for (p in first..last) {
            // Solo hacen falta las cajas: texto base, sin limpiar.
            val text = book.pageText(p)
            val s = if (p == first) range.start.offset else 0
            val e = if (p == range.endExclusive.chapter) range.endExclusive.offset else text.length
            val rects = text.rectsForRange(s, e)
            if (rects.isNotEmpty()) out[p] = rects
        }
        return out
    }

    /**
     * Posicion del caracter tocado en la pagina [page] en el punto ([x], [y]) en puntos de
     * pagina. Nulo si no hay texto cerca (por ejemplo, un margen o una imagen).
     */
    suspend fun locatorAt(page: Int, x: Float, y: Float): Locator? {
        val text = book.pageText(page)
        val i = text.charIndexAt(x, y)
        return if (i >= 0) Locator(page, i) else null
    }
}
