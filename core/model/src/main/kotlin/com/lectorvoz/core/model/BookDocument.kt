package com.lectorvoz.core.model

/**
 * Libro refluible ya analizado (EPUB). Lo produce `:format:epub` y lo consume `:reader`
 * para maquetar. Los capitulos se cargan bajo demanda para no tener el libro entero en
 * memoria.
 */
interface DocumentSource : AutoCloseable {
    val metadata: BookMetadata

    /** Indice jerarquico; puede estar vacio si el libro no lo trae. */
    val toc: List<TocEntry>

    /** Numero de capitulos (recursos del spine). */
    val chapterCount: Int

    /** Analiza y devuelve el capitulo [index] (0..chapterCount-1). */
    suspend fun chapter(index: Int): ChapterDocument

    /**
     * Bytes de un recurso del libro (imagenes, fuentes). [href] es la ruta ya resuelta
     * dentro del contenedor, tal como aparece en [Block.Image.href]. Nulo si no existe.
     */
    suspend fun resource(href: String): ByteArray?
}

/** Metadatos del libro. */
data class BookMetadata(
    val title: String,
    val authors: List<String> = emptyList(),
    /** Idioma BCP-47 principal, si se declara. */
    val language: String? = null,
    val publisher: String? = null,
    val description: String? = null,
    /** Identificador declarado (ISBN, UUID...), si existe. */
    val identifier: String? = null,
    /** Ruta del recurso de portada dentro del contenedor, si existe. */
    val coverHref: String? = null,
)

/** Entrada del indice; [target] apunta al inicio de la seccion. */
data class TocEntry(
    val title: String,
    val target: Locator,
    val children: List<TocEntry> = emptyList(),
)

/**
 * Capitulo analizado. [plainText] es la concatenacion de los bloques de texto separados por
 * '\n'; las imagenes ocupan un caracter [OBJECT_CHAR] para que los desplazamientos sean
 * estables. `Locator(index, offset)` se refiere a desplazamientos en [plainText].
 */
data class ChapterDocument(
    val index: Int,
    val title: String?,
    val language: String?,
    val plainText: String,
    val blocks: List<Block>,
    /** Anclas internas (id de elemento -> desplazamiento) para enlaces y el indice. */
    val anchors: Map<String, Int> = emptyMap(),
) {
    companion object {
        /** Caracter de sustitucion de objeto que ocupa el sitio de una imagen. */
        const val OBJECT_CHAR = '￼'
    }
}

/** Bloque de contenido; [start, end) es su rango en [ChapterDocument.plainText]. */
sealed interface Block {
    val start: Int
    val end: Int

    /** Bloque de texto (parrafo, titulo, cita, elemento de lista, codigo...). */
    data class Text(
        override val start: Int,
        override val end: Int,
        val kind: BlockKind,
        val spans: List<InlineSpan> = emptyList(),
        /** Alineacion pedida por el libro, si la hay. */
        val align: BlockAlign? = null,
        /** Nivel de anidamiento (listas, citas). */
        val depth: Int = 0,
    ) : Block

    /** Imagen; ocupa un unico [ChapterDocument.OBJECT_CHAR]. */
    data class Image(
        override val start: Int,
        val href: String,
        val alt: String?,
        val widthPx: Int? = null,
        val heightPx: Int? = null,
    ) : Block {
        override val end: Int get() = start + 1
    }

    /** Separador horizontal (no ocupa texto). */
    data class Rule(override val start: Int) : Block {
        override val end: Int get() = start
    }
}

/** Tipo de bloque de texto. */
sealed interface BlockKind {
    data object Paragraph : BlockKind
    data class Heading(val level: Int) : BlockKind
    data object Blockquote : BlockKind
    /** Elemento de lista; [marker] es "•", "1."... ya calculado. */
    data class ListItem(val marker: String) : BlockKind
    data object Preformatted : BlockKind
    data object Caption : BlockKind
}

enum class BlockAlign { START, CENTER, END, JUSTIFY }

/** Estilo en linea sobre [start, end) del texto plano del capitulo. */
data class InlineSpan(val start: Int, val end: Int, val style: InlineStyle)

sealed interface InlineStyle {
    data object Bold : InlineStyle
    data object Italic : InlineStyle
    data object Underline : InlineStyle
    data object Strikethrough : InlineStyle
    data object Superscript : InlineStyle
    data object Subscript : InlineStyle
    data object Monospace : InlineStyle
    data object SmallCaps : InlineStyle
    /** Enlace; [href] interno ya resuelto ("capitulo.xhtml#id") o externo ("https://..."). */
    data class Link(val href: String) : InlineStyle
}
