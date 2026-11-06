package com.lectorvoz.format.pdf

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Modo MIXTO texto+OCR: tercer camino de extraccion de [PdfBook.pageText]. Con falsos
 * (backend con texto propio + [MixedTextProvider] que anade una linea) se comprueba el contrato:
 * precedencia (OCR de pagina entera > mixto > texto propio), cacheo, interaccion con wait y las
 * guardas de carrera.
 */
@RunWith(AndroidJUnit4::class)
class PdfBookMixedTest {

    private companion object {
        const val OWN = "Esta pagina trae su propio texto legible."
        const val SCANNED = " 12 " // sin texto legible: pagina escaneada
        const val MIX_SUFFIX = "\nLinea anadida por el OCR dirigido."
        const val OCR_FULL = "Pagina reconocida entera por el OCR."
    }

    /** Pagina 0 con texto propio; pagina 1 escaneada (sin texto). Cuenta extracciones de pdfium. */
    private class Backend : PdfBackend {
        var textCalls = 0
        override val pageCount = 2
        override fun pageSizePoints(index: Int) = PageSize(400f, 600f)
        override fun meta() = PdfMeta(null, null, null)
        override fun bookmarks() = emptyList<PdfBookmark>()
        override fun render(index: Int, bitmap: Bitmap, startX: Int, startY: Int, drawWidth: Int, drawHeight: Int) = Unit
        override fun close() = Unit
        override fun pageText(index: Int): RawPageText {
            textCalls++
            val t = if (index == 0) OWN else SCANNED
            return RawPageText(t, FloatArray(t.length * 4))
        }
    }

    private fun raw(text: String) = RawPageText(text, FloatArray(text.length * 4))

    @Test
    fun sinProveedorElTextoEsElDeHoy() = runTest {
        val backend = Backend()
        val book = PdfBook.create(backend, Dispatchers.Unconfined, "x")
        // Sin mixedProvider el texto propio se devuelve tal cual y se cachea (una sola extraccion).
        assertThat(book.pageText(0).rawText).isEqualTo(OWN)
        assertThat(book.pageText(0).rawText).isEqualTo(OWN)
        assertThat(backend.textCalls).isEqualTo(1)
    }

    @Test
    fun mixtoNoListo_sirveProvisionalSinCachearYReintenta() = runTest {
        val backend = Backend()
        val book = PdfBook.create(backend, Dispatchers.Unconfined, "x")
        var calls = 0
        // 1a llamada: el OCR aun no esta listo (lanza MixedTextNotReady); 2a: ya fusiona.
        book.mixedProvider = MixedTextProvider { _, _, native, _ ->
            calls++
            if (calls == 1) throw MixedTextNotReady() else raw(native.rawText + MIX_SUFFIX)
        }
        // Provisional: se sirve el texto propio, NO se cachea y se marca textProvisional.
        assertThat(book.pageText(0).rawText).isEqualTo(OWN)
        assertThat(book.textProvisional(0)).isTrue()
        assertThat(book.cachedPageText(0)).isNull()
        // La siguiente peticion reintenta y ya fusiona; deja de ser provisional y se cachea.
        assertThat(book.pageText(0).rawText).isEqualTo(OWN + MIX_SUFFIX)
        assertThat(book.textProvisional(0)).isFalse()
        assertThat(book.cachedPageText(0)?.rawText).isEqualTo(OWN + MIX_SUFFIX)
        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun proveedorFusionaConEsperaYSeCachea() = runTest {
        val backend = Backend()
        val book = PdfBook.create(backend, Dispatchers.Unconfined, "x")
        var mixedCalls = 0
        book.mixedProvider = MixedTextProvider { _, _, native, _ -> mixedCalls++; raw(native.rawText + MIX_SUFFIX) }
        // Con espera: el texto se fusiona (nativo + OCR dirigido).
        assertThat(book.pageText(0).rawText).isEqualTo(OWN + MIX_SUFFIX)
        assertThat(mixedCalls).isEqualTo(1)
        // Cacheado: la 2a llamada no vuelve a llamar al proveedor ni a pdfium.
        assertThat(book.pageText(0).rawText).isEqualTo(OWN + MIX_SUFFIX)
        assertThat(mixedCalls).isEqualTo(1)
        assertThat(backend.textCalls).isEqualTo(1)
        // Lo fusionado tambien queda en cache para consultas sincronas.
        assertThat(book.cachedPageText(0)?.rawText).isEqualTo(OWN + MIX_SUFFIX)
    }

    @Test
    fun sinEsperaNoFusionaYUnaPosteriorConEsperaSiFusiona() = runTest {
        val backend = Backend()
        val book = PdfBook.create(backend, Dispatchers.Unconfined, "x")
        var mixedCalls = 0
        book.mixedProvider = MixedTextProvider { _, _, native, _ -> mixedCalls++; raw(native.rawText + MIX_SUFFIX) }
        // Sin espera (pasar paginas): NO se fusiona, se devuelve el texto propio.
        assertThat(book.pageText(0, wait = false).rawText).isEqualTo(OWN)
        assertThat(mixedCalls).isEqualTo(0)
        // Una peticion posterior CON espera recomputa y fusiona (no devuelve el propio obsoleto)...
        assertThat(book.pageText(0, wait = true).rawText).isEqualTo(OWN + MIX_SUFFIX)
        assertThat(mixedCalls).isEqualTo(1)
        // ...reutilizando el texto propio ya cacheado, sin re-extraer pdfium (sigue en 1).
        assertThat(backend.textCalls).isEqualTo(1)
    }

    @Test
    fun ocrDePaginaEnteraTienePrecedenciaSobreElMixto() = runTest {
        val backend = Backend()
        val book = PdfBook.create(backend, Dispatchers.Unconfined, "x")
        var mixedCalls = 0
        book.mixedProvider = MixedTextProvider { _, _, native, _ -> mixedCalls++; raw(native.rawText + MIX_SUFFIX) }
        book.textFallback = PageTextFallback { _, _, _ -> raw(OCR_FULL) }
        // Pagina escaneada (sin texto propio) -> needsFallback: el mixto NO corre.
        assertThat(book.pageText(1).rawText).isEqualTo(OCR_FULL)
        assertThat(mixedCalls).isEqualTo(0)
        // Forzar el OCR en una pagina CON texto propio -> needsFallback: el mixto tampoco corre.
        book.forceFallback = true
        assertThat(book.pageText(0).rawText).isEqualTo(OCR_FULL)
        assertThat(mixedCalls).isEqualTo(0)
    }

    @Test
    fun invalidarLimpiaMixedPagesYVuelveAFusionar() = runTest {
        val backend = Backend()
        val book = PdfBook.create(backend, Dispatchers.Unconfined, "x")
        var mixedCalls = 0
        book.mixedProvider = MixedTextProvider { _, _, native, _ -> mixedCalls++; raw(native.rawText + MIX_SUFFIX) }
        assertThat(book.pageText(0).rawText).isEqualTo(OWN + MIX_SUFFIX)
        assertThat(mixedCalls).isEqualTo(1)
        // Invalidar olvida la pagina (y su marca de fusionada): la siguiente peticion la rehace.
        book.invalidateText(0)
        assertThat(book.pageText(0).rawText).isEqualTo(OWN + MIX_SUFFIX)
        assertThat(mixedCalls).isEqualTo(2)
    }

    @Test
    fun siElProveedorSePoneANullTrasDecidirNoCacheaIncoherente() = runTest {
        val backend = Backend()
        val book = PdfBook.create(backend, Dispatchers.Unconfined, "x")
        book.mixedProvider = MixedTextProvider { _, _, native, _ -> raw(native.rawText + MIX_SUFFIX) }
        // Simula la carrera: el proveedor desaparece entre decidir el mixto e insertar en cache.
        book.onBeforeCacheForTest = { book.mixedProvider = null; book.onBeforeCacheForTest = null }
        // La peticion calculo el mixto (devuelve lo fusionado, provisional)...
        assertThat(book.pageText(0).rawText).isEqualTo(OWN + MIX_SUFFIX)
        // ...pero NO lo cacheo (seria incoherente: el proveedor ya no esta).
        assertThat(book.cachedPageText(0)).isNull()
        // Sin proveedor, la siguiente peticion devuelve y cachea el texto propio sin fusionar.
        assertThat(book.pageText(0).rawText).isEqualTo(OWN)
        assertThat(book.cachedPageText(0)?.rawText).isEqualTo(OWN)
    }
}
