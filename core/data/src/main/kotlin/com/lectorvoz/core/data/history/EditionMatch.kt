package com.lectorvoz.core.data.history

import java.text.Normalizer

/**
 * Parecido entre lecturas del historial para proponer "es otra edicion de...". Prudente: solo
 * propone (el usuario decide) y nunca con autores distintos. Compara titulos normalizados (sin
 * acentos, mayusculas, signos, articulos iniciales ni subtitulo) palabra a palabra; los numeros
 * cuentan, asi que el tomo 2 de una serie no se confunde con el 1.
 */
object EditionMatch {
    private val ARTICLES = setOf("el", "la", "los", "las", "un", "una", "lo", "the", "a", "an")

    /** Palabras que no distinguen un libro de otro (nombres de fichero y ediciones). */
    private val NOISE = setOf("edicion", "ed", "edition", "epub", "pdf", "ebook", "libro", "completo", "completa", "spanish", "espanol", "es")

    /** Parecido minimo (palabras en comun sobre el total) para proponerla. */
    private const val MIN_SIMILARITY = 0.6

    /** Lecturas de [others] que pueden ser el mismo libro que [entry], la mas parecida primero. */
    fun candidates(entry: HistoryEntry, others: List<HistoryEntry>, max: Int = 3): List<HistoryEntry> {
        val words = titleWords(entry.title)
        if (words.isEmpty()) return emptyList()
        return others.asSequence()
            .filter { it.id != entry.id && sameAuthorOrUnknown(entry, it) }
            .map { it to similarity(words, titleWords(it.title)) }
            .filter { it.second >= MIN_SIMILARITY }
            .sortedByDescending { it.second }
            .take(max)
            .map { it.first }
            .toList()
    }

    /** Palabras significativas del titulo. */
    fun titleWords(title: String): Set<String> {
        // Sin subtitulo ("Titulo: subtitulo", "Titulo (Saga 1)" se queda con lo de fuera).
        val main = title.substringBefore(':').replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        val words = normalize(main).split(' ').filter { it.isNotEmpty() && it !in NOISE }
        return words.dropWhile { it in ARTICLES }.toSet()
    }

    private fun similarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val common = a.intersect(b).size
        // Los numeros deben coincidir todos (tomos de una serie).
        if (a.filter { it.all(Char::isDigit) }.toSet() != b.filter { it.all(Char::isDigit) }.toSet()) return 0.0
        return common.toDouble() / a.union(b).size
    }

    private fun sameAuthorOrUnknown(a: HistoryEntry, b: HistoryEntry): Boolean {
        val x = surname(a.author) ?: return true
        val y = surname(b.author) ?: return true
        return x == y
    }

    private fun surname(author: String?): String? {
        val n = author?.let { normalize(it) }?.split(' ')?.filter { it.length > 1 }.orEmpty()
        if (n.isEmpty() || n.first() in setOf("desconocido", "unknown", "anonimo")) return null
        // "Apellido, Nombre" o "Nombre Apellido".
        return if (author!!.contains(',')) n.first() else n.last()
    }

    internal fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
}
