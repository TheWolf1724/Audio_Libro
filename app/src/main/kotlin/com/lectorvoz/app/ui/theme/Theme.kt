package com.lectorvoz.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.lectorvoz.core.model.ThemeMode

/** Colores propios de la superficie de lectura (roles `lectura` de temas.json). */
@Immutable
data class ReadingColors(
    val background: Color,
    val text: Color,
    val secondaryText: Color,
    val link: Color,
    val ttsSentenceHighlight: Color,
    val ttsSentenceIndicator: Color,
    val ttsWordHighlight: Color,
    val ttsWordText: Color,
    val ttsWordIndicator: Color,
    val selection: Color,
    val searchHighlight: Color,
    val searchOutline: Color,
    val pageBack: Color,
    val barBackground: Color,
    val barContent: Color,
    val progressTrack: Color,
    val progressIndicator: Color,
    val divider: Color,
    val pageEdgeShadow: Color,
)

/** Tema resuelto: esquema Material 3 + colores de lectura. */
@Immutable
data class ResolvedTheme(val key: String, val isDark: Boolean, val colorScheme: ColorScheme, val reading: ReadingColors)

/** Clave de temas.json para cada [ThemeMode] (SYSTEM se resuelve con [systemDark]). */
fun themeKey(mode: ThemeMode, systemDark: Boolean): String = when (mode) {
    ThemeMode.LIGHT -> "claro"
    ThemeMode.DARK -> "oscuro"
    ThemeMode.DARK_AMOLED -> "oscuroAmoled"
    ThemeMode.EYE_CARE -> "proteccionOcular"
    ThemeMode.EYE_CARE_NIGHT -> "proteccionOcularNoche"
    ThemeMode.SYSTEM -> if (systemDark) "oscuro" else "claro"
}

/** Construye el tema [key] de temas.json, con la capa de aumento de contraste si [highContrast]. */
fun resolveTheme(key: String, highContrast: Boolean): ResolvedTheme {
    val t = TEMAS_GENERADOS.first { it.clave == key }
    val m = if (highContrast) t.material3 + t.altoMaterial3 else t.material3
    val l = if (highContrast) t.lectura + t.altoLectura else t.lectura
    fun c(map: Map<String, Long>, name: String) = Color(map.getValue(name))
    fun m3(name: String) = c(m, name)
    fun r(name: String) = c(l, name)
    val scheme = ColorScheme(
        primary = m3("primary"), onPrimary = m3("onPrimary"),
        primaryContainer = m3("primaryContainer"), onPrimaryContainer = m3("onPrimaryContainer"),
        inversePrimary = m3("inversePrimary"),
        secondary = m3("secondary"), onSecondary = m3("onSecondary"),
        secondaryContainer = m3("secondaryContainer"), onSecondaryContainer = m3("onSecondaryContainer"),
        tertiary = m3("tertiary"), onTertiary = m3("onTertiary"),
        tertiaryContainer = m3("tertiaryContainer"), onTertiaryContainer = m3("onTertiaryContainer"),
        background = m3("background"), onBackground = m3("onBackground"),
        surface = m3("surface"), onSurface = m3("onSurface"),
        surfaceVariant = m3("surfaceVariant"), onSurfaceVariant = m3("onSurfaceVariant"),
        surfaceTint = m3("surfaceTint"),
        inverseSurface = m3("inverseSurface"), inverseOnSurface = m3("inverseOnSurface"),
        error = m3("error"), onError = m3("onError"),
        errorContainer = m3("errorContainer"), onErrorContainer = m3("onErrorContainer"),
        outline = m3("outline"), outlineVariant = m3("outlineVariant"), scrim = m3("scrim"),
        surfaceBright = m3("surfaceBright"), surfaceDim = m3("surfaceDim"),
        surfaceContainer = m3("surfaceContainer"), surfaceContainerHigh = m3("surfaceContainerHigh"),
        surfaceContainerHighest = m3("surfaceContainerHighest"), surfaceContainerLow = m3("surfaceContainerLow"),
        surfaceContainerLowest = m3("surfaceContainerLowest"),
        primaryFixed = m3("primaryFixed"), primaryFixedDim = m3("primaryFixedDim"),
        onPrimaryFixed = m3("onPrimaryFixed"), onPrimaryFixedVariant = m3("onPrimaryFixedVariant"),
        secondaryFixed = m3("secondaryFixed"), secondaryFixedDim = m3("secondaryFixedDim"),
        onSecondaryFixed = m3("onSecondaryFixed"), onSecondaryFixedVariant = m3("onSecondaryFixedVariant"),
        tertiaryFixed = m3("tertiaryFixed"), tertiaryFixedDim = m3("tertiaryFixedDim"),
        onTertiaryFixed = m3("onTertiaryFixed"), onTertiaryFixedVariant = m3("onTertiaryFixedVariant"),
    )
    val reading = ReadingColors(
        background = r("readerBackground"), text = r("readerText"), secondaryText = r("readerSecondaryText"),
        link = r("readerLink"),
        ttsSentenceHighlight = r("ttsSentenceHighlight"), ttsSentenceIndicator = r("ttsSentenceIndicator"),
        ttsWordHighlight = r("ttsWordHighlight"), ttsWordText = r("ttsWordText"), ttsWordIndicator = r("ttsWordIndicator"),
        selection = r("selection"), searchHighlight = r("searchHighlight"), searchOutline = r("searchOutline"),
        pageBack = r("readerPageBack"), barBackground = r("readerBarBackground"), barContent = r("readerBarContent"),
        progressTrack = r("readerProgressTrack"), progressIndicator = r("readerProgressIndicator"),
        divider = r("readerDivider"), pageEdgeShadow = r("pageEdgeShadow"),
    )
    return ResolvedTheme(key, t.esOscuro, scheme, reading)
}

/** Colores de lectura del tema actual. */
val LocalReadingColors = staticCompositionLocalOf { resolveTheme("claro", false).reading }

/** Tema de la app: aplica a toda la UI el tema elegido y la capa de contraste. */
@Composable
fun LectorVozTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    highContrast: Boolean = false,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val key = themeKey(themeMode, systemDark)
    val theme = remember(key, highContrast) { resolveTheme(key, highContrast) }
    CompositionLocalProvider(LocalReadingColors provides theme.reading) {
        MaterialTheme(colorScheme = theme.colorScheme, typography = AppTypography, content = content)
    }
}
