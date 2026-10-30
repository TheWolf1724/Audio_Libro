package com.lectorvoz.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Comprueba que el tema aplica exactamente los colores de docs/diseno/temas.json. */
@RunWith(AndroidJUnit4::class)
class ThemeTest {
    private val json = JSONObject(File("../docs/diseno/temas.json").readText()).getJSONObject("temas")

    private fun color(hex: String): Color {
        val h = hex.removePrefix("#")
        return Color((if (h.length == 6) "FF$h" else h).toLong(16))
    }

    @Test
    fun cincoTemasConColoresDelJson() {
        val claves = json.keys().asSequence().toList()
        assertThat(claves).containsExactly("claro", "oscuro", "oscuroAmoled", "proteccionOcular", "proteccionOcularNoche")
        for (k in claves) {
            val t = json.getJSONObject(k)
            val m3 = t.getJSONObject("material3")
            val lec = t.getJSONObject("lectura")
            val r = resolveTheme(k, highContrast = false)
            assertThat(r.isDark).isEqualTo(t.getBoolean("esOscuro"))
            assertThat(r.colorScheme.primary).isEqualTo(color(m3.getString("primary")))
            assertThat(r.colorScheme.surface).isEqualTo(color(m3.getString("surface")))
            assertThat(r.colorScheme.onSurface).isEqualTo(color(m3.getString("onSurface")))
            assertThat(r.colorScheme.surfaceContainerHighest).isEqualTo(color(m3.getString("surfaceContainerHighest")))
            assertThat(r.reading.background).isEqualTo(color(lec.getString("readerBackground")))
            assertThat(r.reading.text).isEqualTo(color(lec.getString("readerText")))
            assertThat(r.reading.ttsWordHighlight).isEqualTo(color(lec.getString("ttsWordHighlight")))
            assertThat(r.reading.pageEdgeShadow).isEqualTo(color(lec.getString("pageEdgeShadow")))
        }
    }

    @Test
    fun capaDeAltoContrasteSustituyeRoles() {
        val alto = json.getJSONObject("claro").getJSONObject("altoContraste")
        val r = resolveTheme("claro", highContrast = true)
        assertThat(r.colorScheme.primary).isEqualTo(color(alto.getJSONObject("material3").getString("primary")))
        assertThat(r.reading.text).isEqualTo(color(alto.getJSONObject("lectura").getString("readerText")))
        // Lo que la capa no toca se conserva.
        assertThat(r.reading.background).isEqualTo(resolveTheme("claro", false).reading.background)
    }
}
