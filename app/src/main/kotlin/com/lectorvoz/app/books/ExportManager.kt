package com.lectorvoz.app.books

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.runtime.Immutable
import com.lectorvoz.app.integration.ExportOutcome
import com.lectorvoz.app.integration.PlaybackPort
import com.lectorvoz.app.integration.SpeechSource
import com.lectorvoz.app.integration.UtteranceProviderImpl
import com.lectorvoz.core.data.library.Book
import com.lectorvoz.core.data.pronunciation.PronunciationRepository
import com.lectorvoz.core.data.settings.SettingsRepository
import com.lectorvoz.core.model.BookFormat
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.LocatorRange
import com.lectorvoz.core.model.TocEntry
import com.lectorvoz.core.model.Utterance
import com.lectorvoz.core.model.VoiceSettings
import com.lectorvoz.tts.UtteranceProvider
import com.lectorvoz.tts.export.ExportFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Parte exportable: un capitulo EPUB o un tramo de paginas PDF. */
@Immutable
data class ExportPart(val title: String, val chapters: IntRange)

/** Estado de la exportacion en curso o de la ultima. */
@Immutable
data class ExportJobState(
    val bookId: Long,
    val title: String,
    val running: Boolean = true,
    val wholeBook: Boolean = false,
    /** Parte que se esta exportando (0..[parts]-1). */
    val part: Int = 0,
    val parts: Int = 1,
    /** Fraccion total 0..1 (nula si no se puede estimar). */
    val progress: Float? = 0f,
    val result: String? = null,
)

/** Ficheros de destino en el almacenamiento elegido por el usuario (SAF). */
interface ExportFiles {
    /** Crea "[name]" (de tipo [mime]) en la carpeta [tree] (de OpenDocumentTree). */
    fun create(tree: Uri, name: String, mime: String = "audio/wav"): Uri?

    /** true si en [tree] ya hay un fichero [name] con contenido (parte ya exportada: se reanuda sin repetirla). */
    fun existsComplete(tree: Uri, name: String): Boolean = false

    /** Borra un documento creado (exportacion cancelada, fallida o vacia). */
    fun delete(uri: Uri)

    /** La parte [name] de [tree] se escribio entera: solo entonces la reanudacion puede darla por buena. */
    fun markDone(tree: Uri, name: String) {}

    companion object {
        /** Nombre seguro para cualquier proveedor de ficheros (sin caracteres reservados, longitud acotada). */
        fun safeName(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "-").replace(Regex("\\s+"), " ").trim().let {
                if (it.length <= 120) it else {
                    val dot = it.lastIndexOf('.')
                    val ext = if (dot >= it.length - 6 && dot > 0) it.substring(dot) else ""
                    it.take(120 - ext.length).trimEnd() + ext
                }
            }
    }
}

/** [ExportFiles] con `DocumentsContract`. */
class SafExportFiles(private val context: Context) : ExportFiles {
    // Partes "a medias": se anotan al crearlas y se quitan al terminar de escribirlas. Si el proceso muere
    // durante la escritura queda anotada, y la reanudacion la rehace en vez de darla por buena.
    private val prefs get() = context.getSharedPreferences("exportacion", Context.MODE_PRIVATE)
    private fun key(tree: Uri, name: String) = "$tree|$name"

    override fun create(tree: Uri, name: String, mime: String): Uri? {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        // Resto de un intento anterior interrumpido: se borra para no acumular "nombre (1)".
        findDocument(tree, name)?.let { delete(it) }
        val uri = DocumentsContract.createDocument(context.contentResolver, parent, mime, name)
        if (uri != null) prefs.edit().putBoolean(key(tree, name), true).apply()
        return uri
    }

    override fun markDone(tree: Uri, name: String) {
        prefs.edit().remove(key(tree, name)).apply()
    }

    private fun findDocument(tree: Uri, name: String): Uri? = try {
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            var found: Uri? = null
            while (c.moveToNext()) {
                if (c.getString(1) == name) { found = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)); break }
            }
            found
        }
    } catch (e: Exception) {
        null
    }

    override fun existsComplete(tree: Uri, name: String): Boolean = if (prefs.getBoolean(key(tree, name), false)) false else try {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE),
            null, null, null,
        )?.use { c ->
            var found = false
            while (c.moveToNext()) {
                if (c.getString(0) == name && c.getLong(1) > 0L) { found = true; break }
            }
            found
        } ?: false
    } catch (e: Exception) {
        false
    }

    override fun delete(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (e: Exception) {
            Log.w("ExportFiles", "No se pudo borrar el fichero incompleto", e)
        }
    }
}

/**
 * Exportar a audio en el ambito de la aplicacion (revision 3, M8): salir de la pantalla no
 * cancela, el libro se abre con una referencia propia ([SharedBooks]) que se suelta solo
 * cuando termina el trabajo, y un fichero incompleto (cancelado o con error) se borra.
 *
 * Libro completo (requisito "exportar capitulo o libro"): un WAV por parte en una carpeta
 * elegida con OpenDocumentTree. Se descarta un unico WAV porque el formato no pasa de 4 GB
 * (unas 13 h de voz a 44,1 kHz) y su cabecera necesita el tamano total; ademas, por partes
 * el reproductor permite saltar entre capitulos y un fallo solo pierde una parte. En PDF
 * cada pagina es un "capitulo" de la voz, asi que se agrupan por el indice del documento
 * (o en tramos de [PDF_PAGES_PER_PART] paginas si no tiene).
 */
class ExportManager(
    private val scope: CoroutineScope,
    private val playback: PlaybackPort,
    private val books: SharedBooks,
    private val settings: SettingsRepository,
    private val pronunciation: PronunciationRepository,
    private val files: ExportFiles,
) {
    private val _state = MutableStateFlow<ExportJobState?>(null)

    /** Exportacion en curso o la ultima terminada (nula si no hubo ninguna). */
    val state: StateFlow<ExportJobState?> = _state.asStateFlow()

    private var job: Job? = null

    /** Partes exportables de [book] (abre y suelta el libro). */
    suspend fun parts(book: Book): List<ExportPart> {
        val voice = settings.voiceFor(book.id).first()
        val lease = books.acquire(book, voice.skipPdfHeadersFooters)
        return try {
            partsOf(book, lease.opened)
        } finally {
            lease.release()
        }
    }

    /** Exporta la parte [part] de [book] a [uri] (ACTION_CREATE_DOCUMENT) en [format]. */
    fun exportPart(book: Book, part: Int, uri: Uri, format: ExportFormat = ExportFormat.WAV) = runJob(book, wholeBook = false) { provider, parts, voice ->
        val p = parts.getOrNull(part) ?: return@runJob "Parte no valida"
        _state.update { it?.copy(parts = 1, part = 0) }
        when (val r = exportOne(provider, part, uri, voice, format) { f -> _state.update { it?.copy(progress = f) } }) {
            is ExportOutcome.Done ->
                if (r.durationMs <= 0L) {
                    files.delete(uri)
                    "La parte \"${p.title}\" no tiene texto que leer: no se creo el fichero"
                } else {
                    "Exportado \"${p.title}\": ${duration(r.durationMs)}, ${r.bytes / (1024 * 1024)} MB"
                }
            is ExportOutcome.Failed -> "Error: ${r.message}"
        }
    }

    /**
     * Exporta todo [book] a la carpeta [tree] (OpenDocumentTree), un fichero por parte en [format].
     * REANUDABLE: las partes que ya estan en la carpeta (con contenido) se saltan, asi que tras una
     * cancelacion, un fallo o el limite de tiempo de Android basta con volver a pulsar para seguir.
     */
    fun exportBook(book: Book, tree: Uri, format: ExportFormat = ExportFormat.WAV) = runJob(book, wholeBook = true) { provider, parts, voice ->
        _state.update { it?.copy(parts = parts.size) }
        var done = 0
        var skipped = 0
        var totalMs = 0L
        for ((i, p) in parts.withIndex()) {
            _state.update { it?.copy(part = i, progress = i.toFloat() / parts.size) }
            val name = ExportFiles.safeName("%02d - %s.${format.extension}".format(java.util.Locale.ROOT, i + 1, p.title))
            if (files.existsComplete(tree, name)) { skipped++; continue }
            val uri = files.create(tree, name, format.mime) ?: return@runJob "Error: no se pudo crear $name"
            when (val r = exportOne(provider, i, uri, voice, format) { f -> _state.update { it?.copy(progress = (i + (f ?: 0f)) / parts.size) } }) {
                is ExportOutcome.Done -> if (r.durationMs <= 0L) files.delete(uri) else { files.markDone(tree, name); done++; totalMs += r.durationMs }
                is ExportOutcome.Failed -> return@runJob "Error en \"${p.title}\": ${r.message}. Exportadas $done partes."
            }
        }
        val reanudadas = if (skipped > 0) " ($skipped ya estaban hechas)" else ""
        "Libro exportado: ${done + skipped} ficheros$reanudadas, ${duration(totalMs)} nuevos"
    }

    /** Cancela la exportacion en curso. */
    fun cancel() {
        job?.cancel()
    }

    /**
     * Android corta los servicios de datos tras unas horas (Android 15+): se detiene la exportacion con un
     * aviso claro. Las partes ya exportadas se conservan; volver a pulsar "Exportar el libro" continua.
     */
    fun pauseForTimeout() {
        if (job?.isActive != true) return
        pauseReason = "Pausada: Android limita el tiempo de las tareas largas. Vuelve a pulsar \"Exportar el libro completo\" para continuar (se saltan las partes ya hechas)."
        job?.cancel()
    }

    @Volatile private var pauseReason: String? = null

    /** Olvida el resultado de la ultima exportacion. */
    fun clearResult() {
        if (_state.value?.running != true) _state.value = null
    }

    private suspend fun exportOne(provider: UtteranceProvider, part: Int, uri: Uri, voice: VoiceSettings, format: ExportFormat, onProgress: (Float?) -> Unit): ExportOutcome {
        var ok = false
        try {
            val r = playback.exportChapter(uri, provider as SpeechSource, part, voice, format, onProgress)
            ok = r is ExportOutcome.Done
            return r
        } finally {
            if (!ok) withContext(NonCancellable) { files.delete(uri) }
        }
    }

    private fun runJob(book: Book, wholeBook: Boolean, body: suspend (UtteranceProvider, List<ExportPart>, VoiceSettings) -> String) {
        if (job?.isActive == true) return
        pauseReason = null
        _state.value = ExportJobState(book.id, book.title, wholeBook = wholeBook)
        job = scope.launch {
            val result = try {
                val voice = settings.voiceFor(book.id).first()
                val dict = pronunciation.effective(book.id).first()
                val lease = books.acquire(book, voice.skipPdfHeadersFooters)
                try {
                    val parts = partsOf(book, lease.opened)
                    val inner = UtteranceProviderImpl(lease.opened.content, voice, dict, lease.opened.toc)
                    body(GroupedProvider(inner, parts.map { it.chapters }), parts, voice)
                } finally {
                    lease.release()
                }
            } catch (e: CancellationException) {
                val why = pauseReason ?: "Exportacion cancelada"
                pauseReason = null
                _state.update { it?.copy(running = false, progress = null, result = why) }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Exportacion fallida", e)
                "Error: ${e.message ?: "no se pudo exportar"}"
            }
            _state.update { it?.copy(running = false, progress = 1f, result = result) }
        }
    }

    companion object {
        private const val TAG = "ExportManager"

        /** Paginas por fichero en un PDF sin indice. */
        const val PDF_PAGES_PER_PART = 20

        private fun duration(ms: Long) = "${ms / 60_000} min ${(ms / 1000) % 60} s"

        /** Partes de un libro abierto: capitulos EPUB o tramos de paginas PDF. */
        fun partsOf(book: Book, opened: OpenedBook): List<ExportPart> {
            val count = opened.content.chapterCount
            if (count <= 0) return emptyList()
            if (book.format == BookFormat.EPUB) {
                return opened.chapterTitles.mapIndexed { i, t -> ExportPart(t ?: "Seccion ${i + 1}", i..i) }
            }
            return pdfParts(count, opened.toc)
        }

        /** Tramos de un PDF de [pages] paginas segun el primer nivel del indice. */
        fun pdfParts(pages: Int, toc: List<TocEntry>): List<ExportPart> {
            val starts = toc.map { it.target.chapter to it.title }
                .filter { it.first in 0 until pages }
                .distinctBy { it.first }
                .sortedBy { it.first }
            if (starts.size < 2) {
                return (0 until pages step PDF_PAGES_PER_PART).map { s ->
                    val e = minOf(s + PDF_PAGES_PER_PART, pages) - 1
                    ExportPart("Paginas ${s + 1}-${e + 1}", s..e)
                }
            }
            val out = ArrayList<ExportPart>()
            if (starts.first().first > 0) out += ExportPart("Inicio", 0 until starts.first().first)
            for ((i, st) in starts.withIndex()) {
                val end = (starts.getOrNull(i + 1)?.first ?: pages) - 1
                out += ExportPart(st.second, st.first..end)
            }
            return out
        }
    }
}

/**
 * Proveedor que agrupa capitulos de [inner] en partes ([groups]): la parte i es el
 * "capitulo" i para el exportador, que lee hasta que cambia de capitulo.
 */
class GroupedProvider(
    private val inner: UtteranceProvider,
    private val groups: List<IntRange>,
) : UtteranceProvider, SpeechSource {
    private val originals = object : LinkedHashMap<Long, Utterance>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Utterance>) = size > 256
    }

    override val chapterCount: Int get() = groups.size

    private fun groupOf(chapter: Int): Int = groups.indexOfFirst { chapter in it }

    private fun wrap(u: Utterance?): Utterance? {
        u ?: return null
        val g = groupOf(u.range.start.chapter)
        if (g < 0) return null
        synchronized(originals) { originals[u.id] = u }
        return u.copy(range = LocatorRange(Locator(g, u.range.start.offset), Locator(g, u.range.endExclusive.offset)))
    }

    private fun original(u: Utterance): Utterance? = synchronized(originals) { originals[u.id] }

    override suspend fun at(locator: Locator): Utterance? {
        val r = groups.getOrNull(locator.chapter) ?: return null
        return wrap(inner.at(Locator(r.first, if (locator.offset == 0) 0 else locator.offset)))
    }

    override suspend fun next(current: Utterance): Utterance? = original(current)?.let { wrap(inner.next(it)) }

    override suspend fun previous(current: Utterance): Utterance? = original(current)?.let { wrap(inner.previous(it)) }

    override suspend fun textAt(locator: Locator): String? = at(locator)?.displayText
}
