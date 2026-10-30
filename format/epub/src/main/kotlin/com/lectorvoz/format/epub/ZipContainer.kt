package com.lectorvoz.format.epub

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Acceso aleatorio y seguro al zip del EPUB: solo expone entradas con rutas normalizadas
 * dentro del contenedor y lee cada recurso con limite de tamano y de ratio de compresion.
 */
internal class ZipContainer(file: File, private val limits: EpubLimits) : Closeable {
    private val zip: ZipFile = try {
        ZipFile(file, Charsets.UTF_8)
    } catch (e: IOException) {
        throw EpubException.Invalid("No es un zip valido: ${file.name}", e)
    }
    private val entries: Map<String, ZipEntry>

    init {
        val map = HashMap<String, ZipEntry>()
        var count = 0
        try {
            val en = zip.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (++count > limits.maxEntries) throw EpubException.Invalid("Demasiadas entradas en el zip")
                if (e.isDirectory) continue
                // Las entradas con rutas peligrosas no son accesibles.
                val norm = EpubPaths.normalize(e.name.replace('\\', '/')) ?: continue
                map.putIfAbsent(norm, e)
            }
        } catch (t: Throwable) {
            zip.close()
            throw t
        }
        entries = map
    }

    fun exists(path: String): Boolean = entries.containsKey(path)

    /** Nombres normalizados de todas las entradas accesibles. */
    val names: Set<String> get() = entries.keys

    /**
     * Lee [path] o devuelve null si no existe. Lanza [EpubException] si supera [maxBytes]
     * (por defecto el limite general de recursos) o el ratio de compresion.
     */
    fun readOrNull(path: String, maxBytes: Long = limits.maxResourceBytes): ByteArray? {
        val entry = entries[path] ?: return null
        if (entry.size > maxBytes) throw EpubException.TooLarge(path, maxBytes)
        val compressed = entry.compressedSize.takeIf { it > 0 } ?: 1L
        val isStored = entry.method == ZipEntry.STORED
        zip.getInputStream(entry).use { input ->
            val out = ByteArrayOutputStream(if (entry.size in 1..(8L * 1024 * 1024)) entry.size.toInt() else 64 * 1024)
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                total += r
                if (total > maxBytes) throw EpubException.TooLarge(path, maxBytes)
                if (!isStored && total > limits.ratioCheckMinBytes && total / compressed > limits.maxCompressionRatio) {
                    throw EpubException.ZipBomb(path)
                }
                out.write(buf, 0, r)
            }
            return out.toByteArray()
        }
    }

    /** Lee [path] o lanza [EpubException.Invalid] si no existe. */
    fun read(path: String, maxBytes: Long = limits.maxResourceBytes): ByteArray =
        readOrNull(path, maxBytes) ?: throw EpubException.Invalid("Falta el recurso: $path")

    /** Lee un documento de texto (XHTML, OPF, NCX) con el limite especifico de XHTML. */
    fun readDocument(path: String): ByteArray = read(path, minOf(limits.maxXhtmlBytes, limits.maxResourceBytes))

    /** Como [readDocument] pero null si no existe. */
    fun readDocumentOrNull(path: String): ByteArray? = readOrNull(path, minOf(limits.maxXhtmlBytes, limits.maxResourceBytes))

    override fun close() = zip.close()
}

/** Utilidades de rutas dentro del contenedor (siempre con '/'). */
internal object EpubPaths {
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    /** true si [href] es externo (http:, mailto:...). */
    fun isExternal(href: String): Boolean = SCHEME.containsMatchIn(href)

    /** Directorio de [path] con '/' final, o "" en la raiz. */
    fun dirOf(path: String): String = path.substring(0, path.lastIndexOf('/') + 1)

    /**
     * Normaliza [path] (quita ".", resuelve ".."); null si sale del contenedor, es absoluta
     * o tiene unidad de Windows.
     */
    fun normalize(path: String): String? {
        if (path.startsWith("/") || (path.length >= 2 && path[1] == ':')) return null
        val parts = ArrayList<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    /**
     * Resuelve [href] (relativo al recurso [basePath], codificado como URL, sin fragmento)
     * a una ruta del contenedor. Lanza [EpubException.UnsafePath] si sale del contenedor.
     */
    fun resolve(basePath: String, href: String): String {
        val clean = percentDecode(href.substringBefore('#').substringBefore('?'))
        val joined = if (clean.startsWith("/")) clean.trimStart('/') else dirOf(basePath) + clean
        return normalize(joined) ?: throw EpubException.UnsafePath(href)
    }

    /** Valor de un digito hexadecimal ASCII o -1. */
    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /** Decodifica %XX en UTF-8 (sin convertir '+' en espacio, al contrario que URLDecoder); un '%' sin
     * dos digitos hexadecimales detras se deja tal cual. */
    fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hi = hex(s[i + 1])
                val lo = hex(s[i + 2])
                if (hi >= 0 && lo >= 0) {
                    out.write(hi * 16 + lo)
                    i += 3
                    continue
                }
            }
            val cp = s.codePointAt(i)
            val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
            out.write(bytes, 0, bytes.size)
            i += Character.charCount(cp)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
