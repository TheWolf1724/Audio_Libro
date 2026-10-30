package com.lectorvoz.tts.voice

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/** Acceso de solo lectura a los assets (abstraido para pruebas). */
interface AssetFiles {
    /** AssetManager real para sherpa (nulo en pruebas: se usan rutas de fichero). */
    val assetManager: AssetManager?

    /** Abre el asset [path] o lanza [IOException]. */
    fun open(path: String): InputStream

    /** true si el asset [path] existe. */
    fun exists(path: String): Boolean = try {
        open(path).close(); true
    } catch (_: IOException) {
        false
    }
}

/** [AssetFiles] sobre el AssetManager de la app. */
class AndroidAssetFiles(override val assetManager: AssetManager) : AssetFiles {
    override fun open(path: String): InputStream = assetManager.open(path)
}

/**
 * Donde estan los ficheros de una voz lista para cargar en sherpa-onnx.
 * Si [assetManager] no es nulo, [baseDir] es una ruta de assets; si no, de disco.
 */
data class VoiceLocation(
    val voice: VoiceInfo,
    val assetManager: AssetManager?,
    val baseDir: String,
) {
    /** Ruta completa del fichero [name] de la voz. */
    fun path(name: String): String = "$baseDir/$name"
}

/**
 * Voces instaladas: las incluidas se leen de assets sin copiarlas (Supertonic pesa
 * 145 MB); las descargadas viven en `filesDir/voces/<id>/`.
 */
class VoiceStore(
    private val assets: AssetFiles,
    /** `filesDir/voces`. */
    val root: File,
) {
    constructor(context: Context) : this(
        AndroidAssetFiles(context.applicationContext.assets),
        File(context.applicationContext.filesDir, "voces"),
    )

    /** Carpeta de una voz descargada. */
    fun voiceDir(id: String): File = File(root, id)

    /** true si [voice] se puede cargar ya. */
    fun isInstalled(voice: VoiceInfo): Boolean = when (voice.source) {
        VoiceSource.Bundled -> voice.files.all().all { assets.exists("$ASSET_ROOT/${voice.id}/$it") }
        is VoiceSource.Download -> File(voiceDir(voice.id), INSTALLED_MARKER).isFile
    }

    /** Voces del catalogo que estan instaladas. */
    fun installedVoices(): List<VoiceInfo> = VoiceCatalog.all.filter { isInstalled(it) }

    /** Localiza [voice] para cargarla. Lanza [FileNotFoundException] si no esta instalada. */
    fun locate(voice: VoiceInfo): VoiceLocation {
        if (!isInstalled(voice)) throw FileNotFoundException("Voz no instalada: ${voice.id}")
        return when (voice.source) {
            VoiceSource.Bundled -> {
                // Sin AssetManager (pruebas) no se puede leer de assets: error claro.
                val am = assets.assetManager ?: throw FileNotFoundException("Sin AssetManager para ${voice.id}")
                VoiceLocation(voice, am, "$ASSET_ROOT/${voice.id}")
            }
            is VoiceSource.Download -> VoiceLocation(voice, null, voiceDir(voice.id).absolutePath)
        }
    }

    /** Borra una voz descargada. Las incluidas no se pueden borrar. */
    fun delete(voice: VoiceInfo): Boolean {
        if (voice.source !is VoiceSource.Download) return false
        return voiceDir(voice.id).deleteRecursively()
    }

    /**
     * Borra restos de versiones anteriores (copia de `espeak-ng-data` de Piper en
     * `filesDir/voces`). Bloqueante: llamar fuera del hilo principal.
     */
    fun cleanupLegacy() {
        File(root, LEGACY_ESPEAK_DIR).deleteRecursively()
    }

    companion object {
        /** Carpeta de voces dentro de assets. */
        const val ASSET_ROOT = "voces"

        /** Marca de voz descargada completa. */
        const val INSTALLED_MARKER = ".instalada"

        /** Carpeta de espeak-ng-data que copiaban las versiones con Piper. */
        const val LEGACY_ESPEAK_DIR = "espeak-ng-data"
    }
}
