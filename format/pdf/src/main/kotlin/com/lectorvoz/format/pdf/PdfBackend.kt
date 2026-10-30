package com.lectorvoz.format.pdf

import android.graphics.Bitmap

/**
 * Operaciones de bajo nivel sobre un documento PDF abierto. La implementacion real es
 * [PdfiumBackend]; las pruebas usan un falso porque pdfium es nativo y no carga en
 * Robolectric. Todas las llamadas se hacen desde el hilo de [PdfiumDispatcher].
 */
interface PdfBackend : AutoCloseable {
    val pageCount: Int

    /** Tamano de la pagina [index] en puntos PDF (ya con la rotacion aplicada). */
    fun pageSizePoints(index: Int): PageSize

    /** Metadatos del documento (titulo, autor...). */
    fun meta(): PdfMeta

    /** Marcadores (indice) en arbol. */
    fun bookmarks(): List<PdfBookmark>

    /**
     * Dibuja la pagina [index] en [bitmap] con origen en ([startX], [startY]) y tamano de
     * pagina completa [drawWidth] x [drawHeight] pixeles (origen negativo = recorte).
     */
    fun render(index: Int, bitmap: Bitmap, startX: Int, startY: Int, drawWidth: Int, drawHeight: Int)

    /**
     * Texto de la pagina [index]: un caracter por indice de pdfium y sus cajas en
     * coordenadas de pagina con origen arriba a la izquierda, en puntos.
     */
    fun pageText(index: Int): RawPageText
}

/** Tamano en puntos PDF (1/72 de pulgada). */
data class PageSize(val width: Float, val height: Float)

/** Metadatos crudos del PDF; cadenas vacias se tratan como ausentes. */
data class PdfMeta(val title: String?, val author: String?, val subject: String?)

/** Marcador del PDF: titulo y pagina destino. */
data class PdfBookmark(val title: String, val pageIndex: Int, val children: List<PdfBookmark>)

/**
 * Texto crudo de una pagina: [text] tiene exactamente un caracter por indice de pdfium y
 * [boxes] guarda 4 floats por caracter (left, top, right, bottom) en puntos con origen arriba
 * a la izquierda. Una caja vacia (todo 0) indica caracter sin geometria (saltos generados).
 */
class RawPageText(val text: String, val boxes: FloatArray) {
    init {
        require(boxes.size == text.length * 4) { "boxes debe tener 4 floats por caracter" }
    }
}
