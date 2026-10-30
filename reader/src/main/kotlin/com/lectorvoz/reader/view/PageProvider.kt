package com.lectorvoz.reader.view

import android.graphics.Bitmap
import com.lectorvoz.reader.render.HighlightLayer

/**
 * Pagina concreta: capitulo + pagina dentro del capitulo. En PDF cada capitulo es una pagina
 * fisica y [page] es siempre 0.
 */
data class PageKey(val chapter: Int, val page: Int)

/**
 * Fuente de paginas para [PageFlipView] y [ScrollReaderView]. Todo es no bloqueante: si un
 * bitmap no esta listo devuelve null, lo pide en fondo y avisa a la vista cuando lo tenga.
 */
interface PageProvider {
    /** Bitmap ya renderizado de [key] o null (y se pide). Solo desde el hilo principal. */
    fun bitmap(key: PageKey): Bitmap?

    /** Capa que se dibuja encima del bitmap de [key] (resaltado de la voz) o null. Hilo principal. */
    fun overlay(key: PageKey): HighlightLayer? = null

    /**
     * Pagina a [delta] (+1 / -1) de [key]; null si no existe o aun no se conoce (por
     * ejemplo, el capitulo anterior aun no esta paginado: se pagina y se avisa).
     */
    fun neighbor(key: PageKey, delta: Int): PageKey?

    /** Alto en pixeles de [key] para un ancho [width] (modo scroll). */
    fun pageHeight(key: PageKey, width: Int, viewHeight: Int): Int = viewHeight

    /** Color de fondo mientras no hay bitmap. */
    val placeholderColor: Int
}

/** Avisos de la vista al controlador. */
interface PageViewListener {
    /** La vista muestra ahora [key] (por gesto, toque o API). */
    fun onPageShown(key: PageKey)

    /** Toque en la zona central (mostrar/ocultar menu). */
    fun onCenterTap() {}

    /** Pulsacion larga en ([x], [y]) de la pagina [key] (leer desde aqui). */
    fun onLongPress(key: PageKey, x: Float, y: Float) {}

    /**
     * La vista acaba de grabar un fotograma con los bitmaps actuales de la cache: los que se
     * sustituyeron antes ya no estan en su lista de dibujo y se pueden reutilizar. Se llama
     * desde `onDraw`/`draw`: no debe hacer trabajo pesado.
     */
    fun onFrameDrawn() {}
}

/** Vista que muestra paginas; el controlador la maneja sin conocer su tipo. */
interface PageHost {
    /** Muestra [key]; si [animated], con la animacion configurada (solo si es vecina). */
    fun showPage(key: PageKey, animated: Boolean)

    /** Pagina mostrada. */
    val currentKey: PageKey?

    /** El bitmap de [key] esta listo o ha cambiado. */
    fun onPageReady(key: PageKey)

    /** Cambiaron vecinos o numero de paginas (repintar/recalcular). */
    fun onPagesChanged()

    /** Texto para TalkBack de la pagina mostrada; [announce] lo lee en voz. */
    fun setPageDescription(text: CharSequence, announce: Boolean)

    fun nextPage(animated: Boolean = true): Boolean
    fun prevPage(animated: Boolean = true): Boolean

    /**
     * Tras repaginar: las claves anteriores ya no significan lo mismo. Olvida el estado
     * (animacion, lista de paginas) y muestra [key] sin animar.
     */
    fun resetPages(key: PageKey) = showPage(key, false)

    /** Vuelve a grabar todo lo dibujado (para soltar bitmaps sustituidos). */
    fun invalidatePages()

    /** Cambio la capa de resaltado: repintar (sin volver a pedir bitmaps ni maquetar). */
    fun onOverlayChanged() = invalidatePages()
}
