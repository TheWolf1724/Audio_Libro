package com.lectorvoz.app

import android.app.Application
import android.content.Intent
import android.os.StatFs
import androidx.core.content.edit
import android.os.StrictMode
import androidx.media3.common.util.UnstableApi
import com.lectorvoz.tts.playback.TtsConfig
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Aplicacion: crea el [AppGraph], StrictMode en depuracion y la configuracion de la voz. */
class LectorVozApp : Application() {
    /**
     * Ambito de aplicacion (marcadores desde la notificacion, ajustes de voz, escucha,
     * importar y exportar). Un error no capturado se registra en lugar de tumbar el proceso.
     */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Log.e("LectorVozApp", "Error en segundo plano", e) },
    )

    @Volatile
    private var created: AppGraph? = null

    /**
     * Grafo de dependencias (se crea al pedirlo por primera vez). Doble-check sincronizado: aunque
     * casi todo accede desde el hilo principal, `resumeBook`/`keepModelWarm` pueden invocarse desde
     * un hilo del servicio; sin esto se podrian crear DOS grafos (dos Room, dos OcrManager...).
     */
    val graph: AppGraph
        get() = created ?: synchronized(this) {
            created ?: AppGraph.create(this, appScope).also { created = it; it.start() }
        }

    /** Sustituye el grafo (pruebas con repositorios falsos). */
    fun replaceGraph(g: AppGraph) {
        synchronized(this) { created = g }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) enableStrictMode()
        TtsConfig.sessionActivityIntent = { ctx ->
            Intent(ctx, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_PLAYING)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        TtsConfig.audioCacheMaxBytes = audioCacheBudget()
        // Hilos de inferencia segun la gama del movil (2 en modestos, hasta 4 en potentes).
        val mem = android.app.ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager)?.getMemoryInfo(mem)
        TtsConfig.neuralThreads = com.lectorvoz.tts.neural.NeuralThreads.choose(Runtime.getRuntime().availableProcessors(), mem.totalMem)
        // Velocidad de sintesis de la voz natural medida en sesiones anteriores de este movil.
        val rtfPrefs = getSharedPreferences("tts_rendimiento", MODE_PRIVATE)
        TtsConfig.persistedRtf = rtfPrefs.getFloat("rtf", -1f).takeIf { it > 0f }
        TtsConfig.rtfSink = { rtf -> rtfPrefs.edit { putFloat("rtf", rtf) } }
        // "Play" con el proceso recien creado (auricular, notificacion, coche): ultimo libro.
        TtsConfig.resumeBook = { _ -> graph.resumeRequest() }
        // Con la app en pantalla (biblioteca con el minirreproductor) el modelo no se suelta al
        // pausar: reanudar no debe esperar a recargarlo.
        TtsConfig.keepModelWarm = { created?.appVisible == true }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        created?.trimMemory(level)
    }

    /**
     * Tope de la cache de audio en disco: hasta 300 MB, pero nunca mas del 10 % del espacio libre
     * (con un suelo de 20 MB). En moviles justos de almacenamiento 300 MB fijos eran demasiado y
     * Android puede vaciar la cache bajo presion, perdiendo el trabajo hecho.
     */
    private fun audioCacheBudget(): Long {
        val max = 300L * 1024 * 1024
        val min = 20L * 1024 * 1024
        val free = runCatching { StatFs(cacheDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        // El espacio libre ya descuenta lo que ocupa la propia cache de audio: se suma, si no el tope
        // se encogeria en cada arranque y recortaria lo ya preparado.
        val own = runCatching { TtsConfig.audioCacheDir(this).walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
        return (free / 10 + own / 10).coerceIn(min, max)
    }

    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedSqlLiteObjects()
                .detectActivityLeaks().penaltyLog().build(),
        )
    }
}
