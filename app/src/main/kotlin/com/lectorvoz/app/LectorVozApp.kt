package com.lectorvoz.app

import android.app.Application
import android.content.Intent
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

    private var created: AppGraph? = null

    /** Grafo de dependencias (se crea al pedirlo por primera vez). */
    val graph: AppGraph
        get() = created ?: AppGraph.create(this, appScope).also { created = it; it.start() }

    /** Sustituye el grafo (pruebas con repositorios falsos). */
    fun replaceGraph(g: AppGraph) {
        created = g
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
        TtsConfig.audioCacheMaxBytes = 300L * 1024 * 1024
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        created?.trimMemory(level)
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
