package com.lectorvoz.tts.cache

import com.lectorvoz.tts.neural.SynthesisParams
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Audio guardado de una frase. */
class CachedAudio(val pcm: FloatArray, val sampleRate: Int)

/**
 * Cache de audio por frase en disco (salida cruda del modelo, antes de la cadena de audio,
 * para que cambiar realce/normalizacion/nocturno no la invalide).
 *
 * Formato: una carpeta por libro y, por capitulo, `c<N>.pcm` (PCM 16 bits little-endian,
 * solo se anade al final) + `c<N>.idx` (registros de 24 bytes: clave de 8 bytes, posicion
 * de 8, muestras de 4 y frecuencia de 4). El indice se escribe despues de los datos: un
 * corte deja como mucho datos huerfanos, nunca una entrada que apunte a datos a medias.
 *
 * Limite de tamano (300 MB por defecto) con desalojo LRU por capitulo (fecha de ultimo uso
 * del fichero). Bloqueante: usar fuera del hilo principal (el motor lo usa en su hilo de
 * sintesis). Seguro entre hilos.
 */
class AudioDiskCache(
    val root: File,
    @Volatile var maxBytes: Long = DEFAULT_MAX_BYTES,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val indexes = HashMap<File, HashMap<Long, Entry>>()
    private var totalBytes = -1L

    private class Entry(val offset: Long, val frames: Int, val sampleRate: Int)

    /** Audio de [text] con [params] y voz [voiceId] en el capitulo [chapter] de [bookId]. */
    fun get(bookId: String, chapter: Int, voiceId: String, params: SynthesisParams, text: String): CachedAudio? =
        synchronized(lock) {
            val base = chapterBase(bookId, chapter)
            val e = index(base)[key(voiceId, params, text)] ?: return null
            val pcmFile = File(base.path + PCM)
            try {
                val bytes = ByteArray(e.frames * 2)
                RandomAccessFile(pcmFile, "r").use { raf ->
                    raf.seek(e.offset)
                    raf.readFully(bytes)
                }
                pcmFile.setLastModified(now())
                val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                CachedAudio(FloatArray(e.frames) { shorts.get(it) / 32768f }, e.sampleRate)
            } catch (_: IOException) {
                null
            }
        }

    /** true si ya hay audio para esa frase. */
    fun contains(bookId: String, chapter: Int, voiceId: String, params: SynthesisParams, text: String): Boolean =
        synchronized(lock) { index(chapterBase(bookId, chapter)).containsKey(key(voiceId, params, text)) }

    /** Guarda [pcm] (float -1..1) y desaloja capitulos antiguos si se pasa del limite. */
    fun put(
        bookId: String,
        chapter: Int,
        voiceId: String,
        params: SynthesisParams,
        text: String,
        pcm: FloatArray,
        sampleRate: Int,
    ) = synchronized(lock) {
        val base = chapterBase(bookId, chapter)
        val k = key(voiceId, params, text)
        val idx = index(base)
        if (idx.containsKey(k)) return@synchronized
        ensureTotal()
        base.parentFile?.mkdirs()
        val pcmFile = File(base.path + PCM)
        val offset = pcmFile.length()
        val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (x in pcm) bytes.putShort((x.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        try {
            FileOutputStream(pcmFile, true).use { it.write(bytes.array()) }
            DataOutputStream(BufferedOutputStream(FileOutputStream(File(base.path + IDX), true))).use {
                it.writeLong(k); it.writeLong(offset); it.writeInt(pcm.size); it.writeInt(sampleRate)
            }
        } catch (_: IOException) {
            return@synchronized
        }
        pcmFile.setLastModified(now())
        idx[k] = Entry(offset, pcm.size, sampleRate)
        totalBytes += pcm.size * 2L + RECORD
        evictUntil(maxBytes) { it != pcmFile }
    }

    /**
     * Desaloja capitulos (los menos usados primero) hasta ocupar [targetBytes] o menos, sin
     * tocar los del libro [keepBookId]. true si se consigue.
     */
    fun trimTo(targetBytes: Long, keepBookId: String? = null): Boolean = synchronized(lock) {
        ensureTotal()
        val keepDir = keepBookId?.let { File(root, hashHex(it)) }
        evictUntil(targetBytes) { keepDir == null || it.parentFile != keepDir }
        totalBytes <= targetBytes
    }

    /** Bytes ocupados en disco. */
    fun sizeBytes(): Long = synchronized(lock) { ensureTotal(); totalBytes }

    /** Borra toda la cache. */
    fun clear() = synchronized(lock) {
        root.deleteRecursively()
        indexes.clear()
        totalBytes = 0
    }

    private fun ensureTotal() {
        if (totalBytes >= 0) return
        totalBytes = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Desaloja capitulos permitidos por [allowed] (los menos usados primero) hasta ocupar [limit] o menos. */
    private fun evictUntil(limit: Long, allowed: (File) -> Boolean) {
        if (totalBytes <= limit) return
        val chapters = root.walkTopDown().filter { it.isFile && it.name.endsWith(PCM) && allowed(it) }
            .sortedBy { it.lastModified() }.toList()
        for (pcm in chapters) {
            if (totalBytes <= limit) break
            val idx = File(pcm.path.removeSuffix(PCM) + IDX)
            totalBytes -= pcm.length() + idx.length()
            pcm.delete(); idx.delete()
            indexes.remove(File(pcm.path.removeSuffix(PCM)))
            pcm.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
        }
    }

    private fun index(base: File): HashMap<Long, Entry> = indexes.getOrPut(base) {
        val map = HashMap<Long, Entry>()
        val idx = File(base.path + IDX)
        val pcmLen = File(base.path + PCM).length()
        if (idx.isFile) {
            try {
                // Un registro a medias (corte durante la escritura) desalinearia los siguientes.
                val whole = idx.length() / RECORD * RECORD
                if (whole != idx.length()) {
                    RandomAccessFile(idx, "rw").use { it.setLength(whole) }
                    totalBytes = -1
                }
                DataInputStream(idx.inputStream().buffered()).use { input ->
                    val records = idx.length() / RECORD
                    for (i in 0 until records) {
                        val k = input.readLong()
                        val off = input.readLong()
                        val frames = input.readInt()
                        val rate = input.readInt()
                        if (off >= 0 && frames >= 0 && off + frames * 2L <= pcmLen) map[k] = Entry(off, frames, rate)
                    }
                }
            } catch (_: IOException) {
                // Indice danado: se ignora lo que no se pudo leer.
            }
        }
        map
    }

    private fun chapterBase(bookId: String, chapter: Int): File = File(File(root, hashHex(bookId)), "c$chapter")

    companion object {
        /** Limite por defecto: 300 MB. */
        const val DEFAULT_MAX_BYTES = 300L * 1024 * 1024
        private const val PCM = ".pcm"
        private const val IDX = ".idx"
        private const val RECORD = 24

        /**
         * Version de los ficheros del modelo: se sube cada vez que cambia algun .onnx de una voz
         * (el audio guardado con el modelo anterior deja de encontrarse y la LRU lo acaba
         * borrando). 2 = estimador optimizado para movil (30-10-2026).
         */
        const val MODEL_VERSION = 2

        /** Clave de 64 bits de la frase (SHA-256 truncado de modelo, voz, parametros y texto). */
        fun key(voiceId: String, params: SynthesisParams, text: String): Long {
            val d = MessageDigest.getInstance("SHA-256").digest(
                "m$MODEL_VERSION|$voiceId|${params.sid}|${params.lang}|${params.numSteps}|${params.speed.toBits()}|$text".toByteArray(),
            )
            return ByteBuffer.wrap(d, 0, 8).long
        }

        private fun hashHex(s: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            return d.take(12).joinToString("") { "%02x".format(it) }
        }
    }
}
