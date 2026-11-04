package com.lectorvoz.app.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith

/** Robustez de la instalacion: no se instala sin APK ni sin huella SHA-256 (M2). */
@RunWith(AndroidJUnit4::class)
class UpdateInstallerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun installer() = UpdateInstaller(context, CoroutineScope(Dispatchers.Unconfined))
    private val apk = UpdateChecker.RELEASES_PREFIX + "download/v1.2.0/LectorVoz-1.2.0.apk"

    @Test
    fun sinApkNoInstala() {
        val inst = installer()
        inst.install(AppRelease("1.2.0", UpdateChecker.RELEASES_PREFIX + "tag/v1.2.0"))
        val st = inst.state.value
        assertThat(st).isInstanceOf(InstallState.Failed::class.java)
        assertThat((st as InstallState.Failed).message).contains("no incluye")
    }

    @Test
    fun sinHuellaShaNoInstala() {
        val inst = installer()
        // URL de APK valida del repo pero sin digest (p. ej. preferencias viejas): no se instala.
        inst.install(AppRelease("1.2.0", UpdateChecker.RELEASES_PREFIX + "tag/v1.2.0", apkUrl = apk, apkSha256 = null))
        val st = inst.state.value
        assertThat(st).isInstanceOf(InstallState.Failed::class.java)
        assertThat((st as InstallState.Failed).message).contains("verificar")
    }

    @Test
    fun apkDeOtroOrigenNoInstala() {
        val inst = installer()
        inst.install(AppRelease("1.2.0", UpdateChecker.RELEASES_PREFIX + "tag/v1.2.0", apkUrl = "https://ejemplo.invalid/app.apk", apkSha256 = "a".repeat(64)))
        assertThat(inst.state.value).isInstanceOf(InstallState.Failed::class.java)
    }
}
