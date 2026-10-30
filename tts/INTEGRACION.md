# Integracion de `:tts` en `:app`

## Preparar el entorno
- `tools/descargar-voces.sh`: deja Supertonic 3 int8 (10 locutores, 145 MB) en
  `tts/src/main/assets/voces/` (fuera de Git, SHA-256 verificado) y borra restos de Piper y
  `espeak-ng-data`. Sin este paso la app compila, pero la voz neuronal informa "Voz no
  instalada" y cae a la voz del sistema.
- `tools/descargar-dependencias.sh` (opcional): libreria nativa de sherpa-onnx 2.0.1 para
  Linux x86_64, para la prueba JVM `SherpaJvmTest` (sintesis real en el PC).
- sherpa-onnx 2.0.1 (sin espeak-ng ni GPL) se resuelve desde JitPack (repositorio limitado a
  su grupo en `settings.gradle.kts`). Piper esta eliminado (decision del usuario).
- Muestra de la cadena completa: `./gradlew :tts:testDebugUnitTest
  -Plectorvoz.muestra=/ruta/cadena.wav` (necesita la libreria nativa y la voz).

## `app/build.gradle.kts`
```kotlin
android {
    // Los modelos se leen de assets con AssetManager: sin comprimir (si no, se descomprimen en RAM).
    androidResources { noCompress += listOf("onnx", "bin") }
    packaging {
        jniLibs {
            // libsherpa-onnx-jni.so solo depende de libonnxruntime.so (readelf, 2.0.1).
            excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
        }
    }
}
```
ABIs: ya se filtran a arm64-v8a y x86_64 en el convention plugin.

## Manifest
El manifest de `:tts` ya declara el servicio y los permisos (se fusionan solos):
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKE_LOCK`, `INTERNET`,
`<queries>` de `TTS_SERVICE` y `PlaybackService` (`exported=true`,
`foregroundServiceType="mediaPlayback"`, intent-filter `androidx.media3.session.MediaSessionService`).
`POST_NOTIFICATIONS` no es obligatorio para la notificacion multimedia. El acelerometro y
`ACTION_BATTERY_CHANGED` no necesitan permisos.
No declarar `android:process` en el servicio: `PlaybackHub` comparte memoria con la UI.

## Arranque
```kotlin
// Application.onCreate
TtsConfig.sessionActivityIntent = { ctx -> Intent(ctx, ReaderActivity::class.java) }
TtsConfig.audioCacheMaxBytes = 300L * 1024 * 1024   // cache de "preparar mientras carga"

// Pantalla lectora (desde un toque del usuario: Android 17 exige iniciar audio en primer plano)
val conn = PlaybackConnection(context)
conn.updateSettings(voiceSettings)            // desde DataStore (VoiceSettings completo)
conn.openBook(BookInfo(id, titulo, autor, portada, titulosCapitulos), provider, locator, autoPlay = true)
conn.state.collect { /* posicion, reproduciendo, temporizador, bateria, velocidad */ }
conn.events.collect { /* UtteranceStarted / WordRange -> resaltado */ }
conn.close()                                   // en onDestroy; la lectura sigue

// Ambito de aplicacion (un unico recolector): boton "Marcar" de la notificacion.
appScope.launch { PlaybackHub.default.bookmarkRequests.collect { loc -> bookmarks.add(bookId, loc) } }
```
`provider` es el `UtteranceProvider` de `:core:text`. Debe rellenar en cada `Utterance`
`language` ("es"/"en"), `kind`, `pauseAfter` y `role`; ademas, si implementa
`chapterLength(chapter)` (longitud del texto del capitulo, misma escala que
`Locator.offset`), hay tiempo restante y barra de progreso en la notificacion.

`Utterance.id` debe ser unico en el libro y estable (la misma frase, mismo id, se construya
el capitulo desde donde se construya): `UtteranceBuilder.build(..., fromOffset)` lo cumple
con su `idBase` por defecto (`UtteranceBuilder.idBase(capitulo)`); no pasar otra base.

Notas EPUB al final del parrafo: `TextOptions.from(settings, ..., footnoteTexts =
epub.footnoteTexts(i), chapterPath = epub.chapterPath(i))`. Con `chapterPath` las notas del
propio capitulo solo se buscan si el enlace apunta a ese fichero.

Velocidad y tono pueden cambiar desde fuera de la app (mando Media3, reloj, Android Auto):
se escriben en `PlaybackHub.settings`; `:app` debe recoger `conn.settings` y guardarlos
en DataStore (si no, se pierden al reiniciar).

## Que hace `:tts` con cada ajuste de `VoiceSettings`
- `voiceId`: `"supertonic-3-int8#<sid>"` (nulo = F5, sid 4). Locutores visibles en
  `VoiceCatalog.find(id).speakers` (F5 "Mujer 5" primero, F2 segundo). La velocidad 1,0 del
  usuario es 1,15 del modelo (`VoiceInfo.baseSpeed`).
- `dialogVoiceId`: locutor para `role = DIALOG` (mismo modelo: solo cambia el sid, sin
  recargar). Si es de otro modelo se ignora.
- `readingLanguage`: AUTO usa `Utterance.language`; ES/EN lo fuerzan. Supertonic recibe
  `extra["lang"]` por frase; la voz del sistema cambia a la mejor voz instalada de es-ES o
  en-US/en-GB al cambiar de idioma.
- `quality`: `num_steps` por frase (8/12/16). Hilos de inferencia: `TtsConfig.neuralThreads` (2).
- Pausas: `pauseAfter` x `pause*Ms` x `punctuationPause`, tras recortar el silencio del modelo
  (voz del sistema: `playSilentUtterance`).
- `loudnessNormalize`, `voiceEnhance`, `nightMode`: cadena `VoiceProcessor` (solo voz neuronal).
  Siempre: recorte de silencios y fundidos de 8 ms (sin clics).
- `adaptivePace`: x0,92 en titulos/anuncios, hasta x0,9 con muchas cifras.
- `smartRewind`: al reanudar tras >2 min repite 1 frase; >30 min, 2; >3 h, inicio del parrafo.
- `shakeToExtendTimer`: agitar en los ultimos 30 s (o en el fundido de fin de capitulo) = +10 min.
- `lowBattery`: con <15 % y sin cargar, `state.lowBattery` (WARN) o paso a la voz del sistema
  (`state.switchedToSystemForBattery`).
- `headphoneDoubleTapRewind`: doble toque del auricular = frase anterior (toque simple =
  reproducir/pausa, con 400 ms de espera).
- `prepareWhileCharging`: con el movil cargando y el servicio activo, se sintetizan hasta 2
  capitulos por delante en una cache de disco (`cacheDir/audio-voz`, LRU, 300 MB por defecto);
  la lectura la usa si existe. Sin WorkManager. Al pasar del 90 % se desalojan otros libros
  (LRU) hasta el 70 %; si el propio libro lo llena, se deja de preparar. Preparar y exportar
  ceden el hilo de sintesis a la lectura (abandonan la frase a medias y la repiten luego).

## Foco, temporizador y comandos
- Perdida temporal del foco (llamada): `playWhenReady` sigue a true y `state.focusSuppressed`
  a true (Media3 publica `PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS` y mantiene
  el primer plano aunque la llamada dure mas de 10 min); se reanuda sola al recuperar el foco.
- Temporizador por minutos: la cuenta se congela mientras la lectura esta en pausa.
- "Fin de capitulo": para al terminar de sonar el capitulo; quitarlo o cambiarlo antes sigue
  leyendo sin cortes; saltar de capitulo hace que cuente el capitulo de destino.
- Comandos propios (abrir libro, ir a, temporizador, marcar, capitulos): solo para la propia
  app, la notificacion, controladores de confianza del sistema y Android Auto.

## Tiempo restante y notificacion
`PlaybackState.charsPerSecond` (media movil medida) y `chapterLength`;
`PlaybackState.chapterRemainingMs` lo calcula. Para el libro, :app suma las longitudes de los
capitulos que faltan y divide por `charsPerSecond`. La notificacion muestra duracion y
posicion estimadas del capitulo (barra no desplazable).

## Exportar a audio
```kotlin
// uri de ACTION_CREATE_DOCUMENT ("audio/wav"); cancelable cancelando la corrutina.
val result = conn.exportChapter(uri, provider, chapter, voiceSettings) { p -> progreso(p.fraction) }
```
WAV PCM 16 bits mono a 44,1 kHz (~5 MB/min) con la misma cadena que la lectura. M4A/AAC no
implementado (pendiente).
