package com.lectorvoz.core.data.settings

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.ReaderSettings
import com.lectorvoz.core.model.VoiceSettings
import org.junit.Test

class SettingsRangesTest {
    @Test
    fun valoresPorDefectoYaEstanEnRango() {
        assertThat(SettingsRanges.sanitize(ReaderSettings())).isEqualTo(ReaderSettings())
        assertThat(SettingsRanges.sanitize(VoiceSettings())).isEqualTo(VoiceSettings())
    }

    @Test
    fun recortaFueraDeRango() {
        val s = SettingsRanges.sanitize(
            ReaderSettings(fontSizeSp = 99f, lineSpacing = 0.5f, marginHorizontalDp = 200, fontFamily = "comic", brightness = 0.01f),
        )
        assertThat(s.fontSizeSp).isEqualTo(40f)
        assertThat(s.lineSpacing).isEqualTo(1.2f)
        assertThat(s.marginHorizontalDp).isEqualTo(64)
        assertThat(s.fontFamily).isEqualTo("literata")
        assertThat(s.brightness).isEqualTo(0.05f)
        assertThat(SettingsRanges.sanitize(VoiceSettings(speed = 10f)).speed).isEqualTo(3f)
    }
}
