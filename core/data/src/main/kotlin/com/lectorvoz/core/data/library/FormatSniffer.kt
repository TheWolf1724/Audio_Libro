package com.lectorvoz.core.data.library

import com.lectorvoz.core.model.BookFormat
import java.security.MessageDigest

/** Deteccion de formato por bytes magicos (no por extension ni tipo MIME). */
object FormatSniffer {
    /** Bytes de cabecera que necesita [detect]. */
    const val HEAD_BYTES = 1024

    private val PDF = "%PDF-".toByteArray(Charsets.US_ASCII)
    private val ZIP = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val EPUB_MIME = "application/epub+zip".toByteArray(Charsets.US_ASCII)

    /**
     * Formato de un fichero a partir de sus primeros bytes [head]. PDF: "%PDF-" en el primer
     * KiB (algunos generadores anteponen basura). EPUB: zip cuyo primer fichero es
     * "mimetype" con "application/epub+zip"; un zip sin ese mimetype solo se acepta si
     * [name] acaba en ".epub". Nulo si no es un formato admitido.
     */
    fun detect(head: ByteArray, name: String? = null): BookFormat? {
        if (indexOf(head, PDF, limit = HEAD_BYTES) >= 0) return BookFormat.PDF
        if (startsWith(head, ZIP)) {
            if (indexOf(head, EPUB_MIME, limit = 128) >= 0) return BookFormat.EPUB
            if (name?.lowercase()?.endsWith(".epub") == true) return BookFormat.EPUB
        }
        return null
    }

    private fun startsWith(a: ByteArray, prefix: ByteArray): Boolean =
        a.size >= prefix.size && prefix.indices.all { a[it] == prefix[it] }

    private fun indexOf(a: ByteArray, needle: ByteArray, limit: Int): Int {
        val end = minOf(a.size, limit) - needle.size
        var i = 0
        while (i <= end) {
            var j = 0
            while (j < needle.size && a[i + j] == needle[j]) j++
            if (j == needle.size) return i
            i++
        }
        return -1
    }
}

/**
 * Hash rapido para detectar duplicados sin leer dos veces: SHA-256 de los primeros y los
 * ultimos 64 KiB mas el tamano. Se alimenta en streaming ([update]) mientras se copia.
 */
class QuickHasher(private val window: Int = WINDOW) {
    private val head = ByteArray(window)
    private var headLen = 0
    private val tail = ByteArray(window)
    private var tailPos = 0
    private var tailFilled = false
    var size: Long = 0
        private set

    fun update(buf: ByteArray, off: Int, len: Int) {
        size += len
        var i = off
        val end = off + len
        while (i < end && headLen < window) head[headLen++] = buf[i++]
        // La cola recibe todo (tambien lo que ya entro en la cabeza): resultado determinista.
        var k = off
        while (k < end) {
            val n = minOf(end - k, window - tailPos)
            System.arraycopy(buf, k, tail, tailPos, n)
            tailPos += n
            k += n
            if (tailPos == window) { tailPos = 0; tailFilled = true }
        }
    }

    /** Hash hexadecimal. */
    fun digest(): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(head, 0, headLen)
        if (tailFilled) {
            md.update(tail, tailPos, window - tailPos)
            md.update(tail, 0, tailPos)
        } else {
            md.update(tail, 0, tailPos)
        }
        val s = size
        md.update(ByteArray(8) { i -> (s ushr (56 - 8 * i)).toByte() })
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val WINDOW = 64 * 1024
    }
}
