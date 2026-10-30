package com.lectorvoz.core.data.library

import com.lectorvoz.core.data.db.BookEntity
import com.lectorvoz.core.model.BookFormat
import com.lectorvoz.core.model.Locator
import java.io.File

/** Libro de la biblioteca (modelo inmutable para la UI). */
data class Book(
    val id: Long,
    /** Uri original del usuario (no se modifica nunca). */
    val uri: String,
    /** Copia privada; nula si solo se referencia [uri]. */
    val localPath: String?,
    val format: BookFormat,
    val title: String,
    val authors: List<String>,
    val language: String?,
    val coverPath: String?,
    val sizeBytes: Long,
    val quickHash: String,
    val addedAt: Long,
    val lastOpenedAt: Long?,
    /** Progreso 0..1. */
    val progress: Float,
    val locator: Locator,
    val finished: Boolean,
    val favorite: Boolean,
    val finishedAt: Long? = null,
) {
    val author: String? get() = authors.firstOrNull()

    /** Fichero local del libro si existe. */
    val localFile: File? get() = localPath?.let(::File)?.takeIf { it.exists() }
}

internal fun BookEntity.toBook(): Book = Book(
    id = id,
    uri = uri,
    localPath = localPath,
    format = format,
    title = title,
    authors = authors.split('\n').map { it.trim() }.filter { it.isNotEmpty() },
    language = language,
    coverPath = coverPath,
    sizeBytes = sizeBytes,
    quickHash = quickHash,
    addedAt = addedAt,
    lastOpenedAt = lastOpenedAt,
    progress = progress.coerceIn(0f, 1f),
    locator = Locator(locatorChapter.coerceAtLeast(0), locatorOffset.coerceAtLeast(0)),
    finished = finished,
    favorite = favorite,
    finishedAt = finishedAt,
)

/** Resultado de importar un fichero. */
sealed interface ImportResult {
    data class Imported(val book: Book) : ImportResult
    /** Ya estaba en la biblioteca (mismo hash rapido). */
    data class Duplicate(val book: Book) : ImportResult
    data class Failed(val name: String, val error: ImportError, val detail: String? = null) : ImportResult
}

/** Motivo de fallo al importar. */
enum class ImportError { UNSUPPORTED_FORMAT, UNREADABLE, CORRUPT, NO_SPACE }

/** Datos que se extraen del libro al importarlo. */
data class ExtractedInfo(
    val title: String?,
    val authors: List<String> = emptyList(),
    val language: String? = null,
    /** true si se escribio la portada en el fichero pedido. */
    val coverWritten: Boolean = false,
)

/**
 * Extrae metadatos y portada de un libro. Lo implementa `:app` con los motores de formato
 * (EPUB: portada declarada; PDF: primera pagina). Se llama fuera del hilo principal.
 */
fun interface CoverExtractor {
    /**
     * Lee [file] (formato [format]) y escribe la portada (JPEG pequeno) en [coverOut] si la
     * hay. Lanza excepcion si el libro esta danado.
     */
    suspend fun extract(file: File, format: BookFormat, coverOut: File): ExtractedInfo
}
