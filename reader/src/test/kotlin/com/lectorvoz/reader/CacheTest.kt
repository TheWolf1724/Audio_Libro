package com.lectorvoz.reader

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lectorvoz.reader.cache.BitmapPool
import com.lectorvoz.reader.cache.PageBitmapCache
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CacheTest {

    private fun bmp() = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888) // 40 000 bytes
    private val one = 40_000L

    @Test
    fun lru_respeta_el_limite_y_expulsa_al_pool() {
        val pool = BitmapPool(10 * one)
        val cache = PageBitmapCache<Int>(3 * one, pool)
        val bitmaps = (0 until 5).map { bmp() }
        bitmaps.forEachIndexed { i, b -> cache.put(i, b) }
        assertThat(cache.sizeBytes).isAtMost(3 * one)
        assertThat(cache.keys()).containsExactly(2, 3, 4).inOrder()
        assertThat(pool.count).isEqualTo(2)
        // Acceder a 2 la hace la mas reciente: la siguiente expulsion se lleva la 3.
        assertThat(cache[2]).isSameInstanceAs(bitmaps[2])
        cache.put(5, bmp())
        assertThat(cache.keys()).containsExactly(4, 2, 5).inOrder()
    }

    @Test
    fun las_fijadas_no_se_expulsan() {
        val cache = PageBitmapCache<Int>(2 * one, BitmapPool(10 * one))
        cache.put(0, bmp())
        cache.pin(listOf(0))
        cache.put(1, bmp())
        cache.put(2, bmp())
        cache.put(3, bmp())
        assertThat(cache.keys()).contains(0)
        cache.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        assertThat(cache.keys()).containsExactly(0)
    }

    @Test
    fun sustituir_una_fijada_devuelve_el_viejo_para_liberarlo_despues() {
        val pool = BitmapPool(10 * one)
        val cache = PageBitmapCache<Int>(5 * one, pool)
        val old = bmp()
        cache.put(7, old)
        cache.pin(listOf(7))
        val deferred = cache.put(7, bmp())
        assertThat(deferred).isSameInstanceAs(old)
        assertThat(pool.count).isEqualTo(0)
        assertThat(cache.sizeBytes).isEqualTo(one)
    }

    @Test
    fun pool_reutiliza_el_mismo_tamano() {
        val pool = BitmapPool(5 * one)
        val b = bmp()
        pool.put(b)
        assertThat(pool.get(100, 100)).isSameInstanceAs(b)
        assertThat(pool.count).isEqualTo(0)
        // Otro tamano: bitmap nuevo.
        pool.put(b)
        val other = pool.get(50, 50)
        assertThat(other).isNotSameInstanceAs(b)
        assertThat(other.width).isEqualTo(50)
    }

    @Test
    fun pool_limitado_por_bytes_recicla_lo_que_sobra() {
        val pool = BitmapPool(2 * one)
        val a = bmp(); val b = bmp(); val c = bmp()
        pool.put(a); pool.put(b); pool.put(c)
        assertThat(pool.sizeBytes).isAtMost(2 * one)
        assertThat(a.isRecycled).isTrue()
        pool.trimTo(0)
        assertThat(pool.count).isEqualTo(0)
        assertThat(c.isRecycled).isTrue()
    }

    @Test
    fun limite_por_defecto_es_un_octavo() {
        assertThat(PageBitmapCache.defaultMaxBytes()).isEqualTo(Runtime.getRuntime().maxMemory() / 8)
    }
}
