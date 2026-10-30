package com.lectorvoz.tts.export

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.Utterance
import com.lectorvoz.core.model.VoiceSettings
import com.lectorvoz.tts.FakeUtteranceProvider
import com.lectorvoz.tts.engine.RenderedAudio
import com.lectorvoz.tts.engine.UtteranceRenderer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Exportar un capitulo a WAV con un renderizador falso. */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioExporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val book = FakeUtteranceProvider(
        listOf(listOf("Uno.", "Dos dos."), listOf("Tres.", "Cuatro.")),
    )

    /** 100 muestras de 0,5 por frase + 50 de pausa, a 8000 Hz; tarda 10 ms virtuales. */
    private class FakeRenderer : UtteranceRenderer {
        val rendered = ArrayList<Long>()
        override suspend fun render(u: Utterance, settings: VoiceSettings): RenderedAudio {
            delay(10)
            rendered += u.id
            return RenderedAudio(FloatArray(100) { 0.5f }, 50, 8000)
        }
    }

    @Test
    fun exportaSoloElCapituloConCabeceraWavYProgreso() = runTest {
        val renderer = FakeRenderer()
        val out = ByteArrayOutputStream()
        val progress = ArrayList<ExportProgress>()
        val result = AudioExporter(renderer, StandardTestDispatcher(testScheduler))
            .exportChapterWav(book, 1, VoiceSettings(), out, tmp.root) { progress += it }
        assertThat(renderer.rendered).containsExactly(3L, 4L).inOrder()
        assertThat(result.frames).isEqualTo(300L)
        assertThat(result.sampleRate).isEqualTo(8000)
        val bytes = out.toByteArray()
        assertThat(bytes.size.toLong()).isEqualTo(result.bytes)
        assertThat(bytes.size).isEqualTo(44 + 600)
        val h = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(String(bytes, 0, 4)).isEqualTo("RIFF")
        assertThat(h.getInt(4)).isEqualTo(36 + 600)
        assertThat(String(bytes, 8, 4)).isEqualTo("WAVE")
        assertThat(h.getShort(22).toInt()).isEqualTo(1) // mono
        assertThat(h.getInt(24)).isEqualTo(8000)
        assertThat(h.getShort(34).toInt()).isEqualTo(16)
        assertThat(h.getInt(40)).isEqualTo(600)
        assertThat(h.getShort(44).toInt()).isEqualTo((0.5f * 32767).toInt())
        assertThat(h.getShort(44 + 2 * 120).toInt()).isEqualTo(0) // pausa
        assertThat(progress.map { it.utterances }).containsExactly(1, 2).inOrder()
        assertThat(progress.last().fraction).isWithin(0.15f).of(1f)
        assertThat(tmp.root.listFiles()!!.toList()).isEmpty()
    }

    @Test
    fun cancelarNoEscribeNadaYBorraElTemporal() = runTest {
        val renderer = FakeRenderer()
        val out = ByteArrayOutputStream()
        val job = async {
            AudioExporter(renderer, StandardTestDispatcher(testScheduler))
                .exportChapterWav(book, 0, VoiceSettings(), out, tmp.root)
        }
        advanceTimeBy(15)
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertThat(job.isCancelled).isTrue()
        assertThat(renderer.rendered).hasSize(1)
        assertThat(out.size()).isEqualTo(0)
        assertThat(tmp.root.listFiles()!!.toList()).isEmpty()
    }

    /** Revision 2, B6: el destino (Uri) solo se abre y trunca al terminar de sintetizar. */
    @Test
    fun elDestinoSoloSeAbreAlTerminar() = runTest {
        val renderer = FakeRenderer()
        var opened = 0
        val exporter = AudioExporter(renderer, StandardTestDispatcher(testScheduler))
        val job = async {
            exporter.exportChapterWavTo(book, 0, VoiceSettings(), tmp.root, {}, closeAfter = true) {
                opened++
                ByteArrayOutputStream()
            }
        }
        advanceTimeBy(15)
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertThat(opened).isEqualTo(0)
        val out = ByteArrayOutputStream()
        exporter.exportChapterWavTo(book, 0, VoiceSettings(), tmp.root, {}, closeAfter = true) {
            opened++
            out
        }
        assertThat(opened).isEqualTo(1)
        assertThat(out.size()).isEqualTo(44 + 600)
    }
}
