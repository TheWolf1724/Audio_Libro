package com.lectorvoz.tts.voice

import android.content.res.AssetManager
import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress

/** Descarga reanudable, verificacion SHA-256 e instalacion atomica con un servidor local. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private lateinit var archive: ByteArray
    private val ranges = ArrayList<String?>()

    /** Assets falsos en memoria. */
    private class MapAssets(private val files: Map<String, ByteArray>) : AssetFiles {
        override val assetManager: AssetManager? = null
        override fun open(path: String): InputStream =
            files[path]?.inputStream() ?: throw FileNotFoundException(path)
    }

    @Before
    fun setUp() {
        archive = tarBz2(
            *ModelFiles.Supertonic().all().mapIndexed { i, name ->
                "voz-prueba/$name" to ByteArray(if (i == 0) 100_000 else 100) { (it % 251).toByte() }
            }.toTypedArray(),
            "voz-prueba/LICENSE" to "MIT".toByteArray(),
            "voz-prueba/otro.py" to "ignorar".toByteArray(),
        )
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/voz.tar.bz2") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            ranges += range
            val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            val body = archive.copyOfRange(from, archive.size)
            if (range != null) {
                ex.responseHeaders.add("Content-Range", "bytes $from-${archive.size - 1}/${archive.size}")
                ex.sendResponseHeaders(206, body.size.toLong())
            } else {
                ex.sendResponseHeaders(200, body.size.toLong())
            }
            ex.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After
    fun tearDown() = server.stop(0)

    private fun voice(sha: String) = VoiceInfo(
        id = "voz-prueba",
        displayName = "Prueba",
        language = "es",
        gender = VoiceGender.UNKNOWN,
        downloadBytes = archive.size.toLong(),
        installedBytes = 100_000,
        license = "MIT",
        kind = NeuralModelKind.SUPERTONIC,
        speakers = listOf(VoiceSpeaker(0, "F1", "Mujer 1", VoiceGender.FEMALE)),
        sampleRate = 44_100,
        source = VoiceSource.Download(
            url = "http://127.0.0.1:${server.address.port}/voz.tar.bz2",
            sha256 = sha,
            archiveRoot = "voz-prueba",
        ),
        files = ModelFiles.Supertonic(),
    )

    @Test
    fun instalaVerificaYExtraeSoloLoNecesario() = runTest {
        val store = VoiceStore(MapAssets(emptyMap()), tmp.newFolder("voces"))
        val v = voice(Sha256.of(archive))
        val progress = ArrayList<DownloadProgress>()
        VoiceDownloader(store, StandardTestDispatcher(testScheduler)).install(v) { progress += it }
        assertThat(store.isInstalled(v)).isTrue()
        val dir = store.voiceDir(v.id)
        val first = ModelFiles.Supertonic().all().first()
        assertThat(dir.resolve(first).length()).isEqualTo(100_000L)
        assertThat(dir.resolve("LICENSE").readText()).isEqualTo("MIT")
        assertThat(dir.resolve("otro.py").exists()).isFalse()
        assertThat(progress.last().bytes).isEqualTo(archive.size.toLong())
        // Sin temporales.
        assertThat(store.root.resolve(".descargas").listFiles()!!.toList()).isEmpty()
        // Localizable para sherpa por ruta de disco.
        val loc = store.locate(v)
        assertThat(loc.assetManager).isNull()
        assertThat(loc.path(first)).isEqualTo(dir.resolve(first).absolutePath)
    }

    @Test
    fun shaIncorrectoNoInstalaNada() {
        val store = VoiceStore(MapAssets(emptyMap()), tmp.newFolder("voces"))
        val v = voice("0".repeat(64))
        assertThrows(VoiceDownloadException::class.java) {
            kotlinx.coroutines.runBlocking { VoiceDownloader(store).install(v) }
        }
        assertThat(store.isInstalled(v)).isFalse()
        assertThat(store.voiceDir(v.id).exists()).isFalse()
    }

    @Test
    fun reanudaUnaDescargaParcialConRange() = runTest {
        val store = VoiceStore(MapAssets(emptyMap()), tmp.newFolder("voces"))
        val v = voice(Sha256.of(archive))
        val part = store.root.resolve(".descargas/${v.id}.tar.bz2.part")
        part.parentFile!!.mkdirs()
        part.writeBytes(archive.copyOfRange(0, archive.size / 2))
        VoiceDownloader(store, StandardTestDispatcher(testScheduler)).install(v)
        assertThat(ranges).containsExactly("bytes=${archive.size / 2}-")
        assertThat(store.isInstalled(v)).isTrue()
    }

    @Test
    fun limpiaElEspeakDeVersionesAnteriores() {
        val store = VoiceStore(MapAssets(emptyMap()), tmp.newFolder("voces"))
        store.root.resolve("espeak-ng-data/voices").mkdirs()
        store.root.resolve("espeak-ng-data/voices/es").writeText("viejo")
        store.cleanupLegacy()
        assertThat(store.root.resolve("espeak-ng-data").exists()).isFalse()
    }

    @Test
    fun vozIncluidaSinAssetsNoEstaInstalada() {
        val store = VoiceStore(MapAssets(emptyMap()), tmp.newFolder("voces"))
        val v = VoiceCatalog.find(VoiceCatalog.DEFAULT_VOICE_ID)!!
        assertThat(store.isInstalled(v)).isFalse()
        assertThrows(IOException::class.java) { store.locate(v) }
    }

    private fun tarBz2(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(out)).use { tar ->
            for ((name, data) in entries) {
                val e = TarArchiveEntry(name)
                e.size = data.size.toLong()
                tar.putArchiveEntry(e)
                tar.write(data)
                tar.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }
}
