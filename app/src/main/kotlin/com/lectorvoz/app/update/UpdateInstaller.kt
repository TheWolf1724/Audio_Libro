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
import androidx.core.content.ContextCompat
import com.lectorvoz.app.LectorVozApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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

    /**
     * Android pide que el usuario confirme la instalacion. Si la app estaba en segundo plano,
     * Android no deja abrir esa pantalla sola: el aviso la ofrece con un boton.
     */
    data class NeedsConfirmation(val intent: Intent) : InstallState

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

    /** Sesion de PackageInstaller en curso (para abandonarla si el usuario cancela u omite). */
    @Volatile private var sessionId: Int? = null

    /** Version que se descarga o instala (para la notificacion). */
    @Volatile var version: String? = null
        private set

    /** La app esta en pantalla (MainActivity): solo entonces se puede abrir la confirmacion. */
    @Volatile var foreground = false
    private val dir = File(context.cacheDir, "actualizacion")

    /** true si la conexion actual es de datos medidos (para avisar del tamano). */
    fun isMetered(): Boolean =
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.isActiveNetworkMetered ?: false

    fun install(release: AppRelease) {
        if (job?.isActive == true && mutable.value !is InstallState.Failed) return
        val apkUrl = release.apkUrl
        if (apkUrl == null || !apkUrl.startsWith(UpdateChecker.RELEASES_PREFIX + "download/")) {
            mutable.value = InstallState.Failed("Esta version no incluye una APK para instalar")
            return
        }
        // Sin huella SHA-256 no se instala: defiende ante preferencias viejas que guardaron una
        // URL de APK sin digest (antes de exigirlo). Nunca se instala sin poder verificar.
        if (release.apkSha256 == null) {
            mutable.value = InstallState.Failed("Esta version no se puede verificar (sin huella SHA-256); no se instala")
            return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            mutable.value = InstallState.NeedsPermission
            openInstallPermission()
            return
        }
        val previous = job
        version = release.version
        mutable.value = InstallState.Downloading(0f)
        // Si el usuario sale de la app, la descarga sigue con su notificacion.
        runCatching { ContextCompat.startForegroundService(context, Intent(context, UpdateDownloadService::class.java)) }
            .onFailure { Log.w(TAG, "No se pudo iniciar el servicio de descarga", it) }
        job = scope.launch {
            val me = coroutineContext.job
            // Un intento anterior cancelado puede seguir bloqueado leyendo de la red: se espera a
            // que acabe para que no borre ni pise los ficheros de este.
            previous?.cancelAndJoin()
            fun set(s: InstallState) { if (job === me) mutable.value = s }
            try {
                val apk = download(apkUrl, release) { set(it) }
                set(InstallState.Installing)
                withContext(Dispatchers.IO) { commit(apk) }
            } catch (e: CancellationException) {
                set(InstallState.Idle)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Fallo al actualizar", e)
                set(InstallState.Failed(if (e is UpdateError) e.message.orEmpty() else "No se pudo descargar la actualizacion. Comprueba la conexion y reintentalo."))
            }
        }
    }

    fun cancel() {
        job?.cancel()
        // Si ya se entrego a PackageInstaller (Installing/NeedsConfirmation), se abandona esa
        // sesion pendiente para no dejarla colgada en el sistema (revision M4).
        abandonSession()
        mutable.value = InstallState.Idle
    }

    /** Abandona la sesion de PackageInstaller en curso, si la hay. */
    private fun abandonSession() {
        val id = sessionId ?: return
        sessionId = null
        runCatching { context.packageManager.packageInstaller.abandonSession(id) }
    }

    /** Abre la confirmacion de Android desde la pantalla (la app en primer plano). */
    fun confirm(activity: android.app.Activity) {
        val st = mutable.value as? InstallState.NeedsConfirmation ?: return
        mutable.value = InstallState.Installing
        runCatching { activity.startActivity(st.intent) }.onFailure { mutable.value = InstallState.Failed("No se pudo abrir la confirmacion de Android") }
    }

    /** Ajuste de Android para permitir a LectorVoz instalar la actualizacion. */
    private fun openInstallPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private suspend fun download(link: String, release: AppRelease, set: (InstallState) -> Unit): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val part = File(dir, "actualizacion.apk.part")
        val finalApk = File(dir, "actualizacion.apk")
        // Un .apk ya completo de un intento anterior pudo quedarse (puede ser de otra version): fuera.
        finalApk.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        // Si hay un .part parcial (y menor que el total conocido) se intenta reanudar desde el.
        val hintTotal = release.apkSize.takeIf { it > 0 }
        var existing = if (part.isFile) part.length() else 0L
        if (existing < 0L || (hintTotal != null && existing >= hintTotal)) existing = 0L
        val conn = URL(link).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "LectorVoz")
            // Pide solo lo que falta si hay un parcial; el servidor puede aceptarlo (206) o no (200).
            if (existing > 0L) conn.setRequestProperty("Range", "bytes=$existing-")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw UpdateError("GitHub no entrego la descarga ($code). Reintentalo mas tarde.")
            }
            // Solo se reanuda si pedimos Range y el servidor respondio 206 (si no, se empieza de 0).
            val resume = code == HttpURLConnection.HTTP_PARTIAL && existing > 0L
            val reported = conn.contentLengthLong.takeIf { it > 0 }
            val total = when {
                hintTotal != null -> hintTotal
                resume && reported != null -> existing + reported
                else -> reported
            }
            if ((total ?: 0L) > MAX_APK_BYTES) throw UpdateError("La descarga es demasiado grande")
            var done: Long
            if (resume) {
                // Precarga la huella con los bytes ya escritos (no hace falta volver a bajarlos).
                part.inputStream().use { old ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = old.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                    }
                }
                done = existing
            } else {
                // El servidor ignoro Range (200) o no habia parcial util: empezar de cero.
                done = 0L
            }
            var shown = -1
            conn.inputStream.use { input ->
                // append = resume: con 206 se continua el fichero; con 200 se sobrescribe.
                FileOutputStream(part, resume).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (done > MAX_APK_BYTES) throw UpdateError("La descarga es demasiado grande")
                        if (total != null) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != shown) {
                                shown = pct
                                set(InstallState.Downloading(pct / 100f))
                            }
                        }
                    }
                }
            }
            if (release.apkSize > 0 && done != release.apkSize) throw UpdateError("La descarga quedo incompleta. Reintentalo.")
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            release.apkSha256?.let { if (it != sha) throw UpdateError("La APK descargada no coincide con la publicada") }
        } catch (e: Exception) {
            // Un parcial con huella equivocada (p. ej. de otra version) se borra: el reintento baja entero.
            part.delete()
            if (e !is CancellationException && isNoSpace(e)) throw UpdateError("No hay espacio para la actualizacion")
            throw e
        } finally {
            conn.disconnect()
        }
        finalApk.also { if (!part.renameTo(it)) throw UpdateError("No se pudo guardar la descarga") }
    }

    /** true si [e] (o su causa) es una falta de espacio en disco (ENOSPC). */
    private fun isNoSpace(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            val m = t.message?.lowercase().orEmpty()
            if ("enospc" in m || "no space" in m) return true
            t = t.cause
        }
        return false
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
        sessionId = id
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
            sessionId = null
            throw e
        } finally {
            apk.delete()
        }
    }

    /** Resultado del instalador del sistema. */
    internal fun onResult(status: Int, message: String?, confirm: Intent?) {
        // Salvo cuando aun falta la confirmacion del usuario, la sesion ya esta resuelta.
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) sessionId = null
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android pide confirmar (primera actualizacion o versiones antiguas). Con la app
                // en pantalla se abre ya; si no, Android lo impide en silencio: el aviso ofrece
                // "Confirmar" para abrirla desde la pantalla.
                val i = confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return run { mutable.value = InstallState.Failed("Android no pidio la confirmacion") }
                mutable.value = InstallState.NeedsConfirmation(i)
                if (foreground) runCatching { context.startActivity(i) }.onSuccess { mutable.value = InstallState.Installing }
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
        private const val MAX_APK_BYTES = 400L * 1024 * 1024
    }
}

/** Fallo con un mensaje propio para el usuario (los de la red se resumen en uno). */
private class UpdateError(message: String) : IOException(message)

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
