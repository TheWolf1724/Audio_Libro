package com.lectorvoz.app.ui.settings

import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lectorvoz.app.BuildConfig
import com.lectorvoz.app.R
import com.lectorvoz.app.books.ExportJobState
import com.lectorvoz.app.ui.components.AppIconButton
import com.lectorvoz.app.ui.components.ChoiceRow
import com.lectorvoz.app.ui.components.SectionHeader
import com.lectorvoz.app.ui.components.SwitchRow
import com.lectorvoz.app.update.ManualResult
import com.lectorvoz.app.ui.reader.formatDuration
import com.lectorvoz.core.data.library.Book
import com.lectorvoz.core.data.pronunciation.PronunciationEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
    // Saveable: girar o cambiar el modo oscuro no cierra el dialogo ni pierde la regla escrita.
    var editing by rememberSaveable(stateSaver = entrySaver<PronunciationEntry?>()) { mutableStateOf(null) }
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
                Switch(checked = e.enabled, onCheckedChange = { vm.toggle(e) }, modifier = Modifier.semantics { contentDescription = "Regla ${e.pattern} activa" })
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
    var e by rememberSaveable(stateSaver = entrySaver<PronunciationEntry>()) { mutableStateOf(initial) }
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

/** Guarda una regla (o ninguna) en el estado de la actividad. */
@Suppress("UNCHECKED_CAST")
internal fun <T : PronunciationEntry?> entrySaver(): Saver<T, Any> = Saver(
    save = { e ->
        e?.let { arrayListOf<Any?>(it.id, it.pattern, it.replacement, it.isRegex, it.caseSensitive, it.wholeWord, it.enabled, it.language, it.position, it.bookId) }
    },
    restore = { v ->
        val l = v as List<*>
        PronunciationEntry(
            id = l[0] as Long, pattern = l[1] as String, replacement = l[2] as String, isRegex = l[3] as Boolean,
            caseSensitive = l[4] as Boolean, wholeWord = l[5] as Boolean, enabled = l[6] as Boolean,
            language = l[7] as String?, position = l[8] as Int, bookId = l[9] as Long?,
        ) as T
    },
)

@Composable
private fun Check(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = value, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(value, null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Estadisticas de lectura y escucha. */
@Composable
fun StatsScreen(vm: StatsViewModel, onBack: () -> Unit) {
    val s by vm.summary.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf(false) }
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
                // Con letra grande los textos crecen (anchos minimos, no fijos) y la barra cede sitio.
                Text(fmt.format(Date(d.dayStart)), modifier = Modifier.widthIn(min = 64.dp).padding(end = 8.dp))
                Box(Modifier.weight(1f).widthIn(min = 24.dp).height(16.dp)) {
                    Box(Modifier.fillMaxWidth(total.toFloat() / max).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                }
                Text(formatDuration(total), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp).widthIn(min = 96.dp))
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
        // Pesos: con letra grande el valor parte linea en vez de empujar la etiqueta fuera.
        Text(label, modifier = Modifier.weight(1f).padding(end = 8.dp), style = MaterialTheme.typography.bodyLarge)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.End)
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
    val book = s.book
    if (book == null) {
        // Eleccion de libro: lista perezosa (con miles de libros no se componen todos).
        SettingsScaffold("Exportar audio", onBack, scrollable = false) {
            s.job?.let { ExportJobCard(it, onCancel = vm::cancel, onClear = vm::clearResult) }
            Text("Elige un libro:", modifier = Modifier.padding(16.dp))
            if (books.isEmpty()) Text("La biblioteca esta vacia.", modifier = Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("exportar-libros")) {
                items(books, key = { it.id }) { b ->
                    Text(
                        b.title,
                        modifier = Modifier.fillMaxWidth().clickable { onPickBook(b) }.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    HorizontalDivider()
                }
            }
        }
        return
    }
    SettingsScaffold("Exportar audio", onBack) {
        val job = s.job
        if (job != null) ExportJobCard(job, onCancel = vm::cancel, onClear = vm::clearResult)
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
    Triple("Tesseract OCR y Tesseract4Android (reconocimiento de texto)", "Apache License 2.0", null),
    Triple("Modelos de idioma de Tesseract (tessdata: espanol e ingles)", "Apache License 2.0", null),
    Triple("Leptonica, libjpeg y libpng (incluidas en Tesseract4Android)", "Licencia de Leptonica (BSD 2 clausulas), IJG y libpng", null),
    Triple("jsoup", "MIT", null),
    Triple("AndroidX, Jetpack Compose, Media3, Room, DataStore", "Apache License 2.0", null),
    Triple("Kotlin y kotlinx.coroutines", "Apache License 2.0", null),
    Triple("Apache Commons Compress", "Apache License 2.0", null),
)

/**
 * Acerca de: version, actualizaciones (aviso + comprobacion manual) y licencias. El aviso de
 * nuevas versiones vive aqui, junto a la version instalada, porque es a lo que se refiere.
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    checkUpdates: Boolean = true,
    onCheckUpdates: (Boolean) -> Unit = {},
    onCheckNow: () -> Unit = {},
    manual: ManualResult = ManualResult.NONE,
    onManualDismiss: () -> Unit = {},
    diagnosticsEnabled: Boolean = false,
    onDiagnosticsChange: (Boolean) -> Unit = {},
    diagnosticsFile: () -> File? = { null },
) {
    val context = LocalContext.current
    var shown by rememberSaveable { mutableStateOf<String?>(null) }
    // Al salir de la pantalla se olvida el resultado de la ultima comprobacion manual.
    DisposableEffect(Unit) { onDispose(onManualDismiss) }
    SettingsScaffold("Acerca de", onBack) {
        Text("LectorVoz ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        Text(
            "Lector de EPUB y PDF con voz natural local. Sin anuncios, sin cuentas y sin conexion.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        SectionHeader("Actualizaciones")
        SwitchRow(
            "Avisar de nuevas versiones", checkUpdates, onCheckUpdates,
            summary = "Una vez al dia, al abrir la app, mira en GitHub si hay una version nueva (no envia ningun dato tuyo). Desde el aviso se puede instalar u omitir 24 h",
            tag = "app-checkUpdates",
        )
        // El boton de forzar la comprobacion va JUSTO debajo del interruptor.
        Button(
            onClick = onCheckNow,
            enabled = manual != ManualResult.CHECKING,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).heightIn(min = 48.dp).testTag("app-buscar-actualizaciones"),
        ) { Text(if (manual == ManualResult.CHECKING) "Buscando..." else "Buscar actualizaciones ahora") }
        val manualText = when (manual) {
            ManualResult.NEW_AVAILABLE -> "Hay una version nueva: aparecera el aviso para instalarla."
            ManualResult.UP_TO_DATE -> "Ya tienes la ultima version."
            ManualResult.FAILED -> "No se pudo comprobar. Revisa la conexion e intentalo de nuevo."
            else -> null
        }
        manualText?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (manual == ManualResult.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).testTag("app-buscar-resultado"),
            )
        }
        SectionHeader("Diagnostico")
        SwitchRow(
            "Registrar lectura (diagnostico)", diagnosticsEnabled, onDiagnosticsChange,
            summary = "Guarda que frases lee la voz, para diagnosticar saltos. Activalo, reproduce la pagina y comparte el fichero.",
            tag = "app-diagnostico-lectura",
        )
        var confirmShare by remember { mutableStateOf(false) }
        Button(
            onClick = {
                if (diagnosticsFile() == null) {
                    Toast.makeText(context, "Aun no hay diagnostico. Activa el registro y reproduce una pagina.", Toast.LENGTH_LONG).show()
                } else {
                    confirmShare = true
                }
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).heightIn(min = 48.dp).testTag("app-compartir-diagnostico"),
        ) { Text("Compartir diagnostico") }
        if (confirmShare) {
            AlertDialog(
                onDismissRequest = { confirmShare = false },
                title = { Text("Compartir diagnostico") },
                // Aviso explicito del contenido antes de compartir: el fichero lleva FRAGMENTOS del
                // texto del libro y el modelo/version del dispositivo. Evita adjuntarlo sin querer.
                text = { Text("El fichero incluye fragmentos del texto del libro que estas leyendo y el modelo y version de tu dispositivo. Compartelo solo con quien de soporte.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmShare = false
                        val f = diagnosticsFile() ?: return@TextButton
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, "Compartir diagnostico"))
                    }) { Text("Compartir") }
                },
                dismissButton = { TextButton(onClick = { confirmShare = false }) { Text("Cancelar") } },
            )
        }
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
