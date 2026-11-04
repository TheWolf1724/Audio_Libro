package com.lectorvoz.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.lectorvoz.app.LectorVozApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Estado de la instalacion de una version nueva. */
sealed interface InstallState {
    data object Idle : InstallState

    /** Descargando: [progress] 0..1 (nulo si no se conoce el tamano). */
    data class Downloading(val progress: Float?) : InstallState

    /** Entregada al instalador de Android (puede pedir una confirmacion). */
    data object Installing : InstallState

    /** Falta permitir "instalar apps desconocidas" a LectorVoz (se abrio el ajuste). */
    data object NeedsPermission : InstallState

    data class Failed(val message: String) : InstallState
}

/**
 * Descarga la APK de una version nueva, comprueba su integridad (tamano y SHA-256 que publica
 * GitHub) y se la entrega al instalador del sistema. Android exige que la APK este firmada con
 * la misma clave que la instalada (si no, la rechaza) y, la primera vez, que el usuario permita
 * a LectorVoz instalar apps y confirme; desde Android 12, las siguientes actualizaciones pueden
 * instalarse sin confirmacion.
 */
class UpdateInstaller(private val context: Context, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = mutable

    private var job: Job? = null
    private val dir = File(context.cacheDir, "actualizacion")

    /** true si la conexion actual es de datos medidos (para avisar del tamano). */
    fun isMetered(): Boolean =
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.isActiveNetworkMetered ?: false

    fun install(release: AppRelease) {
        if (job?.isActive == true) return
        val apkUrl = release.apkUrl
        if (apkUrl == null || !apkUrl.startsWith(UpdateChecker.RELEASES_PREFIX + "download/")) {
            mutable.value = InstallState.Failed("Esta version no incluye una APK para instalar")
            return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            mutable.value = InstallState.NeedsPermission
            openInstallPermission()
            return
        }
        job = scope.launch {
            try {
                mutable.value = InstallState.Downloading(0f)
                val apk = download(apkUrl, release)
                mutable.value = InstallState.Installing
                withContext(Dispatchers.IO) { commit(apk) }
            } catch (e: CancellationException) {
                mutable.value = InstallState.Idle
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Fallo al actualizar", e)
                mutable.value = InstallState.Failed(e.message ?: "No se pudo descargar la actualizacion")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        mutable.value = InstallState.Idle
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Ajuste de Android para permitir a LectorVoz instalar la actualizacion. */
    private fun openInstallPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private suspend fun download(link: String, release: AppRelease): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val part = File(dir, "LectorVoz-${release.version}.apk.part")
        val digest = MessageDigest.getInstance("SHA-256")
        val conn = URL(link).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "LectorVoz")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("El servidor respondio ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize.takeIf { it > 0 }
            var done = 0L
            var shown = -1
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total != null) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != shown) {
                                shown = pct
                                mutable.value = InstallState.Downloading(pct / 100f)
                            }
                        }
                    }
                }
            }
            if (release.apkSize > 0 && done != release.apkSize) throw IOException("Descarga incompleta")
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            release.apkSha256?.let { if (it != sha) throw IOException("La APK descargada no coincide con la publicada") }
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        File(dir, "LectorVoz-${release.version}.apk").also { part.renameTo(it) }
    }

    /** Entrega la APK al instalador; el resultado llega a [InstallResultReceiver]. */
    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out, 64 * 1024)
                        session.fsync(out)
                    }
                }
                val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
                // Mutable: el instalador anade el resultado al intent.
                val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        } finally {
            apk.delete()
        }
    }

    /** Resultado del instalador del sistema. */
    internal fun onResult(status: Int, message: String?, confirm: Intent?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android pide confirmar (primera actualizacion o versiones antiguas).
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> mutable.value = InstallState.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED -> mutable.value = InstallState.Idle
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                mutable.value = InstallState.Failed("Android no acepta la actualizacion (firma o version distinta a la instalada)")
            PackageInstaller.STATUS_FAILURE_STORAGE -> mutable.value = InstallState.Failed("No hay espacio suficiente para instalar")
            else -> mutable.value = InstallState.Failed(message ?: "No se pudo instalar la actualizacion")
        }
    }

    companion object {
        private const val TAG = "UpdateInstaller"
    }
}

/** Recibe el resultado del instalador del sistema. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        @Suppress("DEPRECATION")
        val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
        (context.applicationContext as LectorVozApp).graph.installer.onResult(status, message, confirm)
    }
}
