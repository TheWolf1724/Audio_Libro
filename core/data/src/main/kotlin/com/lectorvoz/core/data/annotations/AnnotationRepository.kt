package com.lectorvoz.core.data.annotations

import com.lectorvoz.core.data.db.AnnotationDao
import com.lectorvoz.core.data.db.AnnotationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Subrayado de color con nota opcional, dentro de un capitulo. */
data class Annotation(
    val id: Long,
    val bookId: Long,
    val chapter: Int,
    val start: Int,
    val end: Int,
    /** Indice del color: 0 amarillo, 1 verde, 2 azul, 3 rosa. */
    val color: Int,
    val note: String?,
    val quote: String,
    val createdAt: Long,
)

/** Subrayados y notas por libro. */
interface AnnotationRepository {
    fun observe(bookId: Long): Flow<List<Annotation>>

    /** Crea un subrayado de [start] a [end] (exclusivo) en [chapter]; devuelve su id. */
    suspend fun add(bookId: Long, chapter: Int, start: Int, end: Int, color: Int, quote: String, note: String? = null): Long

    /** Cambia color, nota y/o tramo (al ampliar o reubicar un subrayado). */
    suspend fun update(
        annotation: Annotation,
        color: Int = annotation.color,
        note: String? = annotation.note,
        start: Int = annotation.start,
        end: Int = annotation.end,
        quote: String = annotation.quote,
    )

    suspend fun delete(id: Long)
}

/** [AnnotationRepository] sobre Room. */
class RoomAnnotationRepository(
    private val dao: AnnotationDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : AnnotationRepository {
    override fun observe(bookId: Long): Flow<List<Annotation>> = dao.observeForBook(bookId).map { list ->
        list.map { Annotation(it.id, it.bookId, it.chapter, it.startOffset, it.endOffset, it.color, it.note, it.quote, it.createdAt) }
    }

    override suspend fun add(bookId: Long, chapter: Int, start: Int, end: Int, color: Int, quote: String, note: String?): Long {
        require(end > start) { "Subrayado vacio" }
        return dao.insert(
            AnnotationEntity(
                bookId = bookId,
                chapter = chapter,
                startOffset = start,
                endOffset = end,
                color = color.coerceIn(0, COLORS - 1),
                note = note?.takeIf { it.isNotBlank() },
                quote = quote.trim().take(MAX_QUOTE),
                createdAt = clock(),
            ),
        )
    }

    override suspend fun update(annotation: Annotation, color: Int, note: String?, start: Int, end: Int, quote: String) = dao.update(
        AnnotationEntity(
            id = annotation.id,
            bookId = annotation.bookId,
            chapter = annotation.chapter,
            startOffset = start,
            endOffset = end.coerceAtLeast(start + 1),
            color = color.coerceIn(0, COLORS - 1),
            note = note?.takeIf { it.isNotBlank() },
            quote = quote.trim().take(MAX_QUOTE),
            createdAt = annotation.createdAt,
        ),
    )

    override suspend fun delete(id: Long) = dao.delete(id)

    companion object {
        const val COLORS = 4
        private const val MAX_QUOTE = 1000
    }
}

/** Exportacion de subrayados y notas de un libro a texto. */
object AnnotationExport {
    private val COLOR_NAMES = listOf("amarillo", "verde", "azul", "rosa")

    /** Markdown: una cita por subrayado (en blockquote) y su nota debajo. [chapterTitle] da el titulo del capitulo. */
    fun markdown(bookTitle: String, items: List<Annotation>, chapterTitle: (Int) -> String?): String = buildString {
        append("# ").append(bookTitle).append("\n")
        var last = -1
        for (a in items.sortedWith(compareBy({ it.chapter }, { it.start }))) {
            if (a.chapter != last) {
                last = a.chapter
                append("\n## ").append(chapterTitle(a.chapter) ?: "Capitulo ${a.chapter + 1}").append("\n")
            }
            append("\n> ").append(a.quote.replace("\n", "\n> ")).append("\n")
            append("\n(").append(COLOR_NAMES[a.color.coerceIn(0, COLOR_NAMES.lastIndex)]).append(")")
            if (!a.note.isNullOrBlank()) append(" Nota: ").append(a.note)
            append("\n")
        }
    }

    /** Texto plano equivalente. */
    fun text(bookTitle: String, items: List<Annotation>, chapterTitle: (Int) -> String?): String = buildString {
        append(bookTitle).append("\n")
        for (a in items.sortedWith(compareBy({ it.chapter }, { it.start }))) {
            append("\n[").append(chapterTitle(a.chapter) ?: "Capitulo ${a.chapter + 1}").append("]\n")
            append(a.quote).append("\n")
            if (!a.note.isNullOrBlank()) append("Nota: ").append(a.note).append("\n")
        }
    }
}
