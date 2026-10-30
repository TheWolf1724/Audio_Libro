package com.lectorvoz.reader.content

import android.graphics.Bitmap
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.LocatorRange
import com.lectorvoz.reader.ReaderColors
import com.lectorvoz.reader.layout.LayoutSettings
import com.lectorvoz.reader.layout.Viewport
import com.lectorvoz.reader.render.HighlightLayer
import com.lectorvoz.reader.render.Highlights
import com.lectorvoz.reader.view.PageKey

/** Configuracion de dibujo comun que el controlador pasa al contenido. */
data class RenderConfig(
    val viewport: Viewport,
    val layout: LayoutSettings,
    val colors: ReaderColors,
    /** PDF: recolorear segun el tema. */
    val pdfRecolor: Boolean,
    /** Modo scroll: PDF ajustado solo a ancho (alto variable). */
    val scrollMode: Boolean,
)

/**
 * Contenido paginado (EPUB o PDF) visto por el controlador: numero de paginas por
 * capitulo, conversion Locator <-> pagina, render y toque. En PDF cada capitulo es una
 * pagina fisica con una sola "pagina".
 *
 * Hilos: [render] y [hitTest] se llaman siempre desde el hilo unico de render del
 * controlador; el resto, desde el hilo principal o cualquier otro.
 */
interface PagedContent : AutoCloseable {
    val chapterCount: Int

    /** Titulo del libro. */
    val title: String?

    /** Cambia la configuracion; devuelve true si hay que repaginar (cambia el corte). */
    fun configure(config: RenderConfig): Boolean

    /** Paginas del capitulo si ya se conocen (sin calcular). */
    fun knownPageCount(chapter: Int): Int?

    /** Paginas del capitulo (pagina si hace falta). */
    suspend fun pageCount(chapter: Int): Int

    /** Pagina que contiene [locator]. */
    suspend fun keyFor(locator: Locator): PageKey

    /** Primer Locator de la pagina [key]. */
    suspend fun startOf(key: PageKey): Locator

    /** Rango [inicio, fin) de offsets de la pagina dentro de su capitulo. */
    suspend fun offsetsOf(key: PageKey): IntRange

    /** Tamano del bitmap de la pagina en pixeles. */
    fun bitmapSize(key: PageKey): Pair<Int, Int>

    /** Dibuja [key] en [bitmap] con [highlights]; [footer] es el texto del pie. */
    suspend fun render(key: PageKey, bitmap: Bitmap, highlights: Highlights, footer: String?)

    /**
     * Capa del resaltado de la voz ([sentence], [word]) sobre la pagina [key], en pixeles de su
     * bitmap; nulo si no toca la pagina. Se dibuja encima del bitmap (ver HighlightLayer): asi
     * avanzar la voz no obliga a redibujar la pagina.
     */
    suspend fun voiceLayer(key: PageKey, sentence: LocatorRange?, word: LocatorRange?): HighlightLayer? = null

    /** Locator del texto en ([x], [y]) (pixeles del bitmap) o null. */
    suspend fun hitTest(key: PageKey, x: Float, y: Float): Locator?

    /** Texto de la pagina (TalkBack). */
    suspend fun pageText(key: PageKey): String

    /** Texto legible de [range] (frase actual para el modo solo escuchar), o null. */
    suspend fun textOf(range: LocatorRange): String?

    /** Titulo del capitulo (encabezado). */
    fun chapterTitle(chapter: Int): String?

    /** Estimacion del total de paginas y si ya es exacto. */
    fun totalPages(): Pair<Int, Boolean>

    /** Numero global (0..) de la pagina si se conoce el de los capitulos anteriores. */
    fun globalIndex(key: PageKey): Int?

    /** Caracteres de texto del capitulo si ya se conocen (sin cargarlo). */
    fun knownCharCount(chapter: Int): Int?

    /** Suelta memoria prescindible (`ComponentCallbacks2.onTrimMemory`). */
    fun onTrimMemory(level: Int) {}
}
