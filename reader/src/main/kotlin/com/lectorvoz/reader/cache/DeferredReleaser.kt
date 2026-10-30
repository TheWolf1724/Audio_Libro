package com.lectorvoz.reader.cache

import android.graphics.Bitmap

/**
 * Bitmaps que han salido de la cache pero que la vista puede seguir dibujando (estan en su
 * ultima lista de dibujo, quiza a mitad de una animacion). Solo se devuelven al pool
 * ([sink]) cuando la vista ha grabado un fotograma POSTERIOR a la sustitucion
 * ([onFrameDrawn] + [releaseDrawn]): a partir de ahi ya dibuja el bitmap nuevo.
 *
 * Si se acumulan mas de [maxPending] (pantalla apagada: no hay fotogramas) los mas antiguos
 * se sueltan para el GC sin reciclarlos, que siempre es seguro. Nada vuelve al pool tras
 * [close]. Solo hilo principal.
 */
class DeferredReleaser(
    private val sink: (Bitmap) -> Unit,
    private val maxPending: Int = 8,
) {
    private class Entry(val bitmap: Bitmap, val frame: Long)

    private val pending = ArrayDeque<Entry>()
    private var frame = 0L
    private var closed = false

    val pendingCount: Int get() = pending.size

    /** Retiene [bitmap] hasta el siguiente fotograma grabado. */
    fun defer(bitmap: Bitmap) {
        if (closed) return
        if (pending.any { it.bitmap === bitmap }) return
        pending.addLast(Entry(bitmap, frame))
        while (pending.size > maxPending) pending.removeFirst() // al GC, sin reciclar
    }

    /** La vista ha grabado un fotograma con el contenido actual de la cache. */
    fun onFrameDrawn() { frame++ }

    /**
     * Devuelve al pool lo retenido antes del ultimo fotograma grabado. Llamar despues de
     * que ese fotograma se haya entregado al hilo de render (en un mensaje posterior).
     */
    fun releaseDrawn(): Int {
        if (closed) return 0
        var n = 0
        while (pending.isNotEmpty() && pending.first().frame < frame) {
            sink(pending.removeFirst().bitmap)
            n++
        }
        return n
    }

    /** Suelta todo para el GC sin reciclar ni reutilizar (vista oculta o sin vista). */
    fun dropAll() { pending.clear() }

    /** Tras cerrar, todo se suelta para el GC y nada vuelve al pool. */
    fun close() {
        closed = true
        pending.clear()
    }
}
