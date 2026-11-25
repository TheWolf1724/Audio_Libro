package com.lectorvoz.tts.voice

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Progreso de una descarga: [bytes] de [total] (total -1 si se desconoce). */
data class DownloadProgress(val bytes: Long, val total: Long)

/** Fallo de descarga o de verificacion. */
class VoiceDownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Descarga e instala voces del [VoiceCatalog] (sin WorkManager: la UI la lanza en su
 * ambito y puede cancelarla). Reanudable: el `.part` se conserva y se pide con `Range`.
 * Tras descargar se verifica el SHA-256 completo antes de descomprimir; el `.tar.bz2` se
 * extrae a una carpeta temporal que se renombra al final (instalacion atomica).
 */
class VoiceDownloader(
    private val store: VoiceStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    /** Espacio libre (bytes) en el volumen de [File]. */
    private val freeSpace: (File) -> Long = { runCatching { it.usableSpace }.getOrDefault(Long.MAX_VALUE) },
) {
    /**
     * Espacio que hace falta para instalar [voice] teniendo ya [alreadyDownloaded] bytes del `.part`: lo que falta por
     * bajar mas lo descomprimido (el comprimido y la carpeta temporal conviven hasta el final), con margen.
     */
    internal fun requiredBytes(voice: VoiceInfo, alreadyDownloaded: Long): Long {
        val toDownload = (voice.downloadBytes - alreadyDownloaded).coerceAtLeast(0L)
        val raw = toDownload + voice.installedBytes
        return raw + raw / 10 + SPACE_MARGIN
    }

    /** Instala [voice]; [onProgress] se llama desde el hilo de E/S. Idempotente. */
    suspend fun install(voice: VoiceInfo, onProgress: (DownloadProgress) -> Unit = {}) = withContext(io) {
        val source = voice.source as? VoiceSource.Download
            ?: throw VoiceDownloadException("La voz ${voice.id} no es descargable")
        if (store.isInstalled(voice)) return@withContext
        val downloads = File(store.root, DOWNLOAD_DIR).apply { mkdirs() }
        val part = File(downloads, "${voice.id}.tar.bz2.part")
        val need = requiredBytes(voice, if (part.isFile) part.length() else 0L)
        val free = freeSpace(downloads)
        if (free < need) {
            val mb = { b: Long -> (b + 1024 * 1024 - 1) / (1024 * 1024) }
            Log.w("VoiceDownloader", "Espacio insuficiente para ${voice.id}: necesita ${mb(need)} MB, libre ${mb(free)} MB")
            throw VoiceDownloadException("No hay espacio suficiente para instalar la voz: necesita unos ${mb(need)} MB libres y hay ${mb(free)} MB. Libera espacio y vuelve a intentarlo.")
        }
        download(source.url, part, voice.downloadBytes, onProgress)
        if (!Sha256.matches(part, source.sha256)) {
            part.delete()
            throw VoiceDownloadException("SHA-256 incorrecto en ${voice.id}: descarga corrupta")
        }
        extract(part, voice, source.archiveRoot)
        part.delete()
    }

    /** Descarga [url] en [part], continuando si ya existe una parte. */
    internal suspend fun download(
        url: String,
        part: File,
        expectedTotal: Long,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        var offset = if (part.isFile) part.length() else 0L
        if (expectedTotal > 0 && offset == expectedTotal) return
        if (expectedTotal in 1 until offset) {
            part.delete(); offset = 0
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            val code = conn.responseCode
            val resumed = code == HttpURLConnection.HTTP_PARTIAL
            if (code == 416) {
                // Rango no satisfactorio: la parte guardada no sirve. Se borra y el siguiente intento empieza de cero.
                part.delete()
                throw VoiceDownloadException("La descarga guardada estaba incompleta o corrupta; vuelve a intentarlo")
            }
            if (!resumed && code != HttpURLConnection.HTTP_OK) {
                throw VoiceDownloadException("HTTP $code al descargar $url")
            }
            if (!resumed) offset = 0 // El servidor ignoro Range: empezar de cero.
            val length = conn.contentLengthLong
            val total = if (length >= 0) offset + length else expectedTotal
            RandomAccessFile(part, "rw").use { raf ->
                raf.setLength(offset)
                raf.seek(offset)
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var done = offset
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        done += n
                        if (done - lastReport >= REPORT_EVERY || done == total) {
                            lastReport = done
                            onProgress(DownloadProgress(done, total))
                        }
                    }
                    onProgress(DownloadProgress(done, total))
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Extrae [archive] (`.tar.bz2`) en `voces/<id>` quitando la carpeta raiz [archiveRoot]. */
    internal suspend fun extract(archive: File, voice: VoiceInfo, archiveRoot: String) {
        val tmp = File(store.root, "${voice.id}.tmp").apply { deleteRecursively(); mkdirs() }
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream(), 64 * 1024))).use { tar ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = tar.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.removePrefix("./")
                if (!name.startsWith("$archiveRoot/")) continue
                val rel = name.removePrefix("$archiveRoot/")
                if (rel.contains("..") || rel.startsWith("/")) throw VoiceDownloadException("Ruta peligrosa en el tar: ${entry.name}")
                if (rel !in voice.files.all() && rel != "MODEL_CARD" && rel != "LICENSE") continue
                val target = File(tmp, rel)
                target.parentFile?.mkdirs()
                target.outputStream().use { tar.copyTo(it, 64 * 1024) }
            }
        }
        val missing = voice.files.all().filterNot { File(tmp, it).isFile }
        if (missing.isNotEmpty()) {
            tmp.deleteRecursively()
            throw VoiceDownloadException("Faltan ficheros en ${voice.id}: $missing")
        }
        File(tmp, VoiceStore.INSTALLED_MARKER).writeText(voice.id)
        val dest = store.voiceDir(voice.id)
        dest.deleteRecursively()
        if (!tmp.renameTo(dest)) throw VoiceDownloadException("No se pudo instalar ${voice.id}")
    }

    private companion object {
        const val DOWNLOAD_DIR = ".descargas"
        const val SPACE_MARGIN = 32L * 1024 * 1024
        const val REPORT_EVERY = 256L * 1024
    }
}
