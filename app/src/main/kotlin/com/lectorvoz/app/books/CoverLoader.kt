package com.lectorvoz.app.books

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Cargador de portadas propio: decodifica en fondo con `inSampleSize` al tamano pedido, en
 * RGB_565, y guarda en una [LruCache] acotada por bytes (1/16 de la memoria de la app).
 */
class CoverLoader(maxBytes: Int = (Runtime.getRuntime().maxMemory() / 16).toInt().coerceAtLeast(4 * 1024 * 1024)) {
    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    /** Portada en cache, si ya esta (sin bloquear). */
    fun cached(path: String, widthPx: Int): Bitmap? = cache.get(key(path, widthPx))

    /** Portada de [path] reducida a unos [widthPx] de ancho; nula si no se puede leer. */
    suspend fun load(path: String, widthPx: Int): Bitmap? {
        val k = key(path, widthPx)
        cache.get(k)?.let { return it }
        return withContext(Dispatchers.IO) {
            val f = File(path)
            if (!f.exists()) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return@withContext null
            val reqH = (widthPx * 3) / 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, widthPx, reqH)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(path, opts)?.also { cache.put(k, it) }
        }
    }

    fun clear() = cache.evictAll()

    fun trim(level: Int) {
        @Suppress("DEPRECATION")
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE) cache.evictAll()
        else if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) cache.trimToSize(cache.maxSize() / 2)
    }

    private fun key(path: String, w: Int) = "$path@${bucket(w)}"

    /** Agrupa anchos parecidos para reutilizar entradas. */
    private fun bucket(w: Int): Int = when {
        w <= 160 -> 160
        w <= 320 -> 320
        else -> 480
    }

    companion object {
        /** Mayor potencia de 2 que deja la imagen igual o mayor que lo pedido. */
        fun sampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
            var sample = 1
            if (reqWidth <= 0 || reqHeight <= 0) return 1
            while (width / (sample * 2) >= reqWidth && height / (sample * 2) >= reqHeight) sample *= 2
            return sample
        }
    }
}
