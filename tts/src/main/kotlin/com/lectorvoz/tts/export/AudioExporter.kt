package com.lectorvoz.tts.export

import android.content.Context
import android.net.Uri
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.Utterance
import com.lectorvoz.core.model.VoiceSettings
import com.lectorvoz.tts.UtteranceProvider
import com.lectorvoz.tts.engine.UtteranceRenderer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/** Progreso de una exportacion. [fraction] 0..1 si se conoce la longitud del capitulo. */
data class ExportProgress(val utterances: Int, val offset: Int, val chapterLength: Int?) {
    val fraction: Float?
        get() = chapterLength?.takeIf { it > 0 }?.let { (offset.toFloat() / it).coerceIn(0f, 1f) }
}

/** Resultado: muestras escritas, frecuencia y bytes del fichero. */
data class ExportResult(val frames: Long, val sampleRate: Int, val bytes: Long) {
    val durationMs: Long get() = if (sampleRate > 0) frames * 1000 / sampleRate else 0
}

/**
 * Exporta capitulos a WAV PCM 16 bits mono con la misma cadena que la lectura (voz
 * neuronal, pausas calibradas, fundidos, normalizacion...). Se cancela cancelando la
 * corrutina; el destino solo se escribe al final (primero a un temporal en [tempDir]),
 * asi que una exportacion cancelada no deja un fichero a medias en el destino.
 *
 * M4A/AAC no se implementa: WAV es sencillo y robusto (aprox. 5 MB por minuto a 44,1 kHz).
 */
class AudioExporter(
    private val renderer: UtteranceRenderer,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Exporta el capitulo [chapter] a [out] (no lo cierra). */
    suspend fun exportChapterWav(
        provider: UtteranceProvider,
        chapter: Int,
        settings: VoiceSettings,
        out: OutputStream,
        tempDir: File,
        onProgress: (ExportProgress) -> Unit = {},
    ): ExportResult = exportChapterWavTo(provider, chapter, settings, tempDir, onProgress, closeAfter = false) { out }

    /**
     * Exporta a lo que devuelva [open], que solo se llama al terminar de sintetizar (si se
     * cancela o falla antes, el destino no se toca). Con [closeAfter] se cierra al acabar.
     */
    internal suspend fun exportChapterWavTo(
        provider: UtteranceProvider,
        chapter: Int,
        settings: VoiceSettings,
        tempDir: File,
        onProgress: (ExportProgress) -> Unit,
        closeAfter: Boolean,
        open: () -> OutputStream,
    ): ExportResult {
        val length = provider.chapterLength(chapter)
        val tmp = withContext(io) { File.createTempFile("export", ".pcm", tempDir) }
        try {
            var frames = 0L
            var sampleRate = 0
            var count = 0
            withContext(io) { BufferedOutputStream(tmp.outputStream(), 256 * 1024) }.use { pcmOut ->
                var u: Utterance? = provider.at(Locator(chapter, 0))?.takeIf { it.range.start.chapter == chapter }
                while (u != null) {
                    coroutineContext.ensureActive()
                    val audio = renderer.render(u, settings)
                    if (sampleRate == 0) sampleRate = audio.sampleRate
                    withContext(io) {
                        writePcm16(pcmOut, audio.pcm, audio.silenceFrames)
                    }
                    frames += audio.pcm.size + audio.silenceFrames
                    count++
                    onProgress(ExportProgress(count, u.range.endExclusive.offset, length))
                    u = provider.next(u)?.takeIf { it.range.start.chapter == chapter }
                }
            }
            if (sampleRate == 0) sampleRate = DEFAULT_RATE
            val bytes = withContext(io) {
                val out = open()
                try {
                    WavWriter.writeHeader(out, frames, sampleRate)
                    tmp.inputStream().use { it.copyTo(out, 256 * 1024) }
                    out.flush()
                } finally {
                    if (closeAfter) out.close()
                }
                WavWriter.HEADER_BYTES + frames * 2
            }
            return ExportResult(frames, sampleRate, bytes)
        } finally {
            // Tambien al cancelar (withContext ya no se ejecutaria): borrar es inmediato.
            tmp.delete()
        }
    }

    /** Exporta a un Uri elegido por el usuario (SAF); [context] para el ContentResolver. */
    suspend fun exportChapterWav(
        context: Context,
        uri: Uri,
        provider: UtteranceProvider,
        chapter: Int,
        settings: VoiceSettings,
        onProgress: (ExportProgress) -> Unit = {},
    ): ExportResult {
        val resolver = context.applicationContext.contentResolver
        // El destino se abre (y trunca) solo cuando el audio ya esta completo en el temporal.
        return exportChapterWavTo(provider, chapter, settings, context.cacheDir, onProgress, closeAfter = true) {
            resolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException("No se pudo abrir $uri")
        }
    }

    private fun writePcm16(out: OutputStream, pcm: FloatArray, silence: Int) {
        val buf = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (x in pcm) buf.putShort((x.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        out.write(buf.array())
        if (silence > 0) out.write(ByteArray(silence * 2))
    }

    private companion object {
        const val DEFAULT_RATE = 44_100
    }
}

/** Cabecera RIFF/WAVE de PCM 16 bits mono. */
object WavWriter {
    const val HEADER_BYTES = 44

    /** Escribe la cabecera para [frames] muestras a [sampleRate]. */
    fun writeHeader(out: OutputStream, frames: Long, sampleRate: Int) {
        val data = frames * 2
        require(data + 36 <= 0xffffffffL) { "WAV demasiado grande" }
        val b = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + data).toInt())
        b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        b.putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data.toInt())
        out.write(b.array())
    }

    /** WAV completo de [pcm] float (para pruebas y muestras). */
    fun write(out: OutputStream, pcm: FloatArray, sampleRate: Int) {
        writeHeader(out, pcm.size.toLong(), sampleRate)
        val buf = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (x in pcm) buf.putShort((x.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        out.write(buf.array())
    }
}
