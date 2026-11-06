package com.lectorvoz.format.pdf.ocr

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Aritmetica pura de [OcrEngine] (sin la libreria nativa): bordes del recorte de una region y
 * mascara de cobertura del texto nativo. Es la parte propensa a off-by-one del camino del OCR.
 */
class OcrEngineGeometryTest {

    @Test
    fun cropBounds_regionInterior_aplicaMargenYEscala() {
        // region en puntos [10,20,30,40], escala 2 px/pt, imagen 200x200. margen = round(2*2) = 4.
        val b = OcrEngine.cropBounds(floatArrayOf(10f, 20f, 30f, 40f), 200, 200, 2f)!!
        assertThat(b.toList()).isEqualTo(listOf(16, 36, 64, 84))
    }

    @Test
    fun cropBounds_enElBorde_seRecortaALaImagen() {
        val b = OcrEngine.cropBounds(floatArrayOf(0f, 0f, 1f, 1f), 200, 200, 2f)!!
        assertThat(b[0]).isEqualTo(0) // no se sale por la izquierda/arriba pese al margen
        assertThat(b[1]).isEqualTo(0)
        assertThat(b[2]).isAtMost(200)
        assertThat(b[3]).isAtMost(200)
    }

    @Test
    fun cropBounds_recorteMinusculo_devuelveNull() {
        // Imagen 5x5: la region queda recortada a menos de 2 px de lado.
        assertThat(OcrEngine.cropBounds(floatArrayOf(10f, 10f, 12f, 12f), 5, 5, 1f)).isNull()
    }

    @Test
    fun coverageMask_cubreLaCajaConUnPixelDeMargen() {
        val cov = OcrEngine.coverageMask(floatArrayOf(2f, 2f, 4f, 4f), 10, 10, 1f)!!
        fun at(x: Int, y: Int) = cov[y * 10 + x]
        assertThat(at(2, 2)).isTrue()
        assertThat(at(4, 4)).isTrue() // dentro del margen (xEnd = 5)
        assertThat(at(0, 0)).isFalse()
        assertThat(at(7, 7)).isFalse()
    }

    @Test
    fun coverageMask_sinCajas_esNull() {
        assertThat(OcrEngine.coverageMask(null, 10, 10, 1f)).isNull()
        assertThat(OcrEngine.coverageMask(FloatArray(0), 10, 10, 1f)).isNull()
    }

    @Test
    fun coverageMask_cajaDegenerada_seIgnora() {
        // right<=left: caja sin geometria, no cubre nada.
        val cov = OcrEngine.coverageMask(floatArrayOf(4f, 4f, 2f, 2f), 10, 10, 1f)!!
        assertThat(cov.any { it }).isFalse()
    }
}
