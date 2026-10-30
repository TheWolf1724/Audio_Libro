package com.lectorvoz.reader.cache

import android.content.ComponentCallbacks2
import android.graphics.Bitmap

/**
 * Cache LRU de bitmaps de pagina limitada por bytes. Las claves fijadas con [pin] (pagina
 * actual y vecinas, que la vista puede estar dibujando) nunca se expulsan. Lo expulsado o
 * sustituido va a [onEvict] si se da (el controlador lo retiene hasta que la vista deja de
 * dibujarlo) o, si no, al [pool]. Thread-safe.
 */
class PageBitmapCache<K : Any>(
    val maxBytes: Long,
    val pool: BitmapPool,
    private val onEvict: ((Bitmap) -> Unit)? = null,
) {
    private fun evicted(b: Bitmap) {
        val sink = onEvict
        if (sink != null) sink(b) else pool.put(b)
    }

    private val map = LinkedHashMap<K, Bitmap>(16, 0.75f, true)
    private val pinned = HashSet<K>()
    private var bytes = 0L

    @get:Synchronized
    val sizeBytes: Long get() = bytes

    @get:Synchronized
    val size: Int get() = map.size

    @Synchronized
    operator fun get(key: K): Bitmap? = map[key]

    @Synchronized
    operator fun contains(key: K): Boolean = map.containsKey(key)

    @Synchronized
    fun keys(): List<K> = map.keys.toList()

    /**
     * Guarda [bitmap] para [key]. El bitmap anterior de esa clave se devuelve al pool salvo
     * que este fijado (la vista podria estar dibujandolo); en ese caso se devuelve al
     * llamador para que lo libere mas tarde (despues del siguiente fotograma).
     */
    @Synchronized
    fun put(key: K, bitmap: Bitmap): Bitmap? {
        val old = map.put(key, bitmap)
        bytes += bitmap.allocationByteCount
        var deferred: Bitmap? = null
        if (old != null && old !== bitmap) {
            bytes -= old.allocationByteCount
            if (onEvict != null) onEvict.invoke(old) else if (key in pinned) deferred = old else pool.put(old)
        }
        trimTo(maxBytes)
        return deferred
    }

    /** Quita [key] y devuelve su bitmap (sin liberarlo). */
    @Synchronized
    fun remove(key: K): Bitmap? {
        val b = map.remove(key) ?: return null
        bytes -= b.allocationByteCount
        return b
    }

    /** Fija exactamente [keys] (sustituye a las anteriores). */
    @Synchronized
    fun pin(keys: Collection<K>) {
        pinned.clear()
        pinned.addAll(keys)
        trimTo(maxBytes)
    }

    /** Expulsa (al pool) entradas no fijadas, de la menos usada a la mas, hasta [target] bytes. */
    @Synchronized
    fun trimTo(target: Long) {
        if (bytes <= target) return
        val it = map.entries.iterator()
        while (bytes > target && it.hasNext()) {
            val e = it.next()
            if (e.key in pinned) continue
            it.remove()
            bytes -= e.value.allocationByteCount
            evicted(e.value)
        }
    }

    /** Expulsa todo lo no fijado que cumpla [predicate]. */
    @Synchronized
    fun evictIf(predicate: (K) -> Boolean) {
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key in pinned || !predicate(e.key)) continue
            it.remove()
            bytes -= e.value.allocationByteCount
            evicted(e.value)
        }
    }

    /** Vacia la cache (incluidas las fijadas) devolviendo los bitmaps a [onEvict] o al pool. */
    @Synchronized
    fun clear() {
        map.values.forEach { evicted(it) }
        map.clear()
        bytes = 0
    }

    /** Respuesta a `onTrimMemory`: suelta lo no imprescindible segun el nivel. */
    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                trimTo(0); pool.clear()
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                trimTo(0); pool.trimTo(0)
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                trimTo(maxBytes / 2); pool.trimTo(pool.maxBytes / 2)
            }
        }
    }

    companion object {
        /** Limite por defecto: 1/8 de la memoria maxima del proceso. */
        fun defaultMaxBytes(): Long = Runtime.getRuntime().maxMemory() / 8
    }
}
