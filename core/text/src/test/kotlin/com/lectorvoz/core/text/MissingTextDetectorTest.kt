package com.lectorvoz.core.text

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.text.MissingTextDetector.GrayPage
import com.lectorvoz.core.text.MissingTextDetector.Region
import org.junit.Test

/**
 * Pruebas de [MissingTextDetector.detect]. Construye paginas grises sinteticas dibujando
 * "lineas de texto" como bandas horizontales de tinta (negro) sobre fondo blanco, y comprueba
 * que el detector caza la tinta sin caja, la descarta cuando esta cubierta por cajas y filtra
 * las formas que no parecen linea.
 */
class MissingTextDetectorTest {

    /** Lienzo gris: fondo blanco (255); se pintan rectangulos de tinta (0=negro). */
    private class Canvas(val width: Int, val height: Int) {
        val pixels = ByteArray(width * height) { 255.toByte() }

        /** Pinta de negro el rectangulo de pixeles [x0,x1) x [y0,y1). */
        fun inkRect(x0: Int, y0: Int, x1: Int, y1: Int) {
            for (y in y0 until y1) {
                val base = y * width
                for (x in x0 until x1) pixels[base + x] = 0
            }
        }

        fun page(pxPerPoint: Float) = GrayPage(pixels, width, height, pxPerPoint)
    }

    /** Un punto (en puntos de pagina) cae dentro de la region. */
    private fun Region.contains(xPt: Float, yPt: Float): Boolean =
        xPt in left..right && yPt in top..bottom

    @Test
    fun bandaDeTintaSinCaja_seDetecta() {
        // Banda horizontal: x[30,270) y[60,84) px, pagina 300x180, pxPerPoint=2.
        val c = Canvas(300, 180)
        c.inkRect(30, 60, 270, 84)

        val regions = MissingTextDetector.detect(c.page(2f), boxes = FloatArray(0))

        assertThat(regions).isNotEmpty()
        // Centro de la banda en puntos: x=(150/2)=75, y=((60+84)/2)/2=36.
        assertThat(regions.any { it.contains(75f, 36f) }).isTrue()
    }

    @Test
    fun bandaCubiertaPorCajas_noSeDetecta() {
        // Misma banda, pero una caja (en puntos) la cubre entera -> gap vacio -> sin regiones.
        val c = Canvas(300, 180)
        c.inkRect(30, 60, 270, 84)
        // Banda en puntos (pxPerPoint=2): x[15,135] y[30,42]. Caja algo mayor.
        val boxes = floatArrayOf(14f, 29f, 136f, 43f)

        val regions = MissingTextDetector.detect(c.page(2f), boxes)

        assertThat(regions).isEmpty()
    }

    @Test
    fun manchaPequenaCuadrada_seDescarta() {
        // Mancha 6x6 px (ruido): a 1/3 son ~4 pixeles de tinta, por debajo del minimo (6).
        val c = Canvas(300, 180)
        c.inkRect(150, 90, 156, 96)

        val regions = MissingTextDetector.detect(c.page(2f), boxes = FloatArray(0))

        assertThat(regions).isEmpty()
    }

    @Test
    fun bloqueCuadradoGrande_seDescartaPorAltura() {
        // Un cuadrado grande (figura) no es una linea: su alto supera lhMax (0.6*dpi/ds).
        // pxPerPoint=2 -> dpi/ds = 48; lhMax=28.8 pixeles reducidos ~ 86 px completos.
        val c = Canvas(300, 300)
        c.inkRect(80, 80, 200, 200) // 120x120 px -> ~40 filas reducidas > 28.8

        val regions = MissingTextDetector.detect(c.page(2f), boxes = FloatArray(0))

        // Ninguna region debe cubrir el centro del bloque (en puntos: 70,70).
        assertThat(regions.any { it.contains(70f, 70f) }).isFalse()
    }

    @Test
    fun conversionAPuntos_conPxPerPointDistintoDeUno() {
        // pxPerPoint=4: la region devuelta debe estar en PUNTOS (px/4), no en pixeles.
        val c = Canvas(400, 200)
        c.inkRect(40, 80, 360, 120) // banda ancha

        val regions = MissingTextDetector.detect(c.page(4f), boxes = FloatArray(0))

        assertThat(regions).hasSize(1)
        val r = regions.first()
        // Centro de la banda en puntos: x=((40+360)/2)/4=50, y=((80+120)/2)/4=25.
        assertThat(r.contains(50f, 25f)).isTrue()
        // La pagina mide 400x200 px = 100x50 puntos. La region (algo mayor que la banda por la
        // dilatacion) debe caber en esos puntos. Si estuviera en PIXELES, right rondaria los 360.
        assertThat(r.left).isAtLeast(0f)
        assertThat(r.top).isAtLeast(0f)
        assertThat(r.right).isLessThan(100f)
        assertThat(r.bottom).isLessThan(50f)
    }

    @Test
    fun boxesVacio_conLinea_seDetecta() {
        // boxes vacio y pagina con una linea de tinta -> la detecta (1 region que la solapa).
        val c = Canvas(300, 160)
        c.inkRect(40, 70, 260, 92)

        val regions = MissingTextDetector.detect(c.page(2f), boxes = FloatArray(0))

        assertThat(regions).isNotEmpty()
        assertThat(regions.any { it.contains(75f, 40f) }).isTrue()
    }
}
