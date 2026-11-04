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
import com.lectorvoz.core.model.TocEntry
import com.lectorvoz.reader.content.EpubContent
import com.lectorvoz.reader.content.RenderConfig
import com.lectorvoz.reader.layout.ImageLoader
import com.lectorvoz.reader.layout.ImageSize
import com.lectorvoz.reader.layout.LayoutSettings
import com.lectorvoz.reader.layout.Viewport
import com.lectorvoz.reader.render.Highlights
import com.lectorvoz.reader.view.PageKey
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.IOException

/** Un capitulo que no se puede leer o una imagen sin memoria no dejan el lector atascado. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CapituloIlegibleTest {
    private val viewport = Viewport(widthPx = 720, heightPx = 1280, density = 2f)
    private fun cfg() = RenderConfig(viewport, LayoutSettings.from(ReaderSettings()), ReaderColors.LIGHT, false, false)

    private fun doc(index: Int, text: String, image: Boolean = false): ChapterDocument {
        val blocks = arrayListOf<Block>(Block.Text(0, text.length, BlockKind.Paragraph))
        var plain = text
        if (image) {
            plain = "$text\n${ChapterDocument.OBJECT_CHAR}"
            blocks += Block.Image(text.length + 1, "img/a.png", "a")
        }
        return ChapterDocument(index, null, "es", plain, blocks)
    }

    private class Source(val good: Map<Int, ChapterDocument>, override val chapterCount: Int) : DocumentSource {
        override val metadata = BookMetadata("Libro")
        override val toc = emptyList<TocEntry>()
        override suspend fun chapter(index: Int): ChapterDocument = good[index] ?: throw IOException("Falta el recurso: c$index")
        override suspend fun resource(href: String): ByteArray? = null
        override fun close() {}
    }

    @Test
    fun capituloQueFallaMuestraUnAviso() = runTest {
        val content = EpubContent(Source(mapOf(0 to doc(0, "Uno."), 2 to doc(2, "Tres.")), 3), images = null)
        content.configure(cfg())
        val key = content.keyFor(Locator(1, 0))
        assertThat(content.pageText(key)).contains(EpubContent.UNAVAILABLE)
        assertThat(content.pageText(key)).contains("Falta el recurso")
        // Se puede seguir al capitulo siguiente y contar todas las paginas.
        assertThat(content.pageText(PageKey(2, 0))).contains("Tres.")
        content.computeAllPageCounts()
        assertThat(content.totalPages().second).isTrue()
    }

    @Test
    fun imagenSinMemoriaNoCierraLaApp() = runTest {
        val images = object : ImageLoader {
            override suspend fun size(href: String) = ImageSize(300, 200)
            override suspend fun load(href: String, width: Int, height: Int): Bitmap = throw OutOfMemoryError("prueba")
        }
        val content = EpubContent(Source(mapOf(0 to doc(0, "Uno.", image = true)), 1), images = images)
        content.configure(cfg())
        val target = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        content.render(PageKey(0, 0), target, Highlights.NONE, null)
    }
}
