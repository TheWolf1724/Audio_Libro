package com.lectorvoz.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import android.util.Log
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lectorvoz.core.model.FootnoteMode
import com.lectorvoz.core.model.HighlightMode
import com.lectorvoz.core.model.LowBatteryAction
import com.lectorvoz.core.model.PageAnimation
import com.lectorvoz.core.model.ReaderSettings
import com.lectorvoz.core.model.ReadingLanguage
import com.lectorvoz.core.model.ReadingMode
import com.lectorvoz.core.model.TextAlign
import com.lectorvoz.core.model.ThemeMode
import com.lectorvoz.core.model.TtsEngineType
import com.lectorvoz.core.model.VoiceQuality
import com.lectorvoz.core.model.VoiceSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Ajustes que se pueden fijar por libro (el resto son globales). Nulo = usar el global.
 */
data class BookOverrides(
    val voiceId: String? = null,
    val speed: Float? = null,
    val fontFamily: String? = null,
    val fontSizeSp: Float? = null,
    val readingMode: ReadingMode? = null,
) {
    val isEmpty: Boolean get() = this == NONE

    /** [global] con los valores propios del libro. */
    fun applyTo(global: ReaderSettings): ReaderSettings = SettingsRanges.sanitize(
        global.copy(
            fontFamily = fontFamily ?: global.fontFamily,
            fontSizeSp = fontSizeSp ?: global.fontSizeSp,
            readingMode = readingMode ?: global.readingMode,
        ),
    )

    /** [global] con la voz y velocidad propias del libro. */
    fun applyTo(global: VoiceSettings): VoiceSettings = SettingsRanges.sanitize(
        global.copy(voiceId = voiceId ?: global.voiceId, speed = speed ?: global.speed),
    )

    companion object {
        val NONE = BookOverrides()
    }
}

/** Orden de la biblioteca. */
enum class LibrarySort { RECENT, TITLE, AUTHOR, ADDED, PROGRESS }

/** Preferencias de la interfaz que no son de lectura ni de voz. */
data class AppPrefs(
    val libraryGrid: Boolean = true,
    val librarySort: LibrarySort = LibrarySort.RECENT,
    /** Copiar los libros importados a la memoria privada (si no, solo se referencian). */
    val copyOnImport: Boolean = true,
    /** Preajuste "texto grande" activo (accesibilidad). */
    val largeText: Boolean = false,
    /** Preajuste "fuente para dislexia" activo (accesibilidad). */
    val dyslexiaFont: Boolean = false,
    /** Reducir movimiento: sin curl ni deslizamientos. */
    val reduceMotion: Boolean = false,
)

/** Ajustes persistentes de la app. Todos los valores salen ya saneados. */
interface SettingsRepository {
    val reader: Flow<ReaderSettings>
    val voice: Flow<VoiceSettings>
    val app: Flow<AppPrefs>

    fun overrides(bookId: Long): Flow<BookOverrides>

    /** Ajustes de lectura efectivos para [bookId] (globales + propios del libro). */
    fun readerFor(bookId: Long): Flow<ReaderSettings> =
        combine(reader, overrides(bookId)) { g, o -> o.applyTo(g) }.distinctUntilChanged()

    /** Ajustes de voz efectivos para [bookId]. */
    fun voiceFor(bookId: Long): Flow<VoiceSettings> =
        combine(voice, overrides(bookId)) { g, o -> o.applyTo(g) }.distinctUntilChanged()

    suspend fun updateReader(transform: (ReaderSettings) -> ReaderSettings)
    suspend fun updateVoice(transform: (VoiceSettings) -> VoiceSettings)
    suspend fun updateApp(transform: (AppPrefs) -> AppPrefs)
    suspend fun updateOverrides(bookId: Long, transform: (BookOverrides) -> BookOverrides)
    suspend fun clearOverrides(bookId: Long) = updateOverrides(bookId) { BookOverrides.NONE }
}

/**
 * [SettingsRepository] sobre DataStore Preferences. Los enums se guardan por nombre (un
 * nombre desconocido vuelve al valor por defecto) y todo se sanea con [SettingsRanges] al
 * leer y al escribir.
 */
class DataStoreSettingsRepository(
    private val store: DataStore<Preferences>,
    /** Espera antes de reintentar la lectura tras un error de disco (ms, crece hasta 30 s). */
    private val retryDelayMs: Long = 1_000L,
) : SettingsRepository {

    /**
     * Preferencias; ante un error de E/S (fichero corrupto o disco lleno) se emiten los
     * valores por defecto y se vuelve a leer, sin completar el flujo: los ajustes siguen
     * actualizandose cuando el disco se recupera.
     */
    private val prefs: Flow<Preferences> = store.data.retryWhen { e, attempt ->
        if (e !is IOException) return@retryWhen false
        Log.w(TAG, "No se pudieron leer los ajustes; se usan los valores por defecto", e)
        emit(emptyPreferences())
        delay((retryDelayMs shl attempt.coerceAtMost(5).toInt()).coerceAtMost(30_000L))
        true
    }

    /** Escribe sin propagar errores de disco (el ajuste no se guarda, pero la app no cae). */
    private suspend fun safeEdit(block: (MutablePreferences) -> Unit) {
        try {
            store.edit(block)
        } catch (e: IOException) {
            Log.w(TAG, "No se pudo guardar un ajuste", e)
        }
    }

    override val reader: Flow<ReaderSettings> = prefs.map { readReader(it) }.distinctUntilChanged()
    override val voice: Flow<VoiceSettings> = prefs.map { readVoice(it) }.distinctUntilChanged()
    override val app: Flow<AppPrefs> = prefs.map { readApp(it) }.distinctUntilChanged()

    override fun overrides(bookId: Long): Flow<BookOverrides> =
        prefs.map { readOverrides(it, bookId) }.distinctUntilChanged()

    override suspend fun updateReader(transform: (ReaderSettings) -> ReaderSettings) {
        safeEdit { p -> writeReader(p, SettingsRanges.sanitize(transform(readReader(p)))) }
    }

    override suspend fun updateVoice(transform: (VoiceSettings) -> VoiceSettings) {
        safeEdit { p -> writeVoice(p, SettingsRanges.sanitize(transform(readVoice(p)))) }
    }

    override suspend fun updateApp(transform: (AppPrefs) -> AppPrefs) {
        safeEdit { p -> writeApp(p, transform(readApp(p))) }
    }

    override suspend fun updateOverrides(bookId: Long, transform: (BookOverrides) -> BookOverrides) {
        safeEdit { p -> writeOverrides(p, bookId, transform(readOverrides(p, bookId))) }
    }

    // ------------------------------------------------------------------ lectura

    private object R {
        val theme = stringPreferencesKey("lectura.tema")
        val highContrast = booleanPreferencesKey("lectura.contraste")
        val fontFamily = stringPreferencesKey("lectura.fuente")
        val fontSize = floatPreferencesKey("lectura.tamano")
        val fontWeight = intPreferencesKey("lectura.peso")
        val lineSpacing = floatPreferencesKey("lectura.interlineado")
        val paragraphSpacing = floatPreferencesKey("lectura.espacio_parrafo")
        val indent = floatPreferencesKey("lectura.sangria")
        val letterSpacing = floatPreferencesKey("lectura.espacio_letras")
        val wordSpacing = floatPreferencesKey("lectura.espacio_palabras")
        val marginH = intPreferencesKey("lectura.margen_h")
        val marginV = intPreferencesKey("lectura.margen_v")
        val maxLine = intPreferencesKey("lectura.ancho_linea")
        val align = stringPreferencesKey("lectura.alineacion")
        val hyphen = stringPreferencesKey("lectura.guionado")
        val mode = stringPreferencesKey("lectura.modo")
        val animation = stringPreferencesKey("lectura.animacion")
        val animationMs = intPreferencesKey("lectura.animacion_ms")
        val tapZones = booleanPreferencesKey("lectura.zonas_toque")
        val volumeKeys = booleanPreferencesKey("lectura.teclas_volumen")
        val keepOn = booleanPreferencesKey("lectura.pantalla_encendida")
        val brightness = floatPreferencesKey("lectura.brillo")
        val immersive = booleanPreferencesKey("lectura.inmersivo")
        val pdfRecolor = booleanPreferencesKey("lectura.pdf_recolor")
    }

    private fun readReader(p: Preferences): ReaderSettings {
        val d = ReaderSettings()
        return SettingsRanges.sanitize(
            ReaderSettings(
                theme = p[R.theme].enumOr(d.theme),
                highContrast = p[R.highContrast] ?: d.highContrast,
                fontFamily = p[R.fontFamily] ?: d.fontFamily,
                fontSizeSp = p[R.fontSize].positiveOr(d.fontSizeSp),
                fontWeight = p[R.fontWeight] ?: d.fontWeight,
                lineSpacing = p[R.lineSpacing].positiveOr(d.lineSpacing),
                paragraphSpacingEm = p[R.paragraphSpacing] ?: d.paragraphSpacingEm,
                firstLineIndentEm = p[R.indent],
                letterSpacingEm = p[R.letterSpacing] ?: d.letterSpacingEm,
                wordSpacingEm = p[R.wordSpacing] ?: d.wordSpacingEm,
                marginHorizontalDp = p[R.marginH] ?: d.marginHorizontalDp,
                marginVerticalDp = p[R.marginV] ?: d.marginVerticalDp,
                maxLineChars = p[R.maxLine] ?: d.maxLineChars,
                textAlign = p[R.align].enumOr(d.textAlign),
                hyphenation = p[R.hyphen]?.toBooleanStrictOrNull(),
                readingMode = p[R.mode].enumOr(d.readingMode),
                pageAnimation = p[R.animation].enumOr(d.pageAnimation),
                pageAnimationMillis = (p[R.animationMs] ?: d.pageAnimationMillis).coerceAtLeast(0),
                tapZonesTurnPages = p[R.tapZones] ?: d.tapZonesTurnPages,
                volumeKeysTurnPages = p[R.volumeKeys] ?: d.volumeKeysTurnPages,
                keepScreenOn = p[R.keepOn] ?: d.keepScreenOn,
                brightness = p[R.brightness]?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
                immersive = p[R.immersive] ?: d.immersive,
                pdfRecolor = p[R.pdfRecolor] ?: d.pdfRecolor,
            ),
        )
    }

    private fun writeReader(p: MutablePreferences, s: ReaderSettings) {
        p[R.theme] = s.theme.name
        p[R.highContrast] = s.highContrast
        p[R.fontFamily] = s.fontFamily
        p[R.fontSize] = s.fontSizeSp
        p[R.fontWeight] = s.fontWeight
        p[R.lineSpacing] = s.lineSpacing
        p[R.paragraphSpacing] = s.paragraphSpacingEm
        p.setOrRemove(R.indent, s.firstLineIndentEm)
        p[R.letterSpacing] = s.letterSpacingEm
        p[R.wordSpacing] = s.wordSpacingEm
        p[R.marginH] = s.marginHorizontalDp
        p[R.marginV] = s.marginVerticalDp
        p[R.maxLine] = s.maxLineChars
        p[R.align] = s.textAlign.name
        p.setOrRemove(R.hyphen, s.hyphenation?.toString())
        p[R.mode] = s.readingMode.name
        p[R.animation] = s.pageAnimation.name
        p[R.animationMs] = s.pageAnimationMillis
        p[R.tapZones] = s.tapZonesTurnPages
        p[R.volumeKeys] = s.volumeKeysTurnPages
        p[R.keepOn] = s.keepScreenOn
        p.setOrRemove(R.brightness, s.brightness)
        p[R.immersive] = s.immersive
        p[R.pdfRecolor] = s.pdfRecolor
    }

    // ------------------------------------------------------------------ voz

    private object V {
        val engine = stringPreferencesKey("voz.motor")
        val voiceId = stringPreferencesKey("voz.voz")
        val dialogVoiceId = stringPreferencesKey("voz.voz_dialogos")
        val speed = floatPreferencesKey("voz.velocidad")
        val presets = stringPreferencesKey("voz.preajustes")
        val pitch = floatPreferencesKey("voz.tono")
        val quality = stringPreferencesKey("voz.calidad")
        val language = stringPreferencesKey("voz.idioma")
        val pause = floatPreferencesKey("voz.pausa_factor")
        val pauseClause = intPreferencesKey("voz.pausa_clausula")
        val pauseSentence = intPreferencesKey("voz.pausa_frase")
        val pauseVerse = intPreferencesKey("voz.pausa_verso")
        val pauseParagraph = intPreferencesKey("voz.pausa_parrafo")
        val pauseSection = intPreferencesKey("voz.pausa_seccion")
        val pauseChapter = intPreferencesKey("voz.pausa_capitulo")
        val highlight = stringPreferencesKey("voz.resaltado")
        val follow = booleanPreferencesKey("voz.seguir")
        val pdfFilter = booleanPreferencesKey("voz.filtro_pdf")
        val footnotes = stringPreferencesKey("voz.notas")
        val tables = booleanPreferencesKey("voz.tablas")
        val announce = booleanPreferencesKey("voz.anunciar_capitulos")
        val adaptive = booleanPreferencesKey("voz.ritmo_adaptativo")
        val rewind = booleanPreferencesKey("voz.rebobinado")
        val loudness = booleanPreferencesKey("voz.normalizar")
        val enhance = booleanPreferencesKey("voz.realce")
        val night = booleanPreferencesKey("voz.nocturno")
        val timer = intPreferencesKey("voz.temporizador")
        val shake = booleanPreferencesKey("voz.agitar")
        val battery = stringPreferencesKey("voz.bateria")
        val charging = booleanPreferencesKey("voz.preparar_cargando")
        val doubleTap = booleanPreferencesKey("voz.doble_toque")
    }

    private fun readVoice(p: Preferences): VoiceSettings {
        val d = VoiceSettings()
        return SettingsRanges.sanitize(
            VoiceSettings(
                engine = p[V.engine].enumOr(d.engine),
                voiceId = p[V.voiceId],
                dialogVoiceId = p[V.dialogVoiceId],
                speed = p[V.speed].positiveOr(d.speed),
                speedPresets = p[V.presets]?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }
                    ?.filter { it.isFinite() && it > 0f } ?: d.speedPresets,
                pitch = p[V.pitch].positiveOr(d.pitch),
                quality = p[V.quality].enumOr(d.quality),
                readingLanguage = p[V.language].enumOr(d.readingLanguage),
                punctuationPause = (p[V.pause] ?: d.punctuationPause).let { if (it.isFinite() && it >= 0f) it else d.punctuationPause },
                pauseClauseMs = p[V.pauseClause] ?: d.pauseClauseMs,
                pauseSentenceMs = p[V.pauseSentence] ?: d.pauseSentenceMs,
                pauseVerseMs = p[V.pauseVerse] ?: d.pauseVerseMs,
                pauseParagraphMs = p[V.pauseParagraph] ?: d.pauseParagraphMs,
                pauseSectionMs = p[V.pauseSection] ?: d.pauseSectionMs,
                pauseChapterMs = p[V.pauseChapter] ?: d.pauseChapterMs,
                highlightMode = p[V.highlight].enumOr(d.highlightMode),
                followText = p[V.follow] ?: d.followText,
                skipPdfHeadersFooters = p[V.pdfFilter] ?: d.skipPdfHeadersFooters,
                footnotes = p[V.footnotes].enumOr(d.footnotes),
                readTables = p[V.tables] ?: d.readTables,
                announceChapters = p[V.announce] ?: d.announceChapters,
                adaptivePace = p[V.adaptive] ?: d.adaptivePace,
                smartRewind = p[V.rewind] ?: d.smartRewind,
                loudnessNormalize = p[V.loudness] ?: d.loudnessNormalize,
                voiceEnhance = p[V.enhance] ?: d.voiceEnhance,
                nightMode = p[V.night] ?: d.nightMode,
                sleepTimerMinutes = p[V.timer]?.takeIf { it > 0 },
                shakeToExtendTimer = p[V.shake] ?: d.shakeToExtendTimer,
                lowBattery = p[V.battery].enumOr(d.lowBattery),
                prepareWhileCharging = p[V.charging] ?: d.prepareWhileCharging,
                headphoneDoubleTapRewind = p[V.doubleTap] ?: d.headphoneDoubleTapRewind,
            ),
        )
    }

    private fun writeVoice(p: MutablePreferences, s: VoiceSettings) {
        p[V.engine] = s.engine.name
        p.setOrRemove(V.voiceId, s.voiceId)
        p.setOrRemove(V.dialogVoiceId, s.dialogVoiceId)
        p[V.speed] = s.speed
        p[V.presets] = s.speedPresets.joinToString(",")
        p[V.pitch] = s.pitch
        p[V.quality] = s.quality.name
        p[V.language] = s.readingLanguage.name
        p[V.pause] = s.punctuationPause
        p[V.pauseClause] = s.pauseClauseMs
        p[V.pauseSentence] = s.pauseSentenceMs
        p[V.pauseVerse] = s.pauseVerseMs
        p[V.pauseParagraph] = s.pauseParagraphMs
        p[V.pauseSection] = s.pauseSectionMs
        p[V.pauseChapter] = s.pauseChapterMs
        p[V.highlight] = s.highlightMode.name
        p[V.follow] = s.followText
        p[V.pdfFilter] = s.skipPdfHeadersFooters
        p[V.footnotes] = s.footnotes.name
        p[V.tables] = s.readTables
        p[V.announce] = s.announceChapters
        p[V.adaptive] = s.adaptivePace
        p[V.rewind] = s.smartRewind
        p[V.loudness] = s.loudnessNormalize
        p[V.enhance] = s.voiceEnhance
        p[V.night] = s.nightMode
        p.setOrRemove(V.timer, s.sleepTimerMinutes)
        p[V.shake] = s.shakeToExtendTimer
        p[V.battery] = s.lowBattery.name
        p[V.charging] = s.prepareWhileCharging
        p[V.doubleTap] = s.headphoneDoubleTapRewind
    }

    // ------------------------------------------------------------------ app

    private object A {
        val grid = booleanPreferencesKey("app.cuadricula")
        val sort = stringPreferencesKey("app.orden")
        val copy = booleanPreferencesKey("app.copiar_al_importar")
        val largeText = booleanPreferencesKey("app.texto_grande")
        val dyslexia = booleanPreferencesKey("app.dislexia")
        val reduceMotion = booleanPreferencesKey("app.reducir_movimiento")
    }

    private fun readApp(p: Preferences): AppPrefs {
        val d = AppPrefs()
        return AppPrefs(
            libraryGrid = p[A.grid] ?: d.libraryGrid,
            librarySort = p[A.sort].enumOr(d.librarySort),
            copyOnImport = p[A.copy] ?: d.copyOnImport,
            largeText = p[A.largeText] ?: d.largeText,
            dyslexiaFont = p[A.dyslexia] ?: d.dyslexiaFont,
            reduceMotion = p[A.reduceMotion] ?: d.reduceMotion,
        )
    }

    private fun writeApp(p: MutablePreferences, s: AppPrefs) {
        p[A.grid] = s.libraryGrid
        p[A.sort] = s.librarySort.name
        p[A.copy] = s.copyOnImport
        p[A.largeText] = s.largeText
        p[A.dyslexia] = s.dyslexiaFont
        p[A.reduceMotion] = s.reduceMotion
    }

    // ------------------------------------------------------------------ por libro

    private class O(bookId: Long) {
        val voiceId = stringPreferencesKey("libro.$bookId.voz")
        val speed = floatPreferencesKey("libro.$bookId.velocidad")
        val font = stringPreferencesKey("libro.$bookId.fuente")
        val size = floatPreferencesKey("libro.$bookId.tamano")
        val mode = stringPreferencesKey("libro.$bookId.modo")
    }

    private fun readOverrides(p: Preferences, bookId: Long): BookOverrides {
        val k = O(bookId)
        return BookOverrides(
            voiceId = p[k.voiceId]?.takeIf { it.isNotBlank() },
            speed = p[k.speed]?.takeIf { it.isFinite() && it > 0f }?.let { SettingsRanges.speed.clamp(it) },
            fontFamily = p[k.font]?.takeIf { it in SettingsRanges.fontFamilies },
            fontSizeSp = p[k.size]?.takeIf { it.isFinite() && it > 0f }?.let { SettingsRanges.fontSizeSp.clamp(it) },
            readingMode = p[k.mode]?.let { name -> ReadingMode.entries.firstOrNull { it.name == name } },
        )
    }

    private fun writeOverrides(p: MutablePreferences, bookId: Long, o: BookOverrides) {
        val k = O(bookId)
        p.setOrRemove(k.voiceId, o.voiceId?.takeIf { it.isNotBlank() })
        p.setOrRemove(k.speed, o.speed?.let { SettingsRanges.speed.clamp(it) })
        p.setOrRemove(k.font, o.fontFamily?.takeIf { it in SettingsRanges.fontFamilies })
        p.setOrRemove(k.size, o.fontSizeSp?.let { SettingsRanges.fontSizeSp.clamp(it) })
        p.setOrRemove(k.mode, o.readingMode?.name)
    }

    private companion object {
        const val TAG = "SettingsRepository"

        inline fun <reified E : Enum<E>> String?.enumOr(default: E): E =
            if (this == null) default else enumValues<E>().firstOrNull { it.name == this } ?: default

        fun Float?.positiveOr(default: Float): Float = if (this != null && isFinite() && this > 0f) this else default

        fun <T> MutablePreferences.setOrRemove(key: Preferences.Key<T>, value: T?) {
            if (value == null) remove(key) else set(key, value)
        }

        // Referencias para que los enums usados en ajustes no se pierdan al refactorizar.
        @Suppress("unused")
        val ENUMS = listOf(
            ThemeMode.SYSTEM, TextAlign.START, PageAnimation.CURL, TtsEngineType.NEURAL, VoiceQuality.NORMAL,
            ReadingLanguage.AUTO, HighlightMode.SENTENCE, FootnoteMode.SKIP, LowBatteryAction.WARN,
        )
    }
}
