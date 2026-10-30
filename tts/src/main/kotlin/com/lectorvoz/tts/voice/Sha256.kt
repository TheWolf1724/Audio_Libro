package com.lectorvoz.tts.voice

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Utilidades SHA-256 (hex en minusculas). */
object Sha256 {
    /** SHA-256 de [file]. */
    fun of(file: File): String = file.inputStream().use { of(it) }

    /** SHA-256 del flujo completo [input] (no lo cierra). */
    fun of(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return hex(md.digest())
    }

    /** SHA-256 de [bytes]. */
    fun of(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** true si [file] tiene el SHA-256 [expected] (sin distinguir mayusculas). */
    fun matches(file: File, expected: String): Boolean =
        file.isFile && of(file).equals(expected, ignoreCase = true)

    private fun hex(d: ByteArray): String {
        val sb = StringBuilder(d.size * 2)
        for (b in d) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
