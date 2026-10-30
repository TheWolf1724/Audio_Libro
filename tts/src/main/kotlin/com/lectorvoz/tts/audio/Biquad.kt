package com.lectorvoz.tts.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Filtro biquad (forma directa II traspuesta) con coeficientes del "Audio EQ Cookbook" de
 * R. Bristow-Johnson. Procesa en el sitio, sin asignar memoria.
 */
class Biquad private constructor(
    private val b0: Float,
    private val b1: Float,
    private val b2: Float,
    private val a1: Float,
    private val a2: Float,
) {
    private var z1 = 0f
    private var z2 = 0f

    /** Pone a cero el estado interno (al empezar una frase nueva). */
    fun reset() {
        z1 = 0f
        z2 = 0f
    }

    /** Filtra [buf] en [from, to). */
    fun process(buf: FloatArray, from: Int = 0, to: Int = buf.size) {
        var s1 = z1
        var s2 = z2
        for (i in from until to) {
            val x = buf[i]
            val y = b0 * x + s1
            s1 = b1 * x - a1 * y + s2
            s2 = b2 * x - a2 * y
            buf[i] = y
        }
        z1 = s1
        z2 = s2
    }

    /** Ganancia (lineal) del filtro a la frecuencia [f] Hz con muestreo [fs]. */
    fun magnitudeAt(f: Double, fs: Int): Double {
        val w = 2 * PI * f / fs
        val cw = cos(w); val sw = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * cw + b2 * c2
        val ni = -(b1 * sw + b2 * s2)
        val dr = 1 + a1 * cw + a2 * c2
        val di = -(a1 * sw + a2 * s2)
        return sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    companion object {
        /** Paso alto de 2.o orden en [f0] Hz. */
        fun highPass(fs: Int, f0: Double, q: Double = 0.7071): Biquad {
            val w0 = 2 * PI * f0 / fs
            val alpha = sin(w0) / (2 * q)
            val c = cos(w0)
            return normalized(
                b0 = (1 + c) / 2, b1 = -(1 + c), b2 = (1 + c) / 2,
                a0 = 1 + alpha, a1 = -2 * c, a2 = 1 - alpha,
            )
        }

        /** Campana (peaking) en [f0] Hz con [gainDb] dB. */
        fun peaking(fs: Int, f0: Double, q: Double, gainDb: Double): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w0 = 2 * PI * f0 / fs
            val alpha = sin(w0) / (2 * q)
            val c = cos(w0)
            return normalized(
                b0 = 1 + alpha * a, b1 = -2 * c, b2 = 1 - alpha * a,
                a0 = 1 + alpha / a, a1 = -2 * c, a2 = 1 - alpha / a,
            )
        }

        private fun normalized(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            Biquad(
                (b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(),
                (a1 / a0).toFloat(), (a2 / a0).toFloat(),
            )
    }
}
