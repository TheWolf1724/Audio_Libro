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
    /**
     * Racha: dias seguidos con actividad (>= 1 min en total). Cuenta hasta hoy si hoy ya hay
     * actividad, o hasta ayer si todavia no (la racha sigue viva hasta que acaba el dia).
     */
    val streakDays: Int = 0,
) {
    /** Ritmo de lectura visual en caracteres por minuto (nulo sin datos). */
    val readCharsPerMinute: Int? get() = if (readMs >= 60_000) (readChars * 60_000 / readMs).toInt() else null

    /** Ritmo de escucha en caracteres por minuto. */
    val listenCharsPerMinute: Int? get() = if (listenMs >= 60_000) (listenChars * 60_000 / listenMs).toInt() else null
}

/** Estadisticas de un solo libro. */
data class BookStats(
    val readMs: Long = 0,
    val listenMs: Long = 0,
    val sessions: Int = 0,
    /** Primera y ultima sesion (nulos sin actividad). */
    val firstAt: Long? = null,
    val lastAt: Long? = null,
) {
    val totalMs: Long get() = readMs + listenMs
}

/** Sesiones de lectura y escucha. */
interface ReadingStatsRepository {
    val summary: Flow<StatsSummary>

    /** Estadisticas del libro [bookId] (vacias por defecto). */
    fun forBook(bookId: Long): Flow<BookStats> = kotlinx.coroutines.flow.flowOf(BookStats())

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
    /**
     * Zona horaria de los limites de dia; se lee en cada calculo (no se captura una vez) para
     * que "Hoy" y "ultimos 7 dias" sigan al cambio de zona o del reloj sin reiniciar (M5).
     */
    private val zone: () -> TimeZone = { TimeZone.getDefault() },
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
            // Una sola observacion de los ultimos dias sirve para el grafico (7) y la racha (hasta 120).
            today.flatMapLatest { t -> sessions.observeSince(addDays(t, -STREAK_LOOKBACK_DAYS)).map { t to it } },
        ) { a, b, c -> Triple(a, b, c) },
    ) { readMs, listenMs, readChars, listenChars, (finished, count, recent) ->
        StatsSummary(readMs, listenMs, readChars, listenChars, finished, count, days(recent.first, recent.second), streak(recent.first, recent.second))
    }

    override fun forBook(bookId: Long): Flow<BookStats> = sessions.observeForBook(bookId).map { list ->
        BookStats(
            readMs = list.filter { it.kind == SessionKind.READ.name }.sumOf { it.durationMs },
            listenMs = list.filter { it.kind == SessionKind.LISTEN.name }.sumOf { it.durationMs },
            sessions = list.size,
            firstAt = list.firstOrNull()?.startedAt,
            lastAt = list.lastOrNull()?.startedAt,
        )
    }

    /** Dias seguidos con actividad ([STREAK_MIN_MS] como minimo), contando desde hoy o desde ayer. */
    internal fun streak(today: Long, recent: List<ReadingSessionEntity>): Int {
        val perDay = HashMap<Long, Long>()
        for (s in recent) {
            // Una sesion que cruza la medianoche reparte su duracion entre los dias que toca.
            var day = dayStart(s.startedAt)
            while (true) {
                val next = addDays(day, 1)
                val part = overlap(s, day, next)
                if (part > 0) perDay.merge(day, part, Long::plus)
                if (next >= s.startedAt + s.durationMs) break
                day = next
            }
        }
        fun active(day: Long) = (perDay[day] ?: 0L) >= STREAK_MIN_MS
        var day = if (active(today)) today else addDays(today, -1)
        var n = 0
        while (n < STREAK_LOOKBACK_DAYS && active(day)) {
            n++
            day = addDays(day, -1)
        }
        return n
    }

    /** Siete dias de calendario (con cambio de hora: dias de 23 o 25 h). */
    private fun days(today: Long, recent: List<ReadingSessionEntity>): List<DayStats> =
        (-6..0).map { i ->
            val start = addDays(today, i)
            val end = addDays(today, i + 1)
            DayStats(
                start,
                recent.filter { it.kind == SessionKind.READ.name }.sumOf { overlap(it, start, end) },
                recent.filter { it.kind == SessionKind.LISTEN.name }.sumOf { overlap(it, start, end) },
            )
        }

    /** Milisegundos de la sesion [s] que caen en [start, end) (una sesion que cruza la medianoche cuenta en ambos dias). */
    private fun overlap(s: ReadingSessionEntity, start: Long, end: Long): Long =
        (minOf(end, s.startedAt + s.durationMs) - maxOf(start, s.startedAt)).coerceAtLeast(0L)

    private fun dayStart(t: Long): Long = Calendar.getInstance(zone()).run {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    /** Inicio del dia [n] dias despues de [dayStart] en la zona local. */
    internal fun addDays(dayStart: Long, n: Int): Long = Calendar.getInstance(zone()).run {
        timeInMillis = dayStart
        add(Calendar.DAY_OF_MONTH, n)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    override suspend fun record(bookId: Long, kind: SessionKind, startedAt: Long, durationMs: Long, chars: Long) {
        if (durationMs < ReadingStatsRepository.MIN_SESSION_MS) return
        // Un salto (deslizador, indice) no es lectura: sin tope, 5 min de lectura con un salto de 200 000
        // caracteres daban un "ritmo de lectura" de decenas de miles de car/min. Se acota a un ritmo
        // imposible de superar leyendo o escuchando (150 car/s = 9 000 car/min, ~1 500 palabras/min).
        val plausible = durationMs * MAX_CHARS_PER_SEC / 1000
        sessions.insert(ReadingSessionEntity(bookId = bookId, kind = kind.name, startedAt = startedAt, durationMs = durationMs, chars = chars.coerceIn(0, plausible)))
    }

    override suspend fun clear() = sessions.clear()

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val STREAK_LOOKBACK_DAYS = 120
        const val STREAK_MIN_MS = 60_000L
        const val MAX_CHARS_PER_SEC = 150L
    }
}
