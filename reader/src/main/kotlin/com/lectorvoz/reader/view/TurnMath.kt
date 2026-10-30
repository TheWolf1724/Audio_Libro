package com.lectorvoz.reader.view

import kotlin.math.abs

/** Zona de toque en la pagina. */
enum class TapZone { LEFT, CENTER, RIGHT }

/**
 * Logica pura del gesto de pasar pagina (sin Android, probada en JVM): progreso del
 * arrastre, decision completar/cancelar y zonas de toque.
 */
object TurnMath {
    /** Fraccion de ancho a partir de la cual, sin velocidad, se completa el paso. */
    const val COMPLETE_FRACTION = 0.3f

    /**
     * Progreso 0..1 del arrastre desde [startX] hasta [x] en una vista de ancho [width].
     * Hacia delante el dedo va a la izquierda; hacia atras, a la derecha.
     */
    fun dragProgress(startX: Float, x: Float, width: Float, forward: Boolean): Float {
        if (width <= 0f) return 0f
        val d = if (forward) startX - x else x - startX
        return (d / width).coerceIn(0f, 1f)
    }

    /**
     * true si al soltar hay que completar el paso. [velocityX] en px/s (negativa = hacia la
     * izquierda). Una velocidad clara en el sentido del paso lo completa y en contra lo
     * cancela, sea cual sea el progreso; si no, decide la distancia.
     */
    fun shouldComplete(progress: Float, velocityX: Float, forward: Boolean, minFlingVelocity: Float): Boolean {
        val v = if (forward) -velocityX else velocityX
        if (v > minFlingVelocity) return true
        if (v < -minFlingVelocity) return false
        return progress >= COMPLETE_FRACTION
    }

    /** Duracion del asentamiento proporcional a lo que falta, con un minimo de 60 ms. */
    fun settleDuration(from: Float, to: Float, fullDurationMs: Int): Long {
        if (fullDurationMs <= 0) return 0L
        return (abs(to - from) * fullDurationMs).toLong().coerceAtLeast(60L)
    }

    /** Zona de toque: 30 % izquierda, 40 % centro, 30 % derecha. */
    fun tapZone(x: Float, width: Float): TapZone = when {
        width <= 0f -> TapZone.CENTER
        x < width * 0.3f -> TapZone.LEFT
        x > width * 0.7f -> TapZone.RIGHT
        else -> TapZone.CENTER
    }

    /** Interpolacion con desaceleracion (cuadratica). */
    fun decelerate(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return 1f - (1f - c) * (1f - c)
    }
}

/**
 * Animacion por tiempo de un valor entre [from] y [to] (sin objetos por fotograma). La vista
 * pide [value] en cada `onDraw` con el reloj de animacion.
 */
class Settle {
    var from = 0f; private set
    var to = 0f; private set
    private var startMs = 0L
    private var durationMs = 0L
    var running = false; private set

    fun start(from: Float, to: Float, durationMs: Long, nowMs: Long) {
        this.from = from
        this.to = to
        this.startMs = nowMs
        this.durationMs = durationMs
        running = true
    }

    /** Fraccion de tiempo 0..1 transcurrida. */
    fun fraction(nowMs: Long): Float =
        if (durationMs <= 0L) 1f else ((nowMs - startMs).toFloat() / durationMs).coerceIn(0f, 1f)

    fun value(nowMs: Long): Float = from + (to - from) * TurnMath.decelerate(fraction(nowMs))

    fun isFinished(nowMs: Long): Boolean = fraction(nowMs) >= 1f

    fun stop() { running = false }
}
