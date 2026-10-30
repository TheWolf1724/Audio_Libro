package com.lectorvoz.format.pdf

/**
 * Punto de extension para preparar el texto de una pagina PDF antes de leerlo en voz alta.
 *
 * - [excludedRanges]: indices de pdfium que NO deben leerse (cabeceras, pies, numeros de
 *   pagina).
 * - [flow]: texto fluido para la voz con su mapa a indices de pdfium ([PdfFlowText]). Por
 *   defecto, [BasicPdfTextFlow] (une lineas de un parrafo y quita guiones de fin de linea).
 *
 * `:app` puede sustituirlo por el filtro de `:core:text`: si ese filtro recibe
 * `page.rawText` y devuelve un texto con mapa `toSource` al texto recibido, el mapa ya son
 * indices de pdfium y basta con `PdfFlowText(texto, toSource, page.length)`. Un mapa no
 * monotono se rechaza y se usa el limpiador basico.
 *
 * El `offset` de [com.lectorvoz.core.model.Locator] sigue siendo el indice de pdfium, asi
 * que el resaltado funciona con cualquier limpiador. Las implementaciones no deben leer
 * `page.cleanText`/`page.flow` (se calculan a partir de este limpiador).
 */
fun interface PageTextCleaner {
    fun excludedRanges(page: PdfPageText): List<IntRange>

    /** Texto fluido de [page] sin los rangos [excluded]. */
    fun flow(page: PdfPageText, excluded: List<IntRange>): PdfFlowText = BasicPdfTextFlow.flow(page, excluded)

    companion object {
        /** No quita nada (pero une lineas con [BasicPdfTextFlow]). */
        val Identity: PageTextCleaner = PageTextCleaner { emptyList() }

        /** Limpiador a partir de dos funciones (adaptadores de `:app`). */
        fun of(
            excluded: (PdfPageText) -> List<IntRange>,
            flow: (PdfPageText, List<IntRange>) -> PdfFlowText,
        ): PageTextCleaner = object : PageTextCleaner {
            override fun excludedRanges(page: PdfPageText) = excluded(page)
            override fun flow(page: PdfPageText, excluded: List<IntRange>) = flow(page, excluded)
        }
    }
}
