# LectorVoz

**Lector de libros EPUB y PDF para Android que tambien te los lee en voz alta, con una voz
natural que funciona sin conexion.** Pasa paginas como un libro de verdad y, cuando quieras,
dale a "Escuchar": sigue leyendo con la pantalla apagada, desde la notificacion o con los
auriculares. Gratis, sin anuncios y sin enviar nada a ningun servidor.

---

## Caracteristicas

- **Lectura como un libro**: EPUB y PDF, animaciones de pagina configurables (curvar,
  deslizar, cubrir, fundido o ninguna) o desplazamiento continuo.
- **Voz natural local**: voz neuronal Supertonic 3 que se ejecuta en el propio movil (10
  locutores, 31 idiomas, voz por defecto en espanol). Tambien puede usar la voz del sistema.
- **Escuchar con la pantalla apagada**: servicio en primer plano con notificacion, mandos de
  auriculares y Bluetooth, temporizador de apagado (tambien "al final del capitulo") y
  "agitar para alargar".
- **Resaltado de lo que se lee**: frase o palabra, y la pagina sigue a la voz.
- **Lectura cuidada**: pausas por puntuacion, parrafo y capitulo; anuncio de capitulos;
  filtrado de cabeceras, pies y numeros de pagina en PDF; notas al pie y tablas; dialogos con
  otra voz; diccionario de pronunciacion propio (por libro o global).
- **Velocidad sin perder palabras**: la velocidad se consigue acelerando el audio sin cambiar
  el tono, de modo que el modelo nunca recorta el final de las frases.
- **Ligera en bateria**: precarga de audio adaptada a cada movil (memoria, ahorro de bateria,
  temperatura y velocidad medida), frases cortas sintetizadas juntas y modelo optimizado para
  movil. En Ajustes > Voz se ve el nivel de precarga de tu telefono.
- **Comodidad y accesibilidad**: temas claro, oscuro y proteccion ocular (dia y noche), alto
  contraste, fuentes Literata, Atkinson Hyperlegible y OpenDyslexic, texto grande, reducir
  movimiento.
- **Extras**: marcadores, busqueda, indice, estadisticas de lectura y escucha, series, y
  exportar un capitulo o el libro entero a audio.

---

## Descarga e instalacion

1. Descarga `LectorVoz-<version>.apk` de la seccion
   [Releases](https://github.com/TheWolf1724/Audio_Libro/releases).
2. Abrelo en el movil y permite la instalacion desde esa fuente si Android lo pide.

Requisitos: **Android 8.0 o superior**. La APK pesa unos 170 MB porque incluye la voz
neuronal completa, que funciona sin conexion. Con 3 GB de RAM o mas va con soltura; en
moviles con menos memoria la app precarga menos audio para no agotarla.

---

## Privacidad

Todo ocurre en el telefono: los libros, la voz y las estadisticas no salen de el. La app no
tiene anuncios ni analitica. El permiso de Internet solo existe para un futuro descargador de
voces adicionales (hoy no se usa).

---

## Compilar desde el codigo

Necesitas **JDK 21** y el **Android SDK** (plataforma `android-37`).

```bash
git clone https://github.com/TheWolf1724/Audio_Libro
cd Audio_Libro

# 1) Voz neuronal (no va en el repositorio): descarga Supertonic 3 de las releases oficiales
#    de sherpa-onnx y el estimador optimizado para movil, verificando su SHA-256.
tools/descargar-voces.sh

# 2) Opcional: biblioteca nativa de sherpa-onnx para Linux, para las pruebas con la voz real.
tools/descargar-dependencias.sh

# 3) Compilar y probar
./gradlew assembleDebug testDebugUnitTest test
```

La APK de depuracion queda en `app/build/outputs/apk/debug/`. La de release sale sin firmar
salvo que exista una clave local en `~/.lectorvoz/keystore.properties`.

### Estructura

| Modulo | Contenido |
|---|---|
| `app` | Interfaz (Jetpack Compose), biblioteca, ajustes, integracion |
| `reader` | Motor de lectura: paginacion, animaciones de pagina, resaltado, PDF y EPUB |
| `tts` | Voz: motor neuronal (sherpa-onnx), voz del sistema, servicio Media3, cadena de audio |
| `core:model`, `core:text`, `core:data` | Modelo de datos, troceo y normalizacion del texto, persistencia |
| `format:epub`, `format:pdf` | Lectura de los formatos |

Documentacion tecnica: [`docs/ARQUITECTURA.md`](docs/ARQUITECTURA.md) (modulos y contratos) y
[`docs/diseno/TEMAS.md`](docs/diseno/TEMAS.md) (temas, tipografia y accesibilidad).

---

## Licencia

El codigo de LectorVoz se publica bajo la licencia **MIT** (ver [`LICENSE`](LICENSE)).

### Licencias de terceros

La APK incluye componentes con sus propias licencias (tambien listadas en la pantalla
"Acerca de" de la app):

| Componente | Licencia |
|---|---|
| Supertonic 3 (Supertone Inc.), pesos de la voz | OpenRAIL-M (con restricciones de uso; [texto completo](app/src/main/assets/licencias/OpenRAIL-M-Supertonic.txt)) |
| Estimador optimizado para movil ([WilburDev/supertonic-3-mobile](https://huggingface.co/WilburDev/supertonic-3-mobile)) | OpenRAIL-M |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | Apache 2.0 |
| ONNX Runtime | MIT |
| pdfium (pdfiumandroid) | Apache 2.0 / BSD |
| jsoup | MIT |
| AndroidX, Jetpack Compose, Media3, Room, DataStore, Kotlin, kotlinx.coroutines, Apache Commons Compress | Apache 2.0 |
| Fuentes Literata, Atkinson Hyperlegible Next y OpenDyslexic | SIL Open Font License 1.1 |

El uso de la voz Supertonic 3 esta sujeto a las restricciones de uso de su licencia
OpenRAIL-M.
