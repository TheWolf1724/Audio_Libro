package com.lectorvoz.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lectorvoz.core.model.BookFormat

/**
 * Libro de la biblioteca. [quickHash] (tamano + primeros y ultimos 64 KiB) detecta
 * duplicados al importar. [localPath] es la copia privada (nula si solo se referencia [uri]).
 */
@Entity(
    tableName = "books",
    indices = [
        Index(value = ["quick_hash"], unique = true),
        Index(value = ["last_opened_at"]),
        Index(value = ["added_at"]),
    ],
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    @ColumnInfo(name = "local_path") val localPath: String?,
    val format: BookFormat,
    val title: String,
    /** Autores separados por '\n'. */
    val authors: String,
    val language: String?,
    @ColumnInfo(name = "cover_path") val coverPath: String?,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "quick_hash") val quickHash: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long?,
    /** Progreso global 0..1. */
    val progress: Float = 0f,
    @ColumnInfo(name = "locator_chapter") val locatorChapter: Int = 0,
    @ColumnInfo(name = "locator_offset") val locatorOffset: Int = 0,
    val finished: Boolean = false,
    val favorite: Boolean = false,
    /** Momento en que se marco como terminado (estadisticas); nulo si no lo esta. Version 2. */
    @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
)

/** Marcador de un libro. */
@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["book_id"])],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "book_id") val bookId: Long,
    @ColumnInfo(name = "locator_chapter") val locatorChapter: Int,
    @ColumnInfo(name = "locator_offset") val locatorOffset: Int,
    val excerpt: String,
    val note: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Coleccion (estanteria) de libros. */
@Entity(tableName = "collections", indices = [Index(value = ["name"], unique = true)])
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** Serie: sus libros tienen orden y se ofrece "continuar con el siguiente". Version 2. */
    @ColumnInfo(name = "is_series", defaultValue = "0") val isSeries: Boolean = false,
)

/** Relacion muchos a muchos libro-coleccion. */
@Entity(
    tableName = "book_collections",
    primaryKeys = ["book_id", "collection_id"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collection_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["collection_id"])],
)
data class BookCollectionCrossRef(
    @ColumnInfo(name = "book_id") val bookId: Long,
    @ColumnInfo(name = "collection_id") val collectionId: Long,
    /** Orden dentro de la coleccion o serie (ascendente). Version 2. */
    @ColumnInfo(defaultValue = "0") val position: Int = 0,
)

/**
 * Regla del diccionario de pronunciacion; [language] nulo = todos los idiomas; [bookId]
 * nulo = regla global (si no, solo para ese libro; version 2).
 */
@Entity(tableName = "pronunciation_rules", indices = [Index(value = ["language"]), Index(value = ["book_id"])])
data class PronunciationRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String,
    val replacement: String,
    @ColumnInfo(name = "is_regex") val isRegex: Boolean = false,
    @ColumnInfo(name = "case_sensitive") val caseSensitive: Boolean = false,
    @ColumnInfo(name = "whole_word") val wholeWord: Boolean = true,
    val enabled: Boolean = true,
    val language: String? = null,
    /** Orden de aplicacion (las reglas se aplican en orden ascendente). */
    val position: Int = 0,
    @ColumnInfo(name = "book_id") val bookId: Long? = null,
)

/**
 * Sesion de lectura o escucha (estadisticas; version 2). Sin clave ajena: las estadisticas
 * se conservan aunque el libro se borre de la biblioteca.
 */
@Entity(tableName = "reading_sessions", indices = [Index(value = ["book_id"]), Index(value = ["started_at"])])
data class ReadingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "book_id") val bookId: Long,
    /** "READ" (lectura visual) o "LISTEN" (voz). */
    val kind: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    /** Caracteres avanzados durante la sesion (aprox.). */
    val chars: Long,
)
