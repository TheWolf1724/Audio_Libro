package com.lectorvoz.app.ui.settings

import android.content.Context
import android.os.StatFs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lectorvoz.app.ocr.OcrManager
import com.lectorvoz.app.ui.components.SectionHeader
import com.lectorvoz.core.data.library.LibraryRepository
import com.lectorvoz.core.model.treeSizeBytes
import com.lectorvoz.tts.playback.TtsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Lo que ocupa un libro en el movil, por conceptos. */
data class BookStorage(
    val bookId: Long,
    val title: String,
    /** Copia privada del libro (0 si solo se referencia el original). */
    val copyBytes: Long,
    val coverBytes: Long,
    /** Texto reconocido por OCR guardado. */
    val ocrBytes: Long,
    /** Audio guardado (cache de la voz natural). */
    val audioBytes: Long,
) {
    val totalBytes: Long get() = copyBytes + coverBytes + ocrBytes + audioBytes
}

/** Informe de almacenamiento de la app. */
data class StorageReport(
    val books: List<BookStorage> = emptyList(),
    /** Modelo del OCR descargado/copiado en el movil. */
    val ocrModelBytes: Long = 0,
    val freeBytes: Long = 0,
    val deviceBytes: Long = 0,
) {
    val copies: Long get() = books.sumOf { it.copyBytes }
    val covers: Long get() = books.sumOf { it.coverBytes }
    val ocr: Long get() = books.sumOf { it.ocrBytes }
    val audio: Long get() = books.sumOf { it.audioBytes }
    val total: Long get() = copies + covers + ocr + audio + ocrModelBytes
}

/** Mide lo que ocupa cada libro y deja liberar espacio (audio guardado y texto OCR). */
class StorageInspector(private val context: Context, private val library: LibraryRepository, private val ocr: OcrManager) {

    suspend fun report(): StorageReport = withContext(Dispatchers.IO) {
        val audio = TtsConfig.audioCache(context)
        val items = library.books.first().map { b ->
            BookStorage(
                bookId = b.id,
                title = b.title,
                copyBytes = b.localFile?.length() ?: 0L,
                coverBytes = b.coverPath?.let(::File)?.takeIf { it.isFile }?.length() ?: 0L,
                ocrBytes = ocr.storedBytes(b.id),
                audioBytes = audio.bookSizeBytes(b.id.toString()),
            )
        }.sortedByDescending { it.totalBytes }
        val stat = runCatching { StatFs(context.filesDir.path) }.getOrNull()
        StorageReport(
            books = items,
            ocrModelBytes = File(context.noBackupFilesDir, "ocr-modelos").treeSizeBytes(),
            freeBytes = stat?.availableBytes ?: 0L,
            deviceBytes = stat?.totalBytes ?: 0L,
        )
    }

    /** Borra el audio guardado de [bookId] (o de todos si es nulo). */
    suspend fun clearAudio(bookId: Long?) = withContext(Dispatchers.IO) {
        val cache = TtsConfig.audioCache(context)
        if (bookId == null) cache.clear() else cache.removeBook(bookId.toString())
    }

    /** Borra el texto OCR guardado de [bookId] (o de todos si es nulo). */
    suspend fun clearOcr(bookId: Long?) {
        if (bookId == null) {
            for (b in library.books.first()) ocr.clearBook(b.id)
        } else {
            ocr.clearBook(bookId)
        }
    }
}

data class StorageUiState(val loading: Boolean = true, val report: StorageReport = StorageReport())

class StorageViewModel(private val inspector: StorageInspector) : ViewModel() {
    private val _state = MutableStateFlow(StorageUiState())
    val state: StateFlow<StorageUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val r = runCatching { inspector.report() }.getOrNull()
            _state.update { if (r != null) StorageUiState(false, r) else it.copy(loading = false) }
        }
    }

    fun clearAudio(bookId: Long?) {
        viewModelScope.launch { inspector.clearAudio(bookId); refresh() }
    }

    fun clearOcr(bookId: Long?) {
        viewModelScope.launch { inspector.clearOcr(bookId); refresh() }
    }
}

/** Tamano legible: "1,2 GB", "340 MB", "12 KB". */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

private enum class Pending { AUDIO_ALL, OCR_ALL }

/** Ajustes > Almacenamiento: que ocupa cada libro y como liberar espacio. */
@Composable
fun StorageScreen(vm: StorageViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val r = s.report
    var pending by remember { mutableStateOf<Pending?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }
    SettingsScaffold("Almacenamiento", onBack) {
        SectionHeader("Resumen")
        StorageLine("Espacio libre en el movil", formatBytes(r.freeBytes))
        StorageLine("Total de WolfReader", formatBytes(r.total))
        StorageLine("Libros (copias)", formatBytes(r.copies))
        StorageLine("Portadas", formatBytes(r.covers))
        StorageLine("Texto reconocido (OCR)", formatBytes(r.ocr))
        StorageLine("Audio guardado", formatBytes(r.audio))
        StorageLine("Modelo del OCR", formatBytes(r.ocrModelBytes))
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pending = Pending.AUDIO_ALL }, enabled = r.audio > 0, modifier = Modifier.heightIn(min = 48.dp).testTag("almacen-borrar-audio")) { Text("Borrar audio") }
            OutlinedButton(onClick = { pending = Pending.OCR_ALL }, enabled = r.ocr > 0, modifier = Modifier.heightIn(min = 48.dp).testTag("almacen-borrar-ocr")) { Text("Borrar OCR") }
        }
        SectionHeader("Por libro")
        if (r.books.isEmpty()) {
            Text(
                if (s.loading) "Calculando..." else "No hay libros en la biblioteca.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        r.books.forEach { b -> BookStorageRow(b, onAudio = { vm.clearAudio(b.bookId) }, onOcr = { vm.clearOcr(b.bookId) }) }
    }
    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(if (p == Pending.AUDIO_ALL) "Borrar todo el audio guardado" else "Borrar todo el texto reconocido") },
            text = {
                Text(
                    if (p == Pending.AUDIO_ALL) "Se volvera a generar al escuchar (puede tardar un poco mas la primera vez)."
                    else "Los PDF escaneados se volveran a reconocer al leerlos. Los libros no cambian.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (p == Pending.AUDIO_ALL) vm.clearAudio(null) else vm.clearOcr(null)
                    pending = null
                }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun StorageLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).semantics(mergeDescendants = true) {}) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 8.dp), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun BookStorageRow(b: BookStorage, onAudio: () -> Unit, onOcr: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("almacen-libro-${b.bookId}")
            .semantics(mergeDescendants = false) { contentDescription = "${b.title}, ${formatBytes(b.totalBytes)}" },
    ) {
        Row {
            Text(b.title, modifier = Modifier.weight(1f).padding(end = 8.dp), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(formatBytes(b.totalBytes), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            "Libro ${formatBytes(b.copyBytes + b.coverBytes)}  -  OCR ${formatBytes(b.ocrBytes)}  -  Audio ${formatBytes(b.audioBytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onAudio, enabled = b.audioBytes > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("Borrar audio") }
            TextButton(onClick = onOcr, enabled = b.ocrBytes > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("Borrar OCR") }
        }
        HorizontalDivider()
    }
}
