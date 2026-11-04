package com.lectorvoz.format.epub

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Acceso aleatorio y seguro al zip del EPUB: solo expone entradas con rutas normalizadas
 * dentro del contenedor y lee cada recurso con limite de tamano y de ratio de compresion.
 *
 * Los nombres se leen en UTF-8; si el zip trae nombres en otra codificacion (zips antiguos
 * sin la marca UTF-8) se reabre en ISO-8859-1, que conserva los bytes, y cada entrada se
 * registra con su nombre interpretado como UTF-8, CP437 e ISO-8859-1. Si una ruta no existe
 * tal cual, se busca sin distinguir mayusculas (enlaces con otra capitalizacion).
 */
internal class ZipContainer(file: File, private val limits: EpubLimits) : Closeable {
    private val zip: ZipFile
    private val entries: Map<String, ZipEntry>

    /** Entradas por ruta en minusculas (la primera de cada ruta), para el respaldo. */
    private val lowerEntries: Map<String, ZipEntry>

    init {
        var lastError: Exception? = null
        var opened: ZipFile? = null
        var map: HashMap<String, ZipEntry>? = null
        for (raw in listOf(false, true)) {
            val z = try {
                ZipFile(file, if (raw) Charsets.ISO_8859_1 else Charsets.UTF_8)
            } catch (e: IOException) {
                // Tambien sale aqui un nombre que no es UTF-8 valido: se reintenta en bruto.
                lastError = e
                continue
            }
            try {
                map = index(z, raw)
                opened = z
                break
            } catch (e: EpubException) {
                z.close()
                throw e
            } catch (e: IllegalArgumentException) {
                // Nombre no valido en UTF-8 (al recorrer las entradas): se reintenta en bruto.
                z.close()
                lastError = e
            } catch (t: Throwable) {
                z.close()
                throw t
            }
        }
        if (opened == null || map == null) {
            throw EpubException.Invalid("No es un zip valido: ${file.name}", lastError)
        }
        zip = opened
        entries = map
        lowerEntries = HashMap<String, ZipEntry>(map.size).also { low ->
            for ((k, v) in map) low.putIfAbsent(k.lowercase(Locale.ROOT), v)
        }
    }

    /**
     * Indice de entradas por ruta normalizada. Con [raw] el zip se abrio en ISO-8859-1 (los
     * caracteres son los bytes del nombre) y cada entrada se registra con sus lecturas
     * posibles: UTF-8 si es valido, CP437 (la del formato zip) e ISO-8859-1.
     */
    private fun index(z: ZipFile, raw: Boolean): HashMap<String, ZipEntry> {
        val map = HashMap<String, ZipEntry>()
        var count = 0
        val en = z.entries()
        while (en.hasMoreElements()) {
            val e = en.nextElement()
            if (++count > limits.maxEntries) throw EpubException.Invalid("Demasiadas entradas en el zip")
            if (e.isDirectory) continue
            val names = if (raw) rawNames(e.name) else listOf(e.name)
            for (name in names) {
                // Las entradas con rutas peligrosas no son accesibles.
                val norm = EpubPaths.normalize(name.replace('\\', '/')) ?: continue
                map.putIfAbsent(norm, e)
            }
        }
        return map
    }

    /** Entrada de [path]: exacta o, si no hay, la misma ruta sin distinguir mayusculas. */
    private fun entry(path: String): ZipEntry? = entries[path] ?: lowerEntries[path.lowercase(Locale.ROOT)]

    fun exists(path: String): Boolean = entry(path) != null

    /** Nombres normalizados de todas las entradas accesibles. */
    val names: Set<String> get() = entries.keys

    /**
     * Lee [path] o devuelve null si no existe. Lanza [EpubException] si supera [maxBytes]
     * (por defecto el limite general de recursos) o el ratio de compresion.
     */
    fun readOrNull(path: String, maxBytes: Long = limits.maxResourceBytes): ByteArray? {
        val entry = entry(path) ?: return null
        if (entry.size > maxBytes) throw EpubException.TooLarge(path, maxBytes)
        val compressed = entry.compressedSize.takeIf { it > 0 } ?: 1L
        val isStored = entry.method == ZipEntry.STORED
        zip.getInputStream(entry).use { input ->
            // Con el tamano declarado se lee directamente en el array final (sin la copia de
            // ByteArrayOutputStream.toByteArray); si el tamano declarado miente se sigue con
            // un buffer creciente. Solo se reserva de golpe si el ratio declarado es creible
            // (si no, podria ser una bomba zip que se descubre al leer).
            val declared = entry.size
            val plausible = isStored || declared <= limits.ratioCheckMinBytes || declared / compressed <= limits.maxCompressionRatio
            var data = ByteArray(if (declared in 0..maxBytes && plausible) declared.toInt() else 64 * 1024)
            var total = 0
            while (true) {
                if (total == data.size) {
                    // Lleno: comprobar si queda algo antes de crecer.
                    val next = input.read()
                    if (next < 0) break
                    if (total + 1L > maxBytes) throw EpubException.TooLarge(path, maxBytes)
                    data = data.copyOf(minOf(maxBytes, maxOf(64L * 1024, total * 2L)).toInt().coerceAtLeast(total + 1))
                    data[total++] = next.toByte()
                    continue
                }
                val r = input.read(data, total, data.size - total)
                if (r < 0) break
                total += r
                if (total > maxBytes) throw EpubException.TooLarge(path, maxBytes)
                if (!isStored && total > limits.ratioCheckMinBytes && total / compressed > limits.maxCompressionRatio) {
                    throw EpubException.ZipBomb(path)
                }
            }
            return if (total == data.size) data else data.copyOf(total)
        }
    }

    /**
     * Primeros [maxBytes] bytes de [path] (o todo si es mas corto), sin limite de tamano
     * total porque no se lee el resto; null si no existe. Sirve para leer cabeceras.
     */
    fun readPrefixOrNull(path: String, maxBytes: Int): ByteArray? {
        val entry = entry(path) ?: return null
        zip.getInputStream(entry).use { input ->
            val data = ByteArray(if (entry.size in 0..maxBytes.toLong()) entry.size.toInt() else maxBytes)
            var total = 0
            while (total < data.size) {
                val r = input.read(data, total, data.size - total)
                if (r < 0) break
                total += r
            }
            return if (total == data.size) data else data.copyOf(total)
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

    private companion object {
        private val CP437: Charset? = runCatching { Charset.forName("IBM437") }.getOrNull()

        /** Lecturas de un nombre leido en ISO-8859-1 (sus caracteres son los bytes). */
        fun rawNames(latin1: String): List<String> {
            val bytes = latin1.toByteArray(Charsets.ISO_8859_1)
            val out = ArrayList<String>(3)
            val utf8 = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (e: CharacterCodingException) {
                null
            }
            utf8?.let { out.add(it) }
            CP437?.let { out.add(String(bytes, it)) }
            out.add(latin1)
            return out.distinct()
        }
    }
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
