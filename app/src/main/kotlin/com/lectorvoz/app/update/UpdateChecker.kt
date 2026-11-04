package com.lectorvoz.app.update

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Version publicada en GitHub. */
data class AppRelease(val version: String, val url: String)

/**
 * Aviso de nueva version. Como mucho una vez al dia (al abrir la app y si el usuario no lo ha
 * desactivado) pide a GitHub la ultima publicacion del repositorio y la compara con la
 * instalada. Solo se hace esa peticion, sin datos del usuario; no descarga ni instala nada:
 * el aviso abre la pagina de la publicacion en el navegador.
 */
class UpdateChecker(
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val installed: String,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Ultima publicacion (nula si no se pudo saber). Sustituible en las pruebas. */
    private val fetch: suspend () -> AppRelease? = ::fetchLatest,
) {
    constructor(context: Context, scope: CoroutineScope, installed: String, fetch: suspend () -> AppRelease?) :
        this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), scope, installed, fetch = fetch)

    private val mutable = MutableStateFlow(stored())

    /** Version mas nueva que la instalada y no descartada; nula si no hay. */
    val available: StateFlow<AppRelease?> = mutable

    private var running = false

    /** Comprueba si toca (24 h desde la ultima vez). Llamar desde el hilo principal. */
    fun checkIfDue(enabled: Boolean) {
        if (!enabled) return
        mutable.value = stored()
        if (running) return
        val now = clock()
        // Nunca comprobado: toca. Si el reloj fue hacia atras (diferencia negativa), tambien.
        if (prefs.contains(K_LAST_CHECK) && now - prefs.getLong(K_LAST_CHECK, 0L) in 0 until INTERVAL_MS) return
        running = true
        scope.launch {
            try {
                val latest = fetch() ?: return@launch
                prefs.edit()
                    .putLong(K_LAST_CHECK, now)
                    .putString(K_VERSION, latest.version)
                    .putString(K_URL, latest.url)
                    .apply()
                mutable.value = stored()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "Sin comprobar versiones: ${e.message}")
            } finally {
                running = false
            }
        }
    }

    /** El usuario cierra el aviso: no se vuelve a mostrar para esta version. */
    fun dismiss() {
        val shown = mutable.value ?: return
        prefs.edit().putString(K_DISMISSED, shown.version).apply()
        mutable.value = null
    }

    /** Desactivado en ajustes: se quita el aviso que hubiera. */
    fun disable() {
        mutable.value = null
    }

    /** Aviso guardado, si sigue siendo mas nuevo que la instalada y no se descarto. */
    fun stored(): AppRelease? {
        val version = prefs.getString(K_VERSION, null) ?: return null
        val url = prefs.getString(K_URL, null) ?: return null
        if (!isNewer(version, installed)) return null
        if (prefs.getString(K_DISMISSED, null) == version) return null
        return AppRelease(version, url)
    }

    companion object {
        private const val TAG = "UpdateChecker"
        private const val PREFS = "actualizaciones"
        private const val K_LAST_CHECK = "ultima_comprobacion"
        private const val K_VERSION = "version"
        private const val K_URL = "url"
        private const val K_DISMISSED = "descartada"
        const val INTERVAL_MS = 24L * 60 * 60 * 1000

        private const val API = "https://api.github.com/repos/TheWolf1724/Audio_Libro/releases/latest"

        /** Solo se abren paginas del propio repositorio. */
        const val RELEASES_PREFIX = "https://github.com/TheWolf1724/Audio_Libro/releases/"

        /**
         * true si [candidate] es mayor que [current] ("1.2.0" > "1.1.9"; acepta "v" delante y
         * sufijos como "-beta", que se ignoran). Un formato raro nunca cuenta como nuevo.
         */
        fun isNewer(candidate: String, current: String): Boolean {
            val a = parse(candidate) ?: return false
            val b = parse(current) ?: return false
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        private fun parse(v: String): List<Int>? {
            val core = v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            val parts = core.split('.')
            if (parts.isEmpty() || parts.size > 4) return null
            return parts.map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
        }

        /** Lee la respuesta de la API de publicaciones; nula si no es valida o no es del repo. */
        fun parseRelease(json: String): AppRelease? {
            val o = JSONObject(json)
            if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
            val tag = o.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
            val url = o.optString("html_url").takeIf { it.startsWith(RELEASES_PREFIX) } ?: return null
            if (parse(tag) == null) return null
            return AppRelease(tag.trim().removePrefix("v").removePrefix("V"), url)
        }

        /** Pide a GitHub la ultima publicacion (unica conexion de red de la app). */
        suspend fun fetchLatest(): AppRelease? = withContext(Dispatchers.IO) {
            val conn = URL(API).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "LectorVoz")
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                // Respuesta acotada: una publicacion ocupa unos pocos KB.
                val body = conn.inputStream.use { it.readNBytesCompat(MAX_BODY) }
                parseRelease(String(body, Charsets.UTF_8))
            } finally {
                conn.disconnect()
            }
        }

        private const val MAX_BODY = 256 * 1024

        private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (out.size() < max) {
                val n = read(buf, 0, minOf(buf.size, max - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
