package com.lectorvoz.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LetterSpacingTest {

    private val J = LetterSpacing.JOINER

    /**
     * Texto interletrado: cada [letras] con hueco [gapLetra] entre letras de la misma palabra y
     * [gapPalabra] entre palabras; los espacios llevan caja vacia (como en pdfium) y se separan con
     * [sep] espacios.
     */
    private fun interletrado(palabras: List<String>, gapLetra: Float, gapPalabra: Float, sep: Int = 1): Pair<String, FloatArray> {
        val sb = StringBuilder()
        val boxes = ArrayList<Float>()
        var x = 10f
        palabras.forEachIndexed { w, palabra ->
            palabra.forEachIndexed { k, ch ->
                sb.append(ch); boxes.addAll(listOf(x, 0f, x + 5f, 10f)); x += 5f
                val finDePalabra = k == palabra.lastIndex
                val finDeTodo = finDePalabra && w == palabras.lastIndex
                if (!finDeTodo) {
                    x += if (finDePalabra) gapPalabra else gapLetra
                    repeat(sep) { sb.append(' '); boxes.addAll(listOf(0f, 0f, 0f, 0f)) }
                }
            }
        }
        return sb.toString() to boxes.toFloatArray()
    }

    @Test
    fun unePalabrasInterletradasYRespetaLosHuecosEntrePalabras() {
        val (t, b) = interletrado(listOf("Para", "los", "que", "suenan"), gapLetra = 3f, gapPalabra = 9f, sep = 2)
        val r = LetterSpacing.fix(t, b)
        assertThat(r.length).isEqualTo(t.length) // misma longitud: las cajas siguen valiendo
        // Las palabras quedan unidas por ZWSP y separadas por sus espacios originales.
        val limpio = r.replace(J.toString(), "").replace(Regex(" +"), " ")
        assertThat(limpio).isEqualTo("Para los que suenan")
    }

    @Test
    fun huecoDePalabraSoloUnPocoMayorQueElDeLetra() {
        val (t, b) = interletrado(listOf("Para", "los", "que", "suenan", "con", "mundos"), gapLetra = 6f, gapPalabra = 9f, sep = 2)
        val limpio = LetterSpacing.fix(t, b).replace(J.toString(), "").replace(Regex(" +"), " ")
        assertThat(limpio).isEqualTo("Para los que suenan con mundos")
    }

    @Test
    fun unaSolaPalabraInterletrada() {
        val (t, b) = interletrado(listOf("PRIMERA"), gapLetra = 3f, gapPalabra = 3f)
        assertThat(LetterSpacing.fix(t, b).replace(J.toString(), "")).isEqualTo("PRIMERA")
    }

    @Test
    fun textoNormalNoSeToca() {
        val t = "Este es el dilema cuando de magia se trata y no se acaba"
        val b = FloatArray(t.length * 4) { 1f }
        assertThat(LetterSpacing.fix(t, b)).isSameInstanceAs(t)
        // Palabras sueltas de una letra no disparan la reparacion.
        val t2 = "y a la o e de un"
        assertThat(LetterSpacing.fix(t2, FloatArray(t2.length * 4) { 1f })).isSameInstanceAs(t2)
    }

    @Test
    fun sinGeometriaFiableNoSeToca() {
        val (t, _) = interletrado(listOf("Para", "los"), gapLetra = 3f, gapPalabra = 9f)
        assertThat(LetterSpacing.fix(t, FloatArray(t.length * 4))).isSameInstanceAs(t)
        assertThat(LetterSpacing.fix(t, FloatArray(3))).isSameInstanceAs(t) // invariante rota
    }

    @Test
    fun fraseLargaSinHuecosDePalabraNoSePega() {
        // 20 letras con huecos uniformes: no hay forma de saber donde acaban las palabras.
        val (t, b) = interletrado(listOf("abcdefghijklmnopqrst"), gapLetra = 3f, gapPalabra = 3f)
        assertThat(LetterSpacing.fix(t, b)).isSameInstanceAs(t)
    }

    @Test
    fun tramoEnUnaLineaNoArrastraAlaSiguiente() {
        val (t, b) = interletrado(listOf("HOLA", "MUNDO"), gapLetra = 3f, gapPalabra = 9f)
        val texto = t + "\nTexto normal despues."
        val cajas = b + FloatArray(("\nTexto normal despues.").length * 4) { 1f }
        val r = LetterSpacing.fix(texto, cajas)
        assertThat(r.endsWith("\nTexto normal despues.")).isTrue()
    }
}
