package com.lectorvoz.app.ui.library

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lectorvoz.core.data.collections.BookCollection
import com.lectorvoz.core.data.collections.CollectionRepository
import com.lectorvoz.core.data.library.Book
import com.lectorvoz.core.data.library.ImportError
import com.lectorvoz.core.data.library.ImportResult
import com.lectorvoz.core.data.library.LibraryRepository
import com.lectorvoz.core.data.settings.AppPrefs
import com.lectorvoz.core.data.settings.LibrarySort
import com.lectorvoz.core.data.settings.SettingsRepository
import com.lectorvoz.core.model.BookFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Normalizer

/** Filtro de la biblioteca. */
sealed interface LibraryFilter {
    data object All : LibraryFilter
    data object Epub : LibraryFilter
    data object Pdf : LibraryFilter
    data object Favorites : LibraryFilter
    data object Unfinished : LibraryFilter
    data object Finished : LibraryFilter
    data class Collection(val id: Long) : LibraryFilter
}

/** Estado de la biblioteca (inmutable). */
@Immutable
data class LibraryUiState(
    val loading: Boolean = true,
    val books: List<Book> = emptyList(),
    val totalCount: Int = 0,
    val continueReading: Book? = null,
    val query: String = "",
    val sort: LibrarySort = LibrarySort.RECENT,
    val grid: Boolean = true,
    val filter: LibraryFilter = LibraryFilter.All,
    val collections: List<BookCollection> = emptyList(),
    val importing: Int = 0,
    val message: String? = null,
) {
    /** Coleccion del filtro actual, si es de una coleccion. */
    val currentCollection: BookCollection?
        get() = (filter as? LibraryFilter.Collection)?.let { f -> collections.firstOrNull { it.id == f.id } }
}

private data class LocalState(
    val query: String = "",
    val filter: LibraryFilter = LibraryFilter.All,
    val importing: Int = 0,
    val message: String? = null,
)

/** Biblioteca: lista filtrada y ordenada, importacion y gestion de libros y colecciones. */
class LibraryViewModel(
    private val library: LibraryRepository,
    private val collections: CollectionRepository,
    private val settings: SettingsRepository,
    /** Borrado completo (parar la voz si lee este libro, datos y ajustes); por defecto solo datos. */
    private val onDelete: (suspend (Book) -> Unit)? = null,
) : ViewModel() {
    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<LibraryUiState> = combine(
        library.books,
        library.continueReading,
        collections.collections,
        settings.app,
        local,
    ) { books, cont, cols, prefs, l -> build(books, cont, cols, prefs, l) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    private fun build(books: List<Book>, cont: Book?, cols: List<BookCollection>, prefs: AppPrefs, l: LocalState) =
        LibraryUiState(
            loading = false,
            books = filterBooks(books, l.query, l.filter, prefs.librarySort, cols),
            totalCount = books.size,
            continueReading = cont,
            query = l.query,
            sort = prefs.librarySort,
            grid = prefs.libraryGrid,
            filter = l.filter,
            collections = cols,
            importing = l.importing,
            message = l.message,
        )

    fun setQuery(q: String) = local.update { it.copy(query = q) }

    fun setFilter(f: LibraryFilter) = local.update { it.copy(filter = f) }

    fun setSort(s: LibrarySort) = viewModelScope.launch { settings.updateApp { it.copy(librarySort = s) } }

    fun setGrid(grid: Boolean) = viewModelScope.launch { settings.updateApp { it.copy(libraryGrid = grid) } }

    fun consumeMessage() = local.update { it.copy(message = null) }

    /** Importa varios ficheros; al terminar deja un resumen en [LibraryUiState.message]. */
    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            local.update { it.copy(importing = it.importing + uris.size) }
            val copy = settings.app.first().copyOnImport
            var ok = 0
            var dup = 0
            val failed = ArrayList<String>()
            for (u in uris) {
                when (val r = library.import(u, copy)) {
                    is ImportResult.Imported -> ok++
                    is ImportResult.Duplicate -> dup++
                    is ImportResult.Failed -> failed += "${r.name}: ${errorText(r.error)}"
                }
                local.update { it.copy(importing = (it.importing - 1).coerceAtLeast(0)) }
            }
            local.update { it.copy(message = importSummary(ok, dup, failed)) }
        }
    }

    fun delete(book: Book) = viewModelScope.launch {
        onDelete?.invoke(book) ?: run {
            library.delete(book.id)
            settings.clearOverrides(book.id)
        }
        local.update { it.copy(message = "\"${book.title}\" quitado de la biblioteca. El fichero original no se ha borrado.") }
    }

    fun toggleFavorite(book: Book) = viewModelScope.launch { library.setFavorite(book.id, !book.favorite) }

    fun setFinished(book: Book, finished: Boolean) = viewModelScope.launch { library.setFinished(book.id, finished) }

    fun createCollection(name: String, series: Boolean, addBook: Book? = null) = viewModelScope.launch {
        if (name.isBlank()) return@launch
        val id = try {
            collections.create(name, series)
        } catch (e: Exception) {
            local.update { it.copy(message = "No se pudo crear la coleccion (quiza ya existe).") }
            return@launch
        }
        if (addBook != null) collections.addBook(id, addBook.id)
    }

    fun deleteCollection(c: BookCollection) = viewModelScope.launch {
        collections.delete(c.id)
        if (local.value.filter == LibraryFilter.Collection(c.id)) setFilter(LibraryFilter.All)
    }

    fun addToCollection(c: BookCollection, book: Book) = viewModelScope.launch { collections.addBook(c.id, book.id) }

    fun removeFromCollection(c: BookCollection, book: Book) = viewModelScope.launch { collections.removeBook(c.id, book.id) }

    /** Mueve [book] una posicion arriba ([delta] -1) o abajo (+1) dentro de [c]. */
    fun moveInCollection(c: BookCollection, book: Book, delta: Int) = viewModelScope.launch {
        val i = c.bookIds.indexOf(book.id)
        if (i < 0) return@launch
        collections.move(c.id, book.id, (i + delta).coerceIn(0, c.bookIds.size - 1))
    }

    companion object {
        fun errorText(e: ImportError) = when (e) {
            ImportError.UNSUPPORTED_FORMAT -> "no es un EPUB ni un PDF"
            ImportError.UNREADABLE -> "no se puede leer"
            ImportError.CORRUPT -> "esta danado o protegido"
            ImportError.NO_SPACE -> "no hay espacio suficiente"
        }

        fun importSummary(ok: Int, dup: Int, failed: List<String>): String = buildString {
            if (ok > 0) append(if (ok == 1) "1 libro importado" else "$ok libros importados")
            if (dup > 0) {
                if (isNotEmpty()) append(". ")
                append(if (dup == 1) "1 ya estaba en la biblioteca" else "$dup ya estaban en la biblioteca")
            }
            if (failed.isNotEmpty()) {
                if (isNotEmpty()) append(". ")
                append("No se pudo importar: ").append(failed.joinToString("; "))
            }
            if (isEmpty()) append("Nada que importar")
        }

        /** Quita tildes y pasa a minusculas para buscar. */
        fun fold(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

        /** Filtra y ordena (funcion pura, probada aparte). */
        fun filterBooks(books: List<Book>, query: String, filter: LibraryFilter, sort: LibrarySort, cols: List<BookCollection>): List<Book> {
            val q = fold(query.trim())
            val col = (filter as? LibraryFilter.Collection)?.let { f -> cols.firstOrNull { it.id == f.id } }
            val filtered = books.filter { b ->
                val passFilter = when (filter) {
                    LibraryFilter.All -> true
                    LibraryFilter.Epub -> b.format == BookFormat.EPUB
                    LibraryFilter.Pdf -> b.format == BookFormat.PDF
                    LibraryFilter.Favorites -> b.favorite
                    LibraryFilter.Unfinished -> !b.finished
                    LibraryFilter.Finished -> b.finished
                    is LibraryFilter.Collection -> col?.bookIds?.contains(b.id) == true
                }
                passFilter && (q.isEmpty() || fold(b.title).contains(q) || b.authors.any { fold(it).contains(q) })
            }
            // Dentro de una coleccion manda su orden (series).
            if (col != null) return filtered.sortedBy { col.bookIds.indexOf(it.id) }
            return when (sort) {
                LibrarySort.RECENT -> filtered.sortedWith(compareByDescending<Book> { it.lastOpenedAt ?: 0L }.thenByDescending { it.addedAt })
                LibrarySort.TITLE -> filtered.sortedBy { fold(it.title) }
                LibrarySort.AUTHOR -> filtered.sortedWith(compareBy<Book> { fold(it.author ?: "￿") }.thenBy { fold(it.title) })
                LibrarySort.ADDED -> filtered.sortedByDescending { it.addedAt }
                LibrarySort.PROGRESS -> filtered.sortedByDescending { it.progress }
            }
        }
    }
}
