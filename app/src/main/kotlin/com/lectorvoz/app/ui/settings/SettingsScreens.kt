package com.lectorvoz.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lectorvoz.app.R
import com.lectorvoz.app.integration.VoiceOption
import com.lectorvoz.app.ui.components.AppIconButton
import com.lectorvoz.app.ui.components.ChoiceRow
import com.lectorvoz.app.ui.components.NavRow
import com.lectorvoz.app.ui.components.SectionHeader
import com.lectorvoz.app.ui.components.SliderRow
import com.lectorvoz.app.ui.components.SwitchRow
import com.lectorvoz.app.ui.reader.animationLabel
import com.lectorvoz.app.ui.reader.fontLabel
import com.lectorvoz.app.ui.reader.themeLabel
import com.lectorvoz.core.data.settings.AppPrefs
import com.lectorvoz.core.data.settings.SettingsRanges
import com.lectorvoz.tts.neural.PreloadStatus
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
import kotlin.math.roundToInt

/** Andamio comun de las pantallas de ajustes (columna desplazable). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { AppIconButton(R.drawable.ic_arrow_back, "Volver", onBack) }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp), content = content)
    }
}

/** Pantallas de ajustes. */
object SettingsRoutes {
    const val READING = "ajustes/lectura"
    const val VOICE = "ajustes/voz"
    const val DICTIONARY = "ajustes/diccionario"
    const val ACCESSIBILITY = "ajustes/accesibilidad"
    const val STATS = "ajustes/estadisticas"
    const val EXPORT = "ajustes/exportar"
    const val ABOUT = "ajustes/acerca"
}

/** Menu principal de ajustes. */
@Composable
fun SettingsHome(onNavigate: (String) -> Unit, onBack: () -> Unit) {
    SettingsScaffold("Ajustes", onBack) {
        NavRow("Lectura", "Tema, fuente, margenes, paginas y animaciones", R.drawable.ic_menu_book, { onNavigate(SettingsRoutes.READING) })
        NavRow("Voz", "Voz, velocidad, pausas, idioma y resaltado", R.drawable.ic_record_voice_over, { onNavigate(SettingsRoutes.VOICE) })
        NavRow("Diccionario de pronunciacion", "Como debe sonar cada palabra", R.drawable.ic_edit, { onNavigate(SettingsRoutes.DICTIONARY) })
        NavRow("Accesibilidad", "Texto grande, contraste, fuente para dislexia", R.drawable.ic_accessibility, { onNavigate(SettingsRoutes.ACCESSIBILITY) })
        NavRow("Estadisticas", "Tiempo leido y escuchado", R.drawable.ic_check_circle, { onNavigate(SettingsRoutes.STATS) })
        NavRow("Exportar audio", "Guardar un capitulo o el libro entero en audio", R.drawable.ic_download, { onNavigate(SettingsRoutes.EXPORT) })
        NavRow("Acerca de", "Version y licencias", R.drawable.ic_info, { onNavigate(SettingsRoutes.ABOUT) })
    }
}

private fun pct(v: Float) = "${(v * 100).roundToInt()} %"
private fun em(v: Float) = "%.2f em".format(v)

/** Todos los ajustes de lectura (rangos de docs/diseno/TEMAS.md seccion 6). */
@Composable
fun ReadingSettingsContent(s: ReaderSettings, app: AppPrefs, onChange: ((ReaderSettings) -> ReaderSettings) -> Unit, onApp: ((AppPrefs) -> AppPrefs) -> Unit) {
    val r = SettingsRanges
    SectionHeader("Tema")
    ChoiceRow("Tema", ThemeMode.entries, s.theme, ::themeLabel, { v -> onChange { it.copy(theme = v) } }, tag = "lectura-theme")
    SwitchRow("Aumento de contraste", s.highContrast, { v -> onChange { it.copy(highContrast = v) } }, tag = "lectura-highContrast")
    SectionHeader("Texto")
    ChoiceRow("Fuente", r.fontFamilies, s.fontFamily, ::fontLabel, { v -> onChange { it.copy(fontFamily = v) } }, tag = "lectura-fontFamily")
    SliderRow("Tamano", s.fontSizeSp, r.fontSizeSp.min..r.fontSizeSp.max, r.fontSizeSp.step, { "${it.roundToInt()} sp" }, { v -> onChange { it.copy(fontSizeSp = v) } }, tag = "lectura-fontSizeSp")
    SliderRow("Peso", s.fontWeight.toFloat(), r.fontWeight.min.toFloat()..r.fontWeight.max.toFloat(), r.fontWeight.step.toFloat(), { "${it.roundToInt()}" }, { v -> onChange { it.copy(fontWeight = v.roundToInt()) } }, tag = "lectura-fontWeight")
    SliderRow("Interlineado", s.lineSpacing, r.lineSpacing.min..r.lineSpacing.max, r.lineSpacing.step, { "%.2f".format(it) }, { v -> onChange { it.copy(lineSpacing = v) } }, tag = "lectura-lineSpacing")
    SliderRow("Espacio entre parrafos", s.paragraphSpacingEm, r.paragraphSpacingEm.min..r.paragraphSpacingEm.max, r.paragraphSpacingEm.step, ::em, { v -> onChange { it.copy(paragraphSpacingEm = v) } }, tag = "lectura-paragraphSpacingEm")
    SwitchRow("Sangria del libro", s.firstLineIndentEm == null, { book -> onChange { it.copy(firstLineIndentEm = if (book) null else 1f) } }, summary = "Respetar la sangria de primera linea del EPUB", tag = "lectura-firstLineIndentEm")
    s.firstLineIndentEm?.let { ind ->
        SliderRow("Sangria de primera linea", ind, r.firstLineIndentEm.min..r.firstLineIndentEm.max, r.firstLineIndentEm.step, ::em, { v -> onChange { it.copy(firstLineIndentEm = v) } })
    }
    SliderRow("Espaciado entre letras", s.letterSpacingEm, r.letterSpacingEm.min..r.letterSpacingEm.max, r.letterSpacingEm.step, ::em, { v -> onChange { it.copy(letterSpacingEm = v) } }, tag = "lectura-letterSpacingEm")
    SliderRow("Espaciado entre palabras", s.wordSpacingEm, r.wordSpacingEm.min..r.wordSpacingEm.max, r.wordSpacingEm.step, ::em, { v -> onChange { it.copy(wordSpacingEm = v) } }, tag = "lectura-wordSpacingEm")
    ChoiceRow("Alineacion", TextAlign.entries, s.textAlign, { if (it == TextAlign.START) "Izquierda" else "Justificado" }, { v -> onChange { it.copy(textAlign = v) } }, tag = "lectura-textAlign")
    ChoiceRow("Guionado", listOf<Boolean?>(null, true, false), s.hyphenation, { when (it) { null -> "Automatico"; true -> "Si"; false -> "No" } }, { v -> onChange { it.copy(hyphenation = v) } }, tag = "lectura-hyphenation")
    SectionHeader("Pagina")
    SliderRow("Margen lateral", s.marginHorizontalDp.toFloat(), r.marginHorizontalDp.min.toFloat()..r.marginHorizontalDp.max.toFloat(), r.marginHorizontalDp.step.toFloat(), { "${it.roundToInt()} dp" }, { v -> onChange { it.copy(marginHorizontalDp = v.roundToInt()) } }, tag = "lectura-marginHorizontalDp")
    SliderRow("Margen superior e inferior", s.marginVerticalDp.toFloat(), r.marginVerticalDp.min.toFloat()..r.marginVerticalDp.max.toFloat(), r.marginVerticalDp.step.toFloat(), { "${it.roundToInt()} dp" }, { v -> onChange { it.copy(marginVerticalDp = v.roundToInt()) } }, tag = "lectura-marginVerticalDp")
    SliderRow("Ancho maximo de linea", s.maxLineChars.toFloat(), r.maxLineChars.min.toFloat()..r.maxLineChars.max.toFloat(), r.maxLineChars.step.toFloat(), { "${it.roundToInt()} caracteres" }, { v -> onChange { it.copy(maxLineChars = v.roundToInt()) } }, tag = "lectura-maxLineChars")
    ChoiceRow("Modo", ReadingMode.entries, s.readingMode, { if (it == ReadingMode.PAGED) "Paginas" else "Desplazamiento continuo" }, { v -> onChange { it.copy(readingMode = v) } }, tag = "lectura-readingMode")
    ChoiceRow("Animacion de pagina", PageAnimation.entries, s.pageAnimation, ::animationLabel, { v -> onChange { it.copy(pageAnimation = v) } }, tag = "lectura-pageAnimation")
    SliderRow("Duracion de la animacion", s.pageAnimationMillis.toFloat(), 0f..1000f, 50f, { "${it.roundToInt()} ms" }, { v -> onChange { it.copy(pageAnimationMillis = v.roundToInt()) } }, tag = "lectura-pageAnimationMillis")
    SwitchRow("Tocar los bordes pasa pagina", s.tapZonesTurnPages, { v -> onChange { it.copy(tapZonesTurnPages = v) } }, tag = "lectura-tapZonesTurnPages")
    SwitchRow("Teclas de volumen pasan pagina", s.volumeKeysTurnPages, { v -> onChange { it.copy(volumeKeysTurnPages = v) } }, tag = "lectura-volumeKeysTurnPages")
    SectionHeader("Pantalla")
    SwitchRow("Mantener la pantalla encendida", s.keepScreenOn, { v -> onChange { it.copy(keepScreenOn = v) } }, tag = "lectura-keepScreenOn")
    SwitchRow("Pantalla completa al leer", s.immersive, { v -> onChange { it.copy(immersive = v) } }, tag = "lectura-immersive")
    SwitchRow("Brillo del sistema", s.brightness == null, { sys -> onChange { it.copy(brightness = if (sys) null else 0.5f) } }, tag = "lectura-brightness")
    s.brightness?.let { b ->
        SliderRow("Brillo de lectura", b, r.brightness.min..r.brightness.max, r.brightness.step, ::pct, { v -> onChange { it.copy(brightness = v) } })
    }
    SwitchRow("PDF con los colores del tema", s.pdfRecolor, { v -> onChange { it.copy(pdfRecolor = v) } }, summary = "Recolorea fondo y texto de los PDF", tag = "lectura-pdfRecolor")
    SectionHeader("Biblioteca")
    SwitchRow("Copiar los libros al importar", app.copyOnImport, { v -> onApp { it.copy(copyOnImport = v) } }, summary = "Si se desactiva, solo se guarda un enlace al fichero original")
}

private fun speedText(v: Float) = "x" + "%.2f".format(v)

/** Etiqueta de una voz guardada. */
private fun voiceLabel(id: String?, options: List<VoiceOption>, fallback: String) =
    options.firstOrNull { it.id == id }?.let { "${it.label} - ${it.detail}" } ?: id ?: fallback

/** Todos los ajustes de voz (`VoiceSettings`), aunque el motor aun no use alguno. */
@Composable
fun VoiceSettingsContent(
    v: VoiceSettings,
    neural: List<VoiceOption>,
    system: List<VoiceOption>,
    defaultVoiceId: String,
    onChange: ((VoiceSettings) -> VoiceSettings) -> Unit,
    onTest: () -> Unit,
    onLoadSystemVoices: () -> Unit,
    testMessage: String? = null,
    preload: PreloadStatus? = null,
) {
    val r = SettingsRanges
    LaunchedEffect(v.engine) { if (v.engine == TtsEngineType.SYSTEM) onLoadSystemVoices() }
    SectionHeader("Voz")
    ChoiceRow("Motor", TtsEngineType.entries, v.engine, { if (it == TtsEngineType.NEURAL) "Voz natural (local)" else "Voz del sistema" }, { e ->
        if (e == TtsEngineType.SYSTEM) onLoadSystemVoices()
        onChange { it.copy(engine = e, voiceId = null) }
    }, tag = "voz-engine")
    val options = if (v.engine == TtsEngineType.NEURAL) neural else system
    ChoiceRow(
        "Voz y locutor",
        listOf<String?>(null) + options.map { it.id },
        v.voiceId,
        { id -> if (id == null) "Por defecto (" + voiceLabel(defaultVoiceId, neural, "F5") + ")" else voiceLabel(id, options, id) },
        { id -> onChange { it.copy(voiceId = id) } },
        tag = "voz-voiceId",
    )
    Button(onClick = onTest, modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp).testTag("voz-probar")) { Text("Probar voz") }
    Text(
        testMessage ?: "Si estas escuchando un libro, se pausa durante la prueba y sigue solo despues.",
        style = MaterialTheme.typography.bodySmall,
        color = if (testMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    ChoiceRow(
        "Voz de los dialogos",
        listOf<String?>(null) + neural.map { it.id },
        v.dialogVoiceId,
        { id -> if (id == null) "Igual que el narrador" else voiceLabel(id, neural, id) },
        { id -> onChange { it.copy(dialogVoiceId = id) } },
        tag = "voz-dialogVoiceId",
    )
    SliderRow("Velocidad", v.speed, r.speed.min..r.speed.max, 0.05f, ::speedText, { x -> onChange { it.copy(speed = x) } }, tag = "voz-speed")
    PresetsField(v.speedPresets) { p -> onChange { it.copy(speedPresets = p) } }
    SliderRow("Tono", v.pitch, r.pitch.min..r.pitch.max, r.pitch.step, { "%.2f".format(it) }, { x -> onChange { it.copy(pitch = x) } }, tag = "voz-pitch")
    ChoiceRow("Calidad", VoiceQuality.entries, v.quality, {
        when (it) { VoiceQuality.NORMAL -> "Normal (menos bateria)"; VoiceQuality.HIGH -> "Alta"; VoiceQuality.MAX -> "Maxima (mas bateria)" }
    }, { q -> onChange { it.copy(quality = q) } }, tag = "voz-quality")
    ChoiceRow("Idioma de lectura", ReadingLanguage.entries, v.readingLanguage, {
        when (it) { ReadingLanguage.AUTO -> "Automatico (por frase)"; ReadingLanguage.SPANISH -> "Espanol"; ReadingLanguage.ENGLISH -> "Ingles" }
    }, { l -> onChange { it.copy(readingLanguage = l) } }, tag = "voz-readingLanguage")

    SectionHeader("Pausas")
    SliderRow("Factor global de pausas", v.punctuationPause, r.punctuationPause.min..r.punctuationPause.max, r.punctuationPause.step, { "x%.1f".format(it) }, { x -> onChange { it.copy(punctuationPause = x) } }, tag = "voz-punctuationPause")
    PauseSlider("Coma o inciso", v.pauseClauseMs, "voz-pauseClauseMs") { x -> onChange { it.copy(pauseClauseMs = x) } }
    PauseSlider("Fin de frase", v.pauseSentenceMs, "voz-pauseSentenceMs") { x -> onChange { it.copy(pauseSentenceMs = x) } }
    PauseSlider("Verso", v.pauseVerseMs, "voz-pauseVerseMs") { x -> onChange { it.copy(pauseVerseMs = x) } }
    PauseSlider("Parrafo", v.pauseParagraphMs, "voz-pauseParagraphMs") { x -> onChange { it.copy(pauseParagraphMs = x) } }
    PauseSlider("Seccion", v.pauseSectionMs, "voz-pauseSectionMs") { x -> onChange { it.copy(pauseSectionMs = x) } }
    PauseSlider("Capitulo", v.pauseChapterMs, "voz-pauseChapterMs") { x -> onChange { it.copy(pauseChapterMs = x) } }

    SectionHeader("Lectura")
    ChoiceRow("Resaltado", HighlightMode.entries, v.highlightMode, {
        when (it) { HighlightMode.SENTENCE -> "Frase"; HighlightMode.WORD -> "Frase y palabra"; HighlightMode.NONE -> "Ninguno" }
    }, { m -> onChange { it.copy(highlightMode = m) } }, tag = "voz-highlightMode")
    SwitchRow("Seguir el texto", v.followText, { x -> onChange { it.copy(followText = x) } }, summary = "Pasar pagina sola mientras lee", tag = "voz-followText")
    SwitchRow("Saltar cabeceras y pies en PDF", v.skipPdfHeadersFooters, { x -> onChange { it.copy(skipPdfHeadersFooters = x) } }, summary = "Tambien los numeros de pagina", tag = "voz-skipPdfHeadersFooters")
    ChoiceRow("Notas al pie", FootnoteMode.entries, v.footnotes, { if (it == FootnoteMode.SKIP) "Saltar" else "Leer al final del parrafo" }, { f -> onChange { it.copy(footnotes = f) } }, tag = "voz-footnotes")
    SwitchRow("Leer tablas", v.readTables, { x -> onChange { it.copy(readTables = x) } }, summary = "Fila a fila", tag = "voz-readTables")
    SwitchRow("Anunciar capitulos", v.announceChapters, { x -> onChange { it.copy(announceChapters = x) } }, summary = "\"Capitulo N, titulo\" con pausa", tag = "voz-announceChapters")
    SwitchRow("Ritmo adaptativo", v.adaptivePace, { x -> onChange { it.copy(adaptivePace = x) } }, summary = "Algo mas lento en titulos y cifras", tag = "voz-adaptivePace")
    SwitchRow("Rebobinado inteligente", v.smartRewind, { x -> onChange { it.copy(smartRewind = x) } }, summary = "Repetir 1-2 frases al reanudar tras una pausa larga", tag = "voz-smartRewind")

    SectionHeader("Sonido")
    SwitchRow("Igualar volumen", v.loudnessNormalize, { x -> onChange { it.copy(loudnessNormalize = x) } }, tag = "voz-loudnessNormalize")
    SwitchRow("Realce de voz", v.voiceEnhance, { x -> onChange { it.copy(voiceEnhance = x) } }, summary = "Mas clara por el altavoz del movil", tag = "voz-voiceEnhance")
    SwitchRow("Modo nocturno", v.nightMode, { x -> onChange { it.copy(nightMode = x) } }, summary = "Volumen comprimido, sin picos", tag = "voz-nightMode")

    SectionHeader("Temporizador y controles")
    ChoiceRow(
        "Temporizador por defecto",
        listOf<Int?>(null, 5, 10, 15, 20, 30, 45, 60, 90, 120),
        v.sleepTimerMinutes,
        { if (it == null) "Sin temporizador" else "$it minutos" },
        { m -> onChange { it.copy(sleepTimerMinutes = m) } },
        tag = "voz-sleepTimerMinutes",
    )
    SwitchRow("Agitar para alargar el temporizador", v.shakeToExtendTimer, { x -> onChange { it.copy(shakeToExtendTimer = x) } }, tag = "voz-shakeToExtendTimer")
    ChoiceRow("Con bateria baja", LowBatteryAction.entries, v.lowBattery, {
        when (it) { LowBatteryAction.NONE -> "No hacer nada"; LowBatteryAction.WARN -> "Avisar"; LowBatteryAction.SWITCH_TO_SYSTEM -> "Pasar a la voz del sistema" }
    }, { b -> onChange { it.copy(lowBattery = b) } }, tag = "voz-lowBattery")
    SwitchRow("Preparar audio mientras carga", v.prepareWhileCharging, { x -> onChange { it.copy(prepareWhileCharging = x) } }, summary = "Sintetiza los capitulos siguientes con el movil enchufado", tag = "voz-prepareWhileCharging")
    SwitchRow("Doble toque en el auricular retrocede", v.headphoneDoubleTapRewind, { x -> onChange { it.copy(headphoneDoubleTapRewind = x) } }, tag = "voz-headphoneDoubleTapRewind")
    if (preload != null && v.engine == TtsEngineType.NEURAL) PreloadInfo(preload)
}

/**
 * Nivel de precarga de la voz natural en este movil (solo informativo: lo decide la app segun
 * memoria, ahorro de bateria, temperatura y la velocidad de sintesis medida).
 */
@Composable
private fun PreloadInfo(p: PreloadStatus) {
    SectionHeader("Rendimiento en este movil")
    val level = p.policy.level.replaceFirstChar { it.uppercase() }
    val what = when (p.policy.paragraphsAhead) {
        0 -> "Prepara solo las frases siguientes (unos ${p.policy.bufferAheadMs / 1000} s de audio)."
        1 -> "Prepara el parrafo siguiente entero y unos ${p.policy.bufferAheadMs / 1000} s de audio."
        else -> "Prepara los ${p.policy.paragraphsAhead} parrafos siguientes y unos ${p.policy.bufferAheadMs / 1000} s de audio (aprox. la pagina siguiente)."
    }
    val facts = buildList {
        add("Memoria: " + "%.1f GB".format(p.totalMemBytes / (1024.0 * 1024 * 1024)) + if (p.lowRamDevice) " (poca memoria)" else "")
        if (p.rtf != null) add("Sintesis: " + "%.2fx tiempo real".format(p.rtf))
        if (p.powerSave) add("Ahorro de bateria activo")
        if (p.thermal >= 2) add("Movil caliente")
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("voz-precarga")) {
        Text("Precarga de audio: $level", style = MaterialTheme.typography.bodyLarge)
        Text(what, style = MaterialTheme.typography.bodyMedium)
        Text(facts.joinToString(" - "), style = MaterialTheme.typography.bodySmall)
        Text(
            if (p.live) "Se ajusta solo durante la lectura." else "Estimacion: se ajusta sola al leer (memoria, bateria, temperatura y velocidad).",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PauseSlider(label: String, value: Int, tag: String, onChange: (Int) -> Unit) =
    SliderRow(label, value.toFloat(), 0f..3000f, 10f, { "${it.roundToInt()} ms" }, { onChange(it.roundToInt()) }, tag = tag)

@Composable
private fun PresetsField(presets: List<Float>, onChange: (List<Float>) -> Unit) {
    var text by remember(presets) { mutableStateOf(presets.joinToString("; ") { "%.2f".format(it) }) }
    fun commit() {
        val parsed = text.split(';', ' ').mapNotNull { it.trim().replace(',', '.').toFloatOrNull() }
        if (parsed.isNotEmpty()) onChange(parsed)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("Preajustes de velocidad (separados por ;)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("voz-speedPresets"),
    )
}

/** Opciones de accesibilidad (TEMAS.md seccion 7). */
@Composable
fun AccessibilityContent(s: ReaderSettings, app: AppPrefs, vm: SettingsViewModel) {
    SwitchRow("Texto grande", app.largeText, { vm.setLargeText(it) }, summary = "24 sp, interlineado 1,6, margen 16 dp", tag = "acc-largeText")
    SwitchRow("Aumento de contraste", s.highContrast, { x -> vm.reader { it.copy(highContrast = x) } }, tag = "acc-highContrast")
    SwitchRow("Fuente para dislexia", app.dyslexiaFont, { vm.setDyslexia(it) }, summary = "OpenDyslexic con mas espacio entre letras y palabras", tag = "acc-dyslexia")
    SwitchRow("Reducir movimiento", app.reduceMotion, { vm.setReduceMotion(it) }, summary = "Fundido breve en lugar de curvar o deslizar", tag = "acc-reduceMotion")
    Text(
        "La interfaz respeta el tamano de letra del sistema (hasta 200 %) y TalkBack. Los botones miden al menos 48 dp.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(16.dp),
    )
}
