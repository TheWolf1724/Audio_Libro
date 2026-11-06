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
import androidx.compose.runtime.SideEffect
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

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
        val initial = initialReaderSettings(graph)
        setContent {
            val reader by graph.settings.reader.collectAsStateWithLifecycle(initial)
            SideEffect { lastReaderSettings = reader }
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

    override fun onStart() {
        super.onStart()
        val graph = (application as LectorVozApp).graph
        graph.ocr.setAppVisible(true)
        graph.appVisible = true
        graph.installer.foreground = true
        // Aviso de nueva version: al abrir la app (no al despertar por el widget o la voz), como
        // mucho una vez al dia; tambien vuelve a mirar si acabo el "Omitir por ahora".
        graph.checkUpdatesNow()
    }

    override fun onStop() {
        super.onStop()
        val graph = (application as LectorVozApp).graph
        graph.ocr.setAppVisible(false)
        graph.appVisible = false
        graph.installer.foreground = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val up = keyCode == KeyEvent.KEYCODE_VOLUME_UP
        // Tecla mantenida: una sola pagina por pulsacion (las repeticiones encolaban decenas).
        if ((up || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && event.repeatCount > 0 && volumeConsumed) return true
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
        // Relanzada desde Recientes: el intent es el original (widget, "Abrir con", "Compartir");
        // repetirlo volveria a importar o a arrancar la voz sin que el usuario lo pida.
        if (launchedFromHistory(intent)) return
        when (intent.action) {
            ACTION_OPEN_PLAYING -> {
                val id = (application as LectorVozApp).graph.playback.state.value.bookId ?: return
                navRequests.trySend(id)
            }
            ACTION_OPEN_BOOK -> intent.getLongExtra(EXTRA_BOOK, -1L).takeIf { it >= 0 }?.let { navRequests.trySend(it) }
            ACTION_LISTEN -> {
                val id = intent.getLongExtra(EXTRA_BOOK, -1L).takeIf { it >= 0 } ?: return
                navRequests.trySend(id)
                val graph = (application as LectorVozApp).graph
                // En el ambito de la app: girar la pantalla no corta el arranque de la voz.
                graph.appScope.launch {
                    val book = graph.library.get(id) ?: return@launch
                    try {
                        graph.listenTo(book)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Toast.makeText(graph.context, "No se pudo iniciar la voz: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
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
        // Solo ficheros que entrega otra app (content://), nunca rutas ni ficheros propios:
        // un intent dirigido a esta actividad podria pedir copiar datos privados de la app.
        val safe = uris.filter { it.scheme == "content" && it.authority?.startsWith(packageName) != true }
        if (safe.isNotEmpty()) (application as LectorVozApp).graph.imports.importExternal(safe)
    }

    /**
     * Ajustes de lectura con los que pintar el primer fotograma (sin parpadeo de tema): los
     * ultimos vistos en este proceso (girar, cambiar el modo oscuro) o, en frio, una lectura
     * corta y acotada del almacen; si tarda, los de por defecto.
     */
    private fun initialReaderSettings(graph: AppGraph): ReaderSettings =
        lastReaderSettings ?: runCatching {
            runBlocking { withTimeoutOrNull(INITIAL_SETTINGS_TIMEOUT_MS) { graph.settings.reader.first() } }
        }.getOrNull()?.also { lastReaderSettings = it } ?: ReaderSettings()

    companion object {
        /** Ultimos ajustes de lectura pintados en este proceso. */
        @Volatile
        private var lastReaderSettings: ReaderSettings? = null

        // Lectura acotada en el hilo de UI solo en arranque en frio (sin ajustes cacheados). Se
        // limita para no arriesgar jank/ANR con disco lento; si expira, se pinta con los de por
        // defecto y los reales entran despues por el flujo reactivo (sin parpadeo perceptible).
        private const val INITIAL_SETTINGS_TIMEOUT_MS = 150L

        /** El sistema la relanzo desde Recientes con el intent original. */
        fun launchedFromHistory(intent: Intent): Boolean =
            intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0

        /** Accion del toque en la notificacion: abrir el libro que suena. */
        const val ACTION_OPEN_PLAYING = "com.lectorvoz.app.ABRIR_LO_QUE_SUENA"

        /** Widget: abrir el libro [EXTRA_BOOK]. */
        const val ACTION_OPEN_BOOK = "com.lectorvoz.app.ABRIR_LIBRO"

        /** Widget: abrir el libro [EXTRA_BOOK] y empezar a escucharlo desde donde se dejo. */
        const val ACTION_LISTEN = "com.lectorvoz.app.ESCUCHAR_LIBRO"

        const val EXTRA_BOOK = "libro"
    }
}
