package com.lectorvoz.app.integration

import android.content.Context
import androidx.compose.runtime.Immutable
import com.lectorvoz.core.model.TtsEngineType
import com.lectorvoz.tts.engine.SystemTtsEngine
import com.lectorvoz.tts.voice.VoiceCatalog
import com.lectorvoz.tts.voice.VoiceRef
import com.lectorvoz.tts.voice.VoiceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Voz elegible en ajustes; [id] es el valor que se guarda en `VoiceSettings.voiceId`. */
@Immutable
data class VoiceOption(
    val id: String,
    val label: String,
    val detail: String,
    val engine: TtsEngineType,
    val installed: Boolean = true,
)

/** Catalogo de voces para la UI (neuronales de `:tts` y del sistema). */
interface VoicesPort {
    /** Valor de `voiceId` de la voz por defecto. */
    val defaultVoiceId: String

    fun neuralVoices(): List<VoiceOption>

    /** Voces del motor del sistema (puede tardar: arranca TextToSpeech). */
    suspend fun systemVoices(): List<VoiceOption>

    /** Normaliza un `voiceId` guardado (nulo = defecto) a una opcion de la lista. */
    fun resolve(voiceId: String?): String
}

/** [VoicesPort] sobre `VoiceCatalog`, `VoiceStore` y `SystemTtsEngine`. */
class VoicesFacade(private val context: Context) : VoicesPort {
    private val store by lazy { VoiceStore(context) }

    override val defaultVoiceId: String = VoiceRef(VoiceCatalog.DEFAULT_VOICE_ID, VoiceCatalog.DEFAULT_SID).encode()

    override fun neuralVoices(): List<VoiceOption> = VoiceCatalog.all.flatMap { v ->
        val installed = runCatching { store.isInstalled(v) }.getOrDefault(false)
        v.speakers.map { s ->
            VoiceOption(
                id = VoiceRef(v.id, s.sid).encode(),
                label = "${s.name} (${s.code})",
                detail = v.displayName + if (installed) "" else " - no instalada",
                engine = TtsEngineType.NEURAL,
                installed = installed,
            )
        }
    }

    override suspend fun systemVoices(): List<VoiceOption> {
        val engine = SystemTtsEngine(context)
        return try {
            withTimeoutOrNull(5_000) { engine.voices() }.orEmpty()
                .filter { !it.requiresNetwork }
                .map { VoiceOption(it.name, it.locale.displayName, it.name, TtsEngineType.SYSTEM) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        } finally {
            engine.release()
        }
    }

    override fun resolve(voiceId: String?): String =
        if (voiceId.isNullOrBlank()) defaultVoiceId else if (VoiceCatalog.find(VoiceRef.parse(voiceId).voiceId) != null) VoiceRef.parse(voiceId).encode() else voiceId
}
