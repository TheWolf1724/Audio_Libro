package com.lectorvoz.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Aviso amable de "estoy en ello": rueda animada y una frase. Solo aparece si la espera dura
 * mas de [showAfterMs] (lo rapido no parpadea) y, si se alarga mas de [slowAfterMs], anade
 * [slowText] para que se vea que sigue trabajando y no esta colgada.
 */
@Composable
fun LoadingHint(
    text: String?,
    modifier: Modifier = Modifier,
    slowText: String? = null,
    showAfterMs: Long = 400L,
    slowAfterMs: Long = 4_000L,
) {
    var visible by remember { mutableStateOf(false) }
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(text != null) {
        slow = false
        if (text == null) {
            visible = false
            return@LaunchedEffect
        }
        delay(showAfterMs)
        visible = true
        delay((slowAfterMs - showAfterMs).coerceAtLeast(0))
        slow = true
    }
    // Se recuerda el ultimo texto para que no desaparezca de golpe al irse.
    var shown by remember { mutableStateOf(text.orEmpty()) }
    if (text != null) shown = text
    AnimatedVisibility(visible && text != null, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 3.dp,
            modifier = Modifier.widthIn(max = 360.dp).testTag("cargando")
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                Column {
                    Text(shown, style = MaterialTheme.typography.bodyMedium)
                    if (slow && slowText != null) {
                        Text(slowText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Start)
                    }
                }
            }
        }
    }
}
