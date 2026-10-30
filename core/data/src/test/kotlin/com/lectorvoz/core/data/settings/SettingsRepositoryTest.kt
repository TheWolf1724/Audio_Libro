package com.lectorvoz.core.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.FootnoteMode
import com.lectorvoz.core.model.HighlightMode
import com.lectorvoz.core.model.LowBatteryAction
import com.lectorvoz.core.model.PageAnimation
import com.lectorvoz.core.model.ReaderSettings
import com.lectorvoz.core.model.ReadingLanguage
import com.lectorvoz.core.model.ReadingMode
import com.lectorvoz.core.model.TextAlign
import com.lectorvoz.core.model.ThemeMode
import com.lectorvoz.core.model.VoiceQuality
import com.lectorvoz.core.model.VoiceSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/** Persistencia de ajustes en DataStore: ida y vuelta, saneado y ajustes por libro. */
@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun TestScope.store() = PreferenceDataStoreFactory.create(
        scope = backgroundScope,
        produceFile = { File(tmp.root, "ajustes.preferences_pb") },
    )

    @Test
    fun valoresPorDefectoSinNadaGuardado() = runTest {
        val repo = DataStoreSettingsRepository(store())
        assertThat(repo.reader.first()).isEqualTo(ReaderSettings())
        assertThat(repo.voice.first()).isEqualTo(VoiceSettings())
    }

    @Test
    fun guardaYLeeTodosLosCampos() = runTest {
        val repo = DataStoreSettingsRepository(store())
        val r = ReaderSettings(
            theme = ThemeMode.EYE_CARE, highContrast = true, fontFamily = "atkinson", fontSizeSp = 22f,
            fontWeight = 450, lineSpacing = 1.7f, paragraphSpacingEm = 1f, firstLineIndentEm = 1.5f,
            letterSpacingEm = 0.05f, wordSpacingEm = 0.1f, marginHorizontalDp = 16, marginVerticalDp = 8,
            maxLineChars = 60, textAlign = TextAlign.JUSTIFY, hyphenation = false,
            readingMode = ReadingMode.SCROLL, pageAnimation = PageAnimation.FADE, pageAnimationMillis = 200,
            tapZonesTurnPages = false, volumeKeysTurnPages = true, keepScreenOn = false, brightness = 0.4f,
            immersive = false, pdfRecolor = true,
        )
        repo.updateReader { r }
        assertThat(repo.reader.first()).isEqualTo(r)

        val v = VoiceSettings(
            voiceId = "supertonic-3-int8#1", dialogVoiceId = "supertonic-3-int8#4", speed = 1.3f,
            speedPresets = listOf(0.9f, 1.2f), pitch = 1.1f, quality = VoiceQuality.MAX,
            readingLanguage = ReadingLanguage.ENGLISH, punctuationPause = 1.5f, pauseClauseMs = 100,
            pauseSentenceMs = 300, pauseVerseMs = 400, pauseParagraphMs = 700, pauseSectionMs = 1000,
            pauseChapterMs = 2000, highlightMode = HighlightMode.WORD,
            followText = false, skipPdfHeadersFooters = false, footnotes = FootnoteMode.END_OF_PARAGRAPH,
            readTables = false, announceChapters = false, adaptivePace = false, smartRewind = false,
            loudnessNormalize = false, voiceEnhance = true, nightMode = true, sleepTimerMinutes = 30,
            shakeToExtendTimer = false, lowBattery = LowBatteryAction.SWITCH_TO_SYSTEM,
            prepareWhileCharging = true, headphoneDoubleTapRewind = false,
        )
        repo.updateVoice { v }
        assertThat(repo.voice.first()).isEqualTo(v)

        // Los nulos se pueden volver a quitar.
        repo.updateReader { it.copy(brightness = null, firstLineIndentEm = null, hyphenation = null) }
        val r2 = repo.reader.first()
        assertThat(r2.brightness).isNull()
        assertThat(r2.firstLineIndentEm).isNull()
        assertThat(r2.hyphenation).isNull()
        repo.updateVoice { it.copy(sleepTimerMinutes = null, voiceId = null) }
        assertThat(repo.voice.first().sleepTimerMinutes).isNull()
        assertThat(repo.voice.first().voiceId).isNull()
    }

    @Test
    fun saneaAlEscribirYAlLeer() = runTest {
        val ds = store()
        val repo = DataStoreSettingsRepository(ds)
        repo.updateReader { it.copy(fontSizeSp = 99f) }
        assertThat(repo.reader.first().fontSizeSp).isEqualTo(40f)
        // Valores corruptos escritos por fuera: enum desconocido y tamano imposible.
        ds.edit {
            it[stringPreferencesKey("lectura.tema")] = "TEMA_RARO"
            it[floatPreferencesKey("lectura.tamano")] = -3f
            it[floatPreferencesKey("voz.velocidad")] = 50f
        }
        val r = repo.reader.first()
        assertThat(r.theme).isEqualTo(ThemeMode.SYSTEM)
        assertThat(r.fontSizeSp).isEqualTo(ReaderSettings().fontSizeSp)
        assertThat(repo.voice.first().speed).isEqualTo(SettingsRanges.speed.max)
    }

    @Test
    fun ajustesPorLibroSobreLosGlobales() = runTest {
        val repo = DataStoreSettingsRepository(store())
        repo.updateReader { it.copy(fontSizeSp = 20f) }
        repo.updateOverrides(7) {
            it.copy(fontSizeSp = 26f, readingMode = ReadingMode.SCROLL, voiceId = "v#1", speed = 1.4f, fontFamily = "opendyslexic")
        }
        val r = repo.readerFor(7).first()
        assertThat(r.fontSizeSp).isEqualTo(26f)
        assertThat(r.readingMode).isEqualTo(ReadingMode.SCROLL)
        assertThat(r.fontFamily).isEqualTo("opendyslexic")
        val v = repo.voiceFor(7).first()
        assertThat(v.voiceId).isEqualTo("v#1")
        assertThat(v.speed).isEqualTo(1.4f)
        // Otro libro usa los globales.
        assertThat(repo.readerFor(8).first().fontSizeSp).isEqualTo(20f)
        repo.clearOverrides(7)
        assertThat(repo.overrides(7).first().isEmpty).isTrue()
        assertThat(repo.readerFor(7).first().fontSizeSp).isEqualTo(20f)
    }

    @Test
    fun preferenciasDeApp() = runTest {
        val repo = DataStoreSettingsRepository(store())
        repo.updateApp { it.copy(libraryGrid = false, librarySort = LibrarySort.TITLE, copyOnImport = false) }
        val a = repo.app.first()
        assertThat(a.libraryGrid).isFalse()
        assertThat(a.librarySort).isEqualTo(LibrarySort.TITLE)
        assertThat(a.copyOnImport).isFalse()
    }
}
