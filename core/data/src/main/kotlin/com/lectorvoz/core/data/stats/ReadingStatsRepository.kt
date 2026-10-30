package com.lectorvoz.core.data.stats

import com.lectorvoz.core.data.db.BookDao
import com.lectorvoz.core.data.db.ReadingSessionDao
import com.lectorvoz.core.data.db.ReadingSessionEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.util.Calendar
import java.util.TimeZone

/** Tipo de sesion. */
enum class SessionKind { READ, LISTEN }

/** Actividad de un dia. */
data class DayStats(val dayStart: Long, val readMs: Long, val listenMs: Long)

/** Resumen de estadisticas. */
data class StatsSummary(
    val readMs: Long = 0,
    val listenMs: Long = 0,
    val readChars: Long = 0,
    val listenChars: Long = 0,
    val finishedBooks: Int = 0,
    val sessions: Int = 0,
    /** Ultimos 7 dias, del mas antiguo al de hoy. */
    val lastDays: List<DayStats> = emptyList(),
) {
    /** Ritmo de lectura visual en caracteres por minuto (nulo sin datos). */
    val readCharsPerMinute: Int? get() = if (readMs >= 60_000) (readChars * 60_000 / readMs).toInt() else null

    /** Ritmo de escucha en caracteres por minuto. */
    val listenCharsPerMinute: Int? get() = if (listenMs >= 60_000) (listenChars * 60_000 / listenMs).toInt() else null
}

/** Sesiones de lectura y escucha. */
interface ReadingStatsRepository {
    val summary: Flow<StatsSummary>

    /** Guarda una sesion; las de menos de [MIN_SESSION_MS] se descartan. */
    suspend fun record(bookId: Long, kind: SessionKind, startedAt: Long, durationMs: Long, chars: Long)

    suspend fun clear()

    companion object {
        const val MIN_SESSION_MS = 5_000L
    }
}

/** [ReadingStatsRepository] sobre Room. */
class RoomReadingStatsRepository(
    private val sessions: ReadingSessionDao,
    private val books: BookDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: TimeZone = TimeZone.getDefault(),
) : ReadingStatsRepository {

    /**
     * Inicio del dia actual; se vuelve a emitir al pasar la medianoche, asi que "ultimos 7
     * dias" no se queda fijo en el dia en que se creo el repositorio.
     */
    private val today: Flow<Long> = flow {
        while (true) {
            val now = clock()
            val start = dayStart(now)
            emit(start)
            delay((addDays(start, 1) - now).coerceIn(1_000L, DAY_MS))
        }
    }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val summary: Flow<StatsSummary> = combine(
        sessions.observeTotalMs(SessionKind.READ.name),
        sessions.observeTotalMs(SessionKind.LISTEN.name),
        sessions.observeTotalChars(SessionKind.READ.name),
        sessions.observeTotalChars(SessionKind.LISTEN.name),
        combine(
            books.observeFinishedCount(),
            sessions.observeCount(),
            today.flatMapLatest { t -> sessions.observeSince(addDays(t, -6)).map { t to it } },
        ) { a, b, c -> Triple(a, b, c) },
    ) { readMs, listenMs, readChars, listenChars, (finished, count, recent) ->
        StatsSummary(readMs, listenMs, readChars, listenChars, finished, count, days(recent.first, recent.second))
    }

    /** Siete dias de calendario (con cambio de hora: dias de 23 o 25 h). */
    private fun days(today: Long, recent: List<ReadingSessionEntity>): List<DayStats> =
        (-6..0).map { i ->
            val start = addDays(today, i)
            val end = addDays(today, i + 1)
            val inDay = recent.filter { it.startedAt in start until end }
            DayStats(
                start,
                inDay.filter { it.kind == SessionKind.READ.name }.sumOf { it.durationMs },
                inDay.filter { it.kind == SessionKind.LISTEN.name }.sumOf { it.durationMs },
            )
        }

    private fun dayStart(t: Long): Long = Calendar.getInstance(zone).run {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    /** Inicio del dia [n] dias despues de [dayStart] en la zona local. */
    internal fun addDays(dayStart: Long, n: Int): Long = Calendar.getInstance(zone).run {
        timeInMillis = dayStart
        add(Calendar.DAY_OF_MONTH, n)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    override suspend fun record(bookId: Long, kind: SessionKind, startedAt: Long, durationMs: Long, chars: Long) {
        if (durationMs < ReadingStatsRepository.MIN_SESSION_MS) return
        sessions.insert(ReadingSessionEntity(bookId = bookId, kind = kind.name, startedAt = startedAt, durationMs = durationMs, chars = chars.coerceAtLeast(0)))
    }

    override suspend fun clear() = sessions.clear()

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
