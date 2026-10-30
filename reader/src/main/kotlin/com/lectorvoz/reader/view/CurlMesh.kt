package com.lectorvoz.reader.view

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometria del curl para `Canvas.drawBitmapMesh` (calculo puro, sin Android; reutiliza sus
 * arrays: ninguna asignacion por fotograma).
 *
 * Modelo de cilindro: la hoja se enrolla sobre un cilindro de radio [radius] cuyo eje es
 * perpendicular a la direccion d = (esquina - punto tocado). Para cada vertice V de la hoja
 * plana, s = (V - A) . d es su distancia al eje A:
 * - s <= 0: queda plano.
 * - 0 < s < pi*r: sobre el cilindro, s' = r*sin(s/r). La mitad que mira al lector
 *   (s/r <= pi/2) es el anverso; el resto es el reverso.
 * - s >= pi*r: vuelve plano por encima, s' = -(s - pi*r) (reverso).
 * El eje se coloca para que la esquina caiga justo bajo el dedo: a = (L + pi*r)/2.
 *
 * Se generan dos mallas con la misma textura: [front] (el anverso; lo que pasa de pi/2 se
 * aplasta en el borde del cilindro) y [back] (el reverso; lo que no llega a pi/2 se aplasta
 * en ese mismo borde, area cero). Modelo geometrico de cilindro (tecnica clasica para
 * simular el curvado de una hoja); implementacion propia.
 */
class CurlMesh(val meshWidth: Int = 24, val meshHeight: Int = 32) {
    val vertexCount = (meshWidth + 1) * (meshHeight + 1)

    /** Vertices (x, y) del anverso. */
    val front = FloatArray(vertexCount * 2)

    /** Vertices (x, y) del reverso. */
    val back = FloatArray(vertexCount * 2)

    /** Colores por vertice (sombreado, se multiplican con la textura). */
    val frontColors = IntArray(vertexCount)
    val backColors = IntArray(vertexCount)

    /** true si hay curl (si no, la hoja esta plana y basta con dibujarla). */
    var active = false; private set

    /** Direccion unitaria del curl (hacia la esquina). */
    var dirX = 1f; private set
    var dirY = 0f; private set

    /** Punto del borde del cilindro (donde acaba el anverso visible). */
    var edgeX = 0f; private set
    var edgeY = 0f; private set

    /** Radio usado en el ultimo calculo. */
    var radius = 0f; private set

    /**
     * Calcula las mallas para una hoja de [width] x [height] cuya esquina ([cornerX],
     * [cornerY]) esta en el punto ([touchX], [touchY]); [maxRadius] es el radio maximo del
     * cilindro. Devuelve [active].
     */
    fun compute(
        width: Float, height: Float,
        touchX: Float, touchY: Float,
        cornerX: Float, cornerY: Float,
        maxRadius: Float,
    ): Boolean {
        val dx = cornerX - touchX
        val dy = cornerY - touchY
        val len = sqrt(dx * dx + dy * dy)
        if (!(len > EPS) || !(width > 0f) || !(height > 0f) || !len.isFinite()) {
            fillFlat(width, height)
            return false
        }
        val ux = dx / len
        val uy = dy / len
        // Radio pequeno al principio (pliegue suave sin salto) y fijo despues.
        val r = minOf(maxRadius.coerceAtLeast(1f), len / PI_F)
        val a = (len + PI_F * r) / 2f
        val ax = cornerX - ux * a
        val ay = cornerY - uy * a
        val halfArc = PI_F * r / 2f
        val fullArc = PI_F * r

        dirX = ux; dirY = uy; radius = r
        edgeX = ax + ux * r; edgeY = ay + uy * r

        var k = 0
        for (j in 0..meshHeight) {
            val y = height * j / meshHeight
            for (i in 0..meshWidth) {
                val x = width * i / meshWidth
                val s = (x - ax) * ux + (y - ay) * uy
                // Anverso.
                val sf: Float
                val frontShade: Float
                when {
                    s <= 0f -> { sf = s; frontShade = 1f }
                    s <= halfArc -> {
                        val th = s / r
                        sf = r * sin(th); frontShade = 0.78f + 0.22f * cos(th)
                    }
                    else -> { sf = r; frontShade = 0.78f }
                }
                front[k * 2] = x + ux * (sf - s)
                front[k * 2 + 1] = y + uy * (sf - s)
                frontColors[k] = gray(frontShade)
                // Reverso.
                val sb: Float
                val backShade: Float
                when {
                    s <= halfArc -> { sb = r; backShade = 0.7f }
                    s < fullArc -> {
                        val th = s / r
                        sb = r * sin(th); backShade = 0.7f + 0.25f * ((th - PI_F / 2f) / (PI_F / 2f))
                    }
                    else -> { sb = -(s - fullArc); backShade = 0.95f }
                }
                back[k * 2] = x + ux * (sb - s)
                back[k * 2 + 1] = y + uy * (sb - s)
                backColors[k] = gray(backShade)
                k++
            }
        }
        active = true
        return true
    }

    private fun fillFlat(width: Float, height: Float) {
        var k = 0
        val w = if (width.isFinite()) width else 0f
        val h = if (height.isFinite()) height else 0f
        for (j in 0..meshHeight) {
            val y = h * j / meshHeight
            for (i in 0..meshWidth) {
                val x = w * i / meshWidth
                front[k * 2] = x; front[k * 2 + 1] = y
                back[k * 2] = x; back[k * 2 + 1] = y
                frontColors[k] = WHITE
                backColors[k] = WHITE
                k++
            }
        }
        active = false
        radius = 0f
    }

    companion object {
        private const val EPS = 0.5f
        private const val PI_F = PI.toFloat()
        private const val WHITE = -0x1

        /** Gris opaco con luminosidad [v] (0..1). */
        fun gray(v: Float): Int {
            val c = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            return (0xFF shl 24) or (c shl 16) or (c shl 8) or c
        }

        /**
         * Punto tocado para la fraccion [t] (0 = hoja plana, 1 = hoja totalmente pasada) en
         * una animacion programada: la esquina viaja de ([width], [cornerY]) a
         * (-width - pi*r, cornerY) con una ligera elevacion en medio. Escribe en [out].
         */
        fun touchForProgress(
            t: Float, width: Float, height: Float, cornerY: Float, maxRadius: Float, lift: Float, out: FloatArray,
        ) {
            val c = t.coerceIn(0f, 1f)
            val travel = 2f * width + PI_F * maxRadius
            out[0] = width - c * travel
            val up = if (cornerY >= height / 2f) -1f else 1f
            out[1] = cornerY + up * lift * sin(PI_F * c)
        }

        /** Fraccion 0..1 equivalente a un punto tocado con x = [touchX] (inversa de la anterior). */
        fun progressForTouchX(touchX: Float, width: Float, maxRadius: Float): Float {
            val travel = 2f * width + PI_F * maxRadius
            return if (travel <= 0f) 0f else ((width - touchX) / travel).coerceIn(0f, 1f)
        }
    }
}
