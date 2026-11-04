package com.lectorvoz.app.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
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
    fun consultaComoMuchoUnaVezAlDiaYOmitirPosponeUnDia() = runTest(UnconfinedTestDispatcher()) {
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

        // Omitir por ahora: no sale durante 24 h (tampoco tras reiniciar)...
        c2.snooze()
        assertThat(c2.available.value).isNull()
        now += UpdateChecker.INTERVAL_MS - 10
        assertThat(checker().stored()).isNull()
        // ...y pasado ese tiempo vuelve a avisar.
        now += 20
        c2.checkIfDue(enabled = true)
        assertThat(c2.available.value).isEqualTo(release)
    }

    /** Omitir pospone esa version: si sale otra mas nueva, se avisa de ella enseguida. */
    @Test
    fun omitirSoloPosponeLaVersionAvisada() = runTest(UnconfinedTestDispatcher()) {
        var now = 1_000_000L
        var release = AppRelease("1.1.0", UpdateChecker.RELEASES_PREFIX + "tag/v1.1.0")
        fun checker() = UpdateChecker(prefs, this, "1.0.0", clock = { now }, fetch = { release })
        val c = checker()
        c.checkIfDue(enabled = true)
        c.snooze()
        assertThat(c.available.value).isNull()
        // Al dia siguiente de la ultima consulta (aun dentro de la pausa por reloj) sale la 1.1.1.
        release = AppRelease("1.1.1", UpdateChecker.RELEASES_PREFIX + "tag/v1.1.1")
        now += UpdateChecker.INTERVAL_MS - 1
        assertThat(checker().stored()).isNull()
        now += 2
        val c2 = checker()
        c2.checkIfDue(enabled = true)
        assertThat(c2.available.value?.version).isEqualTo("1.1.1")
    }

    @Test
    fun leeLaApkYResumeLasNovedades() {
        val sha = "a".repeat(64)
        val json = """{"tag_name":"v1.2.0","html_url":"https://github.com/TheWolf1724/Audio_Libro/releases/tag/v1.2.0",
            "body":"## Novedades\n\n- **OCR** para PDF escaneados\n- [Historial](https://x.invalid) de lecturas\n\n| tabla | x |",
            "assets":[{"name":"notas.txt","browser_download_url":"https://github.com/TheWolf1724/Audio_Libro/releases/download/v1.2.0/notas.txt"},
              {"name":"LectorVoz-1.2.0.apk","size":1234,"digest":"sha256:$sha",
               "browser_download_url":"https://github.com/TheWolf1724/Audio_Libro/releases/download/v1.2.0/LectorVoz-1.2.0.apk"}]}"""
        val r = UpdateChecker.parseRelease(json)!!
        assertThat(r.apkUrl).endsWith("LectorVoz-1.2.0.apk")
        assertThat(r.apkSize).isEqualTo(1234)
        assertThat(r.apkSha256).isEqualTo(sha)
        assertThat(r.notes).isEqualTo("Novedades\n\u2022 OCR para PDF escaneados\n\u2022 Historial de lecturas")
        // Una APK alojada fuera del repositorio no se acepta.
        val ajena = json.replace("https://github.com/TheWolf1724/Audio_Libro/releases/download/v1.2.0/LectorVoz-1.2.0.apk", "https://ejemplo.invalid/LectorVoz.apk")
        assertThat(UpdateChecker.parseRelease(ajena)!!.apkUrl).isNull()
        // Sin huella SHA-256 publicada no se instala desde la app (solo "Ver").
        assertThat(UpdateChecker.parseRelease(json.replace(",\"digest\":\"sha256:$sha\"", ""))!!.apkUrl).isNull()
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

    /** La comprobacion manual consulta aunque ya se haya consultado hoy, y avisa de la version mas nueva. */
    @Test
    fun comprobacionManualIgnoraElLimiteYEncuentraVersionNueva() = runTest(UnconfinedTestDispatcher()) {
        val now = 5_000_000L
        var calls = 0
        val release = AppRelease("2.0.0", UpdateChecker.RELEASES_PREFIX + "tag/v2.0.0")
        val c = UpdateChecker(prefs, this, "1.0.0", clock = { now }, fetch = { calls++; release })
        // Ya comprobado hoy: la automatica no vuelve a preguntar.
        c.checkIfDue(enabled = true)
        c.checkIfDue(enabled = true)
        assertThat(calls).isEqualTo(1)
        // La manual consulta igualmente (salta el limite de 24 h).
        c.checkNow()
        assertThat(calls).isEqualTo(2)
        assertThat(c.manualState.value).isEqualTo(ManualCheck.Found(release))
        assertThat(c.available.value).isEqualTo(release)
    }

    /** Si la instalada ya es la ultima publicada, la manual dice que esta al dia. */
    @Test
    fun comprobacionManualConLaUltimaInstaladaDiceUpToDate() = runTest(UnconfinedTestDispatcher()) {
        val release = AppRelease("1.0.0", UpdateChecker.RELEASES_PREFIX + "tag/v1.0.0")
        val c = UpdateChecker(prefs, this, "1.0.0", fetch = { release })
        c.checkNow()
        assertThat(c.manualState.value).isEqualTo(ManualCheck.UpToDate("1.0.0"))
        assertThat(c.available.value).isNull()
    }

    /** Un fallo en la comprobacion manual da un error amable, sin romper nada. */
    @Test
    fun comprobacionManualConFalloDaErrorAmable() = runTest(UnconfinedTestDispatcher()) {
        val c = UpdateChecker(prefs, this, "1.0.0", fetch = { error("sin red") })
        c.checkNow()
        val m = c.manualState.value
        assertThat(m).isInstanceOf(ManualCheck.Error::class.java)
        assertThat((m as ManualCheck.Error).message).contains("sin conexion")
        assertThat(c.available.value).isNull()
    }

    /** Dos pulsaciones seguidas no lanzan dos consultas: la segunda espera a que acabe la primera. */
    @Test
    fun comprobacionManualNoSeSolapaSiYaHayUnaEnCurso() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val release = AppRelease("2.0.0", UpdateChecker.RELEASES_PREFIX + "tag/v2.0.0")
        val c = UpdateChecker(prefs, this, "1.0.0", fetch = { calls++; gate.await(); release })
        c.checkNow()
        assertThat(calls).isEqualTo(1)
        assertThat(c.manualState.value).isEqualTo(ManualCheck.Checking)
        // Segunda pulsacion mientras la primera sigue en curso: no consulta de nuevo.
        c.checkNow()
        assertThat(calls).isEqualTo(1)
        // Al terminar la primera queda su resultado.
        gate.complete(Unit)
        assertThat(c.manualState.value).isEqualTo(ManualCheck.Found(release))
    }
}
