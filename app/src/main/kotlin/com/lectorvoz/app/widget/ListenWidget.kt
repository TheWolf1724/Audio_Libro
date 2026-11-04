package com.lectorvoz.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.lectorvoz.app.LectorVozApp
import com.lectorvoz.app.MainActivity
import com.lectorvoz.app.R
import com.lectorvoz.app.integration.PlaybackPort
import com.lectorvoz.core.data.library.Book
import com.lectorvoz.core.data.library.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Widget de escritorio "seguir escuchando": portada, titulo y progreso del ultimo libro, y un
 * boton para escuchar o pausar. Lo pinta [ListenWidgetUpdater]; aqui solo se reciben avisos.
 */
class ListenWidget : AppWidgetProvider() {
    private fun updater(context: Context) = (context.applicationContext as LectorVozApp).graph.widget

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = updater(context).refresh()

    override fun onEnabled(context: Context) = updater(context).setActive(true)

    override fun onDisabled(context: Context) = updater(context).setActive(false)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) {
            val pending = goAsync()
            updater(context).toggle { pending.finish() }
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        /** Boton del widget con un libro ya cargado en la voz: escuchar/pausar sin abrir la app. */
        const val ACTION_TOGGLE = "com.lectorvoz.app.WIDGET_ESCUCHAR_PAUSAR"
    }
}

/** Lo que muestra el widget (nulo el libro: biblioteca sin "seguir leyendo"). */
internal data class WidgetContent(
    val bookId: Long?,
    val title: String?,
    val author: String?,
    val coverPath: String?,
    /** Progreso en tanto por ciento (entero: los cambios menores no repintan). */
    val percent: Int,
    /** El libro esta cargado en la voz (el boton conmuta sin abrir la app). */
    val loaded: Boolean,
    val playing: Boolean,
) {
    companion object {
        val EMPTY = WidgetContent(null, null, null, null, 0, loaded = false, playing = false)
    }
}

/**
 * Mantiene el widget al dia. Solo observa la biblioteca y la voz mientras haya algun widget
 * en el escritorio, y repinta solo cuando cambia lo que se ve (libro, % entero, escuchando o
 * no), agrupando cambios seguidos: no cuesta nada mientras se escucha.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ListenWidgetUpdater(
    private val context: Context,
    private val scope: CoroutineScope,
    private val library: LibraryRepository,
    private val playback: PlaybackPort,
    /** Empieza a escuchar [Book] desde su posicion guardada. */
    private val listen: suspend (Book) -> Unit,
) {
    private val component = ComponentName(context, ListenWidget::class.java)
    private val active = MutableStateFlow(false)
    @Volatile private var last: WidgetContent? = null

    // Ultima portada decodificada (el widget solo muestra una).
    private var coverKey: String? = null
    private var coverBitmap: Bitmap? = null

    fun start() {
        active.value = hasWidgets()
        scope.launch {
            active.flatMapLatest { on -> if (on) content() else emptyFlow() }
                .debounce(DEBOUNCE_MS)
                .collect { render(it) }
        }
    }

    fun setActive(on: Boolean) {
        active.value = on
        if (!on) {
            last = null
            coverKey = null
            coverBitmap = null
        }
    }

    /** Repintar (el sistema lo pide al anadir el widget o reiniciar el escritorio). */
    fun refresh() {
        if (!active.value) {
            active.value = true // el flujo lo pinta al empezar
            return
        }
        last?.let { scope.launch { render(it) } }
    }

    /**
     * Boton con un libro ya cargado en la voz. Si el proceso se reinicio entre medias (la voz ya
     * no lo tiene), se empieza a escuchar el ultimo libro: el toque en un widget permite
     * arrancar el servicio de voz desde segundo plano.
     */
    fun toggle(done: () -> Unit) {
        scope.launch {
            try {
                if (playback.state.value.bookId != null) {
                    playback.togglePlayPause()
                } else {
                    library.continueReading.first()?.let { listen(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo escuchar desde el widget", e)
                refresh()
            } finally {
                done()
            }
        }
    }

    /** Contenido: el libro que suena o, si no hay, el ultimo abierto. */
    internal fun content(): Flow<WidgetContent> =
        combine(
            playback.state.map { it.bookId to it.playWhenReady }.distinctUntilChanged(),
            library.continueReading.map { it?.id }.distinctUntilChanged(),
        ) { voice, lastId -> voice to lastId }
            .flatMapLatest { (voice, lastId) ->
                val (playingId, play) = voice
                val id = playingId ?: lastId
                val book = if (id == null) flowOf(null) else library.observe(id)
                book.map { b ->
                    if (b == null) {
                        WidgetContent.EMPTY
                    } else {
                        WidgetContent(
                            bookId = b.id,
                            title = b.title,
                            author = b.author,
                            coverPath = b.coverPath,
                            percent = (b.progress.coerceIn(0f, 1f) * 100).roundToInt(),
                            loaded = playingId == b.id,
                            playing = play && playingId == b.id,
                        )
                    }
                }
            }
            .distinctUntilChanged()

    private fun hasWidgets(): Boolean =
        try {
            AppWidgetManager.getInstance(context).getAppWidgetIds(component).isNotEmpty()
        } catch (e: RuntimeException) {
            false
        }

    private suspend fun render(c: WidgetContent) {
        last = c
        val cover = cover(c.coverPath)
        val views = RemoteViews(context.packageName, R.layout.widget_lectorvoz)
        views.setTextViewText(R.id.widget_titulo, c.title ?: context.getString(R.string.app_name))
        views.setTextViewText(
            R.id.widget_detalle,
            if (c.bookId == null) context.getString(R.string.widget_sin_libro)
            else listOfNotNull("${c.percent} %", c.author).joinToString(" · "),
        )
        views.setProgressBar(R.id.widget_progreso, 100, c.percent, false)
        views.setViewVisibility(R.id.widget_progreso, if (c.bookId == null) View.GONE else View.VISIBLE)
        if (cover != null) {
            views.setImageViewBitmap(R.id.widget_portada, cover)
            views.setViewVisibility(R.id.widget_portada, View.VISIBLE)
            views.setViewVisibility(R.id.widget_icono, View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_portada, View.GONE)
            views.setViewVisibility(R.id.widget_icono, View.VISIBLE)
        }
        views.setOnClickPendingIntent(R.id.widget_raiz, activityIntent(MainActivity.ACTION_OPEN_BOOK, c.bookId, REQ_OPEN))
        if (c.bookId == null) {
            views.setViewVisibility(R.id.widget_boton, View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_boton, View.VISIBLE)
            views.setImageViewResource(R.id.widget_boton, if (c.playing) R.drawable.ic_pause else R.drawable.ic_play_arrow)
            views.setContentDescription(
                R.id.widget_boton,
                context.getString(if (c.playing) R.string.widget_pausar else R.string.widget_escuchar),
            )
            // Sin el libro en la voz se abre la app para empezar (arrancar el audio en primer
            // plano es lo que Android garantiza); con el libro cargado conmuta sin abrirla.
            val click = if (c.loaded) toggleIntent() else activityIntent(MainActivity.ACTION_LISTEN, c.bookId, REQ_LISTEN)
            views.setOnClickPendingIntent(R.id.widget_boton, click)
        }
        try {
            AppWidgetManager.getInstance(context).updateAppWidget(component, views)
        } catch (e: RuntimeException) {
            Log.w(TAG, "No se pudo actualizar el widget", e)
        }
    }

    private fun activityIntent(action: String, bookId: Long?, request: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        bookId?.let { intent.putExtra(MainActivity.EXTRA_BOOK, it) }
        return PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun toggleIntent(): PendingIntent {
        val intent = Intent(context, ListenWidget::class.java).setAction(ListenWidget.ACTION_TOGGLE)
        return PendingIntent.getBroadcast(context, REQ_TOGGLE, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Portada reducida al tamano del widget (se decodifica una vez por libro). */
    private suspend fun cover(path: String?): Bitmap? {
        if (path == null) return null
        if (path == coverKey) return coverBitmap
        val bmp = withContext(Dispatchers.IO) { decode(File(path)) }
        coverKey = path
        coverBitmap = bmp
        return bmp
    }

    private fun decode(file: File): Bitmap? = try {
        if (!file.exists()) {
            null
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outHeight / (sample * 2) >= COVER_HEIGHT_PX) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val TAG = "ListenWidget"
        private const val DEBOUNCE_MS = 300L
        /** Alto aproximado de la portada (56 dp en pantallas densas). */
        private const val COVER_HEIGHT_PX = 200
        private const val REQ_OPEN = 1
        private const val REQ_LISTEN = 2
        private const val REQ_TOGGLE = 3
    }
}
