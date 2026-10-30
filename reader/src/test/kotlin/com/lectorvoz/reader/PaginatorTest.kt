package com.lectorvoz.reader

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.Block
import com.lectorvoz.core.model.BlockKind
import com.lectorvoz.core.model.BookMetadata
import com.lectorvoz.core.model.ChapterDocument
import com.lectorvoz.core.model.DocumentSource
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.ReaderSettings
import com.lectorvoz.core.model.TextAlign
import com.lectorvoz.core.model.TocEntry
import com.lectorvoz.reader.content.EpubContent
import com.lectorvoz.reader.content.RenderConfig
import com.lectorvoz.reader.layout.ChapterLayout
import com.lectorvoz.reader.layout.DocumentImageLoader
import com.lectorvoz.reader.layout.EpubPaginator
import com.lectorvoz.reader.layout.ImageLoader
import com.lectorvoz.reader.layout.ImageSize
import com.lectorvoz.reader.layout.LayoutSettings
import com.lectorvoz.reader.layout.Viewport
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Paginador EPUB con StaticLayout real (graficos nativos de Robolectric). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaginatorTest {

    private val viewport = Viewport(widthPx = 720, heightPx = 1280, density = 2f)

    private fun chapter(index: Int = 0, paragraphs: Int = 60): ChapterDocument {
        val sb = StringBuilder()
        val blocks = ArrayList<Block>()
        for (i in 0 until paragraphs) {
            val start = sb.length
            sb.append("Parrafo $i. En un lugar de la Mancha, de cuyo nombre no quiero acordarme, ")
            sb.append("no ha mucho tiempo que vivia un hidalgo de los de lanza en astillero, adarga antigua, ")
            sb.append("rocin flaco y galgo corredor.")
            blocks += Block.Text(start, sb.length, BlockKind.Paragraph)
            if (i < paragraphs - 1) sb.append('\n')
        }
        return ChapterDocument(index, "Capitulo $index", "es", sb.toString(), blocks)
    }

    private fun layoutSettings(s: ReaderSettings = ReaderSettings()) = LayoutSettings.from(s)

    private suspend fun paginate(doc: ChapterDocument, s: ReaderSettings = ReaderSettings(), images: ImageLoader? = null): ChapterLayout =
        EpubPaginator(images = images).paginate(doc, layoutSettings(s), viewport)

    private fun assertContiguous(cl: ChapterLayout) {
        assertThat(cl.pages.startOffset(0)).isEqualTo(0)
        for (p in 0 until cl.pageCount) {
            assertThat(cl.pages.endOffset(p)).isAtLeast(cl.pages.startOffset(p))
            if (p > 0) assertThat(cl.pages.startOffset(p)).isEqualTo(cl.pages.endOffset(p - 1))
            // Cada pagina cabe en el alto util.
            val top = cl.layout.getLineTop(cl.pages.firstLine(p))
            val bottom = cl.layout.getLineBottom(cl.pages.endLine(p) - 1)
            if (cl.pages.endLine(p) - cl.pages.firstLine(p) > 1) {
                assertThat(bottom - top).isAtMost(cl.geometry.contentHeight)
            }
        }
        assertThat(cl.pages.endOffset(cl.pageCount - 1)).isEqualTo(cl.text.length)
    }

    @Test
    fun cortes_estables_y_contiguos() = runTest {
        val doc = chapter()
        val a = paginate(doc)
        val b = paginate(doc)
        assertThat(a.pageCount).isGreaterThan(1)
        assertThat(a.pages).isEqualTo(b.pages)
        assertContiguous(a)
    }

    @Test
    fun justificado_y_guionado_paginan() = runTest {
        val s = ReaderSettings(textAlign = TextAlign.JUSTIFY, letterSpacingEm = 0.05f, firstLineIndentEm = 1.5f)
        val cl = paginate(chapter(), s)
        assertThat(cl.settings.justify).isTrue()
        assertThat(cl.settings.hyphenation).isTrue()
        assertContiguous(cl)
    }

    @Test
    fun conserva_locator_al_cambiar_ajustes() = runTest {
        val doc = chapter()
        val offset = doc.plainText.length * 2 / 3
        val small = paginate(doc, ReaderSettings(fontSizeSp = 16f))
        val big = paginate(doc, ReaderSettings(fontSizeSp = 28f, lineSpacing = 1.8f))
        assertThat(big.pageCount).isGreaterThan(small.pageCount)
        for (cl in listOf(small, big)) {
            val p = cl.pageForOffset(offset)
            assertThat(offset).isAtLeast(cl.pages.startOffset(p))
            assertThat(offset).isLessThan(cl.pages.endOffset(p))
        }
    }

    @Test
    fun capitulo_vacio_tiene_una_pagina() = runTest {
        val cl = paginate(ChapterDocument(3, null, null, "", emptyList()))
        assertThat(cl.pageCount).isEqualTo(1)
        assertThat(cl.pages.startOffset(0)).isEqualTo(0)
        assertThat(cl.pageForOffset(0)).isEqualTo(0)
        assertThat(cl.pageText(0)).isEmpty()
    }

    @Test
    fun imagen_mas_grande_que_la_pagina_se_escala() = runTest {
        val text = "Antes\n" + ChapterDocument.OBJECT_CHAR + "\nDespues"
        val doc = ChapterDocument(
            0, "Img", "es", text,
            listOf(
                Block.Text(0, 5, BlockKind.Paragraph),
                Block.Image(6, "img.png", "foto", widthPx = 4000, heightPx = 6000),
                Block.Text(8, text.length, BlockKind.Paragraph),
            ),
        )
        var decoded = 0
        val loader = object : ImageLoader {
            override suspend fun size(href: String) = ImageSize(400, 600)
            override suspend fun load(href: String, width: Int, height: Int): Bitmap {
                decoded++
                return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
        }
        val cl = paginate(doc, images = loader)
        // Maquetar no decodifica imagenes (solo lee su tamano).
        assertThat(decoded).isEqualTo(0)
        val box = cl.text.getSpans(6, 7, com.lectorvoz.reader.layout.ImageBoxSpan::class.java).single()
        assertThat(box.width).isAtMost(cl.geometry.columnWidth)
        assertThat(box.height).isAtMost(cl.geometry.contentHeight)
        // Proporcion del tamano declarado (4000x6000).
        assertThat(box.height.toFloat() / box.width).isWithin(0.02f).of(1.5f)
        val imageLine = cl.layout.getLineForOffset(6)
        val h = cl.layout.getLineBottom(imageLine) - cl.layout.getLineTop(imageLine)
        assertThat(h).isAtMost(cl.geometry.contentHeight)
        assertContiguous(cl)
    }

    @Test
    fun muestreo_de_imagen() {
        assertThat(DocumentImageLoader.sampleSize(4000, 6000, 700, 1000)).isEqualTo(4)
        assertThat(DocumentImageLoader.sampleSize(300, 300, 700, 1000)).isEqualTo(1)
        assertThat(DocumentImageLoader.sampleSize(1400, 100, 700, 1000)).isEqualTo(2)
    }

    private class FakeSource(private val chapters: List<ChapterDocument>) : DocumentSource {
        override val metadata = BookMetadata("Libro")
        override val toc = emptyList<TocEntry>()
        override val chapterCount get() = chapters.size
        override suspend fun chapter(index: Int) = chapters[index]
        override suspend fun resource(href: String): ByteArray? = null
        override fun close() = Unit
    }

    @Test
    fun contenido_epub_conserva_posicion_y_cuenta_paginas() = runTest {
        val content = EpubContent(FakeSource(listOf(chapter(0), chapter(1, 10), chapter(2, 0))), images = null)
        fun cfg(s: ReaderSettings) = RenderConfig(viewport, LayoutSettings.from(s), ReaderColors.LIGHT, false, false)
        assertThat(content.configure(cfg(ReaderSettings()))).isTrue()
        val target = Locator(0, 3000)
        val k1 = content.keyFor(target)
        assertThat(content.offsetsOf(k1)).contains(3000)

        // Cambiar solo el tema no repagina; cambiar la letra si.
        assertThat(content.configure(cfg(ReaderSettings()).copy(colors = ReaderColors.DARK))).isFalse()
        assertThat(content.configure(cfg(ReaderSettings(fontSizeSp = 26f)))).isTrue()
        val k2 = content.keyFor(target)
        assertThat(content.offsetsOf(k2)).contains(3000)
        assertThat(k2.page).isGreaterThan(k1.page)

        content.computeAllPageCounts()
        val (total, exact) = content.totalPages()
        assertThat(exact).isTrue()
        assertThat(total).isEqualTo((0..2).sumOf { content.knownPageCount(it)!! })
        assertThat(content.globalIndex(com.lectorvoz.reader.view.PageKey(1, 0))).isEqualTo(content.knownPageCount(0))
    }
}
