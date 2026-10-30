# Arquitectura de LectorVoz

Version 1 — 2026-10-28. Diseno visual y de temas: `docs/diseno/TEMAS.md`.

## 1. Principios
- Ligera y rapida: sin frameworks de inyeccion (DI manual con un `AppGraph`), sin WebView
  para leer, render de paginas a bitmap en hilos de fondo con cache acotada en memoria.
- Todo local y gratuito: voces neuronales en el dispositivo, nada de red salvo descargar
  voces adicionales cuando el usuario lo pida.
- Modulos pequenos con contratos claros: la logica pura (texto, EPUB, paginacion abstracta)
  se prueba en JVM sin dispositivo.
- La voz y la vista comparten un unico sistema de posiciones (`Locator`): la voz dice que
  rango esta leyendo y la vista lo resalta y pasa de pagina sola.

## 2. Decisiones
| Tema | Decision | Alternativa descartada |
|---|---|---|
| Lenguaje/UI | Kotlin 2.4.21, Jetpack Compose (BOM 2026.09.00), Material 3 | Vistas XML |
| Vista de pagina | `View` propia (`PageFlipView`) embebida en Compose con `AndroidView` | Compose puro: peor control de mallas y bitmaps |
| Animaciones | curl (Canvas.drawBitmapMesh, acelerado por hardware), deslizar, cubrir, fundido, ninguna; modo scroll continuo aparte | OpenGL ES: mas complejo y mas consumo, se reserva por si drawBitmapMesh no da la talla |
| EPUB | Motor propio: zip + OPF + nav/NCX + jsoup 1.23.2 -> modelo de documento -> paginacion con StaticLayout | Readium (WebView, impide curl real) |
| PDF | `io.legere:pdfiumandroid:2.0.3` (Apache-2.0): render y texto con cajas por caracter | PdfRenderer (solo bloques), MuPDF (AGPL) |
| Voz sistema | `android.speech.tts.TextToSpeech` con `onRangeStart` para resaltar palabra | |
| Voz neuronal | sherpa-onnx (AAR fijado por version exacta). Voz por defecto pendiente de escucha (Supertonic 3 o Piper) | APIs en la nube (coste) |
| Reproduccion | Media3 1.11.1: `MediaSessionService` + `SimpleBasePlayer`; audio por `AudioTrack` PCM float | Servicio propio sin Media3 |
| Persistencia | Room 2.8.5 (biblioteca, progreso, marcadores) + DataStore 1.3.0 (ajustes) | Room 3 (demasiado reciente) |
| SDK | compileSdk/targetSdk 37, minSdk 26 (onRangeStart, AudioFocusRequest) | |
| ABI | solo arm64-v8a en release (y x86_64 opcional para depurar) | |

## 3. Modulos Gradle
```
:app                 Compose: navegacion, pantallas, AppGraph (DI manual), temas
:core:model          Kotlin puro: Locator, rangos, ajustes, contratos (interfaces)
:core:text           Kotlin puro: segmentacion en frases, normalizador espanol,
                     diccionario de pronunciacion, filtro de cabeceras/pies
:core:data           Room + DataStore: biblioteca, progreso, marcadores, ajustes
:format:epub         Lectura EPUB 2/3 -> BookDocument (Kotlin puro + jsoup)
:format:pdf          pdfium: render de pagina, texto con cajas, PdfTextSource
:reader              Motor de maquetacion (StaticLayout), cache de paginas,
                     PageFlipView (animaciones), vista scroll, resaltados
:tts                 Motores (sistema, sherpa), cola de frases, AudioTrack,
                     PlaybackService (Media3), temporizador, foco de audio
```
Dependencias: `app -> todos`; `reader -> core:model, format:*`; `tts -> core:model, core:text`;
`format:* -> core:model`; `core:data -> core:model`. `tts` y `reader` no se conocen: se
comunican por `Locator`/`ReadingSession` a traves de `app`.

## 4. Contratos centrales (en `:core:model`)
```kotlin
enum class BookFormat { EPUB, PDF }

/** Posicion en un libro. EPUB: indice de recurso del spine + desplazamiento de caracter
 *  en el texto plano de ese capitulo. PDF: indice de pagina + indice de caracter de pdfium. */
data class Locator(val chapter: Int, val offset: Int)
data class LocatorRange(val start: Locator, val endExclusive: Locator)

/** Texto legible secuencial para la voz, por capitulo (EPUB) o pagina (PDF). */
interface TextSource {
    val format: BookFormat
    val chapterCount: Int
    suspend fun chapterText(chapter: Int): ChapterText
}
data class ChapterText(
    val chapter: Int,
    val text: String,               // texto plano ya limpio (sin cabeceras/pies en PDF)
    val title: String?,
    val language: String?,          // BCP-47 si se conoce
)

/** Unidad que la voz lee de una vez (normalmente una frase). */
data class Utterance(
    val id: Long,
    val range: LocatorRange,        // rango en el texto plano original (para resaltar)
    val displayText: String,        // texto tal cual aparece
    val spokenText: String,         // texto normalizado para sintetizar
    val spokenToDisplay: IntArray,  // mapa de indices spoken -> display (resaltar palabra)
)
```
- `:core:text` produce `Utterance` a partir de `ChapterText` (segmentador + normalizador +
  pronunciacion). Pruebas JVM exhaustivas.
- `:tts` consume `Utterance` y emite `PlaybackEvent`:
  `UtteranceStarted(id, range)`, `WordRange(id, displayStart, displayEnd)`,
  `UtteranceDone(id)`, `ChapterFinished(chapter)`, `Error(...)`.
- `:reader` expone `fun highlight(range: LocatorRange, kind: HighlightKind)` y
  `fun ensureVisible(locator: Locator)`; emite `currentLocator: StateFlow<Locator>` y
  `onTextTapped(locator)` (para "leer desde aqui").

## 5. Motor de lectura (`:reader`)
- `BookDocument` (de `:format:epub`): lista de capitulos con bloques (`Paragraph`,
  `Heading`, `Image`, `Rule`, `ListItem`, `Blockquote`, `Preformatted`) y estilos en linea
  (negrita, cursiva, enlace, superindice...). El texto plano del capitulo para `TextSource`
  es la concatenacion de bloques, con desplazamientos estables.
- Paginador: convierte un capitulo a `Spannable` + `StaticLayout` con el ancho util y lo
  corta en paginas por lineas (respetando huerfanas/viudas basicas). Resultado:
  `PageMap` (pagina -> rango de offsets). Se calcula en fondo, por capitulo, bajo demanda
  y se cachea por (libro, ajustes de maquetacion, tamano de vista).
- PDF: pagina = pagina fisica; render con pdfium a bitmap del tamano de la vista
  (`RGB_565` si no hay transparencia; ajuste a ancho/alto/zoom); modo reflujo en v1.
- Cache: LRU de bitmaps de paginas (actual, anterior, siguiente + 1) limitada por bytes
  (`maxMemory/8`), con reutilizacion de bitmaps (`inBitmap`/pool).
- `PageFlipView`: recibe proveedores de bitmap de pagina; animaciones `CURL`, `SLIDE`,
  `COVER`, `FADE`, `NONE`; duracion y sensibilidad configurables; toques en zonas
  izquierda/centro/derecha configurables; volumen como pasar pagina (opcional).
- Modo scroll: `RecyclerView` vertical de paginas/trozos de capitulo (reciclado eficiente),
  conmutado desde ajustes. Ambos modos comparten `Locator`.

## 6. Voz (`:tts`)
Detalle de uso en `tts/INTEGRACION.md`.
- `SpeechEngine` (interfaz real): `suspend fun speak(start: Utterance, next: suspend
  (Utterance) -> Utterance?, listener: SpeechListener)`; pausar/parar = cancelar la corrutina.
  Ademas `prepare`, `updateSettings`, `setVolume`, `setBook`, `release`. Implementaciones:
  `SystemTtsEngine` (TextToSpeech; cola propia con `playSilentUtterance` para las pausas,
  voz y velocidad por frase segun idioma y ritmo, `onRangeStart` para la palabra) y
  `NeuralTtsEngine` (sherpa-onnx 2.0.1, Supertonic 3 int8; tambien `UtteranceRenderer`
  para exportar y `Pregenerator` para la cache en disco).
- Voz neuronal: solo Supertonic 3 (10 locutores; por defecto F5, sid 4, velocidad base 1,15).
  Piper y espeak-ng eliminados. Por frase y sin recargar el modelo: locutor (narrador o
  `dialogVoiceId` para `role = DIALOG`), idioma (`extra["lang"]` "es"/"en"), velocidad
  (usuario x base x ritmo adaptativo) y pasos (`VoiceQuality.steps`). 2 hilos de inferencia.
- Pipeline neuronal: productor (hilo de sintesis con prioridad de audio, 2 frases por
  delante) -> audio crudo (cache en memoria -> cache en disco -> modelo) -> `VoiceProcessor`
  (recorte de silencios del modelo, EQ de realce, compresor nocturno, normalizacion RMS con
  limitador suave, fundidos de 8 ms; todo en float y sin asignaciones por muestra) -> cola
  acotada -> `AudioTrack` PCM float + pausa calibrada (`Pauses`: `pauseAfter` x ms de
  `VoiceSettings` x `punctuationPause`). Resaltado de palabra por interpolacion
  (`WordTimings`) sobre la cabeza de reproduccion real.
- `ReadAloudController` (maquina de estados sin Android): foco, navegacion por frase y
  capitulo, temporizador (minutos o fin de capitulo, ambos con fundido; agitar en los
  ultimos 30 s alarga 10 min), rebobinado inteligente, bateria baja (aviso o voz del
  sistema), velocidad efectiva medida (`charsPerSecond`) para el tiempo restante, marcador
  (`PlaybackHub.bookmarkRequests`) y preparacion de audio mientras carga.
- `PlaybackService` (`MediaSessionService`) + `TtsPlayer` (`SimpleBasePlayer`): metadatos,
  duracion/posicion estimadas del capitulo, controles (reproducir/pausa, frase y capitulo,
  temporizador, "Marcar"), doble toque del auricular = frase anterior, "auriculares
  desconectados", WakeLock solo con la voz neuronal. `PlaybackConnection` es el lado UI
  (mandos por MediaController; estado y eventos en memoria via `PlaybackHub`), tambien
  `exportChapter` a WAV.
- La pantalla puede apagarse: todo el ciclo vive en el servicio en primer plano
  (`foregroundServiceType=mediaPlayback`); la UI solo observa.

## 7. Ajustes (DataStore, todo configurable)
Tema (claro, oscuro, oscuro AMOLED, proteccion ocular, seguir sistema), fuente, tamano,
interlineado, margenes, alineacion, guionado, modo (paginas/scroll), animacion y duracion,
zonas de toque, teclas de volumen, mantener pantalla, brillo propio, orientacion; voz:
motor, voz, velocidad, tono, pausas, resaltado (frase/palabra/ninguno), seguir texto,
filtro PDF de cabeceras/pies, diccionario de pronunciacion, temporizador por defecto.

## 8. Pruebas
- JVM: `:core:text`, `:format:epub` (EPUB de prueba generados en el propio test),
  paginador con Robolectric, repositorios Room con Robolectric.
- Instrumentadas: solo en dispositivo real (este equipo no tiene KVM, sin emulador).
- Rendimiento: medidas con Macrobenchmark/Baseline Profile cuando haya movil conectado.
