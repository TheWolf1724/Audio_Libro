package com.lectorvoz.reader

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lectorvoz.reader.cache.BitmapLru
import com.lectorvoz.reader.cache.BitmapPool
import com.lectorvoz.reader.cache.DeferredReleaser
import com.lectorvoz.reader.cache.PageBitmapCache
import com.lectorvoz.reader.layout.DocumentImageLoader
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/** Memoria: liberacion diferida de bitmaps, LRU de imagenes, cache de paginas e imagenes al tamano exacto. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderMemoryTest {

    private fun bmp(w: Int = 100, h: Int = 100) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    @Test
    fun solo_se_libera_tras_un_fotograma_posterior() {
        val released = ArrayList<Bitmap>()
        val r = DeferredReleaser(sink = { released += it })
        val a = bmp()
        r.defer(a)
        // Sin fotograma nuevo no se libera (la vista aun puede estar dibujandolo).
        assertThat(r.releaseDrawn()).isEqualTo(0)
        r.onFrameDrawn()
        val b = bmp()
        r.defer(b) // sustituido despues de ese fotograma: espera al siguiente
        assertThat(r.releaseDrawn()).isEqualTo(1)
        assertThat(released).containsExactly(a)
        r.onFrameDrawn()
        assertThat(r.releaseDrawn()).isEqualTo(1)
        assertThat(released).containsExactly(a, b).inOrder()
        assertThat(a.isRecycled).isFalse()
    }

    @Test
    fun sin_fotogramas_no_crece_sin_limite_y_nada_tras_cerrar() {
        val released = ArrayList<Bitmap>()
        val r = DeferredReleaser(sink = { released += it }, maxPending = 3)
        repeat(10) { r.defer(bmp()) }
        assertThat(r.pendingCount).isEqualTo(3)
        r.defer(bmp())
        r.close()
        r.onFrameDrawn()
        assertThat(r.releaseDrawn()).isEqualTo(0)
        assertThat(released).isEmpty()
        r.defer(bmp())
        assertThat(r.pendingCount).isEqualTo(0)
    }

    @Test
    fun cache_con_sumidero_no_recicla_lo_que_se_muestra() {
        val pool = BitmapPool(40_000L)
        val evicted = ArrayList<Bitmap>()
        val cache = PageBitmapCache<Int>(120_000L, pool) { evicted += it }
        val first = bmp()
        cache.pin(listOf(0))
        cache.put(0, first)
        // Sustituir la fijada: el viejo va al sumidero, no al pool ni se recicla.
        assertThat(cache.put(0, bmp())).isNull()
        assertThat(evicted).containsExactly(first)
        assertThat(first.isRecycled).isFalse()
        assertThat(pool.count).isEqualTo(0)
        for (k in 1..5) cache.put(k, bmp())
        assertThat(cache.sizeBytes).isAtMost(120_000L)
        cache.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        // Solo queda la fijada.
        assertThat(cache.keys()).containsExactly(0)
        assertThat(evicted.none { it.isRecycled }).isTrue()
    }

    @Test
    fun presupuesto_de_cache_para_paginas_reales() {
        // Pagina de un movil 1080x2400 ARGB_8888.
        val page = 1080L * 2400 * 4
        // Con un heap de 256 MB, 1/8 = 32 MB: caben 3 paginas (actual y vecinas fijadas).
        assertThat((256L shl 20) / 8 / page).isAtLeast(3)
        val pool = BitmapPool(page)
        val cache = PageBitmapCache<Int>(3 * page, pool)
        cache.pin(listOf(1, 2, 3))
        for (k in 0..6) cache.put(k, Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888))
        assertThat(cache.sizeBytes).isAtMost(3 * page + page) // como mucho una de mas si todo esta fijado
        assertThat(pool.sizeBytes).isAtMost(page)
    }

    @Test
    fun lru_de_imagenes_por_bytes() {
        val lru = BitmapLru<String>(100_000L)
        val a = bmp(); val b = bmp(); val c = bmp()
        lru.put("a", a); lru.put("b", b)
        assertThat(lru["a"]).isSameInstanceAs(a) // a pasa a ser la mas reciente
        lru.put("c", c)
        assertThat(lru["b"]).isNull()
        assertThat(lru["a"]).isNotNull()
        assertThat(lru.sizeBytes).isAtMost(100_000L)
        assertThat(b.isRecycled).isFalse() // expulsada sin reciclar
        lru.put("enorme", bmp(1000, 1000))
        assertThat(lru["enorme"]).isNull()
    }

    @Test
    fun imagen_decodificada_al_tamano_exacto() {
        val src = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        src.compress(Bitmap.CompressFormat.PNG, 100, out)
        val bytes = out.toByteArray()
        assertThat(DocumentImageLoader.decodeBounds(bytes)).isEqualTo(com.lectorvoz.reader.layout.ImageSize(1600, 1200))
        val d = DocumentImageLoader.decodeScaled(bytes, 300, 225)!!
        // Antes: inSampleSize 4 -> 400x300 (1,8x pixeles de mas). Ahora el tamano pedido.
        assertThat(d.width).isAtMost(301)
        assertThat(d.height).isAtMost(226)
        assertThat(d.width).isAtLeast(299)
        assertThat(DocumentImageLoader.decodeScaled(byteArrayOf(1, 2, 3), 10, 10)).isNull()
    }
}
