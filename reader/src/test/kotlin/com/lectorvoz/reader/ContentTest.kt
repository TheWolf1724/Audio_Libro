package com.lectorvoz.reader

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.Block
import com.lectorvoz.core.model.BlockKind
import com.lectorvoz.core.model.BookMetadata
import com.lectorvoz.core.model.ChapterDocument
import com.lectorvoz.core.model.DocumentSource
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.LocatorRange
import com.lectorvoz.core.model.ReaderSettings
import com.lectorvoz.core.model.TocEntry
import com.lectorvoz.reader.content.EpubContent
import com.lectorvoz.reader.content.RenderConfig
import com.lectorvoz.reader.layout.EpubPaginator
import com.lectorvoz.reader.layout.ImageLoader
import com.lectorvoz.reader.layout.ImageSize
import com.lectorvoz.reader.layout.LayoutSettings
import com.lectorvoz.reader.layout.Viewport
import com.lectorvoz.reader.render.Highlights
import com.lectorvoz.reader.view.PageKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Contenido EPUB: separadores, imagenes al dibujar, conteo en fondo y rendimiento. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContentTest {

    private val viewport = Viewport(widthPx = 720, heightPx = 1280, density = 2f)

    private fun cfg(s: ReaderSettings = ReaderSettings()) =
        RenderConfig(viewport, LayoutSettings.from(s), ReaderColors.LIGHT, false, false)

    private fun paragraphs(index: Int, n: Int, extra: (StringBuilder, MutableList<Block>) -> Unit = { _, _ -> }): ChapterDocument {
        val sb = StringBuilder()
        val blocks = ArrayList<Block>()
        for (i in 0 until n) {
            val s = sb.length
            sb.append("Parrafo $i del capitulo $index. En un lugar de la Mancha, de cuyo nombre no quiero acordarme, ")
            sb.append("no ha mucho tiempo que vivia un hidalgo de los de lanza en astillero.")
            blocks += Block.Text(s, sb.length, BlockKind.Paragraph)
            if (i < n - 1) sb.append('\n')
            if (i == n / 2) extra(sb, blocks)
        }
        return ChapterDocument(index, "Capitulo $index", "es", sb.toString(), blocks)
    }

    private open class Source(val chapters: List<ChapterDocument>) : DocumentSource {
        @Volatile var closed = false
        override val metadata = BookMetadata("Libro")
        override val toc = emptyList<TocEntry>()
        override val chapterCount get() = chapters.size
        override suspend fun chapter(index: Int) = chapters[index]
        override suspend fun resource(href: String): ByteArray? = null
        override fun close() { closed = true }
    }

    @Test
    fun separador_ocupa_alto_y_no_rompe_el_corte() = runTest {
        val plain = paragraphs(0, 3)
        val ruleAt = plain.blocks[1].start
        val withRule = plain.copy(blocks = plain.blocks + Block.Rule(ruleAt))
        val p = EpubPaginator()
        val s = LayoutSettings.from(ReaderSettings())
        val a = p.paginate(plain, s, viewport)
        val b = p.paginate(withRule, s, viewport)
        val lineA = a.layout.getLineForOffset(ruleAt)
        val lineB = b.layout.getLineForOffset(ruleAt)
        // La primera linea del parrafo tras el separador es mas alta (hueco con raya).
        assertThat(b.layout.getLineBottom(lineB) - b.layout.getLineTop(lineB))
            .isGreaterThan(a.layout.getLineBottom(lineA) - a.layout.getLineTop(lineA))
        // Separador al final del capitulo: tambien se acepta.
        val atEnd = plain.copy(blocks = plain.blocks + Block.Rule(plain.plainText.length))
        assertThat(p.paginate(atEnd, s, viewport).pageCount).isAtLeast(1)
    }

    private class Images : ImageLoader {
        var sizes = 0
        var loads = 0
        override suspend fun size(href: String): ImageSize { sizes++; return ImageSize(300, 200) }
        override suspend fun load(href: String, width: Int, height: Int): Bitmap {
            loads++
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        }
    }

    private fun withImage(index: Int) = paragraphs(index, 30) { sb, blocks ->
        sb.append('\n')
        val at = sb.length
        sb.append(ChapterDocument.OBJECT_CHAR)
        blocks += Block.Image(at, "img/a.png", "a")
        sb.append('\n')
    }

    @Test
    fun imagenes_se_decodifican_al_dibujar_y_no_al_maquetar() = runTest {
        val images = Images()
        val doc = withImage(0)
        val content = EpubContent(Source(listOf(doc)), images = images)
        content.configure(cfg())
        val at = doc.blocks.filterIsInstance<Block.Image>().single().start
        val key = content.keyFor(Locator(0, at))
        assertThat(images.loads).isEqualTo(0)
        assertThat(images.sizes).isEqualTo(1)
        val target = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        content.render(key, target, Highlights.NONE, null)
        assertThat(images.loads).isEqualTo(1)
        // Hay pixeles rojos de la imagen en la pagina.
        var red = 0
        for (y in 0 until 1280 step 8) for (x in 0 until 720 step 8) if (target.getPixel(x, y) == Color.RED) red++
        assertThat(red).isGreaterThan(0)
        // Segunda vez: de la LRU. El tamano se memoriza entre maquetaciones.
        content.render(key, target, Highlights.NONE, null)
        assertThat(images.loads).isEqualTo(1)
        content.configure(cfg(ReaderSettings(fontSizeSp = 24f)))
        content.keyFor(Locator(0, 0))
        assertThat(images.sizes).isEqualTo(1)
    }

    /**
     * La capa de la voz cae donde caia el resaltado dibujado dentro de la pagina: misma geometria
     * y mismo aspecto (salvo bordes de letra), en tema claro y oscuro.
     */
    @Test
    fun capa_de_la_voz_igual_que_el_resaltado_dentro_de_la_pagina() = runTest {
        for (colors in listOf(ReaderColors.LIGHT, ReaderColors.DARK)) {
            val content = EpubContent(Source(listOf(paragraphs(0, 30))), images = null)
            content.configure(RenderConfig(viewport, LayoutSettings.from(ReaderSettings()), colors, false, false))
            val key = content.keyFor(Locator(0, 0))
            val sentence = LocatorRange(Locator(0, 5), Locator(0, 180))
            val baked = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
            content.render(key, baked, Highlights(sentence = sentence), null)
            val plain = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
            content.render(key, plain, Highlights.NONE, null)
            val layered = plain.copy(Bitmap.Config.ARGB_8888, true)
            content.voiceLayer(key, sentence, null)!!.draw(android.graphics.Canvas(layered), 0f, 0f)
            fun close(a: Int, b: Int) = maxOf(
                kotlin.math.abs(Color.red(a) - Color.red(b)),
                kotlin.math.abs(Color.green(a) - Color.green(b)),
                kotlin.math.abs(Color.blue(a) - Color.blue(b)),
            ) <= 40
            var highlighted = 0
            var differ = 0
            for (y in 0 until 1280 step 2) for (x in 0 until 720 step 2) {
                val b = baked.getPixel(x, y)
                if (b != plain.getPixel(x, y)) highlighted++
                if (!close(b, layered.getPixel(x, y))) differ++
            }
            // Hay resaltado, y la capa reproduce casi todos sus pixeles (solo difieren bordes de
            // letra con suavizado; medido: 0 % en claro y 3,8 % en oscuro).
            assertThat(highlighted).isGreaterThan(2_000)
            assertThat(differ.toDouble() / highlighted).isLessThan(0.05)
        }
    }

    @Test
    fun conteo_en_fondo_igual_que_la_maquetacion_real() = runTest {
        val chapters = listOf(withImage(0), paragraphs(1, 25), paragraphs(2, 5))
        val counting = EpubContent(Source(chapters), images = Images())
        counting.configure(cfg())
        counting.computeAllPageCounts()
        val (total, exact) = counting.totalPages()
        assertThat(exact).isTrue()
        val real = EpubContent(Source(chapters), images = Images())
        real.configure(cfg())
        val sum = (0..2).sumOf { real.pageCount(it) }
        assertThat(total).isEqualTo(sum)
        assertThat(counting.knownCharCount(1)).isEqualTo(chapters[1].plainText.length)
    }

    @Test
    fun el_conteo_no_bloquea_la_maquetacion_visible() = runBlocking {
        // El capitulo 0 tarda en cargarse (como un capitulo grande que se esta contando).
        val gate = CompletableDeferred<Unit>()
        val chapters = listOf(paragraphs(0, 40), paragraphs(1, 40))
        val src = object : Source(chapters) {
            override suspend fun chapter(index: Int): ChapterDocument {
                if (index == 0) gate.await()
                return chapters[index]
            }
        }
        val content = EpubContent(src, images = null)
        content.configure(cfg())
        val counting = async(Dispatchers.Default) { content.computeAllPageCounts() }
        // Mientras el conteo esta bloqueado en el capitulo 0, la navegacion al 1 responde.
        val key = withTimeout(5_000) { withContext(Dispatchers.Default) { content.keyFor(Locator(1, 100)) } }
        assertThat(key.chapter).isEqualTo(1)
        gate.complete(Unit)
        withTimeout(10_000) { counting.await() }
        assertThat(content.totalPages().second).isTrue()
    }

    @Test
    fun texto_de_un_rango_para_solo_escuchar() = runTest {
        val content = EpubContent(Source(listOf(paragraphs(0, 3), paragraphs(1, 3))), images = null)
        content.configure(cfg())
        val text = content.textOf(LocatorRange(Locator(0, 0), Locator(0, 9)))
        assertThat(text).isEqualTo("Parrafo 0")
        val cross = content.textOf(LocatorRange(Locator(0, content.knownCharCount(0)!! - 10), Locator(1, 9)))!!
        assertThat(cross).endsWith("Parrafo 0")
        assertThat(cross).doesNotContain("\n")
    }

    @Test
    fun rendimiento_paginar_100k_caracteres() = runTest {
        val sb = StringBuilder()
        val blocks = ArrayList<Block>()
        var i = 0
        while (sb.length < 100_000) {
            val s = sb.length
            sb.append("Parrafo $i. En un lugar de la Mancha, de cuyo nombre no quiero acordarme, no ha mucho tiempo ")
            sb.append("que vivia un hidalgo de los de lanza en astillero, adarga antigua, rocin flaco y galgo corredor. ")
            sb.append("Una olla de algo mas vaca que carnero, salpicon las mas noches, duelos y quebrantos los sabados.")
            blocks += Block.Text(s, sb.length, BlockKind.Paragraph)
            sb.append('\n')
            i++
        }
        val doc = ChapterDocument(0, "Largo", "es", sb.toString(), blocks)
        val p = EpubPaginator()
        val s = LayoutSettings.from(ReaderSettings())
        p.paginate(doc, s, viewport) // calentamiento (JIT, fuentes)
        val times = LongArray(3)
        var pages = 0
        for (k in times.indices) {
            val t0 = System.nanoTime()
            pages = p.paginate(doc, s, viewport).pageCount
            times[k] = (System.nanoTime() - t0) / 1_000_000
        }
        val best = times.min()
        println("RENDIMIENTO paginar ${doc.plainText.length} caracteres: $pages paginas, mejor ${best} ms, tiempos ${times.toList()} ms")
        assertThat(pages).isGreaterThan(10)
        // Objetivo orientativo 300 ms en dispositivo; en Robolectric se deja margen amplio.
        assertThat(best).isLessThan(3_000L)
    }

    @Test
    fun cerrar_contenido_cierra_la_fuente() {
        val src = Source(listOf(paragraphs(0, 2)))
        EpubContent(src, images = null).close()
        assertThat(src.closed).isTrue()
    }

    @Test
    fun pagina_fuera_de_rango_se_ajusta() = runTest {
        val content = EpubContent(Source(listOf(paragraphs(0, 3))), images = null)
        content.configure(cfg())
        val offs = content.offsetsOf(PageKey(0, 99))
        assertThat(offs.first).isAtLeast(0)
    }
}
