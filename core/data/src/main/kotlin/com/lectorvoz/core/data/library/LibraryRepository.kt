package com.lectorvoz.core.data.library

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.lectorvoz.core.data.db.BookDao
import com.lectorvoz.core.data.db.BookEntity
import com.lectorvoz.core.data.db.PronunciationRuleDao
import com.lectorvoz.core.model.BookFormat
import com.lectorvoz.core.model.Locator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** Biblioteca: importar, listar, progreso y borrar. */
interface LibraryRepository {
    val books: Flow<List<Book>>

    /** Ultimo libro abierto y sin terminar ("seguir leyendo"). */
    val continueReading: Flow<Book?>

    fun observe(id: Long): Flow<Book?>

    suspend fun get(id: Long): Book?

    /**
     * Importa [uri]. Con [copy] (por defecto) se guarda una copia privada en
     * `filesDir/libros`; si no, solo se referencia el original con permiso persistente.
     */
    suspend fun import(uri: Uri, copy: Boolean = true): ImportResult

    suspend fun updateProgress(id: Long, locator: Locator, progress: Float, finished: Boolean? = null)

    suspend fun markOpened(id: Long)

    suspend fun setFavorite(id: Long, favorite: Boolean)

    suspend fun setFinished(id: Long, finished: Boolean)

    suspend fun rename(id: Long, title: String, authors: List<String>)

    /** Quita el libro de la biblioteca (y su copia privada); el original no se toca. */
    suspend fun delete(id: Long)
}

/**
 * [LibraryRepository] sobre Room. La copia, el hash y la extraccion de metadatos ocurren en
 * [io]; nunca se escribe en el Uri original del usuario.
 */
class RoomLibraryRepository(
    context: Context,
    private val dao: BookDao,
    private val rules: PronunciationRuleDao,
    private val extractor: CoverExtractor,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Base de datos para agrupar escrituras en transacciones (nula: sin transaccion). */
    private val database: RoomDatabase? = null,
    /** Pide permiso persistente de lectura sobre el Uri; false si no se concede. */
    persistPermission: ((Uri) -> Boolean)? = null,
) : LibraryRepository {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val takePermission: (Uri) -> Boolean = persistPermission ?: ::takePersistable

    /** Copias privadas de los libros. */
    val booksDir: File = File(appContext.filesDir, "libros")

    /** Portadas extraidas. */
    val coversDir: File = File(appContext.filesDir, "portadas")

    /** Copias temporales de los libros solo referenciados (las crea `:app` al abrirlos). */
    val openedCacheDir: File = File(appContext.cacheDir, OPENED_CACHE_DIR)

    override val books: Flow<List<Book>> = dao.observeAll().map { list -> list.map { it.toBook() } }.distinctUntilChanged()

    override val continueReading: Flow<Book?> = dao.observeContinueReading().map { it?.toBook() }.distinctUntilChanged()

    override fun observe(id: Long): Flow<Book?> = dao.observe(id).map { it?.toBook() }.distinctUntilChanged()

    override suspend fun get(id: Long): Book? = dao.get(id)?.toBook()

    private suspend fun <T> transaction(block: suspend () -> T): T {
        val db = database ?: return block()
        return db.withTransaction { block() }
    }

    override suspend fun import(uri: Uri, copy: Boolean): ImportResult = withContext(io) {
        val name = displayName(uri)
        booksDir.mkdirs()
        coversDir.mkdirs()
        // Siempre se hace una copia temporal: los motores de formato necesitan un fichero.
        val tmp = File(booksDir, ".importando-${System.nanoTime()}.tmp")
        // Ficheros ya materializados (copia privada y portada) que deben borrarse si la
        // importacion no llega a guardar su fila -por ejemplo si se cancela al girar la
        // pantalla justo tras renombrar-: evita copias huerfanas en libros/ y portadas/.
        var createdFile: File? = null
        var createdCover: File? = null
        var ownedByRow = false
        try {
            val hasher = QuickHasher()
            val head = ByteArray(FormatSniffer.HEAD_BYTES)
            var headLen = 0
            var format: BookFormat? = null
            val input: InputStream = try {
                resolver.openInputStream(uri) ?: return@withContext ImportResult.Failed(name, ImportError.UNREADABLE)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return@withContext ImportResult.Failed(name, ImportError.UNREADABLE, e.message)
            }
            try {
                input.use { ins ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            if (headLen < head.size) {
                                val k = minOf(n, head.size - headLen)
                                System.arraycopy(buf, 0, head, headLen, k)
                                headLen += k
                                // Cabecera completa: si no es un libro se aborta sin copiar el resto.
                                if (headLen == head.size) {
                                    format = FormatSniffer.detect(head, name)
                                        ?: return@withContext ImportResult.Failed(name, ImportError.UNSUPPORTED_FORMAT)
                                }
                            }
                            hasher.update(buf, 0, n)
                            out.write(buf, 0, n)
                        }
                    }
                }
            } catch (e: IOException) {
                val noSpace = e.message?.contains("ENOSPC") == true || e.message?.contains("No space") == true
                return@withContext ImportResult.Failed(name, if (noSpace) ImportError.NO_SPACE else ImportError.UNREADABLE, e.message)
            }
            val fmt = format ?: FormatSniffer.detect(head.copyOf(headLen), name)
                ?: return@withContext ImportResult.Failed(name, ImportError.UNSUPPORTED_FORMAT)
            val hash = hasher.digest()
            dao.findByHash(hash)?.let { return@withContext ImportResult.Duplicate(it.toBook()) }

            val ext = if (fmt == BookFormat.EPUB) "epub" else "pdf"
            val finalFile = File(booksDir, "$hash.$ext")
            if (!tmp.renameTo(finalFile)) {
                tmp.copyTo(finalFile, overwrite = true)
                tmp.delete()
            }
            createdFile = finalFile
            val cover = File(coversDir, "$hash.jpg")
            createdCover = cover
            val info = try {
                extractor.extract(finalFile, fmt, cover)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Libro danado (${fmt.name})", e)
                finalFile.delete()
                cover.delete()
                return@withContext ImportResult.Failed(name, ImportError.CORRUPT, e.message)
            }
            // Sin permiso persistente (Uri de "Abrir con" o "Compartir") se conserva la copia
            // privada: si no, el libro dejaria de abrirse al reiniciar la app.
            val localPath: String? = if (copy || !takePermission(uri)) {
                finalFile.absolutePath
            } else {
                finalFile.delete()
                null
            }
            val entity = BookEntity(
                uri = uri.toString(),
                localPath = localPath,
                format = fmt,
                title = info.title?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.'),
                authors = info.authors.joinToString(NL),
                language = info.language,
                coverPath = cover.absolutePath.takeIf { info.coverWritten && cover.exists() },
                sizeBytes = hasher.size,
                quickHash = hash,
                addedAt = clock(),
                lastOpenedAt = null,
            )
            val id = try {
                dao.insert(entity)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Carrera con otra importacion del mismo fichero (indice unico del hash): la
                // otra ya guardo su copia y su portada (mismos nombres), no se borran.
                dao.findByHash(hash)?.let {
                    ownedByRow = true // los ficheros (mismo hash) son de la importacion ganadora
                    return@withContext ImportResult.Duplicate(it.toBook())
                }
                finalFile.delete()
                cover.delete()
                return@withContext ImportResult.Failed(name, ImportError.UNREADABLE, e.message)
            }
            ownedByRow = true // la fila ya referencia estos ficheros: no son huerfanos
            ImportResult.Imported(entity.copy(id = id).toBook())
        } finally {
            if (tmp.exists()) tmp.delete()
            // Importacion abortada tras materializar los ficheros (cancelacion o error) sin fila
            // que los referencie: se borran para no dejar huerfanos.
            if (!ownedByRow) {
                createdFile?.delete()
                createdCover?.delete()
            }
        }
    }

    private fun takePersistable(uri: Uri): Boolean = try {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (e: SecurityException) {
        Log.i(TAG, "Sin permiso persistente; se guarda una copia privada")
        false
    }

    private fun displayName(uri: Uri): String {
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "libro"
    }

    override suspend fun updateProgress(id: Long, locator: Locator, progress: Float, finished: Boolean?) = transaction {
        val current = dao.get(id) ?: return@transaction
        val fin = finished ?: current.finished
        dao.updateProgress(id, progress.coerceIn(0f, 1f), locator.chapter, locator.offset, clock(), fin)
        if (fin != current.finished) dao.setFinishedAt(id, fin, if (fin) clock() else null)
    }

    override suspend fun markOpened(id: Long) = dao.markOpened(id, clock())

    override suspend fun setFavorite(id: Long, favorite: Boolean) = dao.setFavorite(id, favorite)

    override suspend fun setFinished(id: Long, finished: Boolean) = dao.setFinishedAt(id, finished, if (finished) clock() else null)

    override suspend fun rename(id: Long, title: String, authors: List<String>) =
        dao.rename(id, title.trim(), authors.joinToString(NL) { it.trim() })

    override suspend fun delete(id: Long) = withContext(io) {
        val book = transaction {
            val b = dao.get(id)
            if (b != null) {
                dao.delete(id)
                rules.deleteForBook(id)
            }
            b
        } ?: return@withContext
        // Solo se borra lo que es nuestro: la copia privada, la portada y la copia temporal.
        book.localPath?.let(::File)?.takeIf { it.isInside(booksDir) }?.delete()
        book.coverPath?.let(::File)?.takeIf { it.isInside(coversDir) }?.delete()
        openedCacheDir.listFiles()?.filter { it.name.startsWith(book.quickHash + ".") }?.forEach { it.delete() }
        if (book.localPath == null) {
            try {
                resolver.releasePersistableUriPermission(Uri.parse(book.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                // No habia permiso persistente.
            }
        }
    }

    private fun File.isInside(dir: File): Boolean =
        canonicalPath.startsWith(dir.canonicalPath + File.separator)

    companion object {
        private const val TAG = "LibraryRepository"

        /** Carpeta de `cacheDir` con las copias temporales de los libros solo referenciados. */
        const val OPENED_CACHE_DIR = "abiertos"

        private const val NL = "\n"
    }
}
