package com.lectorvoz.core.model

/** Unidad que la voz lee de una vez (normalmente una frase). */
class Utterance(
    val id: Long,
    /** Rango en el texto plano original (para resaltar). */
    val range: LocatorRange,
    /** Texto tal cual aparece. */
    val displayText: String,
    /** Texto normalizado para sintetizar. */
    val spokenText: String,
    /**
     * Mapa de indices spoken -> display (resaltar palabra): la posicion i de
     * [spokenText] corresponde a la posicion `spokenToDisplay[i]` de [displayText].
     */
    val spokenToDisplay: IntArray,
    /** Idioma con el que se normalizo y debe sintetizarse ("es", "en"). */
    val language: String = "es",
    /** Que clase de texto es (afecta a ritmo, pausas y voz). */
    val kind: UtteranceKind = UtteranceKind.SENTENCE,
    /** Pausa que debe seguir a esta unidad. */
    val pauseAfter: PauseKind = PauseKind.SENTENCE,
    /** Quien habla: narrador o personaje (dialogo con raya o comillas). */
    val role: VoiceRole = VoiceRole.NARRATOR,
) {
    // No es data class: equals/hashCode deben comparar el contenido del IntArray.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Utterance) return false
        return id == other.id &&
            range == other.range &&
            displayText == other.displayText &&
            spokenText == other.spokenText &&
            spokenToDisplay.contentEquals(other.spokenToDisplay) &&
            language == other.language &&
            kind == other.kind &&
            pauseAfter == other.pauseAfter &&
            role == other.role
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + range.hashCode()
        result = 31 * result + displayText.hashCode()
        result = 31 * result + spokenText.hashCode()
        result = 31 * result + spokenToDisplay.contentHashCode()
        result = 31 * result + language.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + pauseAfter.hashCode()
        result = 31 * result + role.hashCode()
        return result
    }

    override fun toString(): String =
        "Utterance(id=$id, range=$range, displayText=$displayText, spokenText=$spokenText, " +
            "spokenToDisplay=${spokenToDisplay.contentToString()}, language=$language, " +
            "kind=$kind, pauseAfter=$pauseAfter, role=$role)"

    fun copy(
        id: Long = this.id,
        range: LocatorRange = this.range,
        displayText: String = this.displayText,
        spokenText: String = this.spokenText,
        spokenToDisplay: IntArray = this.spokenToDisplay,
        language: String = this.language,
        kind: UtteranceKind = this.kind,
        pauseAfter: PauseKind = this.pauseAfter,
        role: VoiceRole = this.role,
    ): Utterance =
        Utterance(id, range, displayText, spokenText, spokenToDisplay, language, kind, pauseAfter, role)
}

/** Clase de texto de una [Utterance]. */
enum class UtteranceKind {
    SENTENCE,
    /** Titulo o encabezado dentro del capitulo. */
    HEADING,
    /** Anuncio sintetico "Capitulo N, titulo" (no existe en el texto). */
    CHAPTER_ANNOUNCE,
    LIST_ITEM,
    /** Linea de poesia o verso (respetar el salto de linea como pausa). */
    VERSE_LINE,
    /** Nota al pie leida en linea. */
    FOOTNOTE,
    /** Celda o fila de tabla linealizada. */
    TABLE,
}

/** Fuerza de la pausa tras una unidad, de menor a mayor. */
enum class PauseKind { NONE, CLAUSE, SENTENCE, VERSE, PARAGRAPH, SECTION, CHAPTER }

/** Papel de quien habla, para poder usar otra voz en los dialogos. */
enum class VoiceRole { NARRATOR, DIALOG }
