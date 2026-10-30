package com.lectorvoz.tts.cache

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.tts.neural.SynthesisParams
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Cache de audio en disco: ida y vuelta, claves, LRU por capitulo y corte a medias. */
class AudioDiskCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private val p = SynthesisParams(speed = 1.15f, sid = 4, lang = "es", numSteps = 8)
    private var clock = 1_000_000L

    private fun cache(max: Long = AudioDiskCache.DEFAULT_MAX_BYTES) =
        AudioDiskCache(tmp.root.resolve("c"), max) { clock }

    @Test
    fun guardaYLeeConPrecisionDe16Bits() {
        val c = cache()
        val pcm = FloatArray(1000) { (it - 500) / 600f }
        c.put("libro", 3, "v", p, "Hola.", pcm, 44_100)
        val back = c.get("libro", 3, "v", p, "Hola.")!!
        assertThat(back.sampleRate).isEqualTo(44_100)
        assertThat(back.pcm.size).isEqualTo(1000)
        for (i in pcm.indices) assertThat(back.pcm[i]).isWithin(1e-4f).of(pcm[i])
        // Otra instancia (indice leido de disco).
        assertThat(cache().get("libro", 3, "v", p, "Hola.")!!.pcm.size).isEqualTo(1000)
        assertThat(c.sizeBytes()).isEqualTo(2000L + 24)
    }

    @Test
    fun laClaveDependeDeTextoVozYParametros() {
        val c = cache()
        c.put("libro", 0, "v", p, "Hola.", FloatArray(10), 44_100)
        assertThat(c.contains("libro", 0, "v", p, "Hola.")).isTrue()
        assertThat(c.get("libro", 0, "v", p, "Adios.")).isNull()
        assertThat(c.get("libro", 0, "v", p.copy(sid = 1), "Hola.")).isNull()
        assertThat(c.get("libro", 0, "v", p.copy(lang = "en"), "Hola.")).isNull()
        assertThat(c.get("libro", 0, "v", p.copy(numSteps = 12), "Hola.")).isNull()
        assertThat(c.get("libro", 0, "v", p.copy(speed = 1.2f), "Hola.")).isNull()
        assertThat(c.get("otro", 0, "v", p, "Hola.")).isNull()
        assertThat(c.get("libro", 1, "v", p, "Hola.")).isNull()
    }

    @Test
    fun desalojaElCapituloMenosUsadoAlPasarseDelLimite() {
        // Cada capitulo: 1000 muestras = 2000 bytes + 24 de indice.
        val c = cache(max = 5_000)
        c.put("l", 0, "v", p, "a", FloatArray(1000), 44_100)
        clock += 10_000
        c.put("l", 1, "v", p, "b", FloatArray(1000), 44_100)
        clock += 10_000
        // Se usa el capitulo 0: pasa a ser el mas reciente.
        assertThat(c.get("l", 0, "v", p, "a")).isNotNull()
        clock += 10_000
        c.put("l", 2, "v", p, "c", FloatArray(1000), 44_100)
        assertThat(c.sizeBytes()).isAtMost(5_000L)
        assertThat(c.get("l", 1, "v", p, "b")).isNull()
        assertThat(c.get("l", 0, "v", p, "a")).isNotNull()
        assertThat(c.get("l", 2, "v", p, "c")).isNotNull()
    }

    @Test
    fun indiceQueApuntaADatosCortadosSeIgnora() {
        val c = cache()
        c.put("l", 0, "v", p, "a", FloatArray(1000) { 0.1f }, 44_100)
        // Simula un corte: los datos quedan mas cortos que lo que dice el indice.
        val pcm = tmp.root.resolve("c").walkTopDown().first { it.name.endsWith(".pcm") }
        pcm.writeBytes(pcm.readBytes().copyOf(500))
        assertThat(cache().get("l", 0, "v", p, "a")).isNull()
    }

    /** Revision 2, M2: recortar a un tamano desaloja otros libros (LRU) y respeta el actual. */
    @Test
    fun recortarDesalojaOtrosLibrosYRespetaElActual() {
        val c = cache(max = 200_000)
        c.put("viejo", 0, "v", p, "a", FloatArray(20_000), 44_100)
        clock += 1_000
        c.put("viejo", 1, "v", p, "b", FloatArray(20_000), 44_100)
        clock += 1_000
        c.put("actual", 0, "v", p, "c", FloatArray(10_000), 44_100)
        assertThat(c.trimTo(70_000, keepBookId = "actual")).isTrue()
        assertThat(c.contains("viejo", 0, "v", p, "a")).isFalse()
        assertThat(c.contains("viejo", 1, "v", p, "b")).isTrue()
        assertThat(c.contains("actual", 0, "v", p, "c")).isTrue()
        // Imposible sin borrar el libro actual: no se toca y devuelve false.
        assertThat(c.trimTo(1_000, keepBookId = "actual")).isFalse()
        assertThat(c.contains("actual", 0, "v", p, "c")).isTrue()
    }

    /** Revision 2, B5: un registro de indice a medias no desalinea los siguientes. */
    @Test
    fun registroDeIndiceAMediasNoDesalineaLosSiguientes() {
        cache().put("l", 0, "v", p, "uno", FloatArray(100) { 0.1f }, 44_100)
        val idx = tmp.root.resolve("c").walkTopDown().first { it.name.endsWith(".idx") }
        idx.appendBytes(ByteArray(10) { 7 }) // corte a mitad de escribir otro registro
        cache().put("l", 0, "v", p, "dos", FloatArray(100) { 0.2f }, 44_100)
        val fresh = cache()
        assertThat(fresh.get("l", 0, "v", p, "uno")).isNotNull()
        assertThat(fresh.get("l", 0, "v", p, "dos")).isNotNull()
        assertThat(idx.length() % 24).isEqualTo(0L)
    }

    @Test
    fun limpiar() {
        val c = cache()
        c.put("l", 0, "v", p, "a", FloatArray(10), 44_100)
        c.clear()
        assertThat(c.sizeBytes()).isEqualTo(0L)
        assertThat(c.get("l", 0, "v", p, "a")).isNull()
    }
}
