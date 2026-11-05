package com.lectorvoz.core.text

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.text.MissingTextDetector.Region
import com.lectorvoz.core.text.PageTextMerger.OcrLine
import org.junit.Test

/**
 * Pruebas de [PageTextMerger.merge]. Modulo JVM puro: solo JUnit4 + Truth.
 *
 * Convenio de coordenadas de los helpers: pagina en PUNTOS, origen arriba-izquierda (y crece hacia
 * abajo). Cada "linea" se coloca a una altura dada y sus caracteres se reparten en X.
 */
class PageTextMergerTest {

    /** Caja [left, top, right, bottom] para el caracter i-esimo de una linea horizontal. */
    private fun charBox(i: Int, top: Float, bottom: Float, x0: Float = 10f, w: Float = 6f): FloatArray {
        val left = x0 + i * w
        return floatArrayOf(left, top, left + w - 1f, bottom)
    }

    /** Texto nativo de una sola linea con cajas por caracter a la altura [top]..[bottom]. */
    private fun nativeLine(text: String, top: Float, bottom: Float, x0: Float = 10f): Pair<String, FloatArray> {
        val boxes = FloatArray(text.length * 4)
        for (i in text.indices) {
            val b = charBox(i, top, bottom, x0)
            boxes[i * 4] = b[0]; boxes[i * 4 + 1] = b[1]; boxes[i * 4 + 2] = b[2]; boxes[i * 4 + 3] = b[3]
        }
        return text to boxes
    }

    /** Concatena varias lineas nativas (texto + cajas) en un unico par, en el orden dado. */
    private fun concat(vararg lines: Pair<String, FloatArray>): Pair<String, FloatArray> {
        val text = buildString { lines.forEach { append(it.first) } }
        val boxes = FloatArray(text.length * 4)
        var off = 0
        for ((t, b) in lines) {
            b.copyInto(boxes, off * 4)
            off += t.length
        }
        return text to boxes
    }

    /** OcrLine con cajas por caracter a la altura [top]..[bottom]; los espacios van con caja vacia. */
    private fun ocrLine(text: String, top: Float, bottom: Float, x0: Float = 10f): OcrLine {
        val boxes = FloatArray(text.length * 4)
        for (i in text.indices) {
            if (text[i] == ' ') continue // caja 0,0,0,0
            val b = charBox(i, top, bottom, x0)
            boxes[i * 4] = b[0]; boxes[i * 4 + 1] = b[1]; boxes[i * 4 + 2] = b[2]; boxes[i * 4 + 3] = b[3]
        }
        val xs = (0 until text.length).map { charBox(it, top, bottom, x0)[0] }
        val region = Region(xs.first(), top, xs.last() + 6f, bottom)
        return OcrLine(text, boxes, region)
    }

    private fun boxAt(boxes: FloatArray, charIndex: Int): FloatArray =
        boxes.copyOfRange(charIndex * 4, charIndex * 4 + 4)

    // ------------------------------------------------------------------------------------------

    @Test
    fun identidad_sinOcr_devuelveTextoYCajasIntactos() {
        val (text, boxes) = nativeLine("Hola mundo", top = 10f, bottom = 20f)
        val merged = PageTextMerger.merge(text, boxes, emptyList())

        assertThat(merged.text).isEqualTo(text)
        assertThat(merged.boxes.toList()).isEqualTo(boxes.toList())
    }

    @Test
    fun invariante_cajasPorCaracter_enTodosLosCasos() {
        val (text, boxes) = concat(
            nativeLine("Arriba", top = 10f, bottom = 20f),
            nativeLine("Abajo", top = 50f, bottom = 60f),
        )
        val casos = listOf(
            emptyList(),
            listOf(ocrLine("medio", top = 30f, bottom = 40f)),
            listOf(ocrLine("encima", top = 0f, bottom = 5f), ocrLine("debajo", top = 90f, bottom = 100f)),
        )
        for (ocr in casos) {
            val merged = PageTextMerger.merge(text, boxes, ocr)
            assertThat(merged.boxes.size).isEqualTo(merged.text.length * 4)
        }
    }

    @Test
    fun ordenDeLectura_ocrEnMedioApareceEntreLasDosLineas() {
        val (text, boxes) = concat(
            nativeLine("ARRIBA", top = 10f, bottom = 20f),
            nativeLine("ABAJO", top = 50f, bottom = 60f),
        )
        val merged = PageTextMerger.merge(text, boxes, listOf(ocrLine("medio", top = 30f, bottom = 40f)))

        val iArriba = merged.text.indexOf("ARRIBA")
        val iMedio = merged.text.indexOf("medio")
        val iAbajo = merged.text.indexOf("ABAJO")
        assertThat(iArriba).isGreaterThan(-1)
        assertThat(iMedio).isGreaterThan(iArriba)
        assertThat(iAbajo).isGreaterThan(iMedio)
    }

    @Test
    fun ocrPorEncimaDeTodo_vaAlPrincipio() {
        val (text, boxes) = nativeLine("CUERPO", top = 50f, bottom = 60f)
        val merged = PageTextMerger.merge(text, boxes, listOf(ocrLine("titulo", top = 10f, bottom = 20f)))

        assertThat(merged.text.indexOf("titulo")).isLessThan(merged.text.indexOf("CUERPO"))
        // El bloque va al principio: arranca con el separador (caja vacia) seguido del texto OCR.
        assertThat(merged.text.indexOf("titulo")).isEqualTo(1)
    }

    @Test
    fun ocrPorDebajoDeTodo_vaAlFinal() {
        val (text, boxes) = nativeLine("CUERPO", top = 10f, bottom = 20f)
        val merged = PageTextMerger.merge(text, boxes, listOf(ocrLine("pie", top = 90f, bottom = 100f)))

        assertThat(merged.text.indexOf("pie")).isGreaterThan(merged.text.indexOf("CUERPO"))
        // El bloque OCR va envuelto en corte duro U+2028 delante y detras (tambien al final).
        assertThat(merged.text).endsWith(" pie ")
    }

    @Test
    fun separador_corteDuro_antesDelBloqueOcr() {
        val (text, boxes) = nativeLine("CUERPO", top = 10f, bottom = 20f)
        val merged = PageTextMerger.merge(text, boxes, listOf(ocrLine("pie", top = 90f, bottom = 100f)))

        // El separador (caracter inmediatamente anterior al texto OCR) es un corte duro U+2028.
        val sepIdx = merged.text.indexOf("pie") - 1
        assertThat(merged.text[sepIdx]).isEqualTo(' ')
        assertThat(boxAt(merged.boxes, sepIdx).toList()).isEqualTo(listOf(0f, 0f, 0f, 0f))
    }

    @Test
    fun separador_corteDuro_aislaElBloqueOcrDeAmbosVecinos() {
        // OCR en la MISMA banda que la primera linea nativa pero mas a la derecha. Hay otra linea
        // nativa por debajo, asi que la insercion NO cae al final. Debe ir envuelto en corte duro
        // U+2028 delante y detras (nunca pegado por un espacio), para quedar como frase propia.
        val (text, boxes) = concat(
            nativeLine("IZQ", top = 10f, bottom = 20f, x0 = 10f),
            nativeLine("ABAJO", top = 50f, bottom = 60f, x0 = 10f),
        )
        val ocr = ocrLine("DER", top = 11f, bottom = 19f, x0 = 200f)
        val merged = PageTextMerger.merge(text, boxes, listOf(ocr))

        val i = merged.text.indexOf("DER")
        assertThat(merged.text[i - 1]).isEqualTo(' ')
        assertThat(merged.text[i + "DER".length]).isEqualTo(' ')
        assertThat(boxAt(merged.boxes, i - 1).toList()).isEqualTo(listOf(0f, 0f, 0f, 0f))
        // Y el bloque OCR queda entre ambas lineas nativas.
        assertThat(i).isGreaterThan(merged.text.indexOf("IZQ"))
        assertThat(i).isLessThan(merged.text.indexOf("ABAJO"))
    }

    @Test
    fun versalitaRecuperada_quedaComoFrasePropia_trasLimpiadorYSegmentador() {
        // Regresion del bug del movil (Carl pag 262): una linea recuperada por OCR (versalita, SIN
        // punto final) insertada en la MISMA banda que el cuerpo debe sonar como frase propia. Antes
        // el separador se colapsaba y la versalita se pegaba a la frase vecina -> la voz se la saltaba.
        // Aqui se recorre el pipeline real de la voz: merge -> PdfPageFlow.sameLength -> segmentador.
        val (text, boxes) = concat(
            nativeLine("En la caja habia cinco piezas. ", top = 10f, bottom = 20f),
            nativeLine("Cuando examine el papel rojo. ", top = 50f, bottom = 60f),
        )
        // Region en la banda del cuerpo (top 30..40, entre ambas lineas) y sin terminador de frase.
        val ocr = ocrLine("3 VALES POR UNA COPA", top = 30f, bottom = 40f)
        val merged = PageTextMerger.merge(text, boxes, listOf(ocr))

        val flowed = PdfPageFlow.flow(merged.text)
        val frases = SentenceSegmenter().segment(flowed.sameLength).map { flowed.sameLength.substring(it.start, it.end).trim() }

        // La versalita aparece como una frase separada (no absorbida por una vecina).
        assertThat(frases).contains("3 VALES POR UNA COPA")
    }

    @Test
    fun cajasDeCaracteresOcr_seConservanEnLaPosicionCorrecta() {
        val (text, boxes) = concat(
            nativeLine("ARRIBA", top = 10f, bottom = 20f),
            nativeLine("ABAJO", top = 50f, bottom = 60f),
        )
        val ocr = ocrLine("XY", top = 30f, bottom = 40f, x0 = 100f)
        val merged = PageTextMerger.merge(text, boxes, listOf(ocr))

        val iX = merged.text.indexOf("XY")
        assertThat(boxAt(merged.boxes, iX).toList()).isEqualTo(boxAt(ocr.wordBoxes, 0).toList())
        assertThat(boxAt(merged.boxes, iX + 1).toList()).isEqualTo(boxAt(ocr.wordBoxes, 1).toList())
    }

    @Test
    fun glifoBasuraDesordenado_noArrastraLaInsercionAlPrincipio() {
        // pdfium emite los glifos que no decodifica (versalitas -> uso privado U+F7xx) al PRINCIPIO
        // del flujo de texto, aunque su caja este geometricamente en otra parte. El cuerpo real va en
        // orden de lectura. La linea OCR (banda media) debe caer ENTRE las lineas del cuerpo, no
        // arrastrada al principio por el glifo basura. (Regresion del mixto en el movil, pag Carl 262.)
        val (text, boxes) = concat(
            nativeLine("", top = 90f, bottom = 100f), // basura uso privado, fuera de orden, caja abajo
            nativeLine("ALTO", top = 10f, bottom = 20f),
            nativeLine("BAJO", top = 50f, bottom = 60f),
        )
        val merged = PageTextMerger.merge(text, boxes, listOf(ocrLine("medio", top = 30f, bottom = 40f)))

        val iAlto = merged.text.indexOf("ALTO")
        val iMedio = merged.text.indexOf("medio")
        val iBajo = merged.text.indexOf("BAJO")
        assertThat(iAlto).isGreaterThan(-1)
        assertThat(iMedio).isGreaterThan(iAlto)
        assertThat(iMedio).isLessThan(iBajo)
        assertThat(merged.boxes.size).isEqualTo(merged.text.length * 4)
    }

    @Test
    fun idempotenciaConceptual_mismasEntradasMismaSalida() {
        val (text, boxes) = concat(
            nativeLine("ARRIBA", top = 10f, bottom = 20f),
            nativeLine("ABAJO", top = 50f, bottom = 60f),
        )
        val ocr = listOf(
            ocrLine("medio", top = 30f, bottom = 40f),
            ocrLine("otro", top = 35f, bottom = 45f, x0 = 120f),
        )
        val a = PageTextMerger.merge(text, boxes, ocr)
        val b = PageTextMerger.merge(text, boxes, ocr)

        assertThat(a.text).isEqualTo(b.text)
        assertThat(a.boxes.toList()).isEqualTo(b.boxes.toList())
    }

    @Test
    fun variasLineasOcr_seInsertanCadaUnaEnSuBanda() {
        val (text, boxes) = concat(
            nativeLine("UNO", top = 10f, bottom = 20f),
            nativeLine("DOS", top = 50f, bottom = 60f),
            nativeLine("TRES", top = 90f, bottom = 100f),
        )
        val ocr = listOf(
            ocrLine("entreUnoDos", top = 30f, bottom = 40f),
            ocrLine("entreDosTres", top = 70f, bottom = 80f),
        )
        val merged = PageTextMerger.merge(text, boxes, ocr)

        val iUno = merged.text.indexOf("UNO")
        val iA = merged.text.indexOf("entreUnoDos")
        val iDos = merged.text.indexOf("DOS")
        val iB = merged.text.indexOf("entreDosTres")
        val iTres = merged.text.indexOf("TRES")
        assertThat(listOf(iUno, iA, iDos, iB, iTres)).isInOrder()
        assertThat(merged.boxes.size).isEqualTo(merged.text.length * 4)
    }
}
