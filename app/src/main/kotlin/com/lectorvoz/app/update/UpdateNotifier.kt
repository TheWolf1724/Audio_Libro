package com.lectorvoz.app.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lectorvoz.app.LectorVozApp
import com.lectorvoz.app.MainActivity
import com.lectorvoz.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Notificacion del estado de una actualizacion: descargando (con progreso y "Cancelar"),
 * instalando, lista para confirmar (tocarla abre la confirmacion de Android) o el error. Sigue
 * al estado del [UpdateInstaller]; sin nada en marcha no hay notificacion.
 */
class UpdateNotifier(private val context: Context, private val state: StateFlow<InstallState>, private val version: () -> String?) {

    fun start(scope: CoroutineScope) {
        scope.launch {
            var shown: InstallState? = null
            state.collect { st ->
                if (st == shown) return@collect
                shown = st
                show(st)
            }
        }
    }

    /** Vuelve a mostrar el estado actual (p. ej. tras conceder el permiso de notificaciones). */
    fun refresh() = show(state.value)

    private fun show(st: InstallState) {
        val nm = NotificationManagerCompat.from(context)
        val n = build(st)
        if (n == null) {
            nm.cancel(ID)
            return
        }
        if (!canNotify()) return
        try {
            nm.notify(ID, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "Sin permiso para notificar", e)
        }
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Notificacion de [st] (nula: ninguna). */
    internal fun build(st: InstallState): android.app.Notification? {
        ensureChannel(context)
        val v = version()?.let { " $it" }.orEmpty()
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentIntent(openApp())
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        return when (st) {
            is InstallState.Downloading -> {
                val p = st.progress
                b.setContentTitle("Descargando LectorVoz$v")
                    .setContentText(if (p != null) "${(p * 100).toInt()} %" else "Descargando...")
                    .setProgress(100, ((p ?: 0f) * 100).toInt(), p == null)
                    .setOngoing(true)
                    .setSilent(true)
                    .addAction(0, "Cancelar", cancelIntent())
                    .build()
            }
            InstallState.Installing -> b.setContentTitle("Instalando LectorVoz$v")
                .setContentText("Descarga comprobada. Instalando...")
                .setProgress(0, 0, true)
                .setSilent(true)
                // Al instalarse la version nueva el proceso muere sin quitarla: caduca sola.
                .setTimeoutAfter(10 * 60_000L)
                .build()
            is InstallState.NeedsConfirmation -> b.setContentTitle("LectorVoz$v lista para instalar")
                .setContentText("Toca para confirmar la instalacion")
                .setContentIntent(PendingIntent.getActivity(context, REQ_CONFIRM, st.intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            is InstallState.Failed -> b.setContentTitle("No se pudo actualizar LectorVoz")
                .setContentText(st.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(st.message))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .build()
            InstallState.Idle, InstallState.NeedsPermission -> null
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, REQ_OPEN,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, REQ_CANCEL,
        Intent(context, UpdateActionReceiver::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "UpdateNotifier"
        const val ID = 4201
        const val CHANNEL = "actualizaciones"
        const val ACTION_CANCEL = "com.lectorvoz.app.ACTUALIZACION_CANCELAR"
        private const val REQ_OPEN = 41
        private const val REQ_CANCEL = 42
        private const val REQ_CONFIRM = 43

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Actualizaciones", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Descarga e instalacion de versiones nuevas de LectorVoz"
                    setSound(null, null)
                },
            )
        }
    }
}

/** "Cancelar" desde la notificacion de la descarga. */
class UpdateActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == UpdateNotifier.ACTION_CANCEL) (context.applicationContext as LectorVozApp).graph.installer.cancel()
    }
}

/**
 * Mantiene viva la app mientras se descarga la actualizacion (si el usuario sale de la app, la
 * descarga sigue y la notificacion muestra el progreso). Se para sola al terminar la descarga.
 */
class UpdateDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Android 15+ (targetSdk 35+): un FGS dataSync tiene un tope de tiempo; al alcanzarlo el sistema
     * llama aqui y, si no se para pronto, mata el servicio y registra ANR. Una descarga de APK no se
     * acerca a ese tope, pero se para por si acaso (la descarga ya no cuenta con primer plano).
     */
    override fun onTimeout(startId: Int) {
        Log.w("UpdateDownloadService", "FGS dataSync agoto su tiempo; parando")
        stop()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        onTimeout(startId)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val graph = (application as LectorVozApp).graph
        // Android exige startForeground tras startForegroundService aunque ya no haya nada que
        // descargar (cancelado antes de arrancar): se cumple con una minima y se para.
        val n = graph.updateNotifier.build(graph.installer.state.value) ?: graph.updateNotifier.build(InstallState.Installing)!!
        try {
            ServiceCompat.startForeground(
                this, UpdateNotifier.ID, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        } catch (e: RuntimeException) {
            // Android no deja empezarlo (segundo plano): la descarga sigue mientras viva la app.
            Log.w("UpdateDownloadService", "Sin servicio en primer plano", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!watching) {
            watching = true
            scope.launch { graph.installer.state.first { it !is InstallState.Downloading }; stop() }
        }
        return START_NOT_STICKY
    }

    private fun stop() {
        val graph = (application as LectorVozApp).graph
        // Cancelada: fuera la notificacion. Si no, se queda y la sigue el notificador.
        val idle = graph.installer.state.value.let { it == InstallState.Idle || it == InstallState.NeedsPermission }
        ServiceCompat.stopForeground(this, if (idle) ServiceCompat.STOP_FOREGROUND_REMOVE else ServiceCompat.STOP_FOREGROUND_DETACH)
        // Mientras era del servicio, Android ignoraba los cambios: se repinta con el estado real.
        graph.updateNotifier.refresh()
        stopSelf()
    }
}
