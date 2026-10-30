package com.lectorvoz.app.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lectorvoz.app.R
import com.lectorvoz.app.books.CoverLoader
import com.lectorvoz.app.ui.components.AppIcon
import com.lectorvoz.app.ui.components.AppIconButton
import com.lectorvoz.app.ui.components.BookCover
import com.lectorvoz.core.data.collections.BookCollection
import com.lectorvoz.core.data.library.Book
import com.lectorvoz.core.data.settings.LibrarySort
import com.lectorvoz.core.model.BookFormat
import kotlin.math.roundToInt

/** Acciones de la biblioteca (la pantalla no conoce el ViewModel: facil de probar). */
interface LibraryActions {
    fun open(book: Book)
    fun import()
    fun settings()
    fun query(q: String)
    fun filter(f: LibraryFilter)
    fun sort(s: LibrarySort)
    fun grid(grid: Boolean)
    fun favorite(book: Book)
    fun finished(book: Book, finished: Boolean)
    fun delete(book: Book)
    fun createCollection(name: String, series: Boolean, addBook: Book?)
    fun deleteCollection(c: BookCollection)
    fun addTo(c: BookCollection, book: Book)
    fun removeFrom(c: BookCollection, book: Book)
    fun move(c: BookCollection, book: Book, delta: Int)
    fun messageShown()
}

/** Ruta de la biblioteca: une ViewModel, selector de ficheros y pantalla. */
@Composable
fun LibraryRoute(vm: LibraryViewModel, covers: CoverLoader, onOpen: (Book) -> Unit, onSettings: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        vm.import(uris)
    }
    val actions = remember(vm) {
        object : LibraryActions {
            override fun open(book: Book) = onOpen(book)
            override fun import() = picker.launch(arrayOf("application/epub+zip", "application/pdf", "application/octet-stream"))
            override fun settings() = onSettings()
            override fun query(q: String) = vm.setQuery(q)
            override fun filter(f: LibraryFilter) = vm.setFilter(f)
            override fun sort(s: LibrarySort) { vm.setSort(s) }
            override fun grid(grid: Boolean) { vm.setGrid(grid) }
            override fun favorite(book: Book) { vm.toggleFavorite(book) }
            override fun finished(book: Book, finished: Boolean) { vm.setFinished(book, finished) }
            override fun delete(book: Book) { vm.delete(book) }
            override fun createCollection(name: String, series: Boolean, addBook: Book?) { vm.createCollection(name, series, addBook) }
            override fun deleteCollection(c: BookCollection) { vm.deleteCollection(c) }
            override fun addTo(c: BookCollection, book: Book) { vm.addToCollection(c, book) }
            override fun removeFrom(c: BookCollection, book: Book) { vm.removeFromCollection(c, book) }
            override fun move(c: BookCollection, book: Book, delta: Int) { vm.moveInCollection(c, book, delta) }
            override fun messageShown() = vm.consumeMessage()
        }
    }
    LibraryScreen(state, covers, actions)
}

/** Pantalla de biblioteca. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(state: LibraryUiState, covers: CoverLoader, actions: LibraryActions) {
    val snackbar = remember { SnackbarHostState() }
    var searching by rememberSaveable { mutableStateOf(state.query.isNotEmpty()) }
    var menuBook by remember { mutableStateOf<Book?>(null) }
    var confirmDelete by remember { mutableStateOf<Book?>(null) }
    var newCollectionFor by remember { mutableStateOf<Book?>(null) }
    var newCollection by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        val m = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(m)
        actions.messageShown()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = actions::query,
                            singleLine = true,
                            placeholder = { Text("Buscar titulo o autor") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            modifier = Modifier.fillMaxWidth().testTag("biblioteca-buscar"),
                        )
                    } else {
                        Text("Biblioteca")
                    }
                },
                actions = {
                    if (searching) {
                        AppIconButton(R.drawable.ic_close, "Cerrar busqueda", { searching = false; actions.query("") })
                    } else {
                        AppIconButton(R.drawable.ic_search, "Buscar", { searching = true })
                    }
                    SortMenu(state.sort, actions::sort)
                    AppIconButton(
                        if (state.grid) R.drawable.ic_view_list else R.drawable.ic_grid_view,
                        if (state.grid) "Ver como lista" else "Ver como cuadricula",
                        { actions.grid(!state.grid) },
                    )
                    AppIconButton(R.drawable.ic_settings, "Ajustes", actions::settings)
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = actions::import,
                icon = { AppIcon(R.drawable.ic_add, null) },
                text = { Text("Importar") },
                modifier = Modifier.testTag("boton-importar"),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.importing > 0) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("importando").semantics { contentDescription = "Importando ${state.importing} libros" })
            }
            if (!state.loading && state.totalCount == 0) {
                EmptyLibrary(actions::import)
                return@Column
            }
            FilterRow(state, actions, onNewCollection = { newCollection = true })
            val header: (@Composable () -> Unit)? = state.continueReading
                ?.takeIf { state.query.isEmpty() && state.filter == LibraryFilter.All }
                ?.let { b -> { ContinueCard(b, covers) { actions.open(b) } } }
            if (state.books.isEmpty()) {
                Text(
                    "Ningun libro coincide con la busqueda o el filtro.",
                    modifier = Modifier.padding(24.dp).testTag("sin-resultados"),
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else if (state.grid) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(120.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize().testTag("biblioteca-lista"),
                ) {
                    if (header != null) item(key = "seguir", span = { GridItemSpan(maxLineSpan) }) { header() }
                    items(state.books, key = { it.id }, contentType = { "libro" }) { b ->
                        GridBook(b, covers, onClick = { actions.open(b) }, onLong = { menuBook = b })
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 96.dp),
                    modifier = Modifier.fillMaxSize().testTag("biblioteca-lista"),
                ) {
                    if (header != null) item(key = "seguir") { header() }
                    items(state.books, key = { it.id }, contentType = { "libro" }) { b ->
                        ListBook(b, covers, onClick = { actions.open(b) }, onLong = { menuBook = b })
                    }
                }
            }
        }
    }

    menuBook?.let { b ->
        BookMenuSheet(
            book = b,
            state = state,
            actions = actions,
            onDismiss = { menuBook = null },
            onDelete = { menuBook = null; confirmDelete = b },
            onNewCollection = { menuBook = null; newCollectionFor = b; newCollection = true },
        )
    }
    confirmDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Quitar de la biblioteca") },
            text = { Text("Se quitara \"${b.title}\" con su progreso y marcadores. El fichero original no se borra.") },
            confirmButton = { TextButton(onClick = { actions.delete(b); confirmDelete = null }) { Text("Quitar") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancelar") } },
        )
    }
    if (newCollection) {
        NewCollectionDialog(
            onDismiss = { newCollection = false; newCollectionFor = null },
            onCreate = { name, series -> actions.createCollection(name, series, newCollectionFor); newCollection = false; newCollectionFor = null },
        )
    }
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp).testTag("biblioteca-vacia"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppIcon(R.drawable.ic_menu_book, null, Modifier.size(72.dp))
        Spacer(Modifier.height(16.dp))
        Text("Tu biblioteca esta vacia", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(8.dp))
        Text(
            "Importa libros EPUB o PDF desde el movil. Tambien puedes abrirlos desde otra app con \"Abrir con\" o \"Compartir\".",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onImport, modifier = Modifier.heightIn(min = 48.dp)) {
            AppIcon(R.drawable.ic_add, null)
            Spacer(Modifier.width(8.dp))
            Text("Importar libros")
        }
    }
}

@Composable
private fun SortMenu(sort: LibrarySort, onSort: (LibrarySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AppIconButton(R.drawable.ic_sort, "Ordenar", { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LibrarySort.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(sortLabel(s)) },
                    leadingIcon = { if (s == sort) AppIcon(R.drawable.ic_check, "Seleccionado") },
                    onClick = { open = false; onSort(s) },
                )
            }
        }
    }
}

fun sortLabel(s: LibrarySort) = when (s) {
    LibrarySort.RECENT -> "Leidos recientemente"
    LibrarySort.TITLE -> "Titulo"
    LibrarySort.AUTHOR -> "Autor"
    LibrarySort.ADDED -> "Anadidos recientemente"
    LibrarySort.PROGRESS -> "Progreso"
}

@Composable
private fun FilterRow(state: LibraryUiState, actions: LibraryActions, onNewCollection: () -> Unit) {
    val fixed = listOf(
        LibraryFilter.All to "Todos", LibraryFilter.Unfinished to "Sin terminar", LibraryFilter.Finished to "Terminados",
        LibraryFilter.Favorites to "Favoritos", LibraryFilter.Epub to "EPUB", LibraryFilter.Pdf to "PDF",
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().testTag("filtros"),
    ) {
        items(fixed, key = { it.second }) { (f, label) ->
            FilterChip(selected = state.filter == f, onClick = { actions.filter(f) }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
        }
        items(state.collections, key = { "c${it.id}" }) { c ->
            val f = LibraryFilter.Collection(c.id)
            FilterChip(
                selected = state.filter == f,
                onClick = { actions.filter(f) },
                label = { Text(if (c.isSeries) "Serie: ${c.name}" else c.name) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
        item(key = "nueva") {
            TextButton(onClick = onNewCollection, modifier = Modifier.heightIn(min = 48.dp)) { Text("Nueva coleccion") }
        }
    }
    state.currentCollection?.let { c ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (c.isSeries) "Serie en orden de lectura" else "Coleccion",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { actions.deleteCollection(c) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Eliminar coleccion") }
        }
    }
}

private fun describe(b: Book): String = buildString {
    append(b.title)
    b.author?.let { append(", de ").append(it) }
    append(", ").append(if (b.format == BookFormat.EPUB) "EPUB" else "PDF")
    if (b.finished) append(", terminado") else if (b.progress > 0f) append(", leido el ${(b.progress * 100).roundToInt()} por ciento")
    if (b.favorite) append(", favorito")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridBook(b: Book, covers: CoverLoader, onClick: () -> Unit, onLong: () -> Unit) {
    Column(
        Modifier.testTag("libro-${b.id}")
            .combinedClickable(onClick = onClick, onLongClick = onLong, onClickLabel = "Abrir", onLongClickLabel = "Opciones")
            .semantics(mergeDescendants = true) { contentDescription = describe(b) },
    ) {
        BookCover(b.coverPath, b.title, covers, 120.dp, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
        if (b.progress > 0f) {
            LinearProgressIndicator(progress = { b.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        Text(b.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        b.author?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListBook(b: Book, covers: CoverLoader, onClick: () -> Unit, onLong: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().testTag("libro-${b.id}")
            .combinedClickable(onClick = onClick, onLongClick = onLong, onClickLabel = "Abrir", onLongClickLabel = "Opciones")
            .semantics(mergeDescendants = true) { contentDescription = describe(b) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(b.coverPath, b.title, covers, 56.dp, Modifier.width(56.dp).aspectRatio(2f / 3f))
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(b.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            b.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            Text(
                (if (b.format == BookFormat.EPUB) "EPUB" else "PDF") +
                    if (b.finished) " - terminado" else " - ${(b.progress * 100).roundToInt()} %",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ContinueCard(b: Book, covers: CoverLoader, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp).testTag("seguir-leyendo")
            .semantics(mergeDescendants = true) { contentDescription = "Seguir leyendo: " + describe(b) },
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(b.coverPath, b.title, covers, 64.dp, Modifier.width(64.dp).aspectRatio(2f / 3f))
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text("Seguir leyendo", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(b.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(progress = { b.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Text("${(b.progress * 100).roundToInt()} % leido", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookMenuSheet(
    book: Book,
    state: LibraryUiState,
    actions: LibraryActions,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onNewCollection: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            SheetItem("Abrir") { onDismiss(); actions.open(book) }
            SheetItem(if (book.favorite) "Quitar de favoritos" else "Anadir a favoritos") { actions.favorite(book); onDismiss() }
            SheetItem(if (book.finished) "Marcar como no terminado" else "Marcar como terminado") { actions.finished(book, !book.finished); onDismiss() }
            state.collections.forEach { c ->
                val inside = book.id in c.bookIds
                SheetItem(if (inside) "Quitar de \"${c.name}\"" else "Anadir a \"${c.name}\"") {
                    if (inside) actions.removeFrom(c, book) else actions.addTo(c, book)
                    onDismiss()
                }
            }
            state.currentCollection?.takeIf { it.isSeries }?.let { c ->
                SheetItem("Subir en la serie") { actions.move(c, book, -1); onDismiss() }
                SheetItem("Bajar en la serie") { actions.move(c, book, +1); onDismiss() }
            }
            SheetItem("Nueva coleccion o serie con este libro", onNewCollection)
            SheetItem("Quitar de la biblioteca", onDelete)
        }
    }
}

@Composable
private fun SheetItem(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp)) {
        Text(text, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun NewCollectionDialog(onDismiss: () -> Unit, onCreate: (String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var series by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nueva coleccion") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") }, singleLine = true)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(value = series, role = Role.Checkbox, onValueChange = { series = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = series, onCheckedChange = null)
                    Text("Es una serie (orden de lectura y \"continuar con el siguiente\")", modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name, series) }, enabled = name.isNotBlank()) { Text("Crear") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
