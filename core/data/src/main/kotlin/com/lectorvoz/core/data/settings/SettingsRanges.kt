package com.lectorvoz.core.data.settings

import com.lectorvoz.core.model.ReaderSettings
import com.lectorvoz.core.model.VoiceSettings

/** Rango con paso de un ajuste numerico (docs/diseno/TEMAS.md, seccion 6). */
data class FloatRange(val min: Float, val max: Float, val step: Float) {
    /** Recorta [v] al rango (los valores no finitos van al minimo). */
    fun clamp(v: Float): Float = if (v.isFinite()) v.coerceIn(min, max) else min
}

/** Rango entero con paso. */
data class IntRange2(val min: Int, val max: Int, val step: Int) {
    fun clamp(v: Int): Int = v.coerceIn(min, max)
}

/** Rangos y validacion de los ajustes de lectura y voz. */
object SettingsRanges {
    val fontSizeSp = FloatRange(12f, 40f, 1f)
    val lineSpacing = FloatRange(1.2f, 2.0f, 0.05f)
    val paragraphSpacingEm = FloatRange(0f, 2f, 0.1f)
    val firstLineIndentEm = FloatRange(0f, 3f, 0.5f)
    val letterSpacingEm = FloatRange(0f, 0.12f, 0.01f)
    val wordSpacingEm = FloatRange(0f, 0.16f, 0.02f)
    val fontWeight = IntRange2(300, 600, 50)
    val marginHorizontalDp = IntRange2(0, 64, 4)
    val marginVerticalDp = IntRange2(0, 64, 4)
    val maxLineChars = IntRange2(45, 90, 5)
    val pageAnimationMillis = IntRange2(0, 1000, 50)
    val brightness = FloatRange(0.05f, 1f, 0.01f)

    val speed = FloatRange(0.5f, 3f, 0.1f)
    val pitch = FloatRange(0.5f, 2f, 0.05f)
    val punctuationPause = FloatRange(0f, 3f, 0.1f)
    val sleepTimerMinutes = IntRange2(1, 240, 5)
    /** Pausas por tipo en milisegundos. */
    val pauseMs = IntRange2(0, 3000, 10)
    /** Numero de preajustes de velocidad. */
    const val MAX_SPEED_PRESETS = 6

    /** Familias tipograficas de lectura admitidas. */
    val fontFamilies = listOf("literata", "atkinson", "opendyslexic", "system", "book")

    /** Devuelve [s] con todos los valores dentro de rango. */
    fun sanitize(s: ReaderSettings): ReaderSettings = s.copy(
        fontFamily = if (s.fontFamily in fontFamilies) s.fontFamily else ReaderSettings().fontFamily,
        fontSizeSp = fontSizeSp.clamp(s.fontSizeSp),
        fontWeight = fontWeight.clamp(s.fontWeight),
        lineSpacing = lineSpacing.clamp(s.lineSpacing),
        paragraphSpacingEm = paragraphSpacingEm.clamp(s.paragraphSpacingEm),
        firstLineIndentEm = s.firstLineIndentEm?.let { firstLineIndentEm.clamp(it) },
        letterSpacingEm = letterSpacingEm.clamp(s.letterSpacingEm),
        wordSpacingEm = wordSpacingEm.clamp(s.wordSpacingEm),
        marginHorizontalDp = marginHorizontalDp.clamp(s.marginHorizontalDp),
        marginVerticalDp = marginVerticalDp.clamp(s.marginVerticalDp),
        maxLineChars = maxLineChars.clamp(s.maxLineChars),
        pageAnimationMillis = pageAnimationMillis.clamp(s.pageAnimationMillis),
        brightness = s.brightness?.let { brightness.clamp(it) },
    )

    /** Devuelve [s] con todos los valores dentro de rango. */
    fun sanitize(s: VoiceSettings): VoiceSettings = s.copy(
        voiceId = s.voiceId?.takeIf { it.isNotBlank() },
        dialogVoiceId = s.dialogVoiceId?.takeIf { it.isNotBlank() },
        speed = speed.clamp(s.speed),
        speedPresets = s.speedPresets.map { speed.clamp(it) }.distinct().sorted().take(MAX_SPEED_PRESETS)
            .ifEmpty { VoiceSettings().speedPresets },
        pitch = pitch.clamp(s.pitch),
        punctuationPause = punctuationPause.clamp(s.punctuationPause),
        pauseClauseMs = pauseMs.clamp(s.pauseClauseMs),
        pauseSentenceMs = pauseMs.clamp(s.pauseSentenceMs),
        pauseVerseMs = pauseMs.clamp(s.pauseVerseMs),
        pauseParagraphMs = pauseMs.clamp(s.pauseParagraphMs),
        pauseSectionMs = pauseMs.clamp(s.pauseSectionMs),
        pauseChapterMs = pauseMs.clamp(s.pauseChapterMs),
        sleepTimerMinutes = s.sleepTimerMinutes?.let { sleepTimerMinutes.clamp(it) },
    )
}
