package com.lectorvoz.app.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lectorvoz.app.R
import com.lectorvoz.app.books.CoverLoader
import com.lectorvoz.app.ui.components.AppIconButton
import com.lectorvoz.app.ui.components.BookCover
import com.lectorvoz.core.data.history.HistoryEntry
import com.lectorvoz.core.data.history.ReadingHistoryRepository
import com.lectorvoz.core.data.library.LibraryRepository
import com.lectorvoz.core.data.settings.AppPrefs
import com.lectorvoz.core.data.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Filtro del historial. */
enum class HistoryFilter(val label: String) { ALL("Todas"), FINISHED("Terminadas"), READING("En curso") }

/** Lo que pinta la pantalla del historial. */
@Immutable
data class HistoryUiState(
    val entries: List<HistoryEntry> = emptyList(),
    val prefs: AppPrefs = AppPrefs(),
    val loading: Boolean = true,
)

/**
 * Historial de lecturas. El progreso de los libros que siguen en la biblioteca se toma en vivo
 * de ella; el de los quitados, de la propia entrada.
 */
class HistoryViewModel(
    private val history: ReadingHistoryRepository,
    library: LibraryRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<HistoryUiState> = combine(history.entries, library.books, settings.app) { entries, books, prefs ->
        val live = books.associateBy { it.id }
        HistoryUiState(
            entries = entries.map { e ->
                val b = e.bookId?.let(live::get) ?: return@map e
                if (e.finished) e else e.copy(progress = b.progress)
            },
            prefs = prefs,
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun delete(e: HistoryEntry) = viewModelScope.launch { history.delete(e.id) }
}

private val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.forLanguageTag("es-ES"))

private fun date(ms: Long): String = synchronized(dateFormat) { dateFormat.format(Date(ms)) }

/** Lineas de detalle de [e] segun lo que se quiere ver. */
internal fun historyDetails(e: HistoryEntry, p: AppPrefs): List<String> = buildList {
    if (p.historyShowStarted) add("Empezado: ${date(e.startedAt)}")
    if (p.historyShowFinished && e.finishedAt != null) add("Terminado: ${date(e.finishedAt!!)}")
    if (p.historyShowProgress && !e.finished) add("Leido: ${(e.progress.coerceIn(0f, 1f) * 100).roundToInt()} %")
    if (e.bookId == null) add("Ya no esta en la biblioteca")
}

/** Pantalla del historial de lecturas. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(vm: HistoryViewModel, covers: CoverLoader, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    HistoryContent(s, covers, onBack, onOpen, vm::delete)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryContent(s: HistoryUiState, covers: CoverLoader, onBack: () -> Unit, onOpen: (Long) -> Unit, onDelete: (HistoryEntry) -> Unit) {
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    var confirm by remember { mutableStateOf<HistoryEntry?>(null) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Historial de lecturas") }, navigationIcon = { AppIconButton(R.drawable.ic_arrow_back, "Volver", onBack) }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryFilter.entries.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }, modifier = Modifier.testTag("historial-filtro-${f.name}"))
                }
            }
            val shown = s.entries.filter {
                when (filter) {
                    HistoryFilter.ALL -> true
                    HistoryFilter.FINISHED -> it.finished
                    HistoryFilter.READING -> !it.finished
                }
            }
            if (!s.prefs.historyEnabled) {
                Text(
                    "El historial esta desactivado (Ajustes > Lectura). Lo anotado antes se conserva.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (!s.loading && shown.isEmpty()) {
                Text(
                    if (s.entries.isEmpty()) "Aun no hay lecturas. Cada libro que abras aparecera aqui, con la fecha en que lo empezaste y la de cuando lo terminaste."
                    else "No hay lecturas con este filtro.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(24.dp).testTag("historial-vacio"),
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize().testTag("historial-lista")) {
                items(shown, key = { it.id }) { e ->
                    val details = historyDetails(e, s.prefs)
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(onClick = { e.bookId?.let(onOpen) }, onLongClick = { confirm = e })
                            .heightIn(min = 56.dp)
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .testTag("historial-${e.id}")
                            .semantics(mergeDescendants = true) {
                                contentDescription = listOfNotNull(e.title, e.author.takeIf { s.prefs.historyShowAuthor }).plus(details).joinToString(". ")
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (s.prefs.historyShowCover) {
                            BookCover(e.coverPath, e.title, covers, 48.dp, Modifier.width(48.dp).aspectRatio(2f / 3f))
                        }
                        Column(Modifier.weight(1f).padding(start = if (s.prefs.historyShowCover) 16.dp else 0.dp)) {
                            Text(e.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (s.prefs.historyShowAuthor) e.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            details.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            if (s.prefs.historyShowProgress && !e.finished) {
                                LinearProgressIndicator(progress = { e.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    confirm?.let { e ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Quitar del historial") },
            text = { Text("Se quitara \"${e.title}\" del historial. El libro de la biblioteca no cambia.") },
            confirmButton = { TextButton(onClick = { onDelete(e); confirm = null }) { Text("Quitar") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancelar") } },
        )
    }
}
