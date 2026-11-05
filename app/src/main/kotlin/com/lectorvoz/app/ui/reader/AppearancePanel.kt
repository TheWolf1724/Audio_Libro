package com.lectorvoz.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lectorvoz.app.ui.components.SectionHeader
import com.lectorvoz.app.ui.components.SliderRow
import com.lectorvoz.app.ui.components.SwitchRow
import com.lectorvoz.app.ui.theme.ReadingFonts
import com.lectorvoz.app.ui.theme.resolveTheme
import com.lectorvoz.app.ui.theme.themeKey
import com.lectorvoz.core.data.settings.BookOverrides
import com.lectorvoz.core.data.settings.SettingsRanges
import com.lectorvoz.core.model.PageAnimation
import com.lectorvoz.core.model.ReaderSettings
import com.lectorvoz.core.model.ReadingMode
import com.lectorvoz.core.model.TextAlign
import com.lectorvoz.core.model.ThemeMode
import kotlin.math.roundToInt

/** Nombre legible de cada tema. */
fun themeLabel(t: ThemeMode) = when (t) {
    ThemeMode.LIGHT -> "Claro"
    ThemeMode.DARK -> "Oscuro"
    ThemeMode.DARK_AMOLED -> "Negro AMOLED"
    ThemeMode.EYE_CARE -> "Proteccion ocular"
    ThemeMode.EYE_CARE_NIGHT -> "Proteccion ocular noche"
    ThemeMode.SYSTEM -> "Segun el sistema"
}

fun fontLabel(f: String) = when (f) {
    ReadingFonts.LITERATA -> "Literata"
    ReadingFonts.ATKINSON -> "Atkinson"
    ReadingFonts.OPENDYSLEXIC -> "OpenDyslexic"
    "book" -> "Del libro"
    else -> "Del sistema"
}

fun animationLabel(a: PageAnimation) = when (a) {
    PageAnimation.CURL -> "Curvar"
    PageAnimation.SLIDE -> "Deslizar"
    PageAnimation.COVER -> "Cubrir"
    PageAnimation.FADE -> "Fundido"
    PageAnimation.NONE -> "Ninguna"
}

/**
 * Panel rapido de apariencia: los deslizadores dan una vista previa con un pequeno retardo y se guardan al soltar. Con "solo este libro",
 * fuente, tamano y modo se guardan en [overrides] en lugar de los ajustes globales.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearancePanel(
    settings: ReaderSettings,
    overrides: BookOverrides,
    systemDark: Boolean,
    onReader: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onOverrides: ((BookOverrides) -> BookOverrides) -> Unit,
    modifier: Modifier = Modifier,
    onPreview: ((ReaderSettings) -> ReaderSettings) -> Unit = {},
) {
    val perBook = overrides.fontFamily != null || overrides.fontSizeSp != null || overrides.readingMode != null
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp).testTag("panel-apariencia")) {
        SectionHeader("Tema")
        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { t ->
                ThemeSwatch(t, selected = settings.theme == t, systemDark = systemDark, highContrast = settings.highContrast) {
                    onReader { it.copy(theme = t) }
                }
            }
        }
        SwitchRow("Aumento de contraste", settings.highContrast, { v -> onReader { it.copy(highContrast = v) } }, tag = "apariencia-contraste")

        SectionHeader("Texto")
        SwitchRow(
            "Solo para este libro",
            perBook,
            { v ->
                if (v) onOverrides { it.copy(fontFamily = settings.fontFamily, fontSizeSp = settings.fontSizeSp, readingMode = settings.readingMode) }
                else onOverrides { it.copy(fontFamily = null, fontSizeSp = null, readingMode = null) }
            },
            summary = "Fuente, tamano y modo propios de este libro",
            tag = "apariencia-por-libro",
        )
        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsRanges.fontFamilies.forEach { f ->
                FilterChip(
                    selected = settings.fontFamily == f,
                    onClick = { if (perBook) onOverrides { it.copy(fontFamily = f) } else onReader { it.copy(fontFamily = f) } },
                    label = { Text(fontLabel(f), fontFamily = ReadingFonts.fontFamily(f)) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("fuente-$f"),
                )
            }
        }
        SliderRow(
            "Tamano", settings.fontSizeSp, 12f..40f, 1f, { "${it.roundToInt()} sp" },
            { v -> if (perBook) onOverrides { it.copy(fontSizeSp = v) } else onReader { it.copy(fontSizeSp = v) } },
            tag = "apariencia-tamano",
            onPreview = { v -> onPreview { it.copy(fontSizeSp = v) } },
        )
        SliderRow("Interlineado", settings.lineSpacing, 1.2f..2f, 0.05f, { "%.2f".format(it) }, { v -> onReader { it.copy(lineSpacing = v) } }, tag = "apariencia-interlineado", onPreview = { v -> onPreview { it.copy(lineSpacing = v) } })
        SliderRow("Margen lateral", settings.marginHorizontalDp.toFloat(), 0f..64f, 4f, { "${it.roundToInt()} dp" }, { v -> onReader { it.copy(marginHorizontalDp = v.roundToInt()) } }, tag = "apariencia-margen", onPreview = { v -> onPreview { it.copy(marginHorizontalDp = v.roundToInt()) } })
        SliderRow("Margen superior e inferior", settings.marginVerticalDp.toFloat(), 0f..64f, 4f, { "${it.roundToInt()} dp" }, { v -> onReader { it.copy(marginVerticalDp = v.roundToInt()) } }, onPreview = { v -> onPreview { it.copy(marginVerticalDp = v.roundToInt()) } })
        Segmented(
            "Alineacion",
            listOf(TextAlign.START to "Izquierda", TextAlign.JUSTIFY to "Justificado"),
            settings.textAlign,
        ) { a -> onReader { it.copy(textAlign = a) } }

        SectionHeader("Pagina")
        Segmented(
            "Modo",
            listOf(ReadingMode.PAGED to "Paginas", ReadingMode.SCROLL to "Desplazamiento"),
            settings.readingMode,
            tag = "apariencia-modo",
        ) { m -> if (perBook) onOverrides { it.copy(readingMode = m) } else onReader { it.copy(readingMode = m) } }
        if (settings.readingMode == ReadingMode.PAGED) {
            Segmented("Animacion", PageAnimation.entries.map { it to animationLabel(it) }, settings.pageAnimation, tag = "apariencia-animacion") { a ->
                onReader { it.copy(pageAnimation = a) }
            }
            if (settings.pageAnimation != PageAnimation.NONE) {
                SliderRow("Duracion de la animacion", settings.pageAnimationMillis.toFloat(), 50f..1000f, 50f, { "${it.roundToInt()} ms" }, { v ->
                    onReader { it.copy(pageAnimationMillis = v.roundToInt()) }
                })
            }
        }

        SectionHeader("Brillo")
        SwitchRow("Brillo del sistema", settings.brightness == null, { sys -> onReader { it.copy(brightness = if (sys) null else 0.5f) } }, tag = "apariencia-brillo-sistema")
        settings.brightness?.let { b ->
            SliderRow("Brillo de lectura", b, 0.05f..1f, 0.01f, { "${(it * 100).roundToInt()} %" }, { v -> onReader { it.copy(brightness = v) } }, tag = "apariencia-brillo", onPreview = { v -> onPreview { it.copy(brightness = v) } })
        }
    }
}

@Composable
private fun ThemeSwatch(t: ThemeMode, selected: Boolean, systemDark: Boolean, highContrast: Boolean, onClick: () -> Unit) {
    val colors = resolveTheme(themeKey(t, systemDark), highContrast).reading
    Column(
        Modifier.widthIn(min = 72.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "Tema " + themeLabel(t) }
            .testTag("tema-${t.name}")
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(56.dp).background(colors.background, RoundedCornerShape(12.dp))
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("Aa", color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        }
        Text(themeLabel(t), style = MaterialTheme.typography.labelSmall, modifier = Modifier.widthIn(max = 88.dp))
    }
}

/** Eleccion visible entre pocas opciones (chips). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> Segmented(label: String, options: List<Pair<T, String>>, selected: T, tag: String? = null, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (v, text) ->
                FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(text) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
    }
}
