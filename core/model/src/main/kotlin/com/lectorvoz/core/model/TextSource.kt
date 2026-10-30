package com.lectorvoz.core.model

/** Texto legible secuencial para la voz, por capitulo (EPUB) o pagina (PDF). */
interface TextSource {
    val format: BookFormat
    val chapterCount: Int

    /** Devuelve el texto plano del capitulo (o pagina) [chapter]. */
    suspend fun chapterText(chapter: Int): ChapterText
}

/** Texto plano de un capitulo o pagina. */
data class ChapterText(
    val chapter: Int,
    /** Texto plano ya limpio (sin cabeceras/pies en PDF). */
    val text: String,
    val title: String?,
    /** Idioma BCP-47 si se conoce. */
    val language: String?,
)
