package com.lectorvoz.tts.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/** Codifica un fichero de PCM 16 bits mono (little-endian) a un fichero comprimido. */
fun interface PcmEncoder {
    /**
     * Codifica [pcm] (PCM s16le mono a [sampleRate]) en [out]. [check] se llama con frecuencia y debe
     * lanzar si se cancelo la exportacion (la codificacion de un libro largo dura minutos).
     */
    fun encode(pcm: File, sampleRate: Int, out: File, check: () -> Unit)
}

/**
 * AAC-LC en MP4 (.m4a) con `MediaCodec` + `MediaMuxer`, ambos de Android (sin dependencias). 64 kbps
 * mono: voz hablada clara con ~0,5 MB por minuto (un libro de 10 h ~ 290 MB en vez de 1,7 GB en WAV).
 * Escribe a un fichero local (seekable), que luego se copia al destino del usuario.
 */
object AacEncoder : PcmEncoder {
    private const val BITRATE = 64_000
    private const val TIMEOUT_US = 10_000L
    private const val CHUNK = 16 * 1024
    private const val MAX_IDLE = 3_000 // ~60 s a 2 x 10 ms por vuelta

    override fun encode(pcm: File, sampleRate: Int, out: File, check: () -> Unit) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            val chunk = ByteArray(CHUNK)
            var track = -1
            var ptsUs = 0L
            var inputDone = false
            var outputDone = false
            var idle = 0 // vueltas seguidas sin avanzar (codec colgado)
            pcm.inputStream().buffered(256 * 1024).use { input ->
                while (!outputDone) {
                    check()
                    check(idle < MAX_IDLE) { "El codificador de audio no responde" }
                    idle++
                    if (!inputDone) {
                        val idx = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (idx >= 0) {
                            val buf = codec.getInputBuffer(idx)!!
                            buf.clear()
                            val cap = minOf(chunk.size, buf.capacity()) and 1.inv() // muestras de 2 bytes enteras
                            var n = input.read(chunk, 0, cap)
                            if (n > 0 && n % 2 == 1) { val m = input.read(chunk, n, 1); n = if (m > 0) n + 1 else n - 1 }
                            idle = 0
                            if (n <= 0) {
                                codec.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                buf.put(chunk, 0, n)
                                codec.queueInputBuffer(idx, 0, n, ptsUs, 0)
                                ptsUs += (n / 2L) * 1_000_000L / sampleRate
                            }
                        }
                    }
                    when (val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            idle = 0
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (o >= 0) {
                            idle = 0
                            val data = codec.getOutputBuffer(o)
                            // Los datos de configuracion ya van en el formato del contenedor: no son muestras.
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0 && muxerStarted && data != null) muxer.writeSampleData(track, data, info)
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }
}
