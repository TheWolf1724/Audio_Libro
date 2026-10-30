package com.lectorvoz.reader

import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.LocatorRange

/**
 * Regla de "seguir la lectura" sin oscilar (logica pura): decide si hay que cambiar de
 * pagina para que [target] sea visible.
 *
 * Una frase que empieza en una pagina y acaba en la siguiente produce peticiones con su
 * inicio (pagina anterior) y con sus palabras (pagina siguiente). Para no ir y volver,
 * no se retrocede por una posicion que pertenece a la frase activa si esa frase sigue
 * visible en la pagina actual.
 */
object FollowPolicy {
    /**
     * [pageStart] y [pageEnd] (exclusivo) delimitan la pagina mostrada; [activeSentence]
     * es la frase que se esta leyendo (o null).
     */
    fun shouldMove(pageStart: Locator, pageEnd: Locator, target: Locator, activeSentence: LocatorRange?): Boolean {
        if (target >= pageStart && target < pageEnd) return false
        if (activeSentence != null && target in activeSentence && target < pageStart &&
            activeSentence.endExclusive > pageStart
        ) {
            return false
        }
        return true
    }
}
