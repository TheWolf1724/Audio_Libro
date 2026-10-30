package com.lectorvoz.reader.cache

import android.graphics.Bitmap

/**
 * LRU de bitmaps auxiliares (imagenes del libro ya escaladas) limitada por bytes. Lo
 * expulsado NO se recicla (puede estar a punto de dibujarse): se suelta para el GC.
 * Thread-safe.
 */
class BitmapLru<K : Any>(val maxBytes: Long) {
    private val map = LinkedHashMap<K, Bitmap>(16, 0.75f, true)
    private var bytes = 0L

    @get:Synchronized
    val sizeBytes: Long get() = bytes

    @Synchronized
    operator fun get(key: K): Bitmap? = map[key]?.takeUnless { it.isRecycled }

    /** Guarda [bitmap]; si por si solo supera el limite no se guarda. */
    @Synchronized
    fun put(key: K, bitmap: Bitmap) {
        val size = bitmap.allocationByteCount.toLong()
        if (size > maxBytes) return
        map.put(key, bitmap)?.let { bytes -= it.allocationByteCount }
        bytes += size
        val it = map.entries.iterator()
        while (bytes > maxBytes && it.hasNext()) {
            val e = it.next()
            if (e.key == key) continue
            it.remove()
            bytes -= e.value.allocationByteCount
        }
    }

    @Synchronized
    fun clear() {
        map.clear()
        bytes = 0
    }
}
