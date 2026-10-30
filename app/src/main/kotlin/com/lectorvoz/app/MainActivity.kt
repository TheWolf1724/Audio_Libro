package com.lectorvoz.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.rememberNavController
import com.lectorvoz.app.navigation.AppNavHost
import com.lectorvoz.app.navigation.LocalAppGraph
import com.lectorvoz.app.navigation.openReader
import com.lectorvoz.app.ui.theme.LectorVozTheme
import com.lectorvoz.core.model.ReaderSettings
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Actividad unica: biblioteca, lector y ajustes con navigation-compose. Atiende "Abrir con",
 * "Compartir" y el toque en la notificacion de la voz.
 */
class MainActivity : ComponentActivity() {
    /** Manejador de teclas de volumen del lector (true = consumida). */
    var volumeKeyHandler: ((up: Boolean) -> Boolean)? = null
    private var volumeConsumed = false

    private val navRequests = Channel<Long>(Channel.BUFFERED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as LectorVozApp).graph
        setContent {
            val reader by graph.settings.reader.collectAsStateWithLifecycle(ReaderSettings())
            LectorVozTheme(themeMode = reader.theme, highContrast = reader.highContrast) {
                CompositionLocalProvider(LocalAppGraph provides graph) {
                    val nav = rememberNavController()
                    LaunchedEffect(nav) {
                        navRequests.receiveAsFlow().collect { id -> nav.openReader(id) }
                    }
                    AppNavHost(graph, nav)
                }
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
        // Resultados de importar lo recibido (la copia sigue aunque se gire la pantalla).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                graph.imports.results.collect { r ->
                    if (r.failed > 0) {
                        val msg = if (r.failed == 1) "No se pudo abrir el fichero (no es un EPUB ni un PDF valido)" else "No se pudieron abrir ${r.failed} ficheros"
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    }
                    r.openBookId?.let { navRequests.trySend(it) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val up = keyCode == KeyEvent.KEYCODE_VOLUME_UP
        if ((up || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && volumeKeyHandler?.invoke(up) == true) {
            volumeConsumed = true
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Si el lector consumio la pulsacion, tambien la suelta (sin pitido de volumen).
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && volumeConsumed) {
            volumeConsumed = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            ACTION_OPEN_PLAYING -> {
                val id = (application as LectorVozApp).graph.playback.state.value.bookId ?: return
                navRequests.trySend(id)
            }
            Intent.ACTION_VIEW -> intent.data?.let { importAndOpen(listOf(it)) }
            Intent.ACTION_SEND -> streamOf(intent)?.let { importAndOpen(listOf(it)) }
            Intent.ACTION_SEND_MULTIPLE -> streamsOf(intent).takeIf { it.isNotEmpty() }?.let { importAndOpen(it) }
        }
    }

    @Suppress("DEPRECATION")
    private fun streamOf(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamsOf(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM))
            .orEmpty()

    /** Importa los ficheros recibidos (en el ambito de la app); si es uno solo, lo abre. */
    private fun importAndOpen(uris: List<Uri>) {
        (application as LectorVozApp).graph.imports.importExternal(uris)
    }

    companion object {
        /** Accion del toque en la notificacion: abrir el libro que suena. */
        const val ACTION_OPEN_PLAYING = "com.lectorvoz.app.ABRIR_LO_QUE_SUENA"
    }
}
