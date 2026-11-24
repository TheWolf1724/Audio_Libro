package com.lectorvoz.app.books

import android.Manifest
import android.app.Notification
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
import kotlinx.coroutines.launch

/** Notificacion de la exportacion de audio: progreso (con "Cancelar") y, al acabar, el resultado. */
object ExportNotifier {
    const val ID = 4202
    const val CHANNEL = "exportacion"
    const val ACTION_CANCEL = "com.lectorvoz.app.EXPORTACION_CANCELAR"
    private const val REQ_OPEN = 51
    private const val REQ_CANCEL = 52

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Exportacion de audio", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progreso y resultado de la exportacion de un libro a audio"
                setSound(null, null)
            },
        )
    }

    /** Notificacion para [st]: en curso (progreso + Cancelar) o terminada (resultado). Nula si no hay exportacion. */
    fun build(context: Context, st: ExportJobState?): Notification? {
        st ?: return null
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, REQ_OPEN,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentIntent(open)
        return if (st.running) {
            val pct = ((st.progress ?: 0f) * 100).toInt().coerceIn(0, 100)
            val part = if (st.wholeBook && st.parts > 1) "Parte ${st.part + 1} de ${st.parts}  -  " else ""
            b.setContentTitle("Exportando audio: ${st.title}")
                .setContentText(if (st.progress != null) "$part$pct %" else "${part}Preparando...")
                .setProgress(100, pct, st.progress == null)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .addAction(0, "Cancelar", PendingIntent.getBroadcast(
                    context, REQ_CANCEL,
                    Intent(context, ExportActionReceiver::class.java).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ))
                .build()
        } else {
            b.setContentTitle(st.title)
                .setContentText(st.result ?: "Exportacion terminada")
                .setStyle(NotificationCompat.BigTextStyle().bigText(st.result ?: "Exportacion terminada"))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
        }
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

/** "Cancelar" desde la notificacion de la exportacion. */
class ExportActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ExportNotifier.ACTION_CANCEL) (context.applicationContext as LectorVozApp).graph.exports.cancel()
    }
}

/**
 * Mantiene viva la app mientras se exporta audio (una exportacion de un libro entero puede durar
 * horas): sin esto, con la pantalla apagada o en otra app, Android congelaba el proceso y la
 * exportacion se perdia. Muestra el progreso y se para sola al terminar. Si Android agota el tiempo del
 * servicio (Android 15+), la exportacion se PAUSA con un aviso y se reanuda saltando lo ya hecho.
 */
class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @android.annotation.SuppressLint("MissingPermission") // `canNotify` comprueba POST_NOTIFICATIONS
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val graph = (application as LectorVozApp).graph
        val n = ExportNotifier.build(this, graph.exports.state.value)
            ?: ExportNotifier.build(this, ExportJobState(bookId = 0, title = "Audio"))!!
        try {
            ServiceCompat.startForeground(
                this, ExportNotifier.ID, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        } catch (e: RuntimeException) {
            // Android no deja empezarlo en segundo plano: la exportacion sigue mientras viva la app.
            stopSelf()
            return START_NOT_STICKY
        }
        if (!watching) {
            watching = true
            scope.launch {
                graph.exports.state.collect { st ->
                    if (st == null) { finish(null); return@collect }
                    if (st.running) {
                        if (ExportNotifier.canNotify(this@ExportService)) {
                            runCatching { NotificationManagerCompat.from(this@ExportService).notify(ExportNotifier.ID, ExportNotifier.build(this@ExportService, st)!!) }
                        }
                    } else {
                        finish(st)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Termina el primer plano dejando (si hay permiso) el resultado en la notificacion. */
    @android.annotation.SuppressLint("MissingPermission") // `canNotify` comprueba POST_NOTIFICATIONS
    private fun finish(st: ExportJobState?) {
        val keep = st != null && ExportNotifier.canNotify(this)
        ServiceCompat.stopForeground(this, if (keep) ServiceCompat.STOP_FOREGROUND_DETACH else ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (keep) runCatching { NotificationManagerCompat.from(this).notify(ExportNotifier.ID, ExportNotifier.build(this, st)!!) }
        stopSelf()
    }

    /** Android 15+: el servicio de datos agoto su tiempo. Se pausa la exportacion (reanudable) y se para. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as LectorVozApp).graph.exports.pauseForTimeout()
    }

    override fun onTimeout(startId: Int) {
        (application as LectorVozApp).graph.exports.pauseForTimeout()
    }
}
