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
     * Separador DURO que envuelve cada linea recuperada por OCR. U+2028 (LINE SEPARATOR) lo
     * respetan tanto el limpiador [PdfPageFlow] (no lo colapsa como a '\n') como el segmentador
     * [SentenceSegmenter] (corta en el), de modo que la linea recuperada queda SIEMPRE como frase
     * propia en su sitio, aunque no lleve signo de puntuacion final.
     */
    private const val HARD_SEP = ' '

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
     *  - envolver la linea con un corte DURO (U+2028) ANTES y DESPUES, con caja vacia, para que
     *    quede como frase propia (sobrevive al limpiador y el segmentador corta en el), y en medio
     *    los caracteres de la linea con sus cajas.
     *  - aplicar las inserciones de mayor a menor indice (o con desplazamientos) para no invalidar
     *    los puntos de insercion.
     * Debe ser determinista e idempotente para una pagina dada. Sin ocrLines, Merged == entrada.
     */
    fun merge(nativeText: String, nativeBoxes: FloatArray, ocrLines: List<OcrLine>): Merged {
        require(nativeBoxes.size == nativeText.length * 4) {
            "nativeBoxes debe tener 4 floats por caracter"
        }
        // Identidad: sin OCR, el resultado es exactamente la entrada (copia defensiva de las cajas).
        if (ocrLines.isEmpty()) return Merged(nativeText, nativeBoxes.copyOf())

        val n = nativeText.length

        // --- clave de lectura ---------------------------------------------------------------
        // La clave de un caracter nativo con caja es (banda, centroX): primero por banda vertical
        // (misma idea que PdfPageText.sameLine: dos cajas comparten banda si su solapamiento en Y
        // supera la mitad de la altura menor), y dentro de la banda por el centro X. Comparamos la
        // clave de cada caracter nativo contra la Region de una linea OCR de forma geometrica:
        //  - si comparten banda -> manda el centro X (quien esta mas a la derecha es "mayor").
        //  - si no -> manda el centro Y (quien esta mas abajo es "mayor").
        fun hasBox(i: Int): Boolean {
            val l = nativeBoxes[i * 4]; val t = nativeBoxes[i * 4 + 1]
            val r = nativeBoxes[i * 4 + 2]; val b = nativeBoxes[i * 4 + 3]
            return r > l && b > t
        }

        // Ancla de ORDEN DE LECTURA: solo el texto real sirve para situar una linea OCR. pdfium emite
        // los glifos que no decodifica (versalitas -> uso privado) con caja pero FUERA del orden de
        // lectura (a menudo al principio del flujo); si se usaran como ancla, arrastrarian la
        // insercion a un sitio equivocado. El cuerpo con texto decodificado si va en orden, asi que
        // sobre el se decide la posicion. Los glifos basura se quedan donde esten (el limpiador los
        // descarta: no se oyen) pero no mandan en el orden.
        fun anchorable(i: Int): Boolean = hasBox(i) && GlyphClass.decodesToText(nativeText[i])

        fun sameBand(t1: Float, b1: Float, t2: Float, b2: Float): Boolean {
            val overlap = minOf(b1, b2) - maxOf(t1, t2)
            val minH = minOf(b1 - t1, b2 - t2)
            return overlap > minH * 0.5f
        }

        // >0 si el caracter nativo i va DESPUES (es "mayor") que la linea OCR en orden de lectura.
        fun nativeAfterOcr(i: Int, region: MissingTextDetector.Region): Int {
            val l = nativeBoxes[i * 4]; val t = nativeBoxes[i * 4 + 1]
            val r = nativeBoxes[i * 4 + 2]; val b = nativeBoxes[i * 4 + 3]
            return if (sameBand(t, b, region.top, region.bottom)) {
                ((l + r) / 2f).compareTo((region.left + region.right) / 2f)
            } else {
                ((t + b) / 2f).compareTo((region.top + region.bottom) / 2f)
            }
        }

        // true si el caracter nativo [a] va ANTES que [b] en orden de LECTURA (banda-Y, luego X).
        fun readingBefore(a: Int, b: Int): Boolean {
            val ta = nativeBoxes[a * 4 + 1]; val ba = nativeBoxes[a * 4 + 3]
            val tb = nativeBoxes[b * 4 + 1]; val bb = nativeBoxes[b * 4 + 3]
            return if (sameBand(ta, ba, tb, bb)) {
                (nativeBoxes[a * 4] + nativeBoxes[a * 4 + 2]) < (nativeBoxes[b * 4] + nativeBoxes[b * 4 + 2])
            } else {
                (ta + ba) < (tb + bb)
            }
        }

        // Punto de insercion: la linea OCR va justo ANTES de su SUCESOR en orden de LECTURA, es
        // decir, del caracter nativo real que es "mayor" que la region y, de todos esos, el MENOR
        // (el mas cercano por debajo/derecha). Se elige por GEOMETRIA, no por el orden del flujo de
        // pdfium: ese no es fiable (emite cabeceras/pies y las versalitas fuera del orden de lectura,
        // lo que antes arrastraba la insercion al principio de la pagina). Sin sucesor -> al final.
        fun insertionIndex(region: MissingTextDetector.Region): Int {
            var best = -1
            for (i in 0 until n) {
                if (!anchorable(i) || nativeAfterOcr(i, region) <= 0) continue
                if (best < 0 || readingBefore(i, best)) best = i
            }
            return if (best < 0) n else best
        }

        // Precalcula el punto de insercion de cada linea contra el texto nativo ORIGINAL y ordena:
        // por indice de insercion y, a igualdad, por posicion de lectura (centro Y, centro X) y
        // finalmente por el orden de entrada, para ser deterministas e idempotentes.
        data class Planned(val line: OcrLine, val insIdx: Int, val order: Int)
        val planned = ocrLines.mapIndexed { idx, line ->
            Planned(line, insertionIndex(line.region), idx)
        }.sortedWith(
            compareBy(
                { it.insIdx },
                { (it.line.region.top + it.line.region.bottom) / 2f },
                { (it.line.region.left + it.line.region.right) / 2f },
                { it.order },
            )
        )

        // --- construccion del resultado ------------------------------------------------------
        val out = StringBuilder(n + planned.sumOf { it.line.text.length + 1 })
        val boxes = ArrayList<Float>((out.capacity().coerceAtLeast(n)) * 4)

        fun emitNative(i: Int) {
            out.append(nativeText[i])
            boxes.add(nativeBoxes[i * 4]); boxes.add(nativeBoxes[i * 4 + 1])
            boxes.add(nativeBoxes[i * 4 + 2]); boxes.add(nativeBoxes[i * 4 + 3])
        }

        fun emitSep() { out.append(HARD_SEP); boxes.add(0f); boxes.add(0f); boxes.add(0f); boxes.add(0f) }

        fun emitOcr(p: Planned) {
            // Una linea recuperada por OCR es texto impreso FUERA del flujo nativo (versalita, titulo
            // decorativo): hay que AISLARLA de ambos vecinos con un corte DURO para que la voz la lea
            // como frase propia aunque no lleve punto final. Se usa U+2028 (LINE SEPARATOR), no '\n':
            //  - el limpiador (PdfPageFlow) solo colapsa '\n'/'\r' al unir parrafos; U+2028 sobrevive.
            //  - el segmentador (SentenceSegmenter.isHardBreak) corta en U+2028.
            // Antes solo se ponia separador DELANTE y podia colapsarse a espacio: la versalita se
            // pegaba a la frase vecina y, sin terminador, no se emitia (se la saltaba la voz).
            emitSep() // corte duro antes
            out.append(p.line.text)
            for (f in p.line.wordBoxes) boxes.add(f)
            emitSep() // corte duro despues (antes no habia: se pegaba a la frase nativa siguiente)
        }

        var k = 0
        for (i in 0 until n) {
            while (k < planned.size && planned[k].insIdx == i) { emitOcr(planned[k]); k++ }
            emitNative(i)
        }
        while (k < planned.size) { emitOcr(planned[k]); k++ }

        return Merged(out.toString(), boxes.toFloatArray())
    }
}
