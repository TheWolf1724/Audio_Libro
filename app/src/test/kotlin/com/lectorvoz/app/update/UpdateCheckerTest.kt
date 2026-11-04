package com.lectorvoz.app.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/** Aviso de nueva version: comparacion, lectura de la respuesta y frecuencia de consulta. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class UpdateCheckerTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("prueba-actualizaciones", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test
    fun comparaVersiones() {
        assertThat(UpdateChecker.isNewer("1.1.0", "1.0.0")).isTrue()
        assertThat(UpdateChecker.isNewer("v1.0.1", "1.0.0")).isTrue()
        assertThat(UpdateChecker.isNewer("1.10.0", "1.9.9")).isTrue()
        assertThat(UpdateChecker.isNewer("2", "1.9.9")).isTrue()
        assertThat(UpdateChecker.isNewer("1.0.0", "1.0.0")).isFalse()
        assertThat(UpdateChecker.isNewer("1.0", "1.0.0")).isFalse()
        assertThat(UpdateChecker.isNewer("0.9.9", "1.0.0")).isFalse()
        assertThat(UpdateChecker.isNewer("1.1.0-beta", "1.0.0")).isTrue()
        assertThat(UpdateChecker.isNewer("1.1.0", "1.0.0-debug")).isTrue()
        // Formatos raros nunca cuentan como nuevos.
        assertThat(UpdateChecker.isNewer("ultima", "1.0.0")).isFalse()
        assertThat(UpdateChecker.isNewer("1.x", "1.0.0")).isFalse()
    }

    @Test
    fun leeLaRespuestaDeGithubYRechazaLoAjeno() {
        val ok = """{"tag_name":"v1.2.0","html_url":"https://github.com/TheWolf1724/Audio_Libro/releases/tag/v1.2.0","draft":false,"prerelease":false}"""
        assertThat(UpdateChecker.parseRelease(ok)).isEqualTo(AppRelease("1.2.0", "https://github.com/TheWolf1724/Audio_Libro/releases/tag/v1.2.0"))
        val ajena = """{"tag_name":"v9.0.0","html_url":"https://ejemplo.invalid/releases/tag/v9.0.0"}"""
        assertThat(UpdateChecker.parseRelease(ajena)).isNull()
        val previa = """{"tag_name":"v9.0.0","html_url":"https://github.com/TheWolf1724/Audio_Libro/releases/tag/v9.0.0","prerelease":true}"""
        assertThat(UpdateChecker.parseRelease(previa)).isNull()
        val sinVersion = """{"tag_name":"nombre","html_url":"https://github.com/TheWolf1724/Audio_Libro/releases/tag/nombre"}"""
        assertThat(UpdateChecker.parseRelease(sinVersion)).isNull()
    }

    @Test
    fun consultaComoMuchoUnaVezAlDiaYRespetaElDescarte() = runTest(UnconfinedTestDispatcher()) {
        var now = 1_000_000L
        var calls = 0
        val release = AppRelease("1.1.0", UpdateChecker.RELEASES_PREFIX + "tag/v1.1.0")
        fun checker() = UpdateChecker(prefs, this, "1.0.0", clock = { now }, fetch = { calls++; release })

        val c = checker()
        c.checkIfDue(enabled = false)
        assertThat(calls).isEqualTo(0)
        c.checkIfDue(enabled = true)
        assertThat(calls).isEqualTo(1)
        assertThat(c.available.value).isEqualTo(release)

        // Mismo dia: no vuelve a preguntar; el aviso se recuerda tras reiniciar.
        now += UpdateChecker.INTERVAL_MS - 1
        val c2 = checker()
        c2.checkIfDue(enabled = true)
        assertThat(calls).isEqualTo(1)
        assertThat(c2.available.value).isEqualTo(release)

        // Descartado: no se muestra mas para esa version.
        c2.dismiss()
        assertThat(c2.available.value).isNull()
        assertThat(checker().stored()).isNull()

        // Al dia siguiente vuelve a preguntar.
        now += 2
        c2.checkIfDue(enabled = true)
        assertThat(calls).isEqualTo(2)
        assertThat(c2.available.value).isNull()
    }

    @Test
    fun sinVersionNuevaNoAvisa() = runTest(UnconfinedTestDispatcher()) {
        val c = UpdateChecker(prefs, this, "1.1.0", fetch = { AppRelease("1.1.0", UpdateChecker.RELEASES_PREFIX + "tag/v1.1.0") })
        c.checkIfDue(enabled = true)
        assertThat(c.available.value).isNull()
    }

    @Test
    fun unFalloDeRedNoRompeNadaYReintentaOtroDia() = runTest(UnconfinedTestDispatcher()) {
        var calls = 0
        val c = UpdateChecker(prefs, this, "1.0.0", fetch = { calls++; error("sin red") })
        c.checkIfDue(enabled = true)
        assertThat(c.available.value).isNull()
        // Sin respuesta valida no se apunta la consulta: se reintenta en la siguiente apertura.
        c.checkIfDue(enabled = true)
        assertThat(calls).isEqualTo(2)
    }
}
