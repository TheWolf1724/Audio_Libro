package com.lectorvoz.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lectorvoz.app.BuildConfig
import com.lectorvoz.app.R
import com.lectorvoz.app.books.ExportJobState
import com.lectorvoz.app.ui.components.AppIconButton
import com.lectorvoz.app.ui.components.ChoiceRow
import com.lectorvoz.app.ui.components.SectionHeader
import com.lectorvoz.app.ui.reader.formatDuration
import com.lectorvoz.core.data.library.Book
import com.lectorvoz.core.data.pronunciation.PronunciationEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Diccionario de pronunciacion: lista, alta/edicion y prueba en vivo. */
@Composable
fun DictionaryScreen(vm: DictionaryViewModel, onBack: () -> Unit) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val sample by vm.sample.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val invalid by vm.invalid.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<PronunciationEntry?>(null) }
    SettingsScaffold(if (vm.bookId == null) "Diccionario de pronunciacion" else "Pronunciacion de este libro", onBack) {
        SectionHeader("Probar")
        OutlinedTextField(
            value = sample,
            onValueChange = vm::setSample,
            label = { Text("Escribe un texto de prueba") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("dic-prueba"),
        )
        if (preview.isNotBlank()) {
            Text("Se leera: $preview", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp).testTag("dic-resultado"))
        }
        TextButton(onClick = vm::listen, modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp)) { Text("Escuchar") }
        val msg by vm.message.collectAsStateWithLifecycle()
        msg?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        SectionHeader("Reglas")
        Button(onClick = { editing = PronunciationEntry(pattern = "", replacement = "") }, modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp).testTag("dic-anadir")) {
            Text("Anadir regla")
        }
        if (entries.isEmpty()) Text("Aun no hay reglas.", modifier = Modifier.padding(16.dp))
        entries.forEach { e ->
            Row(
                Modifier.fillMaxWidth().clickable { editing = e }.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${e.pattern}  ->  ${e.replacement}", style = MaterialTheme.typography.bodyLarge)
                    val flags = listOfNotNull(
                        "expresion regular".takeIf { e.isRegex },
                        "distingue mayusculas".takeIf { e.caseSensitive },
                        "palabra completa".takeIf { e.wholeWord && !e.isRegex },
                        e.language?.let { "idioma $it" },
                        "no valida".takeIf { e.pattern in invalid },
                    )
                    if (flags.isNotEmpty()) Text(flags.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = e.enabled, onCheckedChange = { vm.toggle(e) }, modifier = Modifier.semantics { contentDescription = "Regla activa" })
                AppIconButton(R.drawable.ic_delete, "Borrar regla ${e.pattern}", { vm.delete(e) })
            }
            HorizontalDivider()
        }
    }
    editing?.let { e ->
        RuleDialog(e, onDismiss = { editing = null }, onSave = { vm.save(it); editing = null })
    }
}

@Composable
private fun RuleDialog(initial: PronunciationEntry, onDismiss: () -> Unit, onSave: (PronunciationEntry) -> Unit) {
    var e by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "Nueva regla" else "Editar regla") },
        text = {
            Column {
                OutlinedTextField(e.pattern, { e = e.copy(pattern = it) }, label = { Text("Texto o expresion") }, singleLine = true, modifier = Modifier.testTag("dic-patron"))
                OutlinedTextField(e.replacement, { e = e.copy(replacement = it) }, label = { Text("Se pronuncia") }, singleLine = true, modifier = Modifier.testTag("dic-sustitucion"))
                Check("Expresion regular", e.isRegex) { e = e.copy(isRegex = it) }
                Check("Distinguir mayusculas", e.caseSensitive) { e = e.copy(caseSensitive = it) }
                Check("Solo palabra completa", e.wholeWord) { e = e.copy(wholeWord = it) }
                ChoiceRow("Idioma", listOf<String?>(null, "es", "en"), e.language, { when (it) { null -> "Todos"; "es" -> "Espanol"; else -> "Ingles" } }, { e = e.copy(language = it) })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(e) }, enabled = e.pattern.isNotBlank(), modifier = Modifier.testTag("dic-guardar")) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun Check(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(value, null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Estadisticas de lectura y escucha. */
@Composable
fun StatsScreen(vm: StatsViewModel, onBack: () -> Unit) {
    val s by vm.summary.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    SettingsScaffold("Estadisticas", onBack) {
        SectionHeader("Total")
        StatLine("Tiempo leyendo", formatDuration(s.readMs))
        StatLine("Tiempo escuchando", formatDuration(s.listenMs))
        StatLine("Libros terminados", s.finishedBooks.toString())
        StatLine("Sesiones", s.sessions.toString())
        StatLine("Ritmo de lectura", s.readCharsPerMinute?.let { "$it caracteres por minuto (unas ${it / 6} palabras)" } ?: "sin datos")
        StatLine("Ritmo de escucha", s.listenCharsPerMinute?.let { "$it caracteres por minuto" } ?: "sin datos")
        StatLine("Texto escuchado", "${s.listenChars} caracteres (unas ${s.listenChars / 6} palabras)")
        s.lastDays.lastOrNull()?.let { today ->
            SectionHeader("Hoy")
            StatLine("Leyendo", formatDuration(today.readMs))
            StatLine("Escuchando", formatDuration(today.listenMs))
        }
        if (s.lastDays.isNotEmpty()) {
            StatLine("Media diaria (7 dias)", formatDuration(s.lastDays.sumOf { it.readMs + it.listenMs } / s.lastDays.size))
        }
        Text(
            "La escucha cuenta aunque el lector este cerrado o la pantalla apagada.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        SectionHeader("Ultimos 7 dias")
        val max = s.lastDays.maxOfOrNull { it.readMs + it.listenMs }?.coerceAtLeast(1) ?: 1
        val fmt = SimpleDateFormat("EEE d", Locale.forLanguageTag("es-ES"))
        s.lastDays.forEach { d ->
            val total = d.readMs + d.listenMs
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "${fmt.format(Date(d.dayStart))}: leyendo ${formatDuration(d.readMs)}, escuchando ${formatDuration(d.listenMs)}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(fmt.format(Date(d.dayStart)), modifier = Modifier.width(64.dp))
                Box(Modifier.weight(1f).height(16.dp)) {
                    Box(Modifier.fillMaxWidth(total.toFloat() / max).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                }
                Text(formatDuration(total), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp).width(96.dp))
            }
        }
        OutlinedButton(onClick = { confirm = true }, modifier = Modifier.padding(16.dp).heightIn(min = 48.dp)) { Text("Borrar estadisticas") }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Borrar estadisticas") },
            text = { Text("Se borrara el historial de sesiones. Los libros y su progreso no cambian.") },
            confirmButton = { TextButton(onClick = { vm.clear(); confirm = false }) { Text("Borrar") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).semantics(mergeDescendants = true) {}) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * Exportar a audio: elegir libro (si no viene dado), parte y destino. Una parte va a un
 * fichero (ACTION_CREATE_DOCUMENT); el libro completo, un WAV por parte en una carpeta
 * (ACTION_OPEN_DOCUMENT_TREE). Sigue aunque se salga de la pantalla.
 */
@Composable
fun ExportScreen(vm: ExportViewModel, onBack: () -> Unit, onPickBook: (Book) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val books by vm.books.collectAsStateWithLifecycle()
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        if (uri != null) vm.export(uri)
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.exportBook(uri)
    }
    SettingsScaffold("Exportar audio", onBack) {
        val job = s.job
        if (job != null) ExportJobCard(job, onCancel = vm::cancel, onClear = vm::clearResult)
        val book = s.book
        if (book == null) {
            Text("Elige un libro:", modifier = Modifier.padding(16.dp))
            if (books.isEmpty()) Text("La biblioteca esta vacia.", modifier = Modifier.padding(16.dp))
            books.forEach { b ->
                Text(
                    b.title,
                    modifier = Modifier.fillMaxWidth().clickable { onPickBook(b) }.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
                HorizontalDivider()
            }
            return@SettingsScaffold
        }
        Text(book.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
        if (s.parts.isNotEmpty()) {
            ChoiceRow("Parte", s.parts.indices.toList(), s.part, { s.parts.getOrNull(it) ?: "${it + 1}" }, vm::setPart, tag = "exportar-capitulo")
        }
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        Text(
            "Se crea audio WAV (unos 5 MB por minuto) con la voz y los ajustes actuales. Puede tardar; " +
                "puedes salir de esta pantalla mientras tanto. El libro completo se guarda como un fichero por parte en la carpeta que elijas.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(16.dp),
        )
        val enabled = s.parts.isNotEmpty() && !s.running
        Button(
            onClick = { create.launch("${book.title} - ${s.parts.getOrNull(s.part) ?: (s.part + 1)}.wav".replace('/', '-')) },
            enabled = enabled,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).heightIn(min = 48.dp).testTag("exportar-boton"),
        ) { Text("Exportar esta parte") }
        OutlinedButton(
            onClick = { folder.launch(null) },
            enabled = enabled,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).heightIn(min = 48.dp).testTag("exportar-libro"),
        ) { Text("Exportar el libro completo") }
    }
}

@Composable
private fun ExportJobCard(job: ExportJobState, onCancel: () -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("exportar-estado")) {
        Text(
            if (job.running) {
                if (job.wholeBook) "Exportando \"${job.title}\": parte ${job.part + 1} de ${job.parts}" else "Exportando \"${job.title}\""
            } else {
                job.result ?: "Terminado"
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        if (job.running) {
            val p = job.progress
            if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            else LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancelar") }
        } else {
            TextButton(onClick = onClear, modifier = Modifier.heightIn(min = 48.dp)) { Text("Aceptar") }
        }
    }
    HorizontalDivider()
}

/** Licencias incluidas. */
private val LICENSES = listOf(
    Triple("Literata", "SIL Open Font License 1.1", "licencias/OFL-Literata.txt"),
    Triple("Atkinson Hyperlegible Next", "SIL Open Font License 1.1", "licencias/OFL-AtkinsonHyperlegibleNext.txt"),
    Triple("OpenDyslexic", "SIL Open Font License 1.1 (nombre reservado)", "licencias/OFL-OpenDyslexic.txt"),
    Triple("Supertonic 3 (voz, Supertone Inc.)", "OpenRAIL-M (pesos), MIT (codigo)", "licencias/OpenRAIL-M-Supertonic.txt"),
    Triple("Estimador de Supertonic 3 optimizado para movil (WilburDev/supertonic-3-mobile)", "OpenRAIL-M", "licencias/OpenRAIL-M-Supertonic.txt"),
    Triple("sherpa-onnx", "Apache License 2.0", null),
    Triple("ONNX Runtime", "MIT", null),
    Triple("pdfium (pdfiumandroid)", "Apache License 2.0 / BSD", null),
    Triple("jsoup", "MIT", null),
    Triple("AndroidX, Jetpack Compose, Media3, Room, DataStore", "Apache License 2.0", null),
    Triple("Kotlin y kotlinx.coroutines", "Apache License 2.0", null),
    Triple("Apache Commons Compress", "Apache License 2.0", null),
)

/** Acerca de: version y licencias (texto completo de las OFL incluidas). */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<String?>(null) }
    SettingsScaffold("Acerca de", onBack) {
        Text("LectorVoz ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        Text(
            "Lector de EPUB y PDF con voz natural local. Sin anuncios, sin cuentas y sin conexion.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        SectionHeader("Licencias")
        LICENSES.forEach { (name, license, asset) ->
            Column(
                Modifier.fillMaxWidth().then(if (asset != null) Modifier.clickable { shown = asset } else Modifier)
                    .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp).testTag("licencia"),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Text(license + if (asset != null) " - toca para leerla" else "", style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }
    }
    shown?.let { asset ->
        val text by produceState("Cargando...", asset) {
            value = withContext(Dispatchers.IO) { runCatching { context.assets.open(asset).bufferedReader().readText() }.getOrDefault("No disponible") }
        }
        AlertDialog(
            onDismissRequest = { shown = null },
            title = { Text("Licencia") },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) { Text(text, style = MaterialTheme.typography.bodySmall) }
            },
            confirmButton = { TextButton(onClick = { shown = null }) { Text("Cerrar") } },
        )
    }
}
