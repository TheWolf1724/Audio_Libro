package com.lectorvoz.app.integration

import com.lectorvoz.core.text.FlowedPage
import com.lectorvoz.core.text.PdfPageFlow
import com.lectorvoz.format.pdf.BasicPdfTextFlow
import com.lectorvoz.format.pdf.PageTextCleaner
import com.lectorvoz.format.pdf.PdfBook
import com.lectorvoz.format.pdf.PdfFlowText
import com.lectorvoz.format.pdf.PdfPageText
import kotlinx.coroutines.CancellationException

/**
 * Adaptador del filtro de cabeceras/pies de `:core:text` ([PdfPageFlow]) al contrato
 * [PageTextCleaner] de `:format:pdf`. Las cabeceras repetidas se reconocen comparando con
 * las paginas vecinas: [prefetch] carga en memoria su texto (suspende); si una vecina aun no
 * esta, se usa la cache de [PdfBook] y, si tampoco, se limpia solo con los numeros de pagina.
 *
 * El mapa de [FlowedPage.toOriginal] apunta a indices de pdfium, asi que el resaltado sigue
 * funcionando. Con [enabled] false no se quita nada (solo se unen lineas).
 */
class PdfCleanerAdapter(
    private val book: PdfBook?,
    enabled: Boolean = true,
) : PageTextCleaner {
    /** Filtro activo; al cambiar se rehacen las paginas preparadas. */
    @Volatile var enabled: Boolean = enabled
        set(value) {
            if (field != value) {
                field = value
                synchronized(flowed) { flowed.clear() }
            }
        }

    private val raw = object : LinkedHashMap<Int, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>) = size > RAW_CACHE
    }
    private val flowed = object : LinkedHashMap<Int, Pair<String, FlowedPage>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Pair<String, FlowedPage>>) = size > FLOW_CACHE
    }

    /** Guarda el texto bruto de [page] (para pruebas o si ya se tiene). */
    fun remember(page: Int, rawText: String) {
        synchronized(raw) { raw[page] = rawText }
    }

    /** Olvida el texto preparado (p. ej. al forzar el OCR: el texto bruto de las paginas cambia). */
    fun reset() {
        synchronized(raw) { raw.clear() }
        synchronized(flowed) { flowed.clear() }
    }

    /**
     * Carga el texto bruto de las vecinas de [page] para detectar cabeceras repetidas. Sin
     * esperar al OCR: una vecina escaneada aun sin reconocer se omite (la limpieza se las
     * arregla sin ella) en vez de frenar la voz o la apertura del libro.
     */
    suspend fun prefetch(page: Int) {
        val b = book ?: return
        for (p in (page - 1)..(page + 1)) {
            if (p == page || p < 0 || p >= b.pageCount) continue
            if (synchronized(raw) { raw.containsKey(p) }) continue
            try {
                val t = b.pageText(p, wait = false).rawText
                // Lo provisional (pagina escaneada sin reconocer) no se recuerda.
                if (b.cachedPageText(p) != null || PdfBook.hasReadableText(t)) remember(p, t)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Pagina ilegible: se limpia sin ella.
            }
        }
    }

    private fun rawOf(page: Int): String? =
        synchronized(raw) { raw[page] } ?: book?.cachedPageText(page)?.rawText?.also { remember(page, it) }

    /** Pagina [index] (texto bruto [text]) preparada para la voz. */
    fun flowFor(index: Int, text: String): FlowedPage {
        synchronized(flowed) { flowed[index]?.takeIf { it.first === text || it.first == text }?.let { return it.second } }
        remember(index, text)
        val f = if (enabled) PdfPageFlow.flow(text, rawOf(index - 1), rawOf(index + 1)) else PdfPageFlow.flow(text, null, null, zoneLines = 0)
        synchronized(flowed) { flowed[index] = text to f }
        return f
    }

    override fun excludedRanges(page: PdfPageText): List<IntRange> =
        if (!enabled) emptyList() else flowFor(page.pageIndex, page.rawText).excluded.map { it.start until it.end }

    override fun flow(page: PdfPageText, excluded: List<IntRange>): PdfFlowText {
        val f = flowFor(page.pageIndex, page.rawText)
        return try {
            PdfFlowText(f.text, f.toOriginal, page.length)
        } catch (e: IllegalArgumentException) {
            BasicPdfTextFlow.flow(page, excluded)
        }
    }

    private companion object {
        const val RAW_CACHE = 12
        const val FLOW_CACHE = 8
    }
}
