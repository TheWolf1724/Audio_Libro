package com.lectorvoz.core.data.library

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.data.db.LectorVozDatabase
import com.lectorvoz.core.data.pronunciation.PronunciationEntry
import com.lectorvoz.core.data.pronunciation.RoomPronunciationRepository
import com.lectorvoz.core.model.BookFormat
import com.lectorvoz.core.model.Locator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Importacion (copia, formato por bytes, duplicados, danados) y borrado sin tocar el original. */
@RunWith(AndroidJUnit4::class)
class LibraryRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: LectorVozDatabase
    private lateinit var repo: RoomLibraryRepository
    private var extractorFails = false

    private val extractor = CoverExtractor { file, format, coverOut ->
        if (extractorFails) error("danado")
        coverOut.writeBytes(byteArrayOf(1, 2, 3))
        ExtractedInfo(title = "Titulo de ${file.extension}", authors = listOf("Ana", "Luis"), language = "es", coverWritten = format == BookFormat.EPUB)
    }

    @Before
    fun abre() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LectorVozDatabase::class.java).allowMainThreadQueries().build()
        repo = RoomLibraryRepository(context, db.bookDao(), db.pronunciationRuleDao(), extractor, Dispatchers.Unconfined, clock = { 1000L })
    }

    @After
    fun cierra() = db.close()

    private fun uriFor(file: File, name: String = file.name): Uri {
        val uri = Uri.parse("content://prueba/docs/$name")
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { FileInputStream(file) }
        return uri
    }

    private fun pdf(name: String, body: String = "contenido"): File =
        tmp.newFile(name).apply { writeBytes("%PDF-1.7\n$body\n%%EOF".toByteArray()) }

    private fun epub(name: String): File {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            val mime = "application/epub+zip".toByteArray()
            val e = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = size
                crc = CRC32().apply { update(mime) }.value
            }
            z.putNextEntry(e); z.write(mime); z.closeEntry()
            z.putNextEntry(ZipEntry("META-INF/container.xml")); z.write("<container/>".toByteArray()); z.closeEntry()
        }
        return tmp.newFile(name).apply { writeBytes(bytes.toByteArray()) }
    }

    @Test
    fun importaPdfConCopiaPrivadaYMetadatos() = runTest {
        val original = pdf("libro.pdf")
        val r = repo.import(uriFor(original))
        assertThat(r).isInstanceOf(ImportResult.Imported::class.java)
        val book = (r as ImportResult.Imported).book
        assertThat(book.format).isEqualTo(BookFormat.PDF)
        assertThat(book.title).isEqualTo("Titulo de pdf")
        assertThat(book.authors).containsExactly("Ana", "Luis").inOrder()
        assertThat(book.localFile).isNotNull()
        assertThat(book.localFile!!.readBytes()).isEqualTo(original.readBytes())
        assertThat(book.localFile!!.parentFile).isEqualTo(repo.booksDir)
        assertThat(repo.books.first()).hasSize(1)
    }

    @Test
    fun detectaEpubPorBytesAunqueLaExtensionMienta() = runTest {
        val f = epub("sin-extension.bin")
        val r = repo.import(uriFor(f))
        val book = (r as ImportResult.Imported).book
        assertThat(book.format).isEqualTo(BookFormat.EPUB)
        assertThat(book.coverPath).isNotNull()
        assertThat(File(book.coverPath!!).exists()).isTrue()
    }

    @Test
    fun rechazaFormatoNoAdmitidoYDuplicados() = runTest {
        val txt = tmp.newFile("notas.pdf").apply { writeText("solo texto") }
        val r1 = repo.import(uriFor(txt))
        assertThat((r1 as ImportResult.Failed).error).isEqualTo(ImportError.UNSUPPORTED_FORMAT)

        val f = pdf("a.pdf")
        repo.import(uriFor(f))
        val copia = pdf("a-copia.pdf")
        val r2 = repo.import(uriFor(copia))
        assertThat(r2).isInstanceOf(ImportResult.Duplicate::class.java)
        assertThat(repo.books.first()).hasSize(1)
        // No quedan temporales.
        assertThat(repo.booksDir.listFiles()!!.filter { it.name.endsWith(".tmp") }).isEmpty()
    }

    @Test
    fun libroDanadoNoSeAnade() = runTest {
        extractorFails = true
        val r = repo.import(uriFor(pdf("roto.pdf")))
        assertThat((r as ImportResult.Failed).error).isEqualTo(ImportError.CORRUPT)
        assertThat(repo.books.first()).isEmpty()
        assertThat(repo.booksDir.listFiles()!!.toList()).isEmpty()
    }

    @Test
    fun referenciarSinCopiar() = runTest {
        val r = repo.import(uriFor(pdf("ref.pdf")), copy = false)
        val book = (r as ImportResult.Imported).book
        assertThat(book.localPath).isNull()
        assertThat(book.uri).isEqualTo("content://prueba/docs/ref.pdf")
    }

    @Test
    fun borrarQuitaCopiaYReglasPeroNoElOriginal() = runTest {
        val original = pdf("borrar.pdf")
        val book = (repo.import(uriFor(original)) as ImportResult.Imported).book
        val pron = RoomPronunciationRepository(db.pronunciationRuleDao())
        pron.save(PronunciationEntry(pattern = "Xi", replacement = "Shi", bookId = book.id))
        repo.delete(book.id)
        assertThat(repo.books.first()).isEmpty()
        assertThat(File(book.localPath!!).exists()).isFalse()
        assertThat(original.exists()).isTrue()
        assertThat(pron.forBook(book.id).first()).isEmpty()
    }

    @Test
    fun progresoYSeguirLeyendo() = runTest {
        val book = (repo.import(uriFor(pdf("p.pdf"))) as ImportResult.Imported).book
        assertThat(repo.continueReading.first()).isNull()
        repo.updateProgress(book.id, Locator(3, 40), 0.25f)
        val b = repo.continueReading.first()!!
        assertThat(b.locator).isEqualTo(Locator(3, 40))
        assertThat(b.progress).isEqualTo(0.25f)
        repo.updateProgress(book.id, Locator(9, 0), 1f, finished = true)
        assertThat(repo.get(book.id)!!.finished).isTrue()
        assertThat(repo.get(book.id)!!.finishedAt).isEqualTo(1000L)
        assertThat(repo.continueReading.first()).isNull()
    }
}
