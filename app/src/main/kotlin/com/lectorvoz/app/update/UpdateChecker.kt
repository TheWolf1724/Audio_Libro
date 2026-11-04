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

/**
 * Version publicada en GitHub: numero, pagina, resumen de novedades y la APK (enlace, tamano y
 * SHA-256 si GitHub lo da). Sin APK solo se puede abrir la pagina.
 */
data class AppRelease(
    val version: String,
    val url: String,
    val notes: String = "",
    val apkUrl: String? = null,
    val apkSize: Long = 0L,
    val apkSha256: String? = null,
)

/** Resultado de una comprobacion manual de actualizaciones (boton "Buscar ahora" de Ajustes). */
enum class ManualResult { NONE, CHECKING, NEW_AVAILABLE, UP_TO_DATE, FAILED }

/**
 * Aviso de nueva version. Como mucho una vez al dia (al abrir la app y si el usuario no lo ha
 * desactivado) pide a GitHub la ultima publicacion del repositorio y la compara con la
 * instalada. Solo se hace esa peticion, sin datos del usuario. "Omitir por ahora" pospone el
 * aviso 24 h; instalar lo hace [UpdateInstaller].
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

    /** Version mas nueva que la instalada y no pospuesta; nula si no hay. */
    val available: StateFlow<AppRelease?> = mutable

    private val mutableManual = MutableStateFlow(ManualResult.NONE)

    /** Resultado de la ultima comprobacion manual (para el boton de Ajustes). */
    val manual: StateFlow<ManualResult> = mutableManual

    private var running = false

    /** Comprueba si toca (24 h desde la ultima vez; un reintento corto si fallo). Llamar desde el hilo principal. */
    fun checkIfDue(enabled: Boolean) {
        if (!enabled) return
        mutable.value = stored()
        if (running) return
        val now = clock()
        // Nunca comprobado: toca. Si el reloj fue hacia atras (diferencia negativa), tambien. El
        // intervalo es de 24 h tras una consulta con exito, pero mas corto tras un fallo (sin
        // red, 403, timeout) para reintentar pronto sin reconsultar en cada apertura.
        if (prefs.contains(K_LAST_CHECK)) {
            val interval = if (prefs.getBoolean(K_LAST_OK, true)) INTERVAL_MS else RETRY_MS
            if (now - prefs.getLong(K_LAST_CHECK, 0L) in 0 until interval) return
        }
        running = true
        scope.launch {
            try { refresh(now) } finally { running = false }
        }
    }

    /**
     * Comprobacion manual (boton "Buscar actualizaciones ahora"): ignora el intervalo y deja el
     * resultado en [manual] para avisar al usuario.
     */
    fun checkNow() {
        if (running) return
        running = true
        mutableManual.value = ManualResult.CHECKING
        scope.launch {
            try {
                val ok = refresh(clock())
                mutableManual.value = when {
                    !ok -> ManualResult.FAILED
                    mutable.value != null -> ManualResult.NEW_AVAILABLE
                    else -> ManualResult.UP_TO_DATE
                }
            } catch (e: CancellationException) {
                mutableManual.value = ManualResult.NONE
                throw e
            } finally {
                running = false
            }
        }
    }

    /** Olvida el resultado de la ultima comprobacion manual (al salir de la pantalla). */
    fun clearManual() { mutableManual.value = ManualResult.NONE }

    /**
     * Pide la ultima publicacion y guarda el resultado y el instante (con exito o con fallo, para
     * el throttle). Devuelve true si GitHub respondio con una publicacion.
     */
    private suspend fun refresh(now: Long): Boolean =
        try {
            val latest = fetch()
            val edit = prefs.edit().putLong(K_LAST_CHECK, now).putBoolean(K_LAST_OK, latest != null)
            if (latest != null) {
                edit.putString(K_VERSION, latest.version)
                    .putString(K_URL, latest.url)
                    .putString(K_NOTES, latest.notes)
                    .putString(K_APK_URL, latest.apkUrl)
                    .putLong(K_APK_SIZE, latest.apkSize)
                    .putString(K_APK_SHA, latest.apkSha256)
            }
            edit.apply()
            mutable.value = stored()
            latest != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "Sin comprobar versiones: ${e.message}")
            // Se apunta el fallo para reintentar pronto (no en cada apertura) en vez de ignorarlo.
            prefs.edit().putLong(K_LAST_CHECK, now).putBoolean(K_LAST_OK, false).apply()
            false
        }

    /** "Omitir por ahora": el aviso vuelve a salir pasadas 24 h. */
    fun snooze() {
        // Solo para la version avisada: si sale otra mas nueva, se avisa de esa. Se guarda el
        // instante (K_SNOOZE_AT) para que un reloj hacia atras no haga reaparecer el aviso antes.
        val now = clock()
        prefs.edit()
            .putLong(K_SNOOZE_UNTIL, now + INTERVAL_MS)
            .putLong(K_SNOOZE_AT, now)
            .putString(K_SNOOZE_VERSION, prefs.getString(K_VERSION, null))
            .apply()
        mutable.value = null
    }

    /** Desactivado en ajustes: se quita el aviso que hubiera. */
    fun disable() {
        mutable.value = null
    }

    /** Aviso guardado, si sigue siendo mas nuevo que la instalada y no esta pospuesto. */
    fun stored(): AppRelease? {
        val version = prefs.getString(K_VERSION, null) ?: return null
        val url = prefs.getString(K_URL, null) ?: return null
        if (!isNewer(version, installed)) return null
        val until = prefs.getLong(K_SNOOZE_UNTIL, 0L)
        val now = clock()
        val snoozed = prefs.getString(K_SNOOZE_VERSION, null)
        // Pospuesto si desde que se omitio han pasado menos de 24 h. Se mide contra el instante de
        // omitir (K_SNOOZE_AT), no contra `until`: si el reloj va hacia atras, now - at es negativo
        // (seguimos pospuestos) en vez de hacer reaparecer el aviso antes de tiempo (revision B3).
        if (until > 0L) {
            val at = prefs.getLong(K_SNOOZE_AT, until - INTERVAL_MS)
            if (now - at < INTERVAL_MS && (snoozed == null || snoozed == version)) return null
        }
        return AppRelease(
            version = version,
            url = url,
            notes = prefs.getString(K_NOTES, null).orEmpty(),
            apkUrl = prefs.getString(K_APK_URL, null),
            apkSize = prefs.getLong(K_APK_SIZE, 0L),
            apkSha256 = prefs.getString(K_APK_SHA, null),
        )
    }

    companion object {
        private const val TAG = "UpdateChecker"
        private const val PREFS = "actualizaciones"
        private const val K_LAST_CHECK = "ultima_comprobacion"
        private const val K_LAST_OK = "ultima_comprobacion_ok"
        private const val K_VERSION = "version"
        private const val K_URL = "url"
        private const val K_NOTES = "novedades"
        private const val K_APK_URL = "apk_url"
        private const val K_APK_SIZE = "apk_bytes"
        private const val K_APK_SHA = "apk_sha256"
        private const val K_SNOOZE_UNTIL = "pospuesta_hasta"
        private const val K_SNOOZE_AT = "pospuesta_en"
        private const val K_SNOOZE_VERSION = "pospuesta_version"
        const val INTERVAL_MS = 24L * 60 * 60 * 1000

        /** Reintento tras un fallo de red: mucho antes que las 24 h, para no reconsultar sin parar. */
        const val RETRY_MS = 2L * 60 * 60 * 1000

        private const val API = "https://api.github.com/repos/TheWolf1724/Audio_Libro/releases/latest"

        /** Solo se abren paginas del propio repositorio. */
        const val RELEASES_PREFIX = "https://github.com/TheWolf1724/Audio_Libro/releases/"

        /** Lineas y caracteres maximos del resumen de novedades. */
        private const val NOTES_LINES = 12
        private const val NOTES_CHARS = 900

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
            var apkUrl: String? = null
            var apkSize = 0L
            var apkSha: String? = null
            val assets = o.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name")
                    val link = a.optString("browser_download_url")
                    // Solo APK del propio repositorio (GitHub redirige luego a su almacenamiento).
                    if (!name.endsWith(".apk", ignoreCase = true) || !link.startsWith(RELEASES_PREFIX + "download/")) continue
                    apkSha = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
                        ?.lowercase()?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
                    // Sin huella publicada no se instala desde la app (solo se ofrece la pagina).
                    if (apkSha == null) continue
                    apkUrl = link
                    apkSize = a.optLong("size")
                    break
                }
            }
            return AppRelease(
                version = tag.trim().removePrefix("v").removePrefix("V"),
                url = url,
                notes = summarize(o.optString("body")),
                apkUrl = apkUrl,
                apkSize = apkSize,
                apkSha256 = apkSha,
            )
        }

        /**
         * Resumen legible de las novedades (Markdown de la publicacion): sin cabeceras, negritas
         * ni enlaces, con vinetas simples y acotado a unas lineas.
         */
        fun summarize(markdown: String): String {
            val lines = markdown.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("```") && !it.startsWith("|") && !it.startsWith("---") }
                .map { line ->
                    var l = line.trimStart('#').trim()
                    l = l.replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1")
                        .replace("**", "").replace("__", "").replace("`", "")
                    if (l.startsWith("- ") || l.startsWith("* ")) l = "• " + l.substring(2).trim()
                    l
                }
                .filter { it.isNotBlank() }
                .take(NOTES_LINES)
                .toList()
            val text = lines.joinToString("\n")
            return if (text.length <= NOTES_CHARS) text else text.take(NOTES_CHARS).substringBeforeLast(' ') + "..."
        }

        /** Pide a GitHub la ultima publicacion. */
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
                val body = conn.inputStream.use { it.readAtMost(MAX_BODY) }
                parseRelease(String(body, Charsets.UTF_8))
            } finally {
                conn.disconnect()
            }
        }

        private const val MAX_BODY = 256 * 1024

        private fun java.io.InputStream.readAtMost(max: Int): ByteArray {
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
