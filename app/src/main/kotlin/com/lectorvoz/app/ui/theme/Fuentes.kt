package com.lectorvoz.app.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.res.ResourcesCompat
import com.lectorvoz.app.R

/**
 * Familias tipograficas de la app (todas OFL, licencias en assets/licencias). Nombres
 * estables para ajustes y para `:reader`: "literata", "atkinson", "opendyslexic", "system".
 */
object ReadingFonts {
    const val LITERATA = "literata"
    const val ATKINSON = "atkinson"
    const val OPENDYSLEXIC = "opendyslexic"
    const val SYSTEM = "system"

    /** Nombres disponibles en orden de presentacion. */
    val names = listOf(LITERATA, ATKINSON, OPENDYSLEXIC, SYSTEM)

    private fun variable(res: Int, weight: Int, style: FontStyle) = Font(
        res,
        weight = FontWeight(weight),
        style = style,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
    )

    private val pesos = listOf(300, 400, 500, 600, 700)

    val literata: FontFamily = FontFamily(
        pesos.map { variable(R.font.literata, it, FontStyle.Normal) } +
            pesos.map { variable(R.font.literata_italic, it, FontStyle.Italic) },
    )

    val atkinson: FontFamily = FontFamily(
        pesos.map { variable(R.font.atkinson_next, it, FontStyle.Normal) } +
            pesos.map { variable(R.font.atkinson_next_italic, it, FontStyle.Italic) },
    )

    val openDyslexic: FontFamily = FontFamily(
        Font(R.font.opendyslexic_regular, FontWeight.Normal, FontStyle.Normal),
        Font(R.font.opendyslexic_bold, FontWeight.Bold, FontStyle.Normal),
        Font(R.font.opendyslexic_italic, FontWeight.Normal, FontStyle.Italic),
        Font(R.font.opendyslexic_bold_italic, FontWeight.Bold, FontStyle.Italic),
    )

    /** Familia de Compose para [name]; desconocida -> fuente del sistema. */
    fun fontFamily(name: String): FontFamily = when (name) {
        LITERATA -> literata
        ATKINSON -> atkinson
        OPENDYSLEXIC -> openDyslexic
        else -> FontFamily.Default
    }

    /**
     * Typeface de Android para vistas (`:reader`, StaticLayout). Bloqueante la primera vez:
     * llamar fuera del hilo principal. [weight] solo se aplica en API 28+.
     */
    fun typeface(context: Context, name: String, weight: Int = 400, italic: Boolean = false): Typeface {
        val res = when (name) {
            LITERATA -> if (italic) R.font.literata_italic else R.font.literata
            ATKINSON -> if (italic) R.font.atkinson_next_italic else R.font.atkinson_next
            OPENDYSLEXIC -> when {
                italic && weight >= 600 -> R.font.opendyslexic_bold_italic
                italic -> R.font.opendyslexic_italic
                weight >= 600 -> R.font.opendyslexic_bold
                else -> R.font.opendyslexic_regular
            }
            else -> null
        }
        val base = res?.let { ResourcesCompat.getFont(context, it) } ?: Typeface.DEFAULT
        return if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, weight.coerceIn(1, 1000), italic) else base
    }
}

/** Escala tipografica M3 con Atkinson Hyperlegible Next para toda la interfaz. */
val AppTypography: Typography = Typography().let { t ->
    val f = ReadingFonts.atkinson
    Typography(
        displayLarge = t.displayLarge.copy(fontFamily = f),
        displayMedium = t.displayMedium.copy(fontFamily = f),
        displaySmall = t.displaySmall.copy(fontFamily = f),
        headlineLarge = t.headlineLarge.copy(fontFamily = f),
        headlineMedium = t.headlineMedium.copy(fontFamily = f),
        headlineSmall = t.headlineSmall.copy(fontFamily = f),
        titleLarge = t.titleLarge.copy(fontFamily = f),
        titleMedium = t.titleMedium.copy(fontFamily = f),
        titleSmall = t.titleSmall.copy(fontFamily = f),
        bodyLarge = t.bodyLarge.copy(fontFamily = f),
        bodyMedium = t.bodyMedium.copy(fontFamily = f),
        bodySmall = t.bodySmall.copy(fontFamily = f),
        labelLarge = t.labelLarge.copy(fontFamily = f),
        labelMedium = t.labelMedium.copy(fontFamily = f),
        labelSmall = t.labelSmall.copy(fontFamily = f),
    )
}
