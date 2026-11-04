package com.lectorvoz.app.ui.history

import androidx.compose.foundation.layout.Box
import com.lectorvoz.app.ui.components.LoadingHint
import androidx.compose.foundation.ExperimentalFoundationApi
import com.lectorvoz.app.history.HistoryRecorder
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import com.lectorvoz.app.ui.components.SwitchRow
import androidx.compose.foundation.clickable
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
    private val settings: SettingsRepository,
    private val recorder: HistoryRecorder? = null,
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

    /** Cambia los ajustes del historial (guardar, que mostrar) desde esta misma pantalla. */
    fun app(t: (AppPrefs) -> AppPrefs) = viewModelScope.launch { settings.updateApp(t) }

    /** Borra todas las lecturas anotadas (los libros y su progreso no cambian). */
    fun clearHistory() = viewModelScope.launch { history.clear() }

    /** Lecturas con las que se puede unir [e] (otra edicion del mismo libro). */
    suspend fun mergeOptions(e: HistoryEntry): List<HistoryEntry> = recorder?.mergeOptions(e).orEmpty()

    fun merge(e: HistoryEntry, into: HistoryEntry) { recorder?.merge(e, into) }
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
    HistoryContent(s, covers, onBack, onOpen, vm::delete, mergeOptions = vm::mergeOptions, onMerge = vm::merge, onApp = vm::app, onClearHistory = vm::clearHistory)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryContent(
    s: HistoryUiState,
    covers: CoverLoader,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
    mergeOptions: suspend (HistoryEntry) -> List<HistoryEntry> = { emptyList() },
    onMerge: (HistoryEntry, HistoryEntry) -> Unit = { _, _ -> },
    onApp: ((AppPrefs) -> AppPrefs) -> Unit = {},
    onClearHistory: () -> Unit = {},
) {
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    var menu by remember { mutableStateOf<HistoryEntry?>(null) }
    var confirm by remember { mutableStateOf<HistoryEntry?>(null) }
    var merging by remember { mutableStateOf<Pair<HistoryEntry, List<HistoryEntry>>?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Historial de lecturas") }, navigationIcon = { AppIconButton(R.drawable.ic_arrow_back, "Volver", onBack) }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Desplazable: con letra grande los chips no caben en una fila.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                    "El historial esta desactivado (ajustes aqui arriba). Lo anotado antes se conserva.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (s.loading) {
                Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                    LoadingHint("Cargando el historial...")
                }
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
                // Ajustes del historial (ARRIBA del listado): plegables para no tapar las lecturas.
                item(key = "historial-ajustes") {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .clickable(role = Role.Button) { showSettings = !showSettings }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("historial-ajustes-cabecera"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Ajustes del historial", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        AppIconButton(
                            if (showSettings) R.drawable.ic_keyboard_arrow_up else R.drawable.ic_keyboard_arrow_down,
                            if (showSettings) "Ocultar los ajustes" else "Mostrar los ajustes",
                            { showSettings = !showSettings },
                        )
                    }
                    if (showSettings) {
                        SwitchRow(
                            "Guardar el historial", s.prefs.historyEnabled, { v -> onApp { it.copy(historyEnabled = v) } },
                            summary = "Titulo, portada y fechas de cada libro, aunque lo quites de la biblioteca", tag = "app-historyEnabled",
                        )
                        SwitchRow("Mostrar portada", s.prefs.historyShowCover, { v -> onApp { it.copy(historyShowCover = v) } }, tag = "app-historyShowCover")
                        SwitchRow("Mostrar autor", s.prefs.historyShowAuthor, { v -> onApp { it.copy(historyShowAuthor = v) } }, tag = "app-historyShowAuthor")
                        SwitchRow("Mostrar fecha de inicio", s.prefs.historyShowStarted, { v -> onApp { it.copy(historyShowStarted = v) } }, tag = "app-historyShowStarted")
                        SwitchRow("Mostrar fecha de fin", s.prefs.historyShowFinished, { v -> onApp { it.copy(historyShowFinished = v) } }, tag = "app-historyShowFinished")
                        SwitchRow("Mostrar progreso", s.prefs.historyShowProgress, { v -> onApp { it.copy(historyShowProgress = v) } }, tag = "app-historyShowProgress")
                        TextButton(onClick = { confirmClear = true }, modifier = Modifier.padding(horizontal = 8.dp).testTag("app-historial-borrar")) { Text("Borrar el historial") }
                    }
                    HorizontalDivider()
                }
                items(shown, key = { it.id }) { e ->
                    val details = historyDetails(e, s.prefs)
                    Row(
                        Modifier.fillMaxWidth()
                            // Libro ya quitado: el toque abre las opciones (antes no hacia nada y TalkBack lo ofrecia).
                            .combinedClickable(
                                onClickLabel = if (e.bookId != null) "Abrir" else "Opciones",
                                onLongClickLabel = "Opciones",
                                onClick = { val id = e.bookId; if (id != null) onOpen(id) else menu = e },
                                onLongClick = { menu = e },
                            )
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
    menu?.let { e ->
        AlertDialog(
            onDismissRequest = { menu = null },
            title = { Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    TextButton(
                        onClick = { menu = null; scope.launch { merging = e to mergeOptions(e) } },
                        modifier = Modifier.fillMaxWidth().testTag("historial-unir"),
                    ) { Text("Es otra edicion de otra lectura: unir") }
                    TextButton(onClick = { menu = null; confirm = e }, modifier = Modifier.fillMaxWidth().testTag("historial-quitar")) { Text("Quitar del historial") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { menu = null }) { Text("Cancelar") } },
        )
    }
    merging?.let { (e, options) ->
        EditionChooser(
            title = "Unir con otra lectura",
            message = if (options.isEmpty()) "No hay otra lectura con la que unirla (solo se unen lecturas de libros que ya no estan en la biblioteca)."
            else "Elige la lectura de la que \"${e.title}\" es otra edicion. Quedara una sola, con la fecha de inicio mas antigua.",
            options = options,
            covers = covers,
            confirmLabel = "Unir",
            dismissLabel = "Cancelar",
            onChoose = { into -> merging = null; if (into != null) onMerge(e, into) },
        )
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
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Borrar el historial") },
            text = { Text("Se borraran todas las lecturas anotadas. Los libros y su progreso no cambian.") },
            confirmButton = { TextButton(onClick = { onClearHistory(); confirmClear = false }) { Text("Borrar") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
        )
    }
}

/**
 * Propuesta al abrir un libro nuevo: "es otra edicion de...?". Nada se une sin que el usuario
 * lo elija; "No, es otro libro" lo deja como lectura aparte.
 */
@Composable
fun EditionSuggestionDialog(recorder: HistoryRecorder, covers: CoverLoader) {
    val s by recorder.suggestion.collectAsStateWithLifecycle()
    val sug = s ?: return
    EditionChooser(
        title = "Otra edicion?",
        message = "\"${sug.entry.title}\" se parece a una lectura de tu historial. Si es el mismo libro en otra edicion, eligela para unirlas.",
        options = sug.candidates,
        covers = covers,
        confirmLabel = "Unir",
        dismissLabel = "No, es otro libro",
        onChoose = recorder::answer,
    )
}

/** Dialogo para elegir una lectura de [options] (o ninguna). */
@Composable
private fun EditionChooser(
    title: String,
    message: String,
    options: List<HistoryEntry>,
    covers: CoverLoader,
    confirmLabel: String,
    dismissLabel: String,
    onChoose: (HistoryEntry?) -> Unit,
) {
    var chosen by remember(options) { mutableStateOf(options.firstOrNull()) }
    AlertDialog(
        onDismissRequest = { onChoose(null) },
        title = { Text(title) },
        text = {
            Column {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                LazyColumn(Modifier.heightIn(max = 320.dp).padding(top = 8.dp)) {
                    items(options, key = { it.id }) { o ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = chosen == o, onClick = { chosen = o }, role = Role.RadioButton)
                                .padding(vertical = 6.dp)
                                .testTag("edicion-${o.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = chosen == o, onClick = null)
                            BookCover(o.coverPath, o.title, covers, 32.dp, Modifier.padding(start = 8.dp).width(32.dp).aspectRatio(2f / 3f))
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(o.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val sub = listOfNotNull(o.author, "Empezado: ${date(o.startedAt)}", o.finishedAt?.let { "Terminado: ${date(it)}" })
                                Text(sub.joinToString(" - "), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (options.isNotEmpty()) {
                TextButton(onClick = { onChoose(chosen) }, enabled = chosen != null, modifier = Modifier.testTag("edicion-unir")) { Text(confirmLabel) }
            }
        },
        dismissButton = { TextButton(onClick = { onChoose(null) }, modifier = Modifier.testTag("edicion-no")) { Text(dismissLabel) } },
    )
}
