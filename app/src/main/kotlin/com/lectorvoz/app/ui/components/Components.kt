package com.lectorvoz.app.ui.components

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import com.lectorvoz.app.books.CoverLoader
import kotlin.math.roundToInt

/** Icono de los recursos propios (ic_*.xml). */
@Composable
fun AppIcon(@DrawableRes id: Int, contentDescription: String?, modifier: Modifier = Modifier) =
    Icon(painterResource(id), contentDescription = contentDescription, modifier = modifier)

/** Boton de icono con etiqueta accesible (objetivo de 48 dp). */
@Composable
fun AppIconButton(@DrawableRes id: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    IconButton(onClick = onClick, modifier = modifier, enabled = enabled) { AppIcon(id, label) }

/** Cabecera de seccion en listas de ajustes. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

/** Fila con interruptor; toda la fila es el objetivo tactil. */
@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, summary: String? = null, tag: String? = null) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * Deslizador con paso; [format] da el texto del valor (se anuncia a TalkBack). Mientras se
 * arrastra solo cambia un borrador local (y [onPreview], si se da, para una vista previa
 * ligera); el valor se confirma con [onChange] al soltar: no se escribe en disco ni se
 * repagina a cada paso (revision 3, M5). Con teclado o TalkBack cada paso se confirma.
 */
@Composable
fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
    onPreview: ((Float) -> Unit)? = null,
) {
    val steps = (((range.endInclusive - range.start) / step).roundToInt() - 1).coerceAtLeast(0)
    var dragging by remember { mutableStateOf(false) }
    var draft by rememberDraft(value, dragging)
    val shown = if (dragging) draft else value
    Column(modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier).padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(format(shown), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 12.dp))
        }
        Slider(
            value = shown.coerceIn(range.start, range.endInclusive),
            onValueChange = { v ->
                val snapped = snap(v, range.start, step)
                if (!dragging || snapped != draft) {
                    dragging = true
                    draft = snapped
                    onPreview?.invoke(snapped)
                }
            },
            onValueChangeFinished = {
                if (dragging) {
                    dragging = false
                    if (draft != value) onChange(draft)
                }
            },
            valueRange = range,
            steps = if (steps > 200) 0 else steps,
            modifier = Modifier.semantics { stateDescription = format(shown); contentDescription = label },
        )
    }
}

private fun snap(v: Float, min: Float, step: Float): Float =
    if (step <= 0f) v else (min + ((v - min) / step).roundToInt() * step).let { (it * 1000f).roundToInt() / 1000f }

/**
 * Eleccion entre opciones con un menu desplegable. A dos lineas (etiqueta arriba, valor
 * elegido debajo) para que ni la etiqueta ni valores largos ("Desplazamiento continuo",
 * "Pasar a la voz del sistema") se desborden ni se pisen, con cualquier ancho o tamano de
 * letra del sistema.
 */
@Composable
fun <T> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier)) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.DropdownList) { open = true }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(optionLabel(selected), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(optionLabel(o)) }, onClick = { open = false; onSelect(o) })
            }
        }
    }
}

/** Grupo de radios (para pocas opciones visibles a la vez). */
@Composable
fun <T> RadioGroup(options: List<T>, selected: T, optionLabel: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        options.forEach { o ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected = o == selected, role = Role.RadioButton, onClick = { onSelect(o) })
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = o == selected, onClick = null)
                Text(optionLabel(o), modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

/** Fila de navegacion de ajustes. */
@Composable
fun NavRow(title: String, summary: String?, @DrawableRes icon: Int?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) AppIcon(icon, null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Portada desde disco con [CoverLoader]; sin portada, una tapa con el titulo. */
@Composable
fun BookCover(path: String?, title: String, loader: CoverLoader, width: Dp, modifier: Modifier = Modifier, finished: Boolean = false) {
    val px = with(LocalDensity.current) { width.roundToPx() }
    val bmp: Bitmap? by produceState(initialValue = path?.let { loader.cached(it, px) }, path, px) {
        if (value == null && path != null) value = loader.load(path, px)
    }
    val shape = MaterialTheme.shapes.small
    Box(
        modifier.clip(shape).background(MaterialTheme.colorScheme.secondaryContainer).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp),
            )
        }
        if (finished) FinishedRibbon(width)
    }
}

/**
 * Cinta de "terminado": un marcador que cuelga de la esquina superior de la portada (cuerpo con
 * muesca en V abajo) con una marca de verificacion. Escala con el ancho de la portada y no cambia la
 * semantica (la descripcion de la tarjeta ya dice "terminado"; la portada es decorativa).
 */
@Composable
private fun BoxScope.FinishedRibbon(coverWidth: Dp) {
    val w = (coverWidth * 0.24f).coerceIn(14.dp, 30.dp)
    val h = w * 1.75f
    val color = MaterialTheme.colorScheme.primary
    val onColor = MaterialTheme.colorScheme.onPrimary
    // drawWithCache: los trazados se construyen UNA vez por tamano (no en cada fotograma al hacer
    // scroll por la biblioteca), sin asignaciones en el dibujado.
    Box(
        Modifier.align(Alignment.TopEnd).padding(end = w * 0.4f).size(w, h).testTag("cinta-terminado")
            .drawWithCache {
                val cw = size.width
                val ch = size.height
                val body = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(cw, 0f)
                    lineTo(cw, ch)
                    lineTo(cw / 2f, ch * 0.78f) // muesca del marcador
                    lineTo(0f, ch)
                    close()
                }
                val tick = Path().apply {
                    moveTo(cw * 0.24f, ch * 0.36f)
                    lineTo(cw * 0.43f, ch * 0.50f)
                    lineTo(cw * 0.76f, ch * 0.20f)
                }
                val edge = Stroke(width = 1.dp.toPx())
                val tickStyle = Stroke(width = cw * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                onDrawBehind {
                    drawPath(body, color)
                    // Fino borde oscuro para que se distinga sobre portadas del mismo color.
                    drawPath(body, Color.Black.copy(alpha = 0.28f), style = edge)
                    drawPath(tick, onColor, style = tickStyle)
                }
            },
    )
}

/** Boton de texto con objetivo minimo de 48 dp. */
@Composable
fun SmallTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) { Text(text) }

/**
 * Estado local de un valor que se arrastra (para no escribir en disco a cada paso). Mientras
 * [dragging] es true ignora los cambios externos de [value]: asi el pulgar no salta aunque la
 * vista previa realimentara el valor a mitad de arrastre (revision final, B2). Al soltar vuelve
 * a seguir el valor externo.
 */
@Composable
fun rememberDraft(value: Float, dragging: Boolean): androidx.compose.runtime.MutableFloatState {
    val state = remember { mutableFloatStateOf(value) }
    if (!dragging && state.floatValue != value) state.floatValue = value
    return state
}
