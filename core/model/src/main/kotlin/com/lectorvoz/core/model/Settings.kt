package com.lectorvoz.core.model

/** Tema visual de la app y del lector. */
enum class ThemeMode {
    LIGHT, DARK, DARK_AMOLED,
    /** Proteccion ocular de dia (sepia calido). */
    EYE_CARE,
    /** Proteccion ocular de noche (ambar sobre marron muy oscuro). */
    EYE_CARE_NIGHT,
    /** Claro u oscuro segun el sistema. */
    SYSTEM,
}

/** Modo de lectura: por paginas o scroll continuo. */
enum class ReadingMode { PAGED, SCROLL }

/** Animacion al pasar pagina en modo [ReadingMode.PAGED]. */
enum class PageAnimation { CURL, SLIDE, COVER, FADE, NONE }

/** Alineacion del texto en EPUB. */
enum class TextAlign { START, JUSTIFY }

/** Que resalta la voz mientras lee. */
enum class HighlightMode { SENTENCE, WORD, NONE }

/** Motor de voz. */
enum class TtsEngineType {
    /** TextToSpeech del sistema Android. */
    SYSTEM,

    /** Voz neuronal local (sherpa-onnx). */
    NEURAL,
}

/** Ajustes de lectura y maquetacion (persistidos en DataStore por `:core:data`). */
data class ReaderSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Aumento de contraste sobre el tema elegido (docs/diseno/TEMAS.md). */
    val highContrast: Boolean = false,
    /** Familia tipografica de lectura: "literata", "atkinson", "opendyslexic", "system", "book". */
    val fontFamily: String = "literata",
    /** Tamano de letra en sp (12..40). */
    val fontSizeSp: Float = 18f,
    /** Peso tipografico (300..600). */
    val fontWeight: Int = 400,
    /** Multiplicador de interlineado (1.2..2.0). */
    val lineSpacing: Float = 1.5f,
    /** Espacio entre parrafos en em (0..2). */
    val paragraphSpacingEm: Float = 0.6f,
    /** Sangria de primera linea en em (0..3); nulo = respetar el libro. */
    val firstLineIndentEm: Float? = null,
    /** Espaciado entre letras en em (0..0.12). */
    val letterSpacingEm: Float = 0f,
    /** Espaciado entre palabras en em (0..0.16). */
    val wordSpacingEm: Float = 0f,
    /** Margen horizontal en dp (0..64). */
    val marginHorizontalDp: Int = 24,
    /** Margen vertical en dp (0..64). */
    val marginVerticalDp: Int = 20,
    /** Ancho maximo de linea en caracteres aproximados (45..90). */
    val maxLineChars: Int = 70,
    val textAlign: TextAlign = TextAlign.START,
    /** Guionado; nulo = automatico (activo si justificado). */
    val hyphenation: Boolean? = null,
    val readingMode: ReadingMode = ReadingMode.PAGED,
    val pageAnimation: PageAnimation = PageAnimation.CURL,
    /** Duracion de la animacion de pagina en milisegundos (0..1000). */
    val pageAnimationMillis: Int = 350,
    /** Tocar los bordes izquierdo/derecho pasa pagina. */
    val tapZonesTurnPages: Boolean = true,
    /** Pasar pagina con las teclas de volumen. */
    val volumeKeysTurnPages: Boolean = false,
    val keepScreenOn: Boolean = true,
    /** Brillo propio 0.05..1; nulo = brillo del sistema. */
    val brightness: Float? = null,
    /** Pantalla completa (ocultar barras del sistema al leer). */
    val immersive: Boolean = true,
    /** PDF: recolorear con el tema (fondo y texto) en lugar de mostrar el original. */
    val pdfRecolor: Boolean = false,
) {
    init {
        require(fontSizeSp > 0f) { "fontSizeSp debe ser positivo" }
        require(lineSpacing > 0f) { "lineSpacing debe ser positivo" }
        require(pageAnimationMillis >= 0) { "pageAnimationMillis negativo" }
        require(brightness == null || brightness in 0f..1f) { "brightness fuera de 0..1" }
    }
}

/** Idioma de lectura en voz alta. */
enum class ReadingLanguage {
    /** Detectar por frase (espanol/ingles); si es dudoso, idioma del libro o espanol. */
    AUTO,
    SPANISH,
    ENGLISH,
}

/** Calidad de la voz neuronal (pasos de generacion: mas calidad, mas bateria). */
enum class VoiceQuality(val steps: Int) { NORMAL(8), HIGH(12), MAX(16) }

/** Que hacer con las notas al pie al leer en voz alta. */
enum class FootnoteMode { SKIP, END_OF_PARAGRAPH }

/** Reaccion con bateria baja mientras suena la voz neuronal. */
enum class LowBatteryAction { NONE, WARN, SWITCH_TO_SYSTEM }

/** Ajustes de voz. */
data class VoiceSettings(
    val engine: TtsEngineType = TtsEngineType.NEURAL,
    /** Voz como "id" o "id#sid"; nulo = voz por defecto (Supertonic 3, locutora F5). */
    val voiceId: String? = null,
    /** Voz para los dialogos; nulo = la misma que el narrador. */
    val dialogVoiceId: String? = null,
    /** Velocidad relativa (1 = normal; cada voz aplica su velocidad base). */
    val speed: Float = 1.0f,
    /** Preajustes de velocidad a un toque. */
    val speedPresets: List<Float> = listOf(1.0f, 1.25f, 1.5f),
    /** Tono relativo (1 = normal). */
    val pitch: Float = 1.0f,
    val quality: VoiceQuality = VoiceQuality.NORMAL,
    val readingLanguage: ReadingLanguage = ReadingLanguage.AUTO,
    /** Factor global de pausas (1 = normal), multiplica las pausas por tipo. */
    val punctuationPause: Float = 1.0f,
    /** Pausas base en ms por tipo (ver PauseKind). */
    val pauseClauseMs: Int = 120,
    val pauseSentenceMs: Int = 320,
    val pauseVerseMs: Int = 450,
    val pauseParagraphMs: Int = 650,
    val pauseSectionMs: Int = 1100,
    val pauseChapterMs: Int = 1600,
    val highlightMode: HighlightMode = HighlightMode.SENTENCE,
    /** La vista sigue automaticamente al texto leido. */
    val followText: Boolean = true,
    /** Filtrar cabeceras, pies y numeros de pagina en PDF. */
    val skipPdfHeadersFooters: Boolean = true,
    val footnotes: FootnoteMode = FootnoteMode.SKIP,
    /** Leer tablas fila a fila (si no, se saltan). */
    val readTables: Boolean = true,
    /** Anunciar "Capitulo N, titulo" al empezar cada capitulo. */
    val announceChapters: Boolean = true,
    /** Algo mas lento en titulos y frases con muchas cifras. */
    val adaptivePace: Boolean = true,
    /** Al reanudar tras una pausa larga, repetir 1-2 frases (o el parrafo tras horas). */
    val smartRewind: Boolean = true,
    /** Igualar volumen entre frases. */
    val loudnessNormalize: Boolean = true,
    /** Ecualizador ligero para entender mejor por el altavoz del movil. */
    val voiceEnhance: Boolean = false,
    /** Modo nocturno: volumen comprimido, sin picos. */
    val nightMode: Boolean = false,
    /** Temporizador de apagado por defecto en minutos; nulo = sin temporizador. */
    val sleepTimerMinutes: Int? = null,
    /** Agitar el movil alarga el temporizador al final de la cuenta. */
    val shakeToExtendTimer: Boolean = true,
    val lowBattery: LowBatteryAction = LowBatteryAction.WARN,
    /** Preparar en audio los capitulos siguientes mientras el movil carga. */
    val prepareWhileCharging: Boolean = false,
    /** Doble toque en el auricular retrocede una frase. */
    val headphoneDoubleTapRewind: Boolean = true,
) {
    init {
        require(speed > 0f) { "speed debe ser positivo" }
        require(pitch > 0f) { "pitch debe ser positivo" }
        require(punctuationPause >= 0f) { "punctuationPause negativo" }
        require(sleepTimerMinutes == null || sleepTimerMinutes > 0) { "sleepTimerMinutes debe ser positivo" }
    }
}
