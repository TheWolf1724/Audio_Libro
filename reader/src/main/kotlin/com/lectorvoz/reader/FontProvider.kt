package com.lectorvoz.reader

import android.graphics.Typeface
import android.os.Build

/**
 * Proveedor de tipografias de lectura. `:app` aportara Literata, Atkinson Hyperlegible
 * Next y OpenDyslexic desde sus recursos; [Default] usa las del sistema.
 */
fun interface FontProvider {
    /**
     * Tipografia de la familia [family] ("literata", "atkinson", "opendyslexic", "system",
     * "book") con peso [weight] (100..900) y cursiva [italic].
     */
    fun typeface(family: String, weight: Int, italic: Boolean): Typeface

    companion object {
        /** Tipografias del sistema: serif para Literata/libro, sans para el resto. */
        val Default: FontProvider = FontProvider { family, weight, italic ->
            val base = when (family) {
                "literata", "book" -> Typeface.SERIF
                else -> Typeface.SANS_SERIF
            }
            if (Build.VERSION.SDK_INT >= 28) {
                Typeface.create(base, weight.coerceIn(1, 1000), italic)
            } else {
                val style = when {
                    weight >= 600 && italic -> Typeface.BOLD_ITALIC
                    weight >= 600 -> Typeface.BOLD
                    italic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                }
                Typeface.create(base, style)
            }
        }
    }
}
