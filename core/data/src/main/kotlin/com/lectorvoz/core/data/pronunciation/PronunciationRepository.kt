package com.lectorvoz.core.data.pronunciation

import com.lectorvoz.core.data.db.PronunciationRuleDao
import com.lectorvoz.core.data.db.PronunciationRuleEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Entrada del diccionario de pronunciacion; [bookId] nulo = global. */
data class PronunciationEntry(
    val id: Long = 0,
    val pattern: String,
    val replacement: String,
    val isRegex: Boolean = false,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = true,
    val enabled: Boolean = true,
    /** Idioma ("es", "en"); nulo = todos. */
    val language: String? = null,
    val position: Int = 0,
    val bookId: Long? = null,
)

/** Diccionario de pronunciacion global y por libro. */
interface PronunciationRepository {
    val global: Flow<List<PronunciationEntry>>

    fun forBook(bookId: Long): Flow<List<PronunciationEntry>>

    /** Reglas que se aplican al leer [bookId]: primero las del libro, luego las globales. */
    fun effective(bookId: Long): Flow<List<PronunciationEntry>>

    /** Inserta (id 0) o actualiza; devuelve el id. */
    suspend fun save(entry: PronunciationEntry): Long

    suspend fun delete(id: Long)
}

/** [PronunciationRepository] sobre Room. */
class RoomPronunciationRepository(private val dao: PronunciationRuleDao) : PronunciationRepository {
    override val global: Flow<List<PronunciationEntry>> = dao.observeGlobal().map { l -> l.map { it.toEntry() } }

    override fun forBook(bookId: Long): Flow<List<PronunciationEntry>> = dao.observeForBook(bookId).map { l -> l.map { it.toEntry() } }

    override fun effective(bookId: Long): Flow<List<PronunciationEntry>> = dao.observeEffective(bookId).map { l -> l.map { it.toEntry() } }

    override suspend fun save(entry: PronunciationEntry): Long {
        require(entry.pattern.isNotEmpty()) { "Patron vacio" }
        return if (entry.id == 0L) {
            val pos = if (entry.position == 0) dao.nextPosition() else entry.position
            dao.insert(entry.copy(position = pos).toEntity())
        } else {
            dao.update(entry.toEntity())
            entry.id
        }
    }

    override suspend fun delete(id: Long) = dao.delete(id)

    private fun PronunciationRuleEntity.toEntry() =
        PronunciationEntry(id, pattern, replacement, isRegex, caseSensitive, wholeWord, enabled, language, position, bookId)

    private fun PronunciationEntry.toEntity() =
        PronunciationRuleEntity(id, pattern, replacement, isRegex, caseSensitive, wholeWord, enabled, language, position, bookId)
}
