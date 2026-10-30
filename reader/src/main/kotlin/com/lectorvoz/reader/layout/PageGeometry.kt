package com.lectorvoz.reader.layout

import com.lectorvoz.core.model.ReaderSettings
import com.lectorvoz.core.model.TextAlign

/** Tamano de la vista en pixeles y densidades de pantalla. */
data class Viewport(
    val widthPx: Int,
    val heightPx: Int,
    /** Pixeles por dp. */
    val density: Float,
    /** Escala de fuente del sistema (sp = dp * fontScale, sin la escala no lineal de Android 14). */
    val fontScale: Float = 1f,
) {
    val isEmpty: Boolean get() = widthPx <= 0 || heightPx <= 0
}

/** Elementos de pagina opcionales (no estan en [ReaderSettings]). */
data class PageChrome(
    /** Encabezado discreto con el titulo del capitulo. */
    val showHeader: Boolean = true,
    /** Pie con numero de pagina y progreso. */
    val showFooter: Boolean = true,
    /** PDF: pie "pagina / total" en una franja reservada bajo la pagina (por defecto no). */
    val pdfFooter: Boolean = false,
)

/**
 * Parte de [ReaderSettings] que afecta a la maquetacion (si cambia, hay que repaginar).
 * Tema, animacion o brillo no estan aqui: solo obligan a redibujar.
 */
data class LayoutSettings(
    val fontFamily: String,
    val fontSizeSp: Float,
    val fontWeight: Int,
    val lineSpacing: Float,
    val paragraphSpacingEm: Float,
    val firstLineIndentEm: Float?,
    val letterSpacingEm: Float,
    val wordSpacingEm: Float,
    val marginHorizontalDp: Int,
    val marginVerticalDp: Int,
    val maxLineChars: Int,
    val justify: Boolean,
    val hyphenation: Boolean,
    val chrome: PageChrome,
) {
    companion object {
        fun from(s: ReaderSettings, chrome: PageChrome = PageChrome()): LayoutSettings = LayoutSettings(
            fontFamily = s.fontFamily,
            fontSizeSp = s.fontSizeSp,
            fontWeight = s.fontWeight,
            lineSpacing = s.lineSpacing,
            paragraphSpacingEm = s.paragraphSpacingEm,
            firstLineIndentEm = s.firstLineIndentEm,
            letterSpacingEm = s.letterSpacingEm,
            wordSpacingEm = s.wordSpacingEm,
            marginHorizontalDp = s.marginHorizontalDp,
            marginVerticalDp = s.marginVerticalDp,
            maxLineChars = s.maxLineChars,
            justify = s.textAlign == TextAlign.JUSTIFY,
            hyphenation = s.hyphenation ?: (s.textAlign == TextAlign.JUSTIFY),
            chrome = chrome,
        )
    }
}

/**
 * Geometria de pagina en pixeles calculada a partir de [Viewport] y [LayoutSettings]:
 * columna de texto, cabecera y pie.
 */
data class PageGeometry(
    val viewport: Viewport,
    val textSizePx: Float,
    /** Tamano del texto de cabecera y pie. */
    val chromeTextSizePx: Float,
    val columnLeft: Float,
    val columnWidth: Int,
    val contentTop: Float,
    val contentHeight: Int,
    val headerHeight: Float,
    val footerHeight: Float,
    val marginHorizontalPx: Float,
    val marginVerticalPx: Float,
) {
    companion object {
        /**
         * [avgCharWidthPerEm] es el ancho medio de caracter en em de la fuente (0,5 aprox.)
         * para limitar la columna a [LayoutSettings.maxLineChars].
         */
        fun compute(viewport: Viewport, s: LayoutSettings, avgCharWidthPerEm: Float = 0.5f): PageGeometry {
            val d = viewport.density
            val textSize = s.fontSizeSp * d * viewport.fontScale
            val chromeSize = 12f * d * viewport.fontScale
            val marginH = s.marginHorizontalDp * d
            val marginV = s.marginVerticalDp * d
            val header = if (s.chrome.showHeader) chromeSize * 2f else 0f
            val footer = if (s.chrome.showFooter) chromeSize * 2f else 0f
            val available = (viewport.widthPx - 2 * marginH).coerceAtLeast(textSize * 4)
            val maxColumn = s.maxLineChars * textSize * (avgCharWidthPerEm + s.letterSpacingEm)
            // Nunca mas ancha que la vista (vistas diminutas o letra enorme).
            val column = minOf(available, maxColumn, viewport.widthPx.toFloat()).toInt().coerceAtLeast(1)
            val left = ((viewport.widthPx - column) / 2f).coerceAtLeast(0f)
            val top = marginV + header
            val height = (viewport.heightPx - 2 * marginV - header - footer).toInt().coerceAtLeast((textSize * 2).toInt())
            return PageGeometry(
                viewport = viewport,
                textSizePx = textSize,
                chromeTextSizePx = chromeSize,
                columnLeft = left,
                columnWidth = column,
                contentTop = top,
                contentHeight = height,
                headerHeight = header,
                footerHeight = footer,
                marginHorizontalPx = marginH,
                marginVerticalPx = marginV,
            )
        }
    }
}
