package com.lectorvoz.reader.cache

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap

/**
 * Pool de bitmaps reutilizables (todas las paginas tienen el mismo tamano, asi que casi
 * siempre hay uno libre). Limitado por bytes; lo que no cabe se recicla. Thread-safe.
 */
class BitmapPool(maxBytes: Long) {
    private val free = ArrayDeque<Bitmap>()
    private var bytes = 0L

    var maxBytes: Long = maxBytes
        private set

    /** Bytes retenidos ahora. */
    @get:Synchronized
    val sizeBytes: Long get() = bytes

    @get:Synchronized
    val count: Int get() = free.size

    /** Bitmap de [width] x [height] con [config]: reutilizado si hay uno igual, si no nuevo. */
    @Synchronized
    fun get(width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        val it = free.iterator()
        while (it.hasNext()) {
            val b = it.next()
            if (b.isRecycled) { it.remove(); continue }
            if (b.width == width && b.height == height && b.config == config) {
                it.remove()
                bytes -= b.allocationByteCount
                return b
            }
        }
        return createBitmap(width, height, config)
    }

    /** Devuelve [bitmap] al pool (o lo recicla si no cabe). No usarlo despues. */
    @Synchronized
    fun put(bitmap: Bitmap) {
        if (bitmap.isRecycled || !bitmap.isMutable) return
        val size = bitmap.allocationByteCount.toLong()
        if (size > maxBytes) { bitmap.recycle(); return }
        while (bytes + size > maxBytes && free.isNotEmpty()) {
            val old = free.removeFirst()
            bytes -= old.allocationByteCount
            old.recycle()
        }
        free.addLast(bitmap)
        bytes += size
    }

    /** Reduce el pool a [targetBytes] (0 = vaciar). */
    @Synchronized
    fun trimTo(targetBytes: Long) {
        while (bytes > targetBytes && free.isNotEmpty()) {
            val old = free.removeFirst()
            bytes -= old.allocationByteCount
            old.recycle()
        }
    }

    @Synchronized
    fun clear() = trimTo(0)
}
