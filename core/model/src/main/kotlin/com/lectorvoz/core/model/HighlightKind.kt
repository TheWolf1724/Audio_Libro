package com.lectorvoz.core.model

/** Tipo de resaltado que pinta el lector. */
enum class HighlightKind {
    /** Frase que se esta leyendo. */
    SENTENCE,

    /** Palabra que se esta leyendo. */
    WORD,

    /** Resultado de busqueda. */
    SEARCH,

    /** Seleccion del usuario. */
    SELECTION,
}
