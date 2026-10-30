package com.lectorvoz.reader

import com.lectorvoz.core.model.ThemeMode

/**
 * Colores de lectura (ARGB) de un tema. Valores copiados de `docs/diseno/temas.json`
 * (seccion `lectura` y `altoContraste.lectura`); si cambia el JSON hay que actualizarlos.
 */
data class ReaderColors(
    val isDark: Boolean,
    val readerBackground: Int,
    val readerText: Int,
    val readerSecondaryText: Int,
    val readerLink: Int,
    val ttsSentenceHighlight: Int,
    val ttsSentenceIndicator: Int,
    val ttsWordHighlight: Int,
    val ttsWordText: Int,
    val ttsWordIndicator: Int,
    val selection: Int,
    val selectionHandle: Int,
    val searchHighlight: Int,
    val searchOutline: Int,
    val searchCurrentHighlight: Int,
    val searchCurrentText: Int,
    val readerPageBack: Int,
    val readerDivider: Int,
    /** Sombra del borde de pagina (con alfa). */
    val pageEdgeShadow: Int,
) {
    companion object {
        val LIGHT = ReaderColors(
            isDark = false,
            readerBackground = 0xFFFDFAF6.toInt(), readerText = 0xFF211F1D.toInt(), readerSecondaryText = 0xFF514D47.toInt(),
            readerLink = 0xFF154C97.toInt(), ttsSentenceHighlight = 0xFFD5E7FF.toInt(), ttsSentenceIndicator = 0xFF0078B8.toInt(),
            ttsWordHighlight = 0xFFF3D25A.toInt(), ttsWordText = 0xFF211F1D.toInt(), ttsWordIndicator = 0xFF7F4F0F.toInt(),
            selection = 0xFFCAC0F0.toInt(), selectionHandle = 0xFF325CAA.toInt(), searchHighlight = 0xFFFFBDA0.toInt(),
            searchOutline = 0xFFB9442C.toInt(), searchCurrentHighlight = 0xFFFB8F4F.toInt(), searchCurrentText = 0xFF211F1D.toInt(),
            readerPageBack = 0xFFF1EDE8.toInt(), readerDivider = 0xFFCCC6BB.toInt(), pageEdgeShadow = 0x1F000000,
        )

        val DARK = ReaderColors(
            isDark = true,
            readerBackground = 0xFF191715.toInt(), readerText = 0xFFE0DCD7.toInt(), readerSecondaryText = 0xFFB4B0A9.toInt(),
            readerLink = 0xFFAEBEFF.toInt(), ttsSentenceHighlight = 0xFF1F3951.toInt(), ttsSentenceIndicator = 0xFF5DA6E7.toInt(),
            ttsWordHighlight = 0xFFE9C256.toInt(), ttsWordText = 0xFF191715.toInt(), ttsWordIndicator = 0xFF774806.toInt(),
            selection = 0xFF5E3869.toInt(), selectionHandle = 0xFFB6C4FF.toInt(), searchHighlight = 0xFF5D3620.toInt(),
            searchOutline = 0xFFF28A5B.toInt(), searchCurrentHighlight = 0xFFFA985F.toInt(), searchCurrentText = 0xFF191715.toInt(),
            readerPageBack = 0xFF252321.toInt(), readerDivider = 0xFF4B463D.toInt(), pageEdgeShadow = 0x66000000,
        )

        val DARK_AMOLED = ReaderColors(
            isDark = true,
            readerBackground = 0xFF000000.toInt(), readerText = 0xFFD7D4CF.toInt(), readerSecondaryText = 0xFFAAA59F.toInt(),
            readerLink = 0xFFAEBEFF.toInt(), ttsSentenceHighlight = 0xFF17324A.toInt(), ttsSentenceIndicator = 0xFF5DA6E7.toInt(),
            ttsWordHighlight = 0xFFE9C256.toInt(), ttsWordText = 0xFF000000.toInt(), ttsWordIndicator = 0xFF774806.toInt(),
            selection = 0xFF573162.toInt(), selectionHandle = 0xFFB6C4FF.toInt(), searchHighlight = 0xFF553019.toInt(),
            searchOutline = 0xFFF28A5B.toInt(), searchCurrentHighlight = 0xFFFA985F.toInt(), searchCurrentText = 0xFF000000.toInt(),
            readerPageBack = 0xFF191715.toInt(), readerDivider = 0xFF4B463D.toInt(), pageEdgeShadow = 0x99000000.toInt(),
        )

        val EYE_CARE = ReaderColors(
            isDark = false,
            readerBackground = 0xFFEBDAC5.toInt(), readerText = 0xFF2E2118.toInt(), readerSecondaryText = 0xFF4E3C30.toInt(),
            readerLink = 0xFF752415.toInt(), ttsSentenceHighlight = 0xFFEAC9A8.toInt(), ttsSentenceIndicator = 0xFF914C22.toInt(),
            ttsWordHighlight = 0xFFC59716.toInt(), ttsWordText = 0xFF120903.toInt(), ttsWordIndicator = 0xFF743417.toInt(),
            selection = 0xFFB0B5CF.toInt(), selectionHandle = 0xFF833F24.toInt(), searchHighlight = 0xFFF29498.toInt(),
            searchOutline = 0xFFA42F30.toInt(), searchCurrentHighlight = 0xFFE47665.toInt(), searchCurrentText = 0xFF20150C.toInt(),
            readerPageBack = 0xFFDACAB4.toInt(), readerDivider = 0xFFC4AD93.toInt(), pageEdgeShadow = 0x29301C00,
        )

        val EYE_CARE_NIGHT = ReaderColors(
            isDark = true,
            readerBackground = 0xFF1B140F.toInt(), readerText = 0xFFE4BA84.toInt(), readerSecondaryText = 0xFFC59F76.toInt(),
            readerLink = 0xFFF5A273.toInt(), ttsSentenceHighlight = 0xFF3E2716.toInt(), ttsSentenceIndicator = 0xFFC78140.toInt(),
            ttsWordHighlight = 0xFFDDA041.toInt(), ttsWordText = 0xFF1B140F.toInt(), ttsWordIndicator = 0xFF763B17.toInt(),
            selection = 0xFF383D52.toInt(), selectionHandle = 0xFFE5A35A.toInt(), searchHighlight = 0xFF723338.toInt(),
            searchOutline = 0xFFEA7B6A.toInt(), searchCurrentHighlight = 0xFFDE7B59.toInt(), searchCurrentText = 0xFF1B140F.toInt(),
            readerPageBack = 0xFF251E1A.toInt(), readerDivider = 0xFF533E2C.toInt(), pageEdgeShadow = 0x80000000.toInt(),
        )

        /**
         * Colores para [theme]. [systemDark] resuelve [ThemeMode.SYSTEM]; [highContrast]
         * aplica la capa de aumento de contraste.
         */
        fun forTheme(theme: ThemeMode, systemDark: Boolean = false, highContrast: Boolean = false): ReaderColors {
            val base = when (theme) {
                ThemeMode.LIGHT -> LIGHT
                ThemeMode.DARK -> DARK
                ThemeMode.DARK_AMOLED -> DARK_AMOLED
                ThemeMode.EYE_CARE -> EYE_CARE
                ThemeMode.EYE_CARE_NIGHT -> EYE_CARE_NIGHT
                ThemeMode.SYSTEM -> if (systemDark) DARK else LIGHT
            }
            if (!highContrast) return base
            return when (base) {
                LIGHT -> base.copy(
                    readerText = 0xFF000000.toInt(), readerSecondaryText = 0xFF2F2C26.toInt(), readerLink = 0xFF00336E.toInt(),
                    ttsSentenceIndicator = 0xFF004A75.toInt(), ttsWordText = 0xFF000000.toInt(), readerDivider = 0xFF706A61.toInt(),
                )
                DARK, DARK_AMOLED -> base.copy(
                    readerText = 0xFFF8F6F3.toInt(), readerSecondaryText = 0xFFE1DCD5.toInt(), readerLink = 0xFFD5DBFF.toInt(),
                    ttsSentenceIndicator = 0xFF8FC5FF.toInt(), readerDivider = 0xFF898379.toInt(),
                )
                EYE_CARE -> base.copy(
                    readerText = 0xFF190F06.toInt(), readerSecondaryText = 0xFF33251C.toInt(), readerLink = 0xFF5A0F00.toInt(),
                    ttsSentenceIndicator = 0xFF6F3005.toInt(), ttsWordText = 0xFF190F06.toInt(), readerDivider = 0xFF7B674F.toInt(),
                )
                else -> base.copy(
                    readerText = 0xFFFFDEAE.toInt(), readerSecondaryText = 0xFFEBC699.toInt(), readerLink = 0xFFFFC9A6.toInt(),
                    ttsSentenceIndicator = 0xFFF4A865.toInt(), readerDivider = 0xFF997F6B.toInt(),
                )
            }
        }
    }
}
