package com.lectorvoz.core.text

import com.lectorvoz.core.model.FootnoteMode
import com.lectorvoz.core.model.ReadingLanguage
import com.lectorvoz.core.model.VoiceSettings

/**
 * Opciones de preparacion del texto para la voz (ver [UtteranceBuilder]).
 *
 * @param readingLanguage AUTO detecta por frase; SPANISH/ENGLISH fuerzan todo el capitulo.
 * @param footnotes SKIP omite las llamadas de nota; END_OF_PARAGRAPH ademas lee el texto de
 *   la nota (si se puede resolver) al final del parrafo, como unidad FOOTNOTE.
 * @param readTables false omite las filas de tabla.
 * @param announceChapters anade al inicio una unidad CHAPTER_ANNOUNCE "Capitulo N. Titulo."
 *   (solo si se empieza desde el principio y hay numero o titulo).
 * @param chapterNumber numero de capitulo para el anuncio (nulo: sin numero; en PDF, nulo).
 * @param chapterTitle titulo para el anuncio; nulo: el de `ChapterText`/`ChapterDocument`.
 * @param footnoteTexts texto de cada nota por `href` del enlace (p. ej. de
 *   `EpubBook.footnoteTexts`); las notas del mismo capitulo se resuelven solas.
 * @param chapterPath ruta del capitulo EPUB en el contenedor (`EpubBook.chapterPath`): una
 *   nota se busca en el propio capitulo solo si el enlace apunta a esta ruta. Nulo: solo si
 *   el destino parece una nota.
 */
data class TextOptions(
    val readingLanguage: ReadingLanguage = ReadingLanguage.AUTO,
    val footnotes: FootnoteMode = FootnoteMode.SKIP,
    val readTables: Boolean = true,
    val announceChapters: Boolean = false,
    val chapterNumber: Int? = null,
    val chapterTitle: String? = null,
    val footnoteTexts: Map<String, String> = emptyMap(),
    val chapterPath: String? = null,
) {
    companion object {
        /** Opciones a partir de los ajustes de voz del usuario. */
        fun from(
            settings: VoiceSettings,
            chapterNumber: Int? = null,
            chapterTitle: String? = null,
            footnoteTexts: Map<String, String> = emptyMap(),
            chapterPath: String? = null,
        ): TextOptions = TextOptions(
            readingLanguage = settings.readingLanguage,
            footnotes = settings.footnotes,
            readTables = settings.readTables,
            announceChapters = settings.announceChapters,
            chapterNumber = chapterNumber,
            chapterTitle = chapterTitle,
            footnoteTexts = footnoteTexts,
            chapterPath = chapterPath,
        )
    }
}
