package com.lectorvoz.app.playback

import android.content.Context
import android.os.Build
import com.lectorvoz.app.BuildConfig
import com.lectorvoz.tts.playback.TtsConfig
import java.io.File
import java.util.concurrent.Executors

/**
 * Registro opcional de la secuencia real de lectura, para diagnosticar saltos de voz. Apagado
 * por defecto y sin coste cuando lo esta (no se conecta ningun sumidero). Al activarlo empieza
 * un fichero nuevo en `getExternalFilesDir(null)/diagnostico/lectura.txt` con una cabecera
 * (version, dispositivo y motor) y conecta [TtsConfig.diagnosticSink]; al desactivarlo suelta
 * el sumidero.
 *
 * Los mensajes se escriben en un hilo propio (no bloquea los de audio ni sintesis) y el fichero
 * se acota a ~2 MB: al pasarse se recorta por el principio (se conserva lo mas reciente).
 */
class ReadingDiagnostics(private val context: Context) {
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "diag-lectura").apply { isDaemon = true }
    }

    @Volatile private var out: File? = null

    /** true si el registro esta activo. */
    val isEnabled: Boolean get() = TtsConfig.diagnostics

    /** Fichero de diagnostico para compartir, o nulo si aun no existe. */
    fun currentFile(): File? = file().takeIf { it.exists() }

    private fun file(): File = File(File(context.getExternalFilesDir(null), "diagnostico"), "lectura.txt")

    /**
     * Activa el registro: borra el fichero anterior, escribe la cabecera y conecta el sumidero.
     * [config] son lineas extra de estado (OCR, mixto, voz...) que ayudan al soporte a leer de un
     * vistazo con que ajustes se grabo.
     */
    fun enable(engineLabel: String, config: List<String> = emptyList()) {
        val f = file()
        out = f
        writer.execute {
            try {
                f.parentFile?.mkdirs()
                f.writeText(header(engineLabel, config))
            } catch (_: Exception) {
            }
        }
        TtsConfig.diagnosticSink = { line -> append(line) }
        TtsConfig.diagnostics = true
    }

    /** Desactiva el registro y suelta el sumidero (vuelve a no tener coste). */
    fun disable() {
        TtsConfig.diagnostics = false
        TtsConfig.diagnosticSink = null
    }

    private fun append(line: String) {
        val f = out ?: return
        writer.execute {
            try {
                f.appendText(line + "\n")
                if (f.length() > MAX_BYTES) truncate(f)
            } catch (_: Exception) {
            }
        }
    }

    /** Conserva la segunda mitad del fichero (recorta por el principio) para no crecer sin fin. */
    private fun truncate(f: File) {
        try {
            val bytes = f.readBytes()
            val keep = bytes.copyOfRange(bytes.size - (MAX_BYTES / 2).toInt(), bytes.size)
            f.writeBytes("[... recortado por tamano ...]\n".toByteArray())
            f.appendBytes(keep)
        } catch (_: Exception) {
        }
    }

    private fun header(engineLabel: String, config: List<String>): String = buildString {
        append("LectorVoz - diagnostico de lectura\n")
        append("version=${BuildConfig.VERSION_NAME}\n")
        append("dispositivo=${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})\n")
        append("motor=$engineLabel\n")
        for (line in config) append("$line\n")
        append("Guia de trazas:\n")
        append("  PAGINA/[i]  frases extraidas de la pagina (comparar con lo que suena)\n")
        append("  START/DONE  frase que empieza/termina de sonar; ERROR  fallo de voz\n")
        append("  MOTOR       motor y voz elegidos (y cambios por bateria)\n")
        append("  OCR         reconocimiento de PDF escaneado a demanda\n")
        append("  MIXTO       recuperar palabras que faltan (texto nativo + OCR por regiones)\n")
        append("----\n")
    }

    private companion object {
        const val MAX_BYTES = 2L * 1024 * 1024
    }
}
