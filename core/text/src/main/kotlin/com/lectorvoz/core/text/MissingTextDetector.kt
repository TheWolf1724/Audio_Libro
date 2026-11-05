package com.lectorvoz.core.text

import kotlin.math.ceil
import kotlin.math.max

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
        val w = gray.width
        val h = gray.height
        val ds = if (downsample < 1) 1 else downsample
        if (w <= 0 || h <= 0) return emptyList()

        val pxPerPoint = gray.pxPerPoint
        // dpi = pixeles por punto * 72; todos los umbrales se expresan en unidades reducidas (dpi/ds).
        val dpiOverDs = pxPerPoint * 72f / ds

        // 2. Mascara de cobertura a resolucion completa: rasterizar cada caja (puntos->px) con pad.
        val cov = BooleanArray(w * h)
        var i = 0
        val n = boxes.size - boxes.size % 4
        while (i < n) {
            val left = boxes[i]
            val top = boxes[i + 1]
            val right = boxes[i + 2]
            val bottom = boxes[i + 3]
            i += 4
            // Caja sin geometria: ignorar.
            if (right <= left || bottom <= top) continue
            val x0 = left * pxPerPoint
            val y0 = top * pxPerPoint
            val x1 = right * pxPerPoint
            val y1 = bottom * pxPerPoint
            var xa = (x0 - boxPadPx).toInt(); if (xa < 0) xa = 0
            var xb = ceil(x1 + boxPadPx).toInt(); if (xb > w) xb = w
            var ya = (y0 - boxPadPx).toInt(); if (ya < 0) ya = 0
            var yb = ceil(y1 + boxPadPx).toInt(); if (yb > h) yb = h
            if (xb <= xa || yb <= ya) continue
            var yy = ya
            while (yy < yb) {
                val base = yy * w
                var xx = xa
                while (xx < xb) {
                    cov[base + xx] = true
                    xx++
                }
                yy++
            }
        }

        // 4. gap = tinta AND NOT cobertura, muestreado a 1/ds (submuestreo estriado, como gap[::ds,::ds]).
        val gw = (w + ds - 1) / ds
        val gh = (h + ds - 1) / ds
        val gap = BooleanArray(gw * gh)
        var gy = 0
        while (gy < gh) {
            val srcY = gy * ds
            val srcBase = srcY * w
            val dstBase = gy * gw
            var gx = 0
            while (gx < gw) {
                val srcX = gx * ds
                val idx = srcBase + srcX
                if ((gray.pixels[idx].toInt() and 0xFF) < inkThreshold && !cov[idx]) {
                    gap[dstBase + gx] = true
                }
                gx++
            }
            gy++
        }

        // 5. Dilatacion horizontal (kx ~ 0.12*dpi/ds) + vertical pequena (ky=1), separable.
        val kx = max(2, (0.12f * dpiOverDs).toInt())
        val ky = 1
        val dil = dilateSeparable(gap, gw, gh, kx, ky)

        // 6. Etiquetar componentes conexas (4-conexion, flood fill iterativo).
        val labels = IntArray(gw * gh)
        val queue = IntArray(gw * gh)
        val regions = ArrayList<Region>()

        val lhMin = 0.06f * dpiOverDs
        val lhMax = 0.6f * dpiOverDs
        val wMin = 0.15f * dpiOverDs

        var seed = 0
        var label = 0
        while (seed < dil.size) {
            if (dil[seed] && labels[seed] == 0) {
                label++
                var head = 0
                var tail = 0
                queue[tail++] = seed
                labels[seed] = label
                var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE
                var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
                while (head < tail) {
                    val p = queue[head++]
                    val px = p % gw
                    val py = p / gw
                    if (px < minX) minX = px
                    if (px > maxX) maxX = px
                    if (py < minY) minY = py
                    if (py > maxY) maxY = py
                    // vecinos 4-conexos
                    if (px > 0) { val q = p - 1; if (dil[q] && labels[q] == 0) { labels[q] = label; queue[tail++] = q } }
                    if (px < gw - 1) { val q = p + 1; if (dil[q] && labels[q] == 0) { labels[q] = label; queue[tail++] = q } }
                    if (py > 0) { val q = p - gw; if (dil[q] && labels[q] == 0) { labels[q] = label; queue[tail++] = q } }
                    if (py < gh - 1) { val q = p + gw; if (dil[q] && labels[q] == 0) { labels[q] = label; queue[tail++] = q } }
                }

                // 7. gpix = tinta real (gap sin dilatar) dentro del bounding box de la componente.
                val boxH = maxY - minY + 1
                val boxW = maxX - minX + 1
                var gpix = 0
                var ry = minY
                while (ry <= maxY) {
                    val rb = ry * gw
                    var rx = minX
                    while (rx <= maxX) {
                        if (gap[rb + rx]) gpix++
                        rx++
                    }
                    ry++
                }
                if (gpix < MIN_GAP_PIXELS) { seed++; continue }
                val dens = gpix.toFloat() / (boxH * boxW)
                val looksText = boxH >= lhMin && boxH <= lhMax && boxW >= wMin && dens >= MIN_DENSITY
                if (looksText) {
                    // 8. Bounding box a puntos: (x*ds) px / pxPerPoint.
                    regions.add(
                        Region(
                            left = minX * ds / pxPerPoint,
                            top = minY * ds / pxPerPoint,
                            right = maxX * ds / pxPerPoint,
                            bottom = maxY * ds / pxPerPoint,
                        )
                    )
                }
            }
            seed++
        }
        return regions
    }

    /** gpix minimo (en resolucion reducida) para no tratar ruido como texto (medir.py usa 6). */
    private const val MIN_GAP_PIXELS = 6

    /** Densidad minima de tinta dentro del bounding box para "parecer texto". */
    private const val MIN_DENSITY = 0.04f

    /**
     * Dilatacion binaria con elemento estructurante rectangular (2*rx+1 x 2*ry+1), separable:
     * primero en X (radio [rx]) y despues en Y (radio [ry]). Usa distancia al "true" mas cercano
     * para que sea lineal en el numero de pixeles.
     */
    private fun dilateSeparable(src: BooleanArray, w: Int, h: Int, rx: Int, ry: Int): BooleanArray {
        val afterX = if (rx <= 0) src.copyOf() else dilateX(src, w, h, rx)
        return if (ry <= 0) afterX else dilateY(afterX, w, h, ry)
    }

    private fun dilateX(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val out = BooleanArray(src.size)
        var y = 0
        while (y < h) {
            val base = y * w
            // distancia al true mas cercano a la izquierda
            var last = -1
            var x = 0
            while (x < w) {
                if (src[base + x]) last = x
                if (last >= 0 && x - last <= r) out[base + x] = true
                x++
            }
            // pasada hacia atras: true mas cercano a la derecha
            last = -1
            x = w - 1
            while (x >= 0) {
                if (src[base + x]) last = x
                if (last >= 0 && last - x <= r) out[base + x] = true
                x--
            }
            y++
        }
        return out
    }

    private fun dilateY(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val out = BooleanArray(src.size)
        var x = 0
        while (x < w) {
            var last = -1
            var y = 0
            while (y < h) {
                if (src[y * w + x]) last = y
                if (last >= 0 && y - last <= r) out[y * w + x] = true
                y++
            }
            last = -1
            y = h - 1
            while (y >= 0) {
                if (src[y * w + x]) last = y
                if (last >= 0 && last - y <= r) out[y * w + x] = true
                y--
            }
            x++
        }
        return out
    }
}
