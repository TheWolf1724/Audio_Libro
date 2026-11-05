package com.lectorvoz.core.text

/**
 * Fusiona el texto NATIVO de una pagina PDF (pdfium) con las lineas recuperadas por OCR en el
 * modo de lectura MIXTA, produciendo un unico par (texto, cajas) en ORDEN DE LECTURA. Es la pieza
 * critica del mixto: el resultado se envuelve tal cual en un RawPageText/PdfPageText y el resto
 * del pipeline (limpiador, UtteranceBuilder, resaltado) NO cambia.
 *
 * PURO (JVM, sin Android). Invariante de salida: [Merged.boxes].size == [Merged.text].length * 4
 * (4 floats por caracter: left, top, right, bottom en PUNTOS de pagina; caja 0,0,0,0 = sin
 * geometria, como los saltos de linea que ya genera pdfium).
 */
object PageTextMerger {

    /**
     * Una linea de texto recuperada por OCR, lista para insertar.
     * @param text texto reconocido de la linea (sin salto final).
     * @param wordBoxes cajas por CARACTER de [text], 4 floats por caracter (left, top, right,
     *   bottom en PUNTOS de pagina), es decir wordBoxes.size == text.length*4. Los caracteres sin
     *   geometria (espacios) llevan caja 0,0,0,0.
     * @param region rect de la linea en puntos (para ordenar por posicion de lectura).
     */
    class OcrLine(val text: String, val wordBoxes: FloatArray, val region: MissingTextDetector.Region) {
        init { require(wordBoxes.size == text.length * 4) { "wordBoxes debe tener 4 floats por caracter" } }
    }

    /** Resultado fusionado; misma invariante que RawPageText. */
    class Merged(val text: String, val boxes: FloatArray) {
        init { require(boxes.size == text.length * 4) { "boxes debe tener 4 floats por caracter" } }
    }

    /**
     * Inserta cada [OcrLine] en ([nativeText], [nativeBoxes]) en su posicion de lectura.
     *
     * @param nativeText texto de pdfium.
     * @param nativeBoxes cajas por caracter de [nativeText] (4 floats/char, puntos), invariante
     *   nativeBoxes.size == nativeText.length*4.
     * @param ocrLines lineas recuperadas (puede estar vacia -> devuelve identidad).
     *
     * Orden de lectura (ver docs/revisiones/estudio-mixto-detector.md §2; una columna en v1):
     *  - clave de lectura de un caracter nativo con caja = (bandaY, x), donde bandaY agrupa por
     *    solapamiento vertical de linea (misma idea que PdfPageText.sameLine).
     *  - punto de insercion de una OcrLine = primer indice nativo cuyo caracter tenga clave de
     *    lectura > la de la linea OCR (esta debajo, o misma banda mas a la derecha); si no hay,
     *    al final.
     *  - insertar un separador ('\n' si no comparte banda con el vecino; ' ' si misma banda) con
     *    caja vacia, y luego los caracteres de la linea con sus cajas.
     *  - aplicar las inserciones de mayor a menor indice (o con desplazamientos) para no invalidar
     *    los puntos de insercion.
     * Debe ser determinista e idempotente para una pagina dada. Sin ocrLines, Merged == entrada.
     */
    fun merge(nativeText: String, nativeBoxes: FloatArray, ocrLines: List<OcrLine>): Merged {
        TODO("Fase 2: implementar la fusion en orden de lectura (implementa el experto)")
    }
}
