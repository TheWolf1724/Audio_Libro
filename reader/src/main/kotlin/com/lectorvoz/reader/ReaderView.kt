package com.lectorvoz.reader

import android.view.View
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.lectorvoz.core.model.ReadingMode
import com.lectorvoz.reader.view.PageFlipView
import com.lectorvoz.reader.view.PageHost
import com.lectorvoz.reader.view.ScrollReaderView

/**
 * Vista de lectura para Compose: envuelve [PageFlipView] (modo paginas) o
 * [ScrollReaderView] (modo scroll) segun `settings.readingMode` del [controller].
 */
@Composable
fun ReaderView(controller: ReaderController, modifier: Modifier = Modifier) {
    val settings by controller.settings.collectAsState()
    val dark = isSystemInDarkTheme()
    LaunchedEffect(controller, dark) { controller.setSystemDark(dark) }

    key(settings.readingMode) {
        AndroidView(
            factory = { ctx ->
                val view: View = if (settings.readingMode == ReadingMode.SCROLL) ScrollReaderView(ctx) else PageFlipView(ctx)
                view.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
                    val cfg = v.resources.configuration
                    controller.setViewport(r - l, b - t, v.resources.displayMetrics.density, cfg.fontScale)
                }
                controller.attach(view as PageHost)
                view
            },
            modifier = modifier,
            onRelease = { view -> controller.detach(view as PageHost) },
        )
    }
}
