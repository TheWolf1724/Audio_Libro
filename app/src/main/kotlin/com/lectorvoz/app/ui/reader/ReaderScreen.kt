package com.lectorvoz.app.ui.reader

import androidx.activity.compose.LocalActivity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lectorvoz.app.MainActivity
import com.lectorvoz.app.R
import com.lectorvoz.app.integration.TimerChoice
import com.lectorvoz.app.ui.components.AppIcon
import com.lectorvoz.app.ui.components.AppIconButton
import com.lectorvoz.reader.ReaderView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

/** Paneles del lector. */
enum class ReaderPanel { NONE, APPEARANCE, TOC, BOOKMARKS, SEARCH }

/** Pantalla del lector con voz integrada. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    vm: ReaderViewModel,
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit,
    onVoiceSettings: () -> Unit,
    onDictionary: (Long) -> Unit,
    onExport: (Long) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val bars by vm.barsVisible.collectAsStateWithLifecycle()
    var panel by rememberSaveable { mutableStateOf(ReaderPanel.NONE) }
    // Toques como flujo (no estado de composicion): tocar no recompone la pantalla.
    val touches = remember { MutableStateFlow(0L) }
    val snackbar = remember { SnackbarHostState() }
    val systemDark = isSystemInDarkTheme()

    // Pantalla encendida solo si se pide y con voz o toques recientes (A3).
    var idle by remember { mutableStateOf(false) }
    LaunchedEffect(touches) {
        touches.collectLatest {
            idle = false
            delay(SCREEN_IDLE_MS)
            idle = true
        }
    }
    WindowEffects(
        keepScreenOn = shouldKeepScreenOn(state.settings.keepScreenOn, state.isPlaying, if (idle) SCREEN_IDLE_MS else 0L),
        brightness = state.settings.brightness,
        immersive = state.settings.immersive && !bars,
    )
    VolumeKeys { up -> vm.volumeKey(up) }

    // Ciclo de vida: visibilidad y estadisticas.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, vm) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> vm.setVisible(true)
                Lifecycle.Event.ON_PAUSE -> vm.setVisible(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); vm.setVisible(false) }
    }

    LaunchedEffect(state.message) {
        val m = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(m)
        vm.consumeMessage()
    }

    Box(
        Modifier.fillMaxSize().background(com.lectorvoz.app.ui.theme.LocalReadingColors.current.background)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        touches.value = System.currentTimeMillis()
                    }
                }
            },
    ) {
        when {
            state.error != null -> ErrorBox(state.error!!, onBack)
            else -> ReaderView(vm.controller, Modifier.fillMaxSize().testTag("vista-lectura"))
        }
        if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center).semantics { contentDescription = "Abriendo libro" })

        AnimatedVisibility(bars, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            TopBar(state, onBack, onPanel = { panel = it }, onBookmark = vm::addBookmark, onVoiceSettings = onVoiceSettings, onBookVoice = vm::toggleBookVoice,
                onDictionary = { state.book?.let { onDictionary(it.id) } }, onExport = { state.book?.let { onExport(it.id) } })
        }
        AnimatedVisibility(bars, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            BottomBar(state, vm, onOpenBook = onOpenBook)
        }
        if (!bars && state.voiceOnThisBook) {
            // Con las barras ocultas, un boton pequeno de pausa sigue a mano.
            FilledIconButton(
                onClick = vm::togglePlay,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
            ) { AppIcon(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow, if (state.isPlaying) "Pausa" else "Reproducir") }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 120.dp))
    }

    if (panel != ReaderPanel.NONE) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = panel != ReaderPanel.APPEARANCE)
        ModalBottomSheet(onDismissRequest = { panel = ReaderPanel.NONE }, sheetState = sheet) {
            when (panel) {
                ReaderPanel.APPEARANCE -> AppearancePanel(state.settings, state.overrides, systemDark, vm::updateReader, vm::updateOverrides, onPreview = vm::previewReader)
                ReaderPanel.TOC -> TocList(state.toc, state.locator.chapter) { vm.goTo(it); panel = ReaderPanel.NONE }
                ReaderPanel.BOOKMARKS -> BookmarkList(state, onGo = { vm.goTo(it); panel = ReaderPanel.NONE }, onDelete = vm::deleteBookmark, onAdd = vm::addBookmark)
                ReaderPanel.SEARCH -> SearchPanel(state.search, vm::search, { vm.openSearchResult(it); panel = ReaderPanel.NONE }, vm::clearSearch)
                ReaderPanel.NONE -> Unit
            }
        }
    }

    state.textAction?.let { action ->
        TextActionDialog(action, vm)
    }
}

@Composable
private fun ErrorBox(message: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.heightIn(min = 16.dp))
        Button(onClick = onBack) { Text("Volver a la biblioteca") }
    }
}

@Composable
private fun TopBar(
    state: ReaderUiState,
    onBack: () -> Unit,
    onPanel: (ReaderPanel) -> Unit,
    onBookmark: () -> Unit,
    onVoiceSettings: () -> Unit,
    onBookVoice: () -> Unit,
    onDictionary: () -> Unit,
    onExport: () -> Unit,
) {
    val colors = com.lectorvoz.app.ui.theme.LocalReadingColors.current
    Surface(color = colors.barBackground, contentColor = colors.barContent, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIconButton(R.drawable.ic_arrow_back, "Volver", onBack)
            Column(Modifier.weight(1f)) {
                Text(state.book?.title.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                state.pageInfo.chapterTitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            AppIconButton(R.drawable.ic_toc, "Indice", { onPanel(ReaderPanel.TOC) })
            AppIconButton(R.drawable.ic_search, "Buscar en el libro", { onPanel(ReaderPanel.SEARCH) })
            AppIconButton(R.drawable.ic_text_fields, "Apariencia", { onPanel(ReaderPanel.APPEARANCE) })
            var menu by remember { mutableStateOf(false) }
            Box {
                AppIconButton(R.drawable.ic_more_vert, "Mas opciones", { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Anadir marcador") }, onClick = { menu = false; onBookmark() })
                    DropdownMenuItem(text = { Text("Marcadores") }, onClick = { menu = false; onPanel(ReaderPanel.BOOKMARKS) })
                    DropdownMenuItem(text = { Text("Ajustes de voz") }, onClick = { menu = false; onVoiceSettings() })
                    val ownVoice = state.overrides.voiceId != null || state.overrides.speed != null
                    DropdownMenuItem(
                        text = { Text(if (ownVoice) "Usar la voz general en este libro" else "Recordar voz y velocidad para este libro") },
                        onClick = { menu = false; onBookVoice() },
                    )
                    DropdownMenuItem(text = { Text("Pronunciacion de este libro") }, onClick = { menu = false; onDictionary() })
                    DropdownMenuItem(text = { Text("Exportar a audio") }, onClick = { menu = false; onExport() })
                }
            }
        }
    }
}

@Composable
private fun BottomBar(state: ReaderUiState, vm: ReaderViewModel, onOpenBook: (Long) -> Unit) {
    val colors = com.lectorvoz.app.ui.theme.LocalReadingColors.current
    Surface(color = colors.barBackground, contentColor = colors.barContent, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            ProgressSlider(state, vm)
            val next = state.nextInSeries
            if (next != null && (state.pageInfo.bookProgress >= 0.98f || (state.voiceOnThisBook && state.playback.ended))) {
                TextButton(onClick = { onOpenBook(next.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Continuar con el siguiente: ${next.title}")
                }
            }
            if (state.voiceOnThisBook) MiniPlayer(state, vm)
            else {
                Button(
                    onClick = { vm.listen() },
                    enabled = !state.loading && state.error == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("boton-escuchar"),
                ) {
                    AppIcon(R.drawable.ic_headphones, null)
                    Spacer(Modifier.width(12.dp))
                    Text("Escuchar", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun ProgressSlider(state: ReaderUiState, vm: ReaderViewModel) {
    val info = state.pageInfo
    var dragging by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    val pct = (info.bookProgress * 100).roundToInt()
    val gp = info.globalPage
    val pageText = if (gp != null) "Pagina ${gp + 1} de ${if (info.totalExact) "" else "unas "}${info.totalPages}" else "Pagina ${info.pageInChapter + 1} de ${info.chapterPages}"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(pageText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text("$pct %", style = MaterialTheme.typography.bodySmall)
    }
    Slider(
        value = if (dragging) drag else info.bookProgress,
        onValueChange = {
            if (!dragging) vm.prepareSeek()
            dragging = true
            drag = it
        },
        onValueChangeFinished = { vm.seekFraction(drag); dragging = false },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Posicion en el libro"; stateDescription = "$pct por ciento" },
    )
}

@Composable
private fun MiniPlayer(state: ReaderUiState, vm: ReaderViewModel) {
    val p = state.playback
    Column(Modifier.testTag("mini-reproductor")) {
        val rem = state.remaining
        Text(
            "Queda: capitulo ${formatDuration(rem.chapterMs)}, libro ${formatDuration(rem.bookMs)}" +
                (p.timerRemainingMs?.let { " - Temporizador ${formatDuration(it)}" } ?: if (p.timer == TimerChoice.EndOfChapter) " - Para al final del capitulo" else ""),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("tiempo-restante"),
        )
        if (p.focusSuppressed) Text("En pausa por una llamada; seguira sola", style = MaterialTheme.typography.bodySmall)
        if (p.lowBattery) Text("Bateria baja", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (p.error != null) Text(p.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        // Dos filas (M9): 5 mandos de lectura caben en 360 dp; el resto, debajo y desplazable.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            AppIconButton(R.drawable.ic_skip_previous, "Capitulo anterior", vm::previousChapter)
            AppIconButton(R.drawable.ic_keyboard_arrow_up, "Frase anterior", vm::previousSentence)
            FilledIconButton(onClick = vm::togglePlay, modifier = Modifier.heightIn(min = 56.dp).width(56.dp).testTag("boton-reproducir")) {
                if (p.isPreparing && p.playWhenReady) CircularProgressIndicator(Modifier.semantics { contentDescription = "Preparando la voz" })
                else AppIcon(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow, if (state.isPlaying) "Pausa" else "Reproducir")
            }
            AppIconButton(R.drawable.ic_keyboard_arrow_down, "Frase siguiente", vm::nextSentence)
            AppIconButton(R.drawable.ic_skip_next, "Capitulo siguiente", vm::nextChapter)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TimerButton(p.timer, vm::setTimer)
            // Icono decorativo: los chips ya dicen la velocidad (B11).
            AppIcon(R.drawable.ic_speed, null)
            state.voice.speedPresets.forEach { s ->
                FilterChip(
                    selected = kotlin.math.abs(state.voice.speed - s) < 0.01f,
                    onClick = { vm.setSpeed(s) },
                    label = { Text("x" + "%.2f".format(s).trimEnd('0').trimEnd('.', ',')) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

@Composable
private fun TimerButton(current: TimerChoice?, onSet: (TimerChoice?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AppIconButton(R.drawable.ic_timer, if (current == null) "Temporizador" else "Temporizador activo", { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(5, 10, 15, 30, 45, 60, 90).forEach { m ->
                DropdownMenuItem(text = { Text("$m minutos") }, onClick = { open = false; onSet(TimerChoice.Minutes(m)) })
            }
            DropdownMenuItem(text = { Text("Al final del capitulo") }, onClick = { open = false; onSet(TimerChoice.EndOfChapter) })
            if (current != null) DropdownMenuItem(text = { Text("Quitar temporizador") }, onClick = { open = false; onSet(null) })
        }
    }
}

@Composable
private fun TocList(toc: List<TocItem>, currentChapter: Int, onGo: (com.lectorvoz.core.model.Locator) -> Unit) {
    if (toc.isEmpty()) {
        Text("Este libro no tiene indice.", modifier = Modifier.padding(24.dp))
        return
    }
    LazyColumn(Modifier.fillMaxWidth().testTag("indice")) {
        items(toc, key = { "${it.locator.chapter}-${it.locator.offset}-${it.title}" }) { t ->
            Text(
                t.title,
                style = if (t.locator.chapter == currentChapter) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                color = if (t.locator.chapter == currentChapter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().clickable { onGo(t.locator) }.heightIn(min = 48.dp)
                    .padding(start = (16 + 16 * t.depth).dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun BookmarkList(state: ReaderUiState, onGo: (com.lectorvoz.core.model.Locator) -> Unit, onDelete: (com.lectorvoz.core.data.bookmarks.Bookmark) -> Unit, onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = onAdd, modifier = Modifier.padding(horizontal = 12.dp).heightIn(min = 48.dp)) { Text("Anadir marcador aqui") }
        if (state.bookmarks.isEmpty()) Text("Sin marcadores.", modifier = Modifier.padding(24.dp))
        LazyColumn(Modifier.testTag("marcadores")) {
            items(state.bookmarks, key = { it.id }) { b ->
                Row(Modifier.fillMaxWidth().clickable { onGo(b.locator) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.excerpt, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        b.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    AppIconButton(R.drawable.ic_delete, "Borrar marcador", { onDelete(b) })
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SearchPanel(search: SearchState, onSearch: (String) -> Unit, onOpen: (Int) -> Unit, onClear: () -> Unit) {
    var q by rememberSaveable { mutableStateOf(search.query) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = q,
            onValueChange = { q = it },
            label = { Text("Buscar en el libro") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(q) }),
            trailingIcon = { AppIconButton(R.drawable.ic_search, "Buscar", { onSearch(q) }) },
            modifier = Modifier.fillMaxWidth().testTag("buscar-libro"),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    search.running -> "Buscando... ${search.results.size} resultados"
                    search.done -> "${search.results.size} resultados"
                    else -> ""
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (search.query.isNotEmpty()) TextButton(onClick = { q = ""; onClear() }) { Text("Limpiar") }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            itemsIndexed(search.results, key = { i, _ -> i }) { i, hit ->
                Text(
                    hit.excerpt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (i == search.current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(i) }.heightIn(min = 48.dp).padding(vertical = 8.dp),
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun TextActionDialog(action: TextAction, vm: ReaderViewModel) {
    var fixing by remember { mutableStateOf(false) }
    var replacement by remember { mutableStateOf("") }
    var forBook by remember { mutableStateOf(true) }
    if (!fixing) {
        AlertDialog(
            onDismissRequest = vm::dismissTextAction,
            title = { Text(action.word ?: "Texto") },
            text = {
                Column {
                    TextButton(onClick = { vm.readFromHere(action.locator) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Leer desde aqui") }
                    if (action.word != null) {
                        TextButton(onClick = { fixing = true; replacement = action.word }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Corregir pronunciacion") }
                    }
                    TextButton(onClick = { vm.dismissTextAction(); vm.addBookmarkAt(action.locator) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Marcar") }
                }
            },
            confirmButton = { TextButton(onClick = vm::dismissTextAction) { Text("Cerrar") } },
        )
    } else {
        AlertDialog(
            onDismissRequest = vm::dismissTextAction,
            title = { Text("Como debe sonar \"${action.word}\"") },
            text = {
                Column {
                    OutlinedTextField(value = replacement, onValueChange = { replacement = it }, label = { Text("Pronunciacion") }, singleLine = true)
                    Row(Modifier.fillMaxWidth().clickable { forBook = !forBook }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = forBook, onCheckedChange = null)
                        Text("Solo en este libro", modifier = Modifier.padding(start = 8.dp))
                    }
                    TextButton(onClick = { vm.previewPronunciation(replacement) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Escuchar prueba") }
                }
            },
            confirmButton = { TextButton(onClick = { vm.savePronunciation(action.word.orEmpty(), replacement, forBook) }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = vm::dismissTextAction) { Text("Cancelar") } },
        )
    }
}

/** Mantener pantalla, brillo propio y pantalla completa. */
@Composable
private fun WindowEffects(keepScreenOn: Boolean, brightness: Float?, immersive: Boolean) {
    val view = LocalView.current
    val activity = LocalActivity.current
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(brightness, activity) {
        val w = activity?.window
        fun set(v: Float) {
            if (w == null) return
            w.attributes = w.attributes.apply { screenBrightness = v }
        }
        set(brightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        onDispose { set(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) }
    }
    DisposableEffect(immersive, activity) {
        val w = activity?.window
        val ctl = w?.let { WindowCompat.getInsetsController(it, view) }
        if (ctl != null) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (immersive) ctl.hide(WindowInsetsCompat.Type.systemBars()) else ctl.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { ctl?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** Teclas de volumen para pasar pagina mientras el lector esta en pantalla. */
@Composable
private fun VolumeKeys(handler: (Boolean) -> Boolean) {
    val activity = LocalActivity.current as? MainActivity ?: return
    val current by rememberUpdatedState(handler)
    DisposableEffect(activity) {
        val h: (Boolean) -> Boolean = { up -> current(up) }
        activity.volumeKeyHandler = h
        onDispose { if (activity.volumeKeyHandler === h) activity.volumeKeyHandler = null }
    }
}
