package com.lectorvoz.reader

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.Block
import com.lectorvoz.core.model.BlockKind
import com.lectorvoz.core.model.BookMetadata
import com.lectorvoz.core.model.ChapterDocument
import com.lectorvoz.core.model.DocumentSource
import com.lectorvoz.core.model.HighlightKind
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.LocatorRange
import com.lectorvoz.core.model.PageAnimation
import com.lectorvoz.core.model.ReaderSettings
import com.lectorvoz.core.model.TocEntry
import com.lectorvoz.reader.view.PageFlipView
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/** Controlador completo con un EPUB falso y una PageFlipView sin pantalla. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderControllerTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val controller = ReaderController(context)

    @After
    fun tearDown() = controller.close()

    private class FakeSource : DocumentSource {
        private val chapters = (0 until 3).map { ch ->
            val sb = StringBuilder()
            val blocks = ArrayList<Block>()
            for (i in 0 until 40) {
                val s = sb.length
                sb.append("Capitulo $ch, parrafo $i. Texto de prueba para llenar varias lineas de la pagina ")
                sb.append("y comprobar el corte, la posicion y los resaltados del lector.")
                blocks += Block.Text(s, sb.length, BlockKind.Paragraph)
                if (i < 39) sb.append('\n')
            }
            ChapterDocument(ch, "Capitulo $ch", "es", sb.toString(), blocks)
        }
        override val metadata = BookMetadata("Libro de prueba")
        override val toc = emptyList<TocEntry>()
        override val chapterCount get() = chapters.size
        override suspend fun chapter(index: Int) = chapters[index]
        override suspend fun resource(href: String): ByteArray? = null
        override fun close() = Unit
    }

    /** Ejecuta el looper principal hasta que [cond] se cumpla (el render va en otros hilos). */
    private fun waitUntil(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(10)
        }
        error("Tiempo agotado")
    }

    private fun open(start: Locator): PageFlipView {
        val view = PageFlipView(context)
        controller.setSettings(ReaderSettings(pageAnimation = PageAnimation.NONE))
        controller.openEpub(FakeSource(), start)
        controller.attach(view)
        controller.setViewport(720, 1280, 2f)
        waitUntil { view.currentKey != null && controller.cache[view.currentKey!!] != null }
        return view
    }

    @Test
    fun abre_en_la_posicion_y_renderiza() {
        val start = Locator(1, 2500)
        val view = open(start)
        assertThat(view.currentKey!!.chapter).isEqualTo(1)
        assertThat(controller.currentLocator.value).isEqualTo(start)
        assertThat(view.contentDescription?.toString()).contains("Pagina")
    }

    @Test
    fun cambiar_ajustes_conserva_el_locator() {
        val start = Locator(1, 2500)
        val view = open(start)
        val before = view.currentKey!!
        controller.setSettings(ReaderSettings(fontSizeSp = 30f, pageAnimation = PageAnimation.NONE))
        waitUntil { view.currentKey != before && controller.cache[view.currentKey!!] != null }
        assertThat(controller.currentLocator.value).isEqualTo(start)
        assertThat(view.currentKey!!.page).isGreaterThan(before.page)
    }

    @Test
    fun pasar_pagina_y_seguir_la_voz() {
        val view = open(Locator(0, 0))
        assertThat(controller.nextPage()).isTrue()
        waitUntil { controller.currentLocator.value.offset > 0 }
        assertThat(view.currentKey).isEqualTo(com.lectorvoz.reader.view.PageKey(0, 1))

        // La voz lee una frase del capitulo 2: la vista salta alli.
        val range = LocatorRange(Locator(2, 1000), Locator(2, 1100))
        controller.highlight(range, HighlightKind.SENTENCE)
        controller.ensureVisible(range.start)
        waitUntil { view.currentKey?.chapter == 2 }
        val shown = view.currentKey!!
        // Una peticion dentro de la misma pagina no mueve nada.
        controller.ensureVisible(Locator(2, 1050))
        waitUntil { controller.currentLocator.value == range.start }
        assertThat(view.currentKey).isEqualTo(shown)
        controller.clearHighlight(HighlightKind.SENTENCE)
    }

    /**
     * El resaltado de la voz es una capa encima: resaltar una frase (o una palabra) de la pagina
     * visible no vuelve a dibujar la pagina (el mismo bitmap sigue en la cache) y quitarlo quita
     * la capa. Antes cada frase/palabra redibujaba la pagina entera.
     */
    @Test
    fun resaltado_de_la_voz_como_capa_sin_redibujar_la_pagina() {
        val view = open(Locator(0, 0))
        val key = view.currentKey!!
        val bitmap = controller.cache[key]!!
        val range = LocatorRange(Locator(0, 0), Locator(0, 40))
        controller.highlight(range, HighlightKind.SENTENCE)
        waitUntil { controller.overlay(key) != null }
        controller.highlight(LocatorRange(Locator(0, 10), Locator(0, 18)), HighlightKind.WORD)
        waitUntil { controller.overlay(key) != null }
        // Dejar correr cualquier render que se hubiera pedido: no debe haber ninguno.
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertThat(controller.cache[key]).isSameInstanceAs(bitmap)
        // Fuera de la pagina visible no hay capa.
        assertThat(controller.overlay(com.lectorvoz.reader.view.PageKey(2, 0))).isNull()
        controller.clearHighlight(HighlightKind.WORD)
        controller.clearHighlight(HighlightKind.SENTENCE)
        waitUntil { controller.overlay(key) == null }
        assertThat(controller.cache[key]).isSameInstanceAs(bitmap)
    }

    @Test
    fun pulsacion_larga_da_locator() {
        val view = open(Locator(0, 0))
        var tapped: Locator? = null
        controller.onTextTapped = { tapped = it }
        controller.onLongPress(view.currentKey!!, 200f, 200f)
        waitUntil { tapped != null }
        assertThat(tapped!!.chapter).isEqualTo(0)
    }

    @Test
    fun cerrar_espera_a_la_maquetacion_antes_de_cerrar_la_fuente() {
        val busy = java.util.concurrent.atomic.AtomicBoolean(false)
        val closedWhileBusy = java.util.concurrent.atomic.AtomicBoolean(false)
        val closed = java.util.concurrent.CountDownLatch(1)
        val inner = FakeSource()
        val src = object : DocumentSource by inner {
            override suspend fun chapter(index: Int): ChapterDocument =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    busy.set(true)
                    Thread.sleep(400) // lectura del zip no cancelable
                    busy.set(false)
                    inner.chapter(index)
                }

            override fun close() {
                if (busy.get()) closedWhileBusy.set(true)
                closed.countDown()
            }
        }
        val c = ReaderController(context)
        c.openEpub(src, Locator(0, 0))
        c.setViewport(720, 1280, 2f)
        waitUntil { busy.get() }
        c.close()
        // El cierre se aplaza hasta que acaba la lectura en curso.
        // (los trabajos cancelados terminan en el hilo principal: hay que dejarlo correr)
        waitUntil { closed.count == 0L }
        assertThat(closedWhileBusy.get()).isFalse()
    }

    @Test
    fun informacion_para_solo_escuchar_y_tiempo_restante() {
        open(Locator(1, 0))
        val range = LocatorRange(Locator(1, 0), Locator(1, 10))
        controller.highlight(range, HighlightKind.SENTENCE)
        waitUntil { controller.currentSentence.value != null }
        assertThat(controller.currentSentence.value).isEqualTo("Capitulo 1")
        assertThat(controller.bookTitle).isEqualTo("Libro de prueba")
        val info = controller.pageInfo.value
        assertThat(info.chapterCount).isEqualTo(3)
        assertThat(info.chapterCharsRemaining).isGreaterThan(1000)
        assertThat(info.bookCharsRemaining).isAtLeast(info.chapterCharsRemaining.toLong())
        controller.clearHighlight(HighlightKind.SENTENCE)
        assertThat(controller.currentSentence.value).isNull()
    }

    @Test
    fun la_misma_peticion_durante_una_animacion_no_da_dos_pasos() {
        val view = PageFlipView(context)
        val keys = (0 until 10).map { com.lectorvoz.reader.view.PageKey(0, it) }
        view.provider = object : com.lectorvoz.reader.view.PageProvider {
            override fun bitmap(key: com.lectorvoz.reader.view.PageKey): android.graphics.Bitmap? = null
            override fun neighbor(key: com.lectorvoz.reader.view.PageKey, delta: Int) = keys.getOrNull(key.page + delta)
            override val placeholderColor = 0
        }
        view.animation = PageAnimation.SLIDE
        view.animationDurationMs = 500
        view.showPage(keys[0], false)
        view.showPage(keys[1], true) // empieza la animacion hacia 1
        view.showPage(keys[1], true) // la voz insiste: no debe encadenar otro paso
        assertThat(view.currentKey).isEqualTo(keys[0])
        // Terminar la animacion en curso y pasar una mas: 0 -> 1 -> 2 (no 3).
        view.nextPage(false)
        assertThat(view.currentKey).isEqualTo(keys[2])
    }
}
