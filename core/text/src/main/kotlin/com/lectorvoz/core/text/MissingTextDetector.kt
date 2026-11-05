package com.lectorvoz.core.text

/**
 * Detecta las zonas de una pagina PDF con "tinta de texto" que NINGUNA caja de pdfium cubre:
 * texto que pdfium dibuja pero no supo extraer (versalitas, titulos en fuentes decorativas). Es
 * el detector del modo de lectura MIXTA texto+OCR (ver docs/revisiones/estudio-mixto-detector.md).
 *
 * Idea (prototipo validado en PC, docs/revisiones/detector.py): la pagina renderizada contiene
 * TODA la tinta; pdfium da cajas SOLO del texto que leyo. "tinta - cajas" deja lo que falta; se
 * agrupa en lineas y se filtra por forma (alto de linea, ancho, densidad) para descartar lo que
 * no parece texto. Se trabaja a baja resolucion (downsample) para que sea barato.
 *
 * PURO (JVM, sin Android): opera sobre primitivas. La imagen gris y las cajas las aporta quien
 * llama (en :app, puente entre el render de :format:pdf y este detector). Devuelve rects en
 * PUNTOS de pagina (origen arriba-izquierda), el mismo sistema que las cajas de [PdfPageText].
 */
object MissingTextDetector {

    /**
     * Pagina renderizada en gris. [pixels] = 1 byte por pixel (0=negro .. 255=blanco, tratado sin
     * signo), fila a fila, tamano [width]*[height]. [pxPerPoint] = pixeles por punto de pagina
     * (= escala del render = dpi/72), para convertir pixeles <-> puntos.
     */
    class GrayPage(val pixels: ByteArray, val width: Int, val height: Int, val pxPerPoint: Float) {
        init {
            require(pixels.size == width * height) { "pixels debe ser width*height" }
            require(pxPerPoint > 0f) { "pxPerPoint debe ser > 0" }
        }
        /** Valor de gris sin signo (0..255) del pixel (x,y). */
        fun gray(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF
    }

    /** Region de "texto que falta", en PUNTOS de pagina (origen arriba-izquierda). */
    data class Region(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * Devuelve las lineas de "tinta sin caja" que parecen texto, en puntos de pagina.
     *
     * @param gray pagina renderizada en gris.
     * @param boxes cajas de caracteres de pdfium, 4 floats por caracter (left, top, right, bottom)
     *   en PUNTOS de pagina (origen arriba-izquierda); una caja con right<=left o bottom<=top se
     *   ignora (caracter sin geometria). Es el mismo array que [PdfPageText]/RawPageText.
     * @param inkThreshold un pixel es "tinta" si su gris < este umbral.
     * @param boxPadPx margen (px) con que se dilatan las cajas al marcarlas como cubiertas, para
     *   absorber el antialias del borde de los glifos.
     * @param downsample factor de reduccion de resolucion para la morfologia (3 = 1/3).
     *
     * Algoritmo (ver docs/revisiones/detector.py `detect` y medir.py `detect`):
     *  1. mascara de tinta (gris < inkThreshold).
     *  2. mascara de cobertura: rasterizar cada caja (puntos->px con pxPerPoint) con pad.
     *  3. gap = tinta AND NOT cobertura.
     *  4. reducir gap a 1/downsample.
     *  5. dilatacion horizontal (kx ~ 0.12*dpi/downsample, ky pequeno) para unir caracteres en
     *     linea; dpi = pxPerPoint*72.
     *  6. etiquetar componentes conexas.
     *  7. por componente: descartar si poca tinta real (gpix pequeno); filtrar "parece texto"
     *     (alto de linea en [~0.06*dpi, ~0.6*dpi], ancho >= ~0.15*dpi, densidad >= ~0.04).
     *  8. devolver el bounding box en PUNTOS (dividiendo px por pxPerPoint).
     */
    fun detect(
        gray: GrayPage,
        boxes: FloatArray,
        inkThreshold: Int = 160,
        boxPadPx: Float = 1.5f,
        downsample: Int = 3,
    ): List<Region> {
        TODO("Fase 1: portar el detector de docs/revisiones/detector.py (implementa el experto)")
    }
}
