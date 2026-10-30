package com.lectorvoz.app.fakes

import android.net.Uri
import com.lectorvoz.app.integration.BookForVoice
import com.lectorvoz.app.integration.ExportOutcome
import com.lectorvoz.app.integration.PlaybackPort
import com.lectorvoz.app.integration.SpeechSource
import com.lectorvoz.app.integration.TimerChoice
import com.lectorvoz.app.integration.VoiceOption
import com.lectorvoz.app.integration.VoiceUiState
import com.lectorvoz.app.integration.VoicesPort
import com.lectorvoz.core.data.bookmarks.Bookmark
import com.lectorvoz.core.data.bookmarks.BookmarkRepository
import com.lectorvoz.core.data.collections.BookCollection
import com.lectorvoz.core.data.pronunciation.PronunciationEntry
import com.lectorvoz.core.data.pronunciation.PronunciationRepository
import com.lectorvoz.core.data.stats.ReadingStatsRepository
import com.lectorvoz.core.data.stats.SessionKind
import com.lectorvoz.core.data.stats.StatsSummary
import com.lectorvoz.core.data.collections.CollectionRepository
import com.lectorvoz.core.data.library.Book
import com.lectorvoz.core.data.library.ImportError
import com.lectorvoz.core.data.library.ImportResult
import com.lectorvoz.core.data.library.LibraryRepository
import com.lectorvoz.core.data.settings.AppPrefs
import com.lectorvoz.core.data.settings.BookOverrides
import com.lectorvoz.core.data.settings.SettingsRanges
import com.lectorvoz.core.data.settings.SettingsRepository
import com.lectorvoz.core.model.BookFormat
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.PlaybackEvent
import com.lectorvoz.core.model.ReaderSettings
import com.lectorvoz.core.model.TtsEngineType
import com.lectorvoz.core.model.VoiceSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Libro de prueba. */
fun book(id: Long, title: String = "Libro $id", author: String? = null, format: BookFormat = BookFormat.EPUB, progress: Float = 0f, opened: Long? = null, added: Long = id, favorite: Boolean = false, finished: Boolean = false) = Book(
    id = id, uri = "content://x/$id", localPath = null, format = format, title = title,
    authors = listOfNotNull(author), language = "es", coverPath = null, sizeBytes = 1, quickHash = "h$id",
    addedAt = added, lastOpenedAt = opened, progress = progress, locator = Locator.START, finished = finished, favorite = favorite,
)

class FakeSettingsRepository : SettingsRepository {
    val readerState = MutableStateFlow(ReaderSettings())
    val voiceState = MutableStateFlow(VoiceSettings())
    val appState = MutableStateFlow(AppPrefs())
    val overridesState = MutableStateFlow<Map<Long, BookOverrides>>(emptyMap())

    override val reader: Flow<ReaderSettings> = readerState
    override val voice: Flow<VoiceSettings> = voiceState
    override val app: Flow<AppPrefs> = appState
    override fun overrides(bookId: Long): Flow<BookOverrides> = overridesState.map { it[bookId] ?: BookOverrides.NONE }
    override suspend fun updateReader(transform: (ReaderSettings) -> ReaderSettings) = readerState.update { SettingsRanges.sanitize(transform(it)) }
    override suspend fun updateVoice(transform: (VoiceSettings) -> VoiceSettings) = voiceState.update { SettingsRanges.sanitize(transform(it)) }
    override suspend fun updateApp(transform: (AppPrefs) -> AppPrefs) = appState.update(transform)
    override suspend fun updateOverrides(bookId: Long, transform: (BookOverrides) -> BookOverrides) =
        overridesState.update { it + (bookId to transform(it[bookId] ?: BookOverrides.NONE)) }
}

class FakeLibraryRepository(initial: List<Book> = emptyList()) : LibraryRepository {
    val list = MutableStateFlow(initial)
    /** Resultado por Uri al importar (por defecto, un libro nuevo). */
    val results = HashMap<String, ImportResult>()
    val imported = ArrayList<Pair<Uri, Boolean>>()
    private var next = 1000L

    override val books: Flow<List<Book>> = list
    override val continueReading: Flow<Book?> = list.map { l -> l.filter { it.lastOpenedAt != null && !it.finished }.maxByOrNull { it.lastOpenedAt!! } }
    override fun observe(id: Long): Flow<Book?> = list.map { l -> l.firstOrNull { it.id == id } }
    override suspend fun get(id: Long): Book? = list.value.firstOrNull { it.id == id }
    override suspend fun import(uri: Uri, copy: Boolean): ImportResult {
        imported += uri to copy
        results[uri.toString()]?.let { return it }
        val b = book(next++, title = uri.lastPathSegment ?: "nuevo")
        list.update { it + b }
        return ImportResult.Imported(b)
    }
    override suspend fun updateProgress(id: Long, locator: Locator, progress: Float, finished: Boolean?) =
        list.update { l -> l.map { if (it.id == id) it.copy(locator = locator, progress = progress, finished = finished ?: it.finished) else it } }
    override suspend fun markOpened(id: Long) = list.update { l -> l.map { if (it.id == id) it.copy(lastOpenedAt = 99) else it } }
    override suspend fun setFavorite(id: Long, favorite: Boolean) = list.update { l -> l.map { if (it.id == id) it.copy(favorite = favorite) else it } }
    override suspend fun setFinished(id: Long, finished: Boolean) = list.update { l -> l.map { if (it.id == id) it.copy(finished = finished) else it } }
    override suspend fun rename(id: Long, title: String, authors: List<String>) = list.update { l -> l.map { if (it.id == id) it.copy(title = title, authors = authors) else it } }
    override suspend fun delete(id: Long) = list.update { l -> l.filterNot { it.id == id } }

    companion object {
        fun failed(name: String) = ImportResult.Failed(name, ImportError.UNSUPPORTED_FORMAT)
    }
}

class FakeCollectionRepository : CollectionRepository {
    val state = MutableStateFlow<List<BookCollection>>(emptyList())
    private var next = 1L
    override val collections: Flow<List<BookCollection>> = state
    override suspend fun create(name: String, isSeries: Boolean): Long {
        val id = next++
        state.update { it + BookCollection(id, name, isSeries, emptyList()) }
        return id
    }
    override suspend fun update(id: Long, name: String, isSeries: Boolean) = state.update { l -> l.map { if (it.id == id) it.copy(name = name, isSeries = isSeries) else it } }
    override suspend fun delete(id: Long) = state.update { l -> l.filterNot { it.id == id } }
    override suspend fun addBook(collectionId: Long, bookId: Long) = state.update { l -> l.map { if (it.id == collectionId && bookId !in it.bookIds) it.copy(bookIds = it.bookIds + bookId) else it } }
    override suspend fun removeBook(collectionId: Long, bookId: Long) = state.update { l -> l.map { if (it.id == collectionId) it.copy(bookIds = it.bookIds - bookId) else it } }
    override suspend fun move(collectionId: Long, bookId: Long, newIndex: Int) = state.update { l ->
        l.map { c ->
            if (c.id != collectionId) c else {
                val ids = c.bookIds.toMutableList()
                ids.remove(bookId)
                ids.add(newIndex.coerceIn(0, ids.size), bookId)
                c.copy(bookIds = ids)
            }
        }
    }
    override suspend fun nextInSeries(bookId: Long): Book? = null
}

class FakePlaybackPort : PlaybackPort {
    val mutable = MutableStateFlow(VoiceUiState())
    val calls = ArrayList<String>()
    val eventFlow = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 16)
    override val state: StateFlow<VoiceUiState> = mutable
    override val events: Flow<PlaybackEvent> = eventFlow
    val bookmarkFlow = MutableSharedFlow<Locator>(extraBufferCapacity = 8)
    override val bookmarkRequests: Flow<Locator> = bookmarkFlow
    override var currentSource: SpeechSource? = null
    val active = MutableStateFlow(VoiceSettings())
    override val activeSettings: Flow<VoiceSettings> = active
    override suspend fun start(book: BookForVoice, source: SpeechSource, start: Locator, autoPlay: Boolean) {
        calls += "start:${book.id}@${start.chapter}:${start.offset}"
        currentSource = source
        mutable.value = VoiceUiState(bookId = book.id, position = start, playWhenReady = autoPlay, isPlaying = autoPlay)
    }
    override fun play() { calls += "play" }
    override fun pause() { calls += "pause" }
    override fun stop() {
        calls += "stop"
        // El servicio limpia el estado (bookId a null) al parar.
        mutable.value = VoiceUiState()
    }
    override fun togglePlayPause() { calls += "toggle" }
    override fun nextSentence() { calls += "next" }
    override fun previousSentence() { calls += "prev" }
    override fun nextChapter() { calls += "nextChapter" }
    override fun previousChapter() { calls += "prevChapter" }
    override fun seekTo(locator: Locator) { calls += "seek:${locator.chapter}:${locator.offset}" }
    override fun setSleepTimer(choice: TimerChoice?) { calls += "timer:$choice" }
    override fun updateSettings(settings: VoiceSettings) {
        calls += "settings"
        active.value = settings
    }
    override suspend fun preview(settings: VoiceSettings, text: String): String? {
        calls += "preview:${settings.voiceId}:$text"
        return null
    }
    /** Si no es nulo, exportar espera a que se complete (para probar cancelaciones). */
    var exportGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    val exported = ArrayList<Pair<Uri, Int>>()
    override suspend fun exportChapter(uri: Uri, source: SpeechSource, chapter: Int, settings: VoiceSettings, onProgress: (Float?) -> Unit): ExportOutcome {
        exported += uri to chapter
        exportGate?.await()
        onProgress(1f)
        return ExportOutcome.Done(1000, 10)
    }
}

class FakeVoices : VoicesPort {
    override val defaultVoiceId = "supertonic-3-int8#4"
    override fun neuralVoices() = listOf(
        VoiceOption("supertonic-3-int8#4", "Mujer 5 (F5)", "Supertonic 3", TtsEngineType.NEURAL),
        VoiceOption("supertonic-3-int8#1", "Mujer 2 (F2)", "Supertonic 3", TtsEngineType.NEURAL),
    )
    override suspend fun systemVoices() = listOf(VoiceOption("es-es-sys", "Espanol", "es-es-sys", TtsEngineType.SYSTEM))
    override fun resolve(voiceId: String?) = voiceId ?: defaultVoiceId
}

class FakeBookmarks : BookmarkRepository {
    val added = ArrayList<Triple<Long, Locator, String>>()
    /** Libros que "no existen" (la insercion falla como con la clave ajena de Room). */
    val missing = HashSet<Long>()
    override fun observe(bookId: Long): Flow<List<Bookmark>> = MutableStateFlow(emptyList())
    override suspend fun add(bookId: Long, locator: Locator, excerpt: String, note: String?): Long {
        if (bookId in missing) throw IllegalStateException("FOREIGN KEY constraint failed")
        added += Triple(bookId, locator, excerpt)
        return added.size.toLong()
    }
    override suspend fun updateNote(bookmark: Bookmark, note: String?) = Unit
    override suspend fun delete(id: Long) = Unit
}

class FakePronunciation : PronunciationRepository {
    val state = MutableStateFlow<List<PronunciationEntry>>(emptyList())
    override val global: Flow<List<PronunciationEntry>> = state
    override fun forBook(bookId: Long): Flow<List<PronunciationEntry>> = state
    override fun effective(bookId: Long): Flow<List<PronunciationEntry>> = state
    override suspend fun save(entry: PronunciationEntry): Long { state.update { it + entry }; return 1 }
    override suspend fun delete(id: Long) = Unit
}

class FakeStats : ReadingStatsRepository {
    data class Rec(val bookId: Long, val kind: SessionKind, val startedAt: Long, val durationMs: Long, val chars: Long)
    val records = ArrayList<Rec>()
    override val summary: Flow<StatsSummary> = MutableStateFlow(StatsSummary())
    override suspend fun record(bookId: Long, kind: SessionKind, startedAt: Long, durationMs: Long, chars: Long) {
        records += Rec(bookId, kind, startedAt, durationMs, chars)
    }
    override suspend fun clear() = records.clear()
}
