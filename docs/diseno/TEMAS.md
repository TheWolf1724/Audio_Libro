# LectorVoz: temas, tipografia y accesibilidad de lectura

Fecha: 2026-10-28. Estado: propuesta de diseno lista para implementar.
Fuente de verdad de los colores: `docs/diseno/temas.json`, generado por `tools/contraste.py`.
Muestra visual local: `docs/diseno/muestra-temas.html` (abrir en el navegador; sin red).

Ningun color de este documento esta escrito a mano: todos salen del script, que los construye,
los verifica y vuelca las tablas de la seccion 8. Para cambiar un color se edita la definicion en
`tools/contraste.py` y se ejecuta `python3 tools/contraste.py`; si algun par queda por debajo de
su minimo, el script termina con codigo 1 y lista el fallo.

## 1. Filosofia

1. **La pagina manda.** La interfaz (Material 3) y la superficie de lectura comparten tema, pero
   la pagina tiene roles propios (`reader*`, `tts*`, `selection`, `search*`). Asi se puede afinar
   la lectura sin romper la coherencia de botones, menus y dialogos.
2. **Contraste alto pero no extremo.** Texto de cuerpo >= 7:1 (AAA) en todos los temas, sin
   llegar al blanco puro sobre negro en el tema oscuro por defecto (halos y "vibracion" en
   lectores con astigmatismo). El extremo queda como opcion explicita ("aumento de contraste").
3. **Nada depende solo del color.** Cada estado tiene una senal de forma: la frase leida lleva
   barra lateral, la palabra un subrayado grueso, la busqueda un contorno, la seleccion sus
   tiradores, los enlaces van subrayados y los errores llevan icono y texto.
4. **Resaltados que no ensucian la lectura.** La frase en voz alta es un tinte suave (se lee a
   traves de el durante minutos); la palabra es un acento fuerte y breve. Ambos mantienen el
   texto encima por encima de 4.5:1 (en la practica >= 7:1 en el texto de cuerpo).
5. **Ejes de color seguros para daltonismo.** Frase y palabra se separan por el eje azul-amarillo
   (que conservan protanopes y deuteranopes) y por luminosidad; en los temas calidos, donde no
   hay azul, la separacion es sobre todo de luminosidad y de croma.
6. **Paletas tonales como Material 3.** Cada tema parte de seis paletas (primaria, secundaria,
   terciaria, neutra, neutra variante, error) y asigna los roles por tono, igual que M3. El tono
   es L* de CIELAB (lo mismo que el "tone" de HCT); el matiz y el croma son de LCh(ab), no de CAM16,
   asi que los hex no coinciden exactamente con Material Theme Builder, pero las relaciones de
   contraste por tono si.

## 2. Los temas

| Clave JSON | Nombre | Papel | Por defecto |
|---|---|---|---|
| `claro` | Claro | Tema 1. Lectura diurna | Si el sistema esta en modo claro |
| `oscuro` | Oscuro | Tema 2. Gris carbon calido, no negro | Si el sistema esta en modo oscuro |
| `oscuroAmoled` | Oscuro AMOLED | Variante opcional del tema 2, negro puro | No |
| `proteccionOcular` | Proteccion ocular | Tema 3. Sepia suave, poca luz azul | No |
| `proteccionOcularNoche` | Proteccion ocular noche | Variante opcional del tema 3, ambar tenue sobre marron casi negro | No |

### 2.1 Claro
- Papel blanco roto apenas calido (`readerBackground` L* 98,5), no blanco puro: reduce el
  deslumbramiento sin parecer amarillento. Tinta casi negra calida.
- Primaria azul profundo (enlaces, botones). Terciaria verde azulado.
- Frase leida: azul palido con barra lateral azul. Palabra: amarillo con subrayado marron.
  Seleccion: lavanda. Busqueda: melocoton con contorno teja; coincidencia actual: naranja.

### 2.2 Oscuro (por defecto en modo oscuro)
- Fondo gris carbon calido (L* 8, `#191715`) y texto gris claro (L* 88). Ratio ~13:1: holgado
  para AAA y por debajo del 15-21:1 que produce halos en pantallas OLED y en ojos con astigmatismo.
- Frase leida: azul noche oscuro con barra azul clara. Palabra: ambar claro con **texto oscuro**
  propio (`ttsWordText`), que la hace inconfundible incluso sin color.
- Seleccion ciruela, busqueda marron rojizo con contorno naranja.

### 2.3 Oscuro AMOLED (variante)
- Fondo y superficies base `#000000` (pixeles apagados, ahorro de bateria en OLED). Las
  superficies elevadas suben en pasos de gris muy oscuro para conservar la jerarquia.
- Texto atenuado a L* 85 para compensar el mayor contraste local y el "smearing" de OLED al
  desplazar. Mismos resaltados que el oscuro, ligeramente mas oscuros.

### 2.4 Proteccion ocular
- Sepia suave de brillo moderado: fondo L* 88 (`#EBDAC5`), un 25 % menos de luminancia y un
  ~40 % menos de azul emitido que el tema claro (ver tabla de luz azul en la seccion 8).
- Tinta marron muy oscura (no negra): 11,4:1. Texto secundario y enlaces por encima de 7:1.
- Sin azules en la interfaz: primaria teja, terciaria oliva, error rojo. Unica excepcion
  consciente: la seleccion es un gris lavanda poco saturado, porque es la unica forma de que se
  distinga de la frase leida y de la busqueda en deuteranopia (los tonos calidos se confunden
  entre si); ocupa poca superficie y poco tiempo.
- Frase leida: crema tostado con barra lateral teja. Palabra: ocre intenso con texto casi negro
  y subrayado marron.

### 2.5 Proteccion ocular noche (variante)
- Texto ambar (`#E4BA84`) sobre marron casi negro (`#1B140F`): 10,1:1. Para leer a oscuras con el
  minimo brillo y practicamente sin azul. Interfaz con neutros calidos.

### 2.6 Aumento de contraste (capa aplicable a cualquier tema)
No es un sexto tema sino una capa (`altoContraste` en el JSON) que sustituye solo unos roles:
texto de cuerpo a negro o casi blanco, secundarios y enlaces mas oscuros/claros, `outline` y
`outlineVariant` reforzados (los divisores pasan a >= 3:1), primaria mas profunda. Resultado
verificado: texto de cuerpo entre 13,8:1 y 20,2:1 segun tema, 0 fallos.

## 3. Roles de lectura (contrato con la implementacion)

| Rol | Uso | Exigencia verificada |
|---|---|---|
| `readerBackground` | Fondo de pagina (EPUB y marco del PDF) | - |
| `readerText` | Texto de cuerpo | >= 7:1 sobre fondo |
| `readerSecondaryText` | Notas, cabeceras/pies, numero de pagina, metadatos | >= 7:1 sobre fondo |
| `readerLink` / `readerLinkVisited` | Enlaces, siempre subrayados | >= 7:1 sobre fondo; >= 4.5:1 sobre la frase leida |
| `ttsSentenceHighlight` | Fondo de la frase que se lee en voz alta | texto encima >= 4.5:1 (objetivo 7:1) |
| `ttsSentenceIndicator` | Barra lateral de 3 dp en el margen (o subrayado si no hay margen) | >= 3:1 sobre fondo y sobre la frase |
| `ttsWordHighlight` + `ttsWordText` | Palabra que se pronuncia: fondo + color de texto propio | >= 4.5:1 (objetivo 7:1) |
| `ttsWordIndicator` | Subrayado de 2-2,5 dp bajo la palabra | >= 3:1 sobre `ttsWordHighlight` |
| `selection` + `selectionHandle` | Seleccion de texto y tiradores | texto >= 4.5:1; tiradores >= 3:1 |
| `searchHighlight` + `searchOutline` | Coincidencias de busqueda con contorno de 1,5 dp | texto >= 4.5:1; contorno >= 3:1 |
| `searchCurrentHighlight` + `searchCurrentText` | Coincidencia actual (contorno de 2,5 dp) | >= 4.5:1 |
| `readerPageBack` | Reverso de la hoja en la animacion de curl | texto >= 4.5:1 |
| `readerBarBackground` / `readerBarContent` | Barras superior e inferior del lector | >= 4.5:1 |
| `readerProgressTrack` / `readerProgressIndicator` | Barra de progreso | indicador >= 3:1 |
| `readerDivider` | Separadores (decorativo) | - (>= 3:1 con alto contraste) |
| `pageEdgeShadow` | Sombra del borde/lomo de la pagina, `#AARRGGBB` | decorativo |

Notas de implementacion (tambien en `temas.json`, campo `notasUso`):
- Los resaltados son opacos y van detras del texto en EPUB, para que el contraste medido sea el
  real (no depende de mezclas con alfa).
- En PDF (texto rasterizado) se pintan con `BlendMode.Multiply`: sobre papel blanco el resultado
  es exactamente el color declarado y el texto negro sigue negro.
- En temas oscuros y calidos los PDF se recolorean mapeando blanco -> `readerBackground` y
  negro -> `readerText` (interpolacion por luminancia), no con inversion pura, que estropea fotos
  y deja el negro puro.
- Si la frase leida ocupa varias lineas, la barra lateral cubre todas; si el usuario elige
  margenes 0, el indicador pasa a subrayado punteado de 1,5 dp bajo la frase.

## 4. Distincion para daltonismo

Metodo: cada color se transforma con las matrices de Machado, Oliveira y Fernandes (2009),
severidad 1.0 (dicromacia completa, el caso peor), en RGB lineal; despues se mide la diferencia
CIEDE2000 entre los resaltados que pueden coincidir en pantalla. Umbrales exigidos (en vision
normal y en las tres simulaciones):

| Par | dE00 minimo | Senal no cromatica adicional |
|---|---:|---|
| fondo / frase | 5 | barra lateral |
| fondo / palabra, frase / palabra | 15 | subrayado grueso + color de texto propio |
| fondo / seleccion, fondo / busqueda | 10 | tiradores / contorno |
| frase / seleccion, frase / busqueda, seleccion / busqueda | 5 | tiradores / contorno |
| busqueda / busqueda actual | 8 | contorno mas grueso |

Resultado: todos los temas los cumplen (tablas de la seccion 8). Limitacion conocida y aceptada:
en proteccion ocular, `primary` (teja) y `error` (rojo) se parecen en deuteranopia
(dE00 1,7); por eso los errores llevan siempre icono y texto, que es ademas la norma de M3.

## 5. Tipografia de lectura

Licencias comprobadas el 2026-10-28 en los `METADATA.pb` del repositorio google/fonts
(`ofl/<familia>/METADATA.pb`, campo `license: "OFL"`) y en el repositorio de OpenDyslexic.
Todas son SIL Open Font License 1.1: se pueden incluir en el APK y redistribuir gratis,
incluyendo el texto de la licencia (pantalla "Licencias" y `assets/licenses/`). No se pueden
vender sueltas; si una fuente tiene *Reserved Font Name*, una version modificada (subconjunto,
conversion) no puede usar ese nombre.

| Familia (nombre exacto en Google Fonts) | Diseno | Licencia | Papel en LectorVoz |
|---|---|---|---|
| **Literata** | TypeTogether | OFL | Fuente de lectura por defecto (serif). Disenada para lectura larga en pantalla (Google Play Libros); variable con ejes `opsz` y `wght`, cursiva real |
| **Atkinson Hyperlegible Next** | Braille Institute, Applied Design Works, Letters From Sweden | OFL | Fuente de interfaz y opcion "sans legible" para lectura; formas muy diferenciadas (I l 1, O 0). Variable 200-800 |
| Atkinson Hyperlegible | Braille Institute y otros | OFL | Alternativa estatica si se prefiere la version original (4 estilos) |
| **Source Serif 4** | Frank Griesshammer | OFL | Serif alternativa, mas compacta y de contraste medio |
| Merriweather | Sorkin Type | OFL | Serif alternativa robusta (ojo grande, buena en tamanos pequenos y pantallas de baja densidad) |
| Lexend | Bonnie Shaver-Troup y otros | OFL | Opcional: sans de anchura amplia pensada para fluidez lectora |
| **OpenDyslexic** | Abbie Gonzalez | OFL 1.1 con Reserved Font Name "OpenDyslexic" | Opcion "fuente para dislexia". Incluir los ficheros sin modificar; si se subconjuntan, renombrar |

Sobre OpenDyslexic: los estudios publicados no muestran mejora objetiva de velocidad ni de
precision lectora frente a fuentes comunes; se ofrece por preferencia personal y comodidad, junto
con el aumento de espaciado, que si tiene mas respaldo. Se acompana automaticamente de
espaciado entre letras +0,05 em y entre palabras +0,10 em.

Recomendacion de inclusion para mantener el APK ligero: Literata (variable, latin + latin-ext),
Atkinson Hyperlegible Next (variable) y OpenDyslexic (regular, negrita, cursiva). Source Serif 4,
Merriweather y Lexend como descarga opcional o fuera de la primera version. Ademas, "Fuente del
sistema" y "Fuente del libro" (respetar la del EPUB, desactivada por defecto en temas de
accesibilidad).

## 6. Ajustes de lectura: valores por defecto y rangos

Unidades Android: tamanos en `sp` (escalan con el tamano de fuente del sistema, incluido el
escalado no lineal de Android 14+), margenes en `dp`. Los rangos cubren el criterio WCAG 1.4.12
(interlineado 1,5; espacio de parrafo 2x; letras 0,12 em; palabras 0,16 em) sin perder contenido.

| Ajuste | Por defecto (telefono) | Rango | Paso | Notas |
|---|---|---|---|---|
| Fuente | Literata | lista de la seccion 5 | - | |
| Tamano de texto | 18 sp | 12-40 sp | 1 sp | Tablet: 20 sp por defecto |
| Interlineado (multiplicador) | 1,50 | 1,20-2,00 | 0,05 | 1,6 por defecto con OpenDyslexic |
| Espacio entre parrafos | 0,6 em | 0-2,0 em | 0,1 em | |
| Sangria de primera linea | 0 em (respetar EPUB) | 0-3 em | 0,5 em | |
| Espaciado entre letras | 0 em | 0-0,12 em | 0,01 em | |
| Espaciado entre palabras | 0 em | 0-0,16 em | 0,02 em | |
| Peso del texto | 400 | 300-600 | 50 | Fuentes variables; en temas oscuros la sugerencia es 380 (el texto claro sobre oscuro parece mas grueso) |
| Alineacion | Izquierda | izquierda / justificado | - | Justificado activa guionado automatico (silabeo del idioma del libro) |
| Guionado | Automatico si justificado | si / no | - | |
| Margen lateral | 24 dp | 0-64 dp | 4 dp | |
| Margen superior/inferior | 20 dp | 0-64 dp | 4 dp | Mas espacio de barras si estan visibles |
| Ancho maximo de linea | 70 caracteres (aprox. 36 em) | 45-90 caracteres | 5 | En tablet u horizontal: centrar columna o pasar a 2 columnas |
| Brillo propio del lector | sistema | 5-100 % | 1 % | Por debajo del minimo del sistema, capa oscura con `scrim` |
| Tema | segun sistema (claro/oscuro) | 5 opciones | - | Programable: proteccion ocular noche al anochecer |

Ajustes rapidos de "texto grande": 24 sp, interlineado 1,6, margen lateral 16 dp, peso 450.
Tamanos tipograficos de la interfaz: escala tipografica estandar de M3 con Atkinson Hyperlegible
Next; ningun texto de interfaz por debajo de 12 sp; todo en sp para respetar el ajuste del sistema
hasta 200 %.

## 7. Opciones de accesibilidad

| Opcion | Efecto | Origen |
|---|---|---|
| Texto grande | Aplica el preajuste anterior; la interfaz ya escala con el sistema | WCAG 1.4.4 |
| Aumento de contraste | Capa `altoContraste` sobre el tema elegido | WCAG 1.4.6 / 1.4.11 |
| Fuente para dislexia | OpenDyslexic + espaciado ampliado + alineacion izquierda forzada | Preferencia de usuario |
| Espaciado de texto | Controles de la seccion 6, sin recortar texto | WCAG 1.4.12 |
| Reducir movimiento | Sin curl ni deslizamientos (corte directo o fundido de 150 ms); se activa solo si el sistema tiene "Quitar animaciones" | WCAG 2.3.3 |
| Resaltado de lectura | Elegir: frase + palabra (por defecto), solo frase, solo palabra, ninguno; grosor de la barra lateral | Comodidad |
| Seguir la lectura | Desplazar o pasar pagina para mantener la frase visible | Comodidad |
| Lector de pantalla | Contenido de la pagina expuesto como texto a TalkBack; botones de 48x48 dp minimo con etiqueta | WCAG 4.1.2, 2.5.8 |
| Indicador de foco | Contorno de 3 dp en `primary` (>= 3:1 en todos los temas) | WCAG 2.4.7 / 2.4.13 |
| Texto en negrita del sistema | Respetar `fontWeightAdjustment` de Android 12+ | Sistema |

## 8. Resultados del script

Salida literal de `python3 tools/contraste.py` (tokens y verificaciones).

<!-- SALIDA-SCRIPT-INICIO -->
Generado por `tools/contraste.py` (no editar a mano este bloque; se regenera).

Formula: contraste WCAG 2.x = (L1+0.05)/(L2+0.05), luminancia relativa sRGB (umbral 0.04045). Daltonismo: Machado et al. 2009, severidad 1.0, en RGB lineal. Distancias: CIEDE2000. Nivel: `AAA` >= 7:1, `AA` >= 4.5:1, `3:1` >= 3:1 (solo valido en pares no textuales), `FALLO` por debajo del minimo exigido al par.

### Tokens de lectura

| Rol | Claro | Oscuro | Oscuro AMOLED | Proteccion ocular | Proteccion ocular noche |
|---|---|---|---|---|---|
| readerBackground | `#FDFAF6` | `#191715` | `#000000` | `#EBDAC5` | `#1B140F` |
| readerText | `#211F1D` | `#E0DCD7` | `#D7D4CF` | `#2E2118` | `#E4BA84` |
| readerSecondaryText | `#514D47` | `#B4B0A9` | `#AAA59F` | `#4E3C30` | `#C59F76` |
| readerLink | `#154C97` | `#AEBEFF` | `#AEBEFF` | `#752415` | `#F5A273` |
| readerLinkVisited | `#73376E` | `#E5B1DD` | `#E5B1DD` | `#6A2B39` | `#E19991` |
| ttsSentenceHighlight | `#D5E7FF` | `#1F3951` | `#17324A` | `#EAC9A8` | `#3E2716` |
| ttsSentenceIndicator | `#0078B8` | `#5DA6E7` | `#5DA6E7` | `#914C22` | `#C78140` |
| ttsWordHighlight | `#F3D25A` | `#E9C256` | `#E9C256` | `#C59716` | `#DDA041` |
| ttsWordText | `#211F1D` | `#191715` | `#000000` | `#120903` | `#1B140F` |
| ttsWordIndicator | `#7F4F0F` | `#774806` | `#774806` | `#743417` | `#763B17` |
| selection | `#CAC0F0` | `#5E3869` | `#573162` | `#B0B5CF` | `#383D52` |
| selectionHandle | `#325CAA` | `#B6C4FF` | `#B6C4FF` | `#833F24` | `#E5A35A` |
| searchHighlight | `#FFBDA0` | `#5D3620` | `#553019` | `#F29498` | `#723338` |
| searchOutline | `#B9442C` | `#F28A5B` | `#F28A5B` | `#A42F30` | `#EA7B6A` |
| searchCurrentHighlight | `#FB8F4F` | `#FA985F` | `#FA985F` | `#E47665` | `#DE7B59` |
| searchCurrentText | `#211F1D` | `#191715` | `#000000` | `#20150C` | `#1B140F` |
| readerPageBack | `#F1EDE8` | `#252321` | `#191715` | `#DACAB4` | `#251E1A` |
| readerBarBackground | `#F0EDE9` | `#211F1C` | `#15130F` | `#E5D5BF` | `#281D16` |
| readerBarContent | `#1D1B18` | `#E5E2DE` | `#E5E2DE` | `#281E0C` | `#D3C4B9` |
| readerProgressTrack | `#CCC6BB` | `#4B463D` | `#4B463D` | `#C4AD93` | `#533E2C` |
| readerProgressIndicator | `#325CAA` | `#B6C4FF` | `#B6C4FF` | `#833F24` | `#E5A35A` |
| readerDivider | `#CCC6BB` | `#4B463D` | `#4B463D` | `#C4AD93` | `#533E2C` |
| pageEdgeShadow | `#1F000000` | `#66000000` | `#99000000` | `#29301C00` | `#80000000` |

### Tokens Material 3 (ColorScheme)

| Rol | Claro | Oscuro | Oscuro AMOLED | Proteccion ocular | Proteccion ocular noche |
|---|---|---|---|---|---|
| primary | `#325CAA` | `#B6C4FF` | `#B6C4FF` | `#833F24` | `#E5A35A` |
| onPrimary | `#FFFFFF` | `#002F66` | `#002F66` | `#FFF4F0` | `#382100` |
| primaryContainer | `#DCE1FF` | `#00458F` | `#00458F` | `#FFAD8C` | `#613900` |
| onPrimaryContainer | `#001B3F` | `#DCE1FF` | `#DCE1FF` | `#391500` | `#FFCB97` |
| inversePrimary | `#B6C4FF` | `#325CAA` | `#325CAA` | `#FFAD8C` | `#784700` |
| secondary | `#565E75` | `#BEC6E0` | `#BEC6E0` | `#664D3D` | `#CAAB93` |
| onSecondary | `#FFFFFF` | `#283044` | `#283044` | `#FFF4EE` | `#36210E` |
| secondaryContainer | `#DAE2FD` | `#3E465C` | `#3E465C` | `#DABBA8` | `#553D29` |
| onSecondaryContainer | `#121B2E` | `#DAE2FD` | `#DAE2FD` | `#2F1B0A` | `#EFCEB6` |
| tertiary | `#00696C` | `#82D4D6` | `#82D4D6` | `#515624` | `#B1B57D` |
| onTertiary | `#FFFFFF` | `#003738` | `#003738` | `#F8FBBF` | `#252800` |
| tertiaryContainer | `#9EF0F2` | `#004F51` | `#004F51` | `#C2C58C` | `#404614` |
| onTertiaryContainer | `#002021` | `#9EF0F2` | `#9EF0F2` | `#202100` | `#D5D89F` |
| error | `#AF2F27` | `#FFB4A6` | `#FFB4A6` | `#9E2127` | `#FF8A77` |
| onError | `#FFFFFF` | `#690003` | `#690003` | `#FFF4F3` | `#520500` |
| errorContainer | `#FFDAD3` | `#900C12` | `#900C12` | `#FFB4AB` | `#831514` |
| onErrorContainer | `#3A0B00` | `#FFDAD3` | `#FFDAD3` | `#490000` | `#FFC7BD` |
| background | `#FCF9F4` | `#15130F` | `#000000` | `#EBDAC5` | `#1E1308` |
| onBackground | `#1D1B18` | `#E5E2DE` | `#E5E2DE` | `#281E0C` | `#D3C4B9` |
| surface | `#FCF9F4` | `#15130F` | `#000000` | `#EBDAC5` | `#1E1308` |
| onSurface | `#1D1B18` | `#E5E2DE` | `#E5E2DE` | `#281E0C` | `#D3C4B9` |
| surfaceVariant | `#E8E2D7` | `#4B463D` | `#4B463D` | `#DBC3A8` | `#533E2C` |
| onSurfaceVariant | `#4B463D` | `#CCC6BB` | `#CCC6BB` | `#4C3B25` | `#C7AB96` |
| surfaceTint | `#325CAA` | `#B6C4FF` | `#B6C4FF` | `#833F24` | `#E5A35A` |
| inverseSurface | `#32302D` | `#E5E2DE` | `#E5E2DE` | `#3E3322` | `#D3C4B9` |
| inverseOnSurface | `#F3F0EC` | `#32302D` | `#32302D` | `#EBDAC5` | `#2E241C` |
| outline | `#7C766D` | `#969086` | `#969086` | `#746048` | `#997F6B` |
| outlineVariant | `#CCC6BB` | `#4B463D` | `#4B463D` | `#C4AD93` | `#533E2C` |
| scrim | `#000000` | `#000000` | `#000000` | `#000000` | `#000000` |
| surfaceBright | `#FCF9F4` | `#3B3935` | `#32302D` | `#F4E3CD` | `#3E322A` |
| surfaceDim | `#DCDAD5` | `#15130F` | `#000000` | `#D2C1AC` | `#1E1308` |
| surfaceContainerLowest | `#FFFFFF` | `#110E09` | `#000000` | `#F7E6D0` | `#180B00` |
| surfaceContainerLow | `#F6F3EF` | `#1D1B18` | `#15130F` | `#F1E0CA` | `#241912` |
| surfaceContainer | `#F0EDE9` | `#211F1C` | `#1B1916` | `#E5D5BF` | `#281D16` |
| surfaceContainerHigh | `#EBE8E3` | `#2C2A26` | `#23211E` | `#E0CFBA` | `#31261E` |
| surfaceContainerHighest | `#E5E2DE` | `#373431` | `#2E2C28` | `#DACAB4` | `#3A2E26` |
| primaryFixed | `#DCE1FF` | `#DCE1FF` | `#DCE1FF` | `#FFDBCD` | `#FFDCBC` |
| primaryFixedDim | `#B6C4FF` | `#B6C4FF` | `#B6C4FF` | `#FFB598` | `#FEB96F` |
| onPrimaryFixed | `#001B3F` | `#001B3F` | `#001B3F` | `#311300` | `#291800` |
| onPrimaryFixedVariant | `#00458F` | `#00458F` | `#00458F` | `#753319` | `#683D00` |
| secondaryFixed | `#DAE2FD` | `#DAE2FD` | `#DAE2FD` | `#FCDCC8` | `#FDDCC4` |
| secondaryFixedDim | `#BEC6E0` | `#BEC6E0` | `#BEC6E0` | `#DFC0AD` | `#E0C1A8` |
| onSecondaryFixed | `#121B2E` | `#121B2E` | `#121B2E` | `#2B1703` | `#2A1700` |
| onSecondaryFixedVariant | `#3E465C` | `#3E465C` | `#3E465C` | `#5A4232` | `#5A422E` |
| tertiaryFixed | `#9EF0F2` | `#9EF0F2` | `#9EF0F2` | `#E3E7AC` | `#E3E7AC` |
| tertiaryFixedDim | `#82D4D6` | `#82D4D6` | `#82D4D6` | `#C7CA91` | `#C7CA91` |
| onTertiaryFixed | `#002021` | `#002021` | `#002021` | `#1C1D00` | `#1C1D00` |
| onTertiaryFixedVariant | `#004F51` | `#004F51` | `#004F51` | `#454A18` | `#454A18` |

### Sustituciones con *aumento de contraste*

| Rol | Claro | Oscuro | Oscuro AMOLED | Proteccion ocular | Proteccion ocular noche |
|---|---|---|---|---|---|
| primary | `#003A7A` | `#D5DBFF` | `#D5DBFF` | `#67280E` | `#FFD5AE` |
| secondary | `#333B50` | `#D4DCF7` | `#D4DCF7` | `#4D3627` | `#F7D7BE` |
| tertiary | `#004345` | `#98EBED` | `#98EBED` | `#393F0D` | `#DEE1A7` |
| error | `#7D000A` | `#FFD3CA` | `#FFD3CA` | `#7D0015` | `#FFD3CA` |
| onSurfaceVariant | `#2A251D` | `#EEE7DD` | `#EEE7DD` | `#32230D` | `#FFE3CE` |
| surfaceTint | `#003A7A` | `#D5DBFF` | `#D5DBFF` | `#67280E` | `#FFD5AE` |
| outline | `#4B463D` | `#BEB8AE` | `#BEB8AE` | `#56442D` | `#CFB49E` |
| outlineVariant | `#706A61` | `#898379` | `#898379` | `#7B674F` | `#997F6B` |
| readerText | `#000000` | `#F8F6F3` | `#F8F6F3` | `#190F06` | `#FFDEAE` |
| readerSecondaryText | `#2F2C26` | `#E1DCD5` | `#E1DCD5` | `#33251C` | `#EBC699` |
| readerLink | `#00336E` | `#D5DBFF` | `#D5DBFF` | `#5A0F00` | `#FFC9A6` |
| ttsSentenceIndicator | `#004A75` | `#8FC5FF` | `#8FC5FF` | `#6F3005` | `#F4A865` |
| ttsWordText | `#000000` | = | = | `#190F06` | = |
| readerProgressTrack | `#706A61` | `#898379` | `#898379` | `#7B674F` | `#997F6B` |
| readerDivider | `#706A61` | `#898379` | `#898379` | `#7B674F` | `#997F6B` |

### Claro (`claro`)

Contraste, pares de lectura:

| Primer plano | Fondo | Ratio | Min / objetivo | Nivel | Uso |
|---|---|---:|---|---|---|
| readerText `#211F1D` | readerBackground `#FDFAF6` | 15.79 | 7.0 / 7.0 | AAA | texto de cuerpo |
| readerSecondaryText `#514D47` | readerBackground `#FDFAF6` | 8.06 | 7.0 / 7.0 | AAA | notas, cabeceras, numero de pagina |
| readerLink `#154C97` | readerBackground `#FDFAF6` | 8.03 | 7.0 / 7.0 | AAA | enlace (siempre subrayado) |
| readerLinkVisited `#73376E` | readerBackground `#FDFAF6` | 8.07 | 7.0 / 7.0 | AAA | enlace visitado (subrayado) |
| readerText `#211F1D` | ttsSentenceHighlight `#D5E7FF` | 13.07 | 4.5 / 7.0 | AAA | frase leida en voz alta |
| readerLink `#154C97` | ttsSentenceHighlight `#D5E7FF` | 6.65 | 4.5 / 7.0 | AA | enlace dentro de la frase leida |
| readerSecondaryText `#514D47` | ttsSentenceHighlight `#D5E7FF` | 6.68 | 4.5 / 7.0 | AA | texto secundario en frase leida |
| ttsWordText `#211F1D` | ttsWordHighlight `#F3D25A` | 11.09 | 4.5 / 7.0 | AAA | palabra que se pronuncia |
| readerText `#211F1D` | selection `#CAC0F0` | 9.63 | 4.5 / 7.0 | AAA | texto seleccionado |
| readerText `#211F1D` | searchHighlight `#FFBDA0` | 10.20 | 4.5 / 7.0 | AAA | coincidencia de busqueda |
| searchCurrentText `#211F1D` | searchCurrentHighlight `#FB8F4F` | 7.15 | 4.5 / 7.0 | AAA | coincidencia actual |
| readerText `#211F1D` | readerPageBack `#F1EDE8` | 14.09 | 4.5 / 7.0 | AAA | reverso de pagina (animacion curl) |
| ttsSentenceIndicator `#0078B8` | readerBackground `#FDFAF6` | 4.61 | 3.0 / 3.0 | AA | barra lateral/subrayado de frase |
| ttsSentenceIndicator `#0078B8` | ttsSentenceHighlight `#D5E7FF` | 3.81 | 3.0 / 3.0 | 3:1 | indicador sobre la frase |
| ttsWordIndicator `#7F4F0F` | ttsWordHighlight `#F3D25A` | 4.68 | 3.0 / 3.0 | AA | subrayado grueso de palabra |
| searchOutline `#B9442C` | readerBackground `#FDFAF6` | 5.14 | 3.0 / 3.0 | AA | contorno de coincidencia |
| searchOutline `#B9442C` | searchHighlight `#FFBDA0` | 3.32 | 3.0 / 3.0 | 3:1 | contorno sobre su relleno |
| selectionHandle `#325CAA` | readerBackground `#FDFAF6` | 6.22 | 3.0 / 3.0 | AA | tiradores de seleccion |
| readerBarContent `#1D1B18` | readerBarBackground `#F0EDE9` | 14.72 | 4.5 / 7.0 | AAA | texto/iconos de barras |
| readerProgressIndicator `#325CAA` | readerBarBackground `#F0EDE9` | 5.54 | 3.0 / 3.0 | AA | progreso de lectura |

Contraste, pares Material 3:

| Primer plano | Fondo | Ratio | Min / objetivo | Nivel | Uso |
|---|---|---:|---|---|---|
| onPrimary `#FFFFFF` | primary `#325CAA` | 6.47 | 4.5 / 7.0 | AA | texto sobre boton relleno |
| onPrimaryContainer `#001B3F` | primaryContainer `#DCE1FF` | 13.23 | 4.5 / 7.0 | AAA | texto en contenedor primario |
| onSecondary `#FFFFFF` | secondary `#565E75` | 6.45 | 4.5 / 7.0 | AA | texto sobre secundario |
| onSecondaryContainer `#121B2E` | secondaryContainer `#DAE2FD` | 13.32 | 4.5 / 7.0 | AAA | chips/tonal button |
| onTertiary `#FFFFFF` | tertiary `#00696C` | 6.49 | 4.5 / 7.0 | AA | texto sobre terciario |
| onTertiaryContainer `#002021` | tertiaryContainer `#9EF0F2` | 13.19 | 4.5 / 7.0 | AAA | contenedor terciario |
| onError `#FFFFFF` | error `#AF2F27` | 6.44 | 4.5 / 7.0 | AA | texto sobre error |
| onErrorContainer `#3A0B00` | errorContainer `#FFDAD3` | 13.21 | 4.5 / 7.0 | AAA | mensaje de error |
| onBackground `#1D1B18` | background `#FCF9F4` | 16.36 | 4.5 / 7.0 | AAA | texto sobre fondo |
| onSurface `#1D1B18` | surface `#FCF9F4` | 16.36 | 4.5 / 7.0 | AAA | texto sobre superficie |
| onSurface `#1D1B18` | surfaceContainerLowest `#FFFFFF` | 17.18 | 4.5 / 7.0 | AAA | texto en contenedor minimo |
| onSurface `#1D1B18` | surfaceContainerLow `#F6F3EF` | 15.53 | 4.5 / 7.0 | AAA | texto en tarjetas |
| onSurface `#1D1B18` | surfaceContainer `#F0EDE9` | 14.72 | 4.5 / 7.0 | AAA | texto en barras/menus |
| onSurface `#1D1B18` | surfaceContainerHigh `#EBE8E3` | 14.06 | 4.5 / 7.0 | AAA | texto en dialogos |
| onSurface `#1D1B18` | surfaceContainerHighest `#E5E2DE` | 13.31 | 4.5 / 7.0 | AAA | texto en campos |
| onSurface `#1D1B18` | surfaceBright `#FCF9F4` | 16.36 | 4.5 / 7.0 | AAA | texto en surfaceBright |
| onSurface `#1D1B18` | surfaceDim `#DCDAD5` | 12.30 | 4.5 / 7.0 | AAA | texto en surfaceDim |
| onSurfaceVariant `#4B463D` | surface `#FCF9F4` | 8.92 | 4.5 / 7.0 | AAA | texto secundario/iconos |
| onSurfaceVariant `#4B463D` | surfaceVariant `#E8E2D7` | 7.26 | 4.5 / 7.0 | AAA | texto en surfaceVariant |
| onSurfaceVariant `#4B463D` | surfaceContainerHighest `#E5E2DE` | 7.25 | 4.5 / 7.0 | AAA | etiqueta de campo |
| inverseOnSurface `#F3F0EC` | inverseSurface `#32302D` | 11.58 | 4.5 / 7.0 | AAA | snackbar |
| inversePrimary `#B6C4FF` | inverseSurface `#32302D` | 7.72 | 4.5 / 7.0 | AAA | accion de snackbar |
| primary `#325CAA` | surface `#FCF9F4` | 6.16 | 4.5 / 7.0 | AA | boton de texto / enlace UI |
| primary `#325CAA` | surfaceContainer `#F0EDE9` | 5.54 | 4.5 / 7.0 | AA | icono activo en barra |
| primary `#325CAA` | surfaceContainerHighest `#E5E2DE` | 5.01 | 3.0 / 4.5 | AA | indicador/switch activo |
| error `#AF2F27` | surface `#FCF9F4` | 6.14 | 4.5 / 7.0 | AA | texto de error |
| outline `#7C766D` | surface `#FCF9F4` | 4.28 | 3.0 / 4.5 | 3:1 | borde de campo/boton (1.4.11) |
| outline `#7C766D` | surfaceContainer `#F0EDE9` | 3.85 | 3.0 / 4.5 | 3:1 | borde sobre barra |
| outline `#7C766D` | surfaceContainerHighest `#E5E2DE` | 3.48 | 3.0 / 4.5 | 3:1 | borde sobre contenedor alto |
| onPrimaryFixed `#001B3F` | primaryFixed `#DCE1FF` | 13.23 | 4.5 / 7.0 | AAA | fixed |
| onPrimaryFixedVariant `#00458F` | primaryFixed `#DCE1FF` | 7.23 | 4.5 / 7.0 | AAA | fixed variante |
| onSecondaryFixed `#121B2E` | secondaryFixed `#DAE2FD` | 13.32 | 4.5 / 7.0 | AAA | fixed |
| onTertiaryFixed `#002021` | tertiaryFixed `#9EF0F2` | 13.19 | 4.5 / 7.0 | AAA | fixed |

Distincion de resaltados y estados (CIEDE2000; normal / protanopia / deuteranopia / tritanopia):

| Par | Minimo | Normal | Prot. | Deut. | Trit. | Nota |
|---|---:|---:|---:|---:|---:|---|
| readerBackground / ttsSentenceHighlight | 5 | 13.5 | 12.6 | 14.8 | 16.1 | fondo / frase (+ barra lateral) |
| readerBackground / ttsWordHighlight | 15 | 25.8 | 27.0 | 25.2 | 19.0 | fondo / palabra |
| ttsSentenceHighlight / ttsWordHighlight | 15 | 41.4 | 38.8 | 39.4 | 33.0 | frase / palabra |
| readerBackground / selection | 10 | 22.9 | 21.4 | 21.9 | 12.7 | fondo / seleccion (+ tiradores) |
| readerBackground / searchHighlight | 10 | 21.6 | 17.3 | 17.1 | 22.7 | fondo / busqueda (+ contorno) |
| ttsSentenceHighlight / selection | 5 | 14.2 | 9.8 | 8.3 | 13.7 | frase / seleccion |
| ttsSentenceHighlight / searchHighlight | 5 | 29.7 | 27.6 | 29.9 | 36.1 | frase / busqueda |
| selection / searchHighlight | 5 | 29.1 | 32.0 | 34.6 | 23.2 | seleccion / busqueda |
| searchHighlight / searchCurrentHighlight | 8 | 13.4 | 15.1 | 12.4 | 13.0 | busqueda / busqueda actual |
| readerText / readerLink | - | 29.0 | 29.7 | 29.0 | 27.1 | texto / enlace (informativo: enlace va subrayado) |
| primary / error | - | 41.3 | 43.1 | 50.4 | 61.4 | estado activo / error (informativo: error lleva icono y texto) |
| primary / outline | - | 30.5 | 30.0 | 32.2 | 33.5 | activo / inactivo (informativo: cambia forma y relleno) |

Con *aumento de contraste* activado: texto de cuerpo 20.18:1, peor par verificado 3.32:1, fallos: 0.

### Oscuro (`oscuro`)

Contraste, pares de lectura:

| Primer plano | Fondo | Ratio | Min / objetivo | Nivel | Uso |
|---|---|---:|---|---|---|
| readerText `#E0DCD7` | readerBackground `#191715` | 13.10 | 7.0 / 7.0 | AAA | texto de cuerpo |
| readerSecondaryText `#B4B0A9` | readerBackground `#191715` | 8.28 | 7.0 / 7.0 | AAA | notas, cabeceras, numero de pagina |
| readerLink `#AEBEFF` | readerBackground `#191715` | 9.88 | 7.0 / 7.0 | AAA | enlace (siempre subrayado) |
| readerLinkVisited `#E5B1DD` | readerBackground `#191715` | 9.93 | 7.0 / 7.0 | AAA | enlace visitado (subrayado) |
| readerText `#E0DCD7` | ttsSentenceHighlight `#1F3951` | 8.73 | 4.5 / 7.0 | AAA | frase leida en voz alta |
| readerLink `#AEBEFF` | ttsSentenceHighlight `#1F3951` | 6.59 | 4.5 / 7.0 | AA | enlace dentro de la frase leida |
| readerSecondaryText `#B4B0A9` | ttsSentenceHighlight `#1F3951` | 5.52 | 4.5 / 7.0 | AA | texto secundario en frase leida |
| ttsWordText `#191715` | ttsWordHighlight `#E9C256` | 10.48 | 4.5 / 7.0 | AAA | palabra que se pronuncia |
| readerText `#E0DCD7` | selection `#5E3869` | 6.85 | 4.5 / 7.0 | AA | texto seleccionado |
| readerText `#E0DCD7` | searchHighlight `#5D3620` | 7.64 | 4.5 / 7.0 | AAA | coincidencia de busqueda |
| searchCurrentText `#191715` | searchCurrentHighlight `#FA985F` | 8.28 | 4.5 / 7.0 | AAA | coincidencia actual |
| readerText `#E0DCD7` | readerPageBack `#252321` | 11.47 | 4.5 / 7.0 | AAA | reverso de pagina (animacion curl) |
| ttsSentenceIndicator `#5DA6E7` | readerBackground `#191715` | 6.87 | 3.0 / 3.0 | AA | barra lateral/subrayado de frase |
| ttsSentenceIndicator `#5DA6E7` | ttsSentenceHighlight `#1F3951` | 4.58 | 3.0 / 3.0 | AA | indicador sobre la frase |
| ttsWordIndicator `#774806` | ttsWordHighlight `#E9C256` | 4.54 | 3.0 / 3.0 | AA | subrayado grueso de palabra |
| searchOutline `#F28A5B` | readerBackground `#191715` | 7.29 | 3.0 / 3.0 | AAA | contorno de coincidencia |
| searchOutline `#F28A5B` | searchHighlight `#5D3620` | 4.25 | 3.0 / 3.0 | 3:1 | contorno sobre su relleno |
| selectionHandle `#B6C4FF` | readerBackground `#191715` | 10.50 | 3.0 / 3.0 | AAA | tiradores de seleccion |
| readerBarContent `#E5E2DE` | readerBarBackground `#211F1C` | 12.73 | 4.5 / 7.0 | AAA | texto/iconos de barras |
| readerProgressIndicator `#B6C4FF` | readerBarBackground `#211F1C` | 9.65 | 3.0 / 3.0 | AAA | progreso de lectura |

Contraste, pares Material 3:

| Primer plano | Fondo | Ratio | Min / objetivo | Nivel | Uso |
|---|---|---:|---|---|---|
| onPrimary `#002F66` | primary `#B6C4FF` | 7.71 | 4.5 / 7.0 | AAA | texto sobre boton relleno |
| onPrimaryContainer `#DCE1FF` | primaryContainer `#00458F` | 7.23 | 4.5 / 7.0 | AAA | texto en contenedor primario |
| onSecondary `#283044` | secondary `#BEC6E0` | 7.73 | 4.5 / 7.0 | AAA | texto sobre secundario |
| onSecondaryContainer `#DAE2FD` | secondaryContainer `#3E465C` | 7.28 | 4.5 / 7.0 | AAA | chips/tonal button |
| onTertiary `#003738` | tertiary `#82D4D6` | 7.69 | 4.5 / 7.0 | AAA | texto sobre terciario |
| onTertiaryContainer `#9EF0F2` | tertiaryContainer `#004F51` | 7.24 | 4.5 / 7.0 | AAA | contenedor terciario |
| onError `#690003` | error `#FFB4A6` | 7.70 | 4.5 / 7.0 | AAA | texto sobre error |
| onErrorContainer `#FFDAD3` | errorContainer `#900C12` | 7.22 | 4.5 / 7.0 | AAA | mensaje de error |
| onBackground `#E5E2DE` | background `#15130F` | 14.37 | 4.5 / 7.0 | AAA | texto sobre fondo |
| onSurface `#E5E2DE` | surface `#15130F` | 14.37 | 4.5 / 7.0 | AAA | texto sobre superficie |
| onSurface `#E5E2DE` | surfaceContainerLowest `#110E09` | 14.91 | 4.5 / 7.0 | AAA | texto en contenedor minimo |
| onSurface `#E5E2DE` | surfaceContainerLow `#1D1B18` | 13.31 | 4.5 / 7.0 | AAA | texto en tarjetas |
| onSurface `#E5E2DE` | surfaceContainer `#211F1C` | 12.73 | 4.5 / 7.0 | AAA | texto en barras/menus |
| onSurface `#E5E2DE` | surfaceContainerHigh `#2C2A26` | 11.09 | 4.5 / 7.0 | AAA | texto en dialogos |
| onSurface `#E5E2DE` | surfaceContainerHighest `#373431` | 9.58 | 4.5 / 7.0 | AAA | texto en campos |
| onSurface `#E5E2DE` | surfaceBright `#3B3935` | 8.92 | 4.5 / 7.0 | AAA | texto en surfaceBright |
| onSurface `#E5E2DE` | surfaceDim `#15130F` | 14.37 | 4.5 / 7.0 | AAA | texto en surfaceDim |
| onSurfaceVariant `#CCC6BB` | surface `#15130F` | 10.92 | 4.5 / 7.0 | AAA | texto secundario/iconos |
| onSurfaceVariant `#CCC6BB` | surfaceVariant `#4B463D` | 5.51 | 4.5 / 7.0 | AA | texto en surfaceVariant |
| onSurfaceVariant `#CCC6BB` | surfaceContainerHighest `#373431` | 7.28 | 4.5 / 7.0 | AAA | etiqueta de campo |
| inverseOnSurface `#32302D` | inverseSurface `#E5E2DE` | 10.19 | 4.5 / 7.0 | AAA | snackbar |
| inversePrimary `#325CAA` | inverseSurface `#E5E2DE` | 5.01 | 4.5 / 7.0 | AA | accion de snackbar |
| primary `#B6C4FF` | surface `#15130F` | 10.89 | 4.5 / 7.0 | AAA | boton de texto / enlace UI |
| primary `#B6C4FF` | surfaceContainer `#211F1C` | 9.65 | 4.5 / 7.0 | AAA | icono activo en barra |
| primary `#B6C4FF` | surfaceContainerHighest `#373431` | 7.26 | 3.0 / 4.5 | AAA | indicador/switch activo |
| error `#FFB4A6` | surface `#15130F` | 10.89 | 4.5 / 7.0 | AAA | texto de error |
| outline `#969086` | surface `#15130F` | 5.86 | 3.0 / 4.5 | AA | borde de campo/boton (1.4.11) |
| outline `#969086` | surfaceContainer `#211F1C` | 5.19 | 3.0 / 4.5 | AA | borde sobre barra |
| outline `#969086` | surfaceContainerHighest `#373431` | 3.90 | 3.0 / 4.5 | 3:1 | borde sobre contenedor alto |
| onPrimaryFixed `#001B3F` | primaryFixed `#DCE1FF` | 13.23 | 4.5 / 7.0 | AAA | fixed |
| onPrimaryFixedVariant `#00458F` | primaryFixed `#DCE1FF` | 7.23 | 4.5 / 7.0 | AAA | fixed variante |
| onSecondaryFixed `#121B2E` | secondaryFixed `#DAE2FD` | 13.32 | 4.5 / 7.0 | AAA | fixed |
| onTertiaryFixed `#002021` | tertiaryFixed `#9EF0F2` | 13.19 | 4.5 / 7.0 | AAA | fixed |

Distincion de resaltados y estados (CIEDE2000; normal / protanopia / deuteranopia / tritanopia):

| Par | Minimo | Normal | Prot. | Deut. | Trit. | Nota |
|---|---:|---:|---:|---:|---:|---|
| readerBackground / ttsSentenceHighlight | 5 | 17.6 | 17.6 | 17.9 | 21.6 | fondo / frase (+ barra lateral) |
| readerBackground / ttsWordHighlight | 15 | 71.3 | 68.4 | 73.4 | 69.0 | fondo / palabra |
| ttsSentenceHighlight / ttsWordHighlight | 15 | 71.1 | 66.7 | 72.1 | 67.7 | frase / palabra |
| readerBackground / selection | 10 | 27.1 | 23.1 | 22.4 | 20.9 | fondo / seleccion (+ tiradores) |
| readerBackground / searchHighlight | 10 | 21.0 | 16.2 | 19.5 | 22.6 | fondo / busqueda (+ contorno) |
| ttsSentenceHighlight / selection | 5 | 20.3 | 5.8 | 6.3 | 38.7 | frase / seleccion |
| ttsSentenceHighlight / searchHighlight | 5 | 29.5 | 26.8 | 31.0 | 39.5 | frase / busqueda |
| selection / searchHighlight | 5 | 28.5 | 31.9 | 31.7 | 9.7 | seleccion / busqueda |
| searchHighlight / searchCurrentHighlight | 8 | 46.2 | 41.7 | 47.3 | 42.9 | busqueda / busqueda actual |
| readerText / readerLink | - | 23.8 | 22.7 | 24.5 | 22.4 | texto / enlace (informativo: enlace va subrayado) |
| primary / error | - | 30.9 | 30.2 | 35.8 | 40.3 | estado activo / error (informativo: error lleva icono y texto) |
| primary / outline | - | 29.3 | 30.2 | 29.6 | 28.0 | activo / inactivo (informativo: cambia forma y relleno) |

Con *aumento de contraste* activado: texto de cuerpo 16.57:1, peor par verificado 4.25:1, fallos: 0.

### Oscuro AMOLED (`oscuroAmoled`)

Contraste, pares de lectura:

| Primer plano | Fondo | Ratio | Min / objetivo | Nivel | Uso |
|---|---|---:|---|---|---|
| readerText `#D7D4CF` | readerBackground `#000000` | 14.21 | 7.0 / 7.0 | AAA | texto de cuerpo |
| readerSecondaryText `#AAA59F` | readerBackground `#000000` | 8.59 | 7.0 / 7.0 | AAA | notas, cabeceras, numero de pagina |
| readerLink `#AEBEFF` | readerBackground `#000000` | 11.61 | 7.0 / 7.0 | AAA | enlace (siempre subrayado) |
| readerLinkVisited `#E5B1DD` | readerBackground `#000000` | 11.66 | 7.0 / 7.0 | AAA | enlace visitado (subrayado) |
| readerText `#D7D4CF` | ttsSentenceHighlight `#17324A` | 8.93 | 4.5 / 7.0 | AAA | frase leida en voz alta |
| readerLink `#AEBEFF` | ttsSentenceHighlight `#17324A` | 7.29 | 4.5 / 7.0 | AAA | enlace dentro de la frase leida |
| readerSecondaryText `#AAA59F` | ttsSentenceHighlight `#17324A` | 5.40 | 4.5 / 7.0 | AA | texto secundario en frase leida |
| ttsWordText `#000000` | ttsWordHighlight `#E9C256` | 12.32 | 4.5 / 7.0 | AAA | palabra que se pronuncia |
| readerText `#D7D4CF` | selection `#573162` | 7.03 | 4.5 / 7.0 | AAA | texto seleccionado |
| readerText `#D7D4CF` | searchHighlight `#553019` | 7.79 | 4.5 / 7.0 | AAA | coincidencia de busqueda |
| searchCurrentText `#000000` | searchCurrentHighlight `#FA985F` | 9.72 | 4.5 / 7.0 | AAA | coincidencia actual |
| readerText `#D7D4CF` | readerPageBack `#191715` | 12.09 | 4.5 / 7.0 | AAA | reverso de pagina (animacion curl) |
| ttsSentenceIndicator `#5DA6E7` | readerBackground `#000000` | 8.07 | 3.0 / 3.0 | AAA | barra lateral/subrayado de frase |
| ttsSentenceIndicator `#5DA6E7` | ttsSentenceHighlight `#17324A` | 5.07 | 3.0 / 3.0 | AA | indicador sobre la frase |
| ttsWordIndicator `#774806` | ttsWordHighlight `#E9C256` | 4.54 | 3.0 / 3.0 | AA | subrayado grueso de palabra |
| searchOutline `#F28A5B` | readerBackground `#000000` | 8.56 | 3.0 / 3.0 | AAA | contorno de coincidencia |
| searchOutline `#F28A5B` | searchHighlight `#553019` | 4.70 | 3.0 / 3.0 | AA | contorno sobre su relleno |
| selectionHandle `#B6C4FF` | readerBackground `#000000` | 12.33 | 3.0 / 3.0 | AAA | tiradores de seleccion |
| readerBarContent `#E5E2DE` | readerBarBackground `#15130F` | 14.37 | 4.5 / 7.0 | AAA | texto/iconos de barras |
| readerProgressIndicator `#B6C4FF` | readerBarBackground `#15130F` | 10.89 | 3.0 / 3.0 | AAA | progreso de lectura |

Contraste, pares Material 3:

| Primer plano | Fondo | Ratio | Min / objetivo | Nivel | Uso |
|---|---|---:|---|---|---|
| onPrimary `#002F66` | primary `#B6C4FF` | 7.71 | 4.5 / 7.0 | AAA | texto sobre boton relleno |
| onPrimaryContainer `#DCE1FF` | primaryContainer `#00458F` | 7.23 | 4.5 / 7.0 | AAA | texto en contenedor primario |
| onSecondary `#283044` | secondary `#BEC6E0` | 7.73 | 4.5 / 7.0 | AAA | texto sobre secundario |
| onSecondaryContainer `#DAE2FD` | secondaryContainer `#3E465C` | 7.28 | 4.5 / 7.0 | AAA | chips/tonal button |
| onTertiary `#003738` | tertiary `#82D4D6` | 7.69 | 4.5 / 7.0 | AAA | texto sobre terciario |
| onTertiaryContainer `#9EF0F2` | tertiaryContainer `#004F51` | 7.24 | 4.5 / 7.0 | AAA | contenedor terciario |
| onError `#690003` | error `#FFB4A6` | 7.70 | 4.5 / 7.0 | AAA | texto sobre error |
| onErrorContainer `#FFDAD3` | errorContainer `#900C12` | 7.22 | 4.5 / 7.0 | AAA | mensaje de error |
| onBackground `#E5E2DE` | background `#000000` | 16.26 | 4.5 / 7.0 | AAA | texto sobre fondo |
| onSurface `#E5E2DE` | surface `#000000` | 16.26 | 4.5 / 7.0 | AAA | texto sobre superficie |
| onSurface `#E5E2DE` | surfaceContainerLowest `#000000` | 16.26 | 4.5 / 7.0 | AAA | texto en contenedor minimo |
| onSurface `#E5E2DE` | surfaceContainerLow `#15130F` | 14.37 | 4.5 / 7.0 | AAA | texto en tarjetas |
| onSurface `#E5E2DE` | surfaceContainer `#1B1916` | 13.59 | 4.5 / 7.0 | AAA | texto en barras/menus |
| onSurface `#E5E2DE` | surfaceContainerHigh `#23211E` | 12.44 | 4.5 / 7.0 | AAA | texto en dialogos |
| onSurface `#E5E2DE` | surfaceContainerHighest `#2E2C28` | 10.79 | 4.5 / 7.0 | AAA | texto en campos |
| onSurface `#E5E2DE` | surfaceBright `#32302D` | 10.19 | 4.5 / 7.0 | AAA | texto en surfaceBright |
| onSurface `#E5E2DE` | surfaceDim `#000000` | 16.26 | 4.5 / 7.0 | AAA | texto en surfaceDim |
| onSurfaceVariant `#CCC6BB` | surface `#000000` | 12.36 | 4.5 / 7.0 | AAA | texto secundario/iconos |
| onSurfaceVariant `#CCC6BB` | surfaceVariant `#4B463D` | 5.51 | 4.5 / 7.0 | AA | texto en surfaceVariant |
| onSurfaceVariant `#CCC6BB` | surfaceContainerHighest `#2E2C28` | 8.20 | 4.5 / 7.0 | AAA | etiqueta de campo |
| inverseOnSurface `#32302D` | inverseSurface `#E5E2DE` | 10.19 | 4.5 / 7.0 | AAA | snackbar |
| inversePrimary `#325CAA` | inverseSurface `#E5E2DE` | 5.01 | 4.5 / 7.0 | AA | accion de snackbar |
| primary `#B6C4FF` | surface `#000000` | 12.33 | 4.5 / 7.0 | AAA | boton de texto / enlace UI |
| primary `#B6C4FF` | surfaceContainer `#1B1916` | 10.30 | 4.5 / 7.0 | AAA | icono activo en barra |
| primary `#B6C4FF` | surfaceContainerHighest `#2E2C28` | 8.18 | 3.0 / 4.5 | AAA | indicador/switch activo |
| error `#FFB4A6` | surface `#000000` | 12.33 | 4.5 / 7.0 | AAA | texto de error |
| outline `#969086` | surface `#000000` | 6.63 | 3.0 / 4.5 | AA | borde de campo/boton (1.4.11) |
| outline `#969086` | surfaceContainer `#1B1916` | 5.54 | 3.0 / 4.5 | AA | borde sobre barra |
| outline `#969086` | surfaceContainerHighest `#2E2C28` | 4.40 | 3.0 / 4.5 | 3:1 | borde sobre contenedor alto |
| onPrimaryFixed `#001B3F` | primaryFixed `#DCE1FF` | 13.23 | 4.5 / 7.0 | AAA | fixed |
| onPrimaryFixedVariant `#00458F` | primaryFixed `#DCE1FF` | 7.23 | 4.5 / 7.0 | AAA | fixed variante |
| onSecondaryFixed `#121B2E` | secondaryFixed `#DAE2FD` | 13.32 | 4.5 / 7.0 | AAA | fixed |
| onTertiaryFixed `#002021` | tertiaryFixed `#9EF0F2` | 13.19 | 4.5 / 7.0 | AAA | fixed |

Distincion de resaltados y estados (CIEDE2000; normal / protanopia / deuteranopia / tritanopia):

| Par | Minimo | Normal | Prot. | Deut. | Trit. | Nota |
|---|---:|---:|---:|---:|---:|---|
| readerBackground / ttsSentenceHighlight | 5 | 17.9 | 18.1 | 18.2 | 20.3 | fondo / frase (+ barra lateral) |
| readerBackground / ttsWordHighlight | 15 | 74.7 | 71.8 | 76.6 | 72.6 | fondo / palabra |
| ttsSentenceHighlight / ttsWordHighlight | 15 | 74.2 | 69.4 | 75.4 | 70.1 | frase / palabra |
| readerBackground / selection | 10 | 27.9 | 23.6 | 23.1 | 23.7 | fondo / seleccion (+ tiradores) |
| readerBackground / searchHighlight | 10 | 23.6 | 19.2 | 22.1 | 25.1 | fondo / busqueda (+ contorno) |
| ttsSentenceHighlight / selection | 5 | 20.2 | 5.7 | 6.2 | 37.2 | frase / seleccion |
| ttsSentenceHighlight / searchHighlight | 5 | 29.6 | 27.5 | 31.4 | 38.6 | frase / busqueda |
| selection / searchHighlight | 5 | 29.2 | 32.7 | 32.0 | 9.5 | seleccion / busqueda |
| searchHighlight / searchCurrentHighlight | 8 | 48.5 | 43.3 | 50.7 | 45.0 | busqueda / busqueda actual |
| readerText / readerLink | - | 23.5 | 22.7 | 24.4 | 21.2 | texto / enlace (informativo: enlace va subrayado) |
| primary / error | - | 30.9 | 30.2 | 35.8 | 40.3 | estado activo / error (informativo: error lleva icono y texto) |
| primary / outline | - | 29.3 | 30.2 | 29.6 | 28.0 | activo / inactivo (informativo: cambia forma y relleno) |

Con *aumento de contraste* activado: texto de cuerpo 19.47:1, peor par verificado 4.54:1, fallos: 0.

### Proteccion ocular (`proteccionOcular`)

Contraste, pares de lectura:

| Primer plano | Fondo | Ratio | Min / objetivo | Nivel | Uso |
|---|---|---:|---|---|---|
| readerText `#2E2118` | readerBackground `#EBDAC5` | 11.41 | 7.0 / 7.0 | AAA | texto de cuerpo |
| readerSecondaryText `#4E3C30` | readerBackground `#EBDAC5` | 7.63 | 7.0 / 7.0 | AAA | notas, cabeceras, numero de pagina |
| readerLink `#752415` | readerBackground `#EBDAC5` | 7.61 | 7.0 / 7.0 | AAA | enlace (siempre subrayado) |
| readerLinkVisited `#6A2B39` | readerBackground `#EBDAC5` | 7.62 | 7.0 / 7.0 | AAA | enlace visitado (subrayado) |
| readerText `#2E2118` | ttsSentenceHighlight `#EAC9A8` | 9.96 | 4.5 / 7.0 | AAA | frase leida en voz alta |
| readerLink `#752415` | ttsSentenceHighlight `#EAC9A8` | 6.64 | 4.5 / 7.0 | AA | enlace dentro de la frase leida |
| readerSecondaryText `#4E3C30` | ttsSentenceHighlight `#EAC9A8` | 6.67 | 4.5 / 7.0 | AA | texto secundario en frase leida |
| ttsWordText `#120903` | ttsWordHighlight `#C59716` | 7.33 | 4.5 / 7.0 | AAA | palabra que se pronuncia |
| readerText `#2E2118` | selection `#B0B5CF` | 7.69 | 4.5 / 7.0 | AAA | texto seleccionado |
| readerText `#2E2118` | searchHighlight `#F29498` | 7.03 | 4.5 / 7.0 | AAA | coincidencia de busqueda |
| searchCurrentText `#20150C` | searchCurrentHighlight `#E47665` | 6.03 | 4.5 / 7.0 | AA | coincidencia actual |
| readerText `#2E2118` | readerPageBack `#DACAB4` | 9.72 | 4.5 / 7.0 | AAA | reverso de pagina (animacion curl) |
| ttsSentenceIndicator `#914C22` | readerBackground `#EBDAC5` | 4.71 | 3.0 / 3.0 | AA | barra lateral/subrayado de frase |
| ttsSentenceIndicator `#914C22` | ttsSentenceHighlight `#EAC9A8` | 4.12 | 3.0 / 3.0 | 3:1 | indicador sobre la frase |
| ttsWordIndicator `#743417` | ttsWordHighlight `#C59716` | 3.48 | 3.0 / 3.0 | 3:1 | subrayado grueso de palabra |
| searchOutline `#A42F30` | readerBackground `#EBDAC5` | 5.08 | 3.0 / 3.0 | AA | contorno de coincidencia |
| searchOutline `#A42F30` | searchHighlight `#F29498` | 3.13 | 3.0 / 3.0 | 3:1 | contorno sobre su relleno |
| selectionHandle `#833F24` | readerBackground `#EBDAC5` | 5.69 | 3.0 / 3.0 | AA | tiradores de seleccion |
| readerBarContent `#281E0C` | readerBarBackground `#E5D5BF` | 11.40 | 4.5 / 7.0 | AAA | texto/iconos de barras |
| readerProgressIndicator `#833F24` | readerBarBackground `#E5D5BF` | 5.40 | 3.0 / 3.0 | AA | progreso de lectura |

Contraste, pares Material 3:

| Primer plano | Fondo | Ratio | Min / objetivo | Nivel | Uso |
|---|---|---:|---|---|---|
| onPrimary `#FFF4F0` | primary `#833F24` | 7.20 | 4.5 / 7.0 | AAA | texto sobre boton relleno |
| onPrimaryContainer `#391500` | primaryContainer `#FFAD8C` | 9.06 | 4.5 / 7.0 | AAA | texto en contenedor primario |
| onSecondary `#FFF4EE` | secondary `#664D3D` | 7.21 | 4.5 / 7.0 | AAA | texto sobre secundario |
| onSecondaryContainer `#2F1B0A` | secondaryContainer `#DABBA8` | 9.09 | 4.5 / 7.0 | AAA | chips/tonal button |
| onTertiary `#F8FBBF` | tertiary `#515624` | 7.22 | 4.5 / 7.0 | AAA | texto sobre terciario |
| onTertiaryContainer `#202100` | tertiaryContainer `#C2C58C` | 9.12 | 4.5 / 7.0 | AAA | contenedor terciario |
| onError `#FFF4F3` | error `#9E2127` | 7.22 | 4.5 / 7.0 | AAA | texto sobre error |
| onErrorContainer `#490000` | errorContainer `#FFB4AB` | 9.64 | 4.5 / 7.0 | AAA | mensaje de error |
| onBackground `#281E0C` | background `#EBDAC5` | 11.99 | 4.5 / 7.0 | AAA | texto sobre fondo |
| onSurface `#281E0C` | surface `#EBDAC5` | 11.99 | 4.5 / 7.0 | AAA | texto sobre superficie |
| onSurface `#281E0C` | surfaceContainerLowest `#F7E6D0` | 13.41 | 4.5 / 7.0 | AAA | texto en contenedor minimo |
| onSurface `#281E0C` | surfaceContainerLow `#F1E0CA` | 12.69 | 4.5 / 7.0 | AAA | texto en tarjetas |
| onSurface `#281E0C` | surfaceContainer `#E5D5BF` | 11.40 | 4.5 / 7.0 | AAA | texto en barras/menus |
| onSurface `#281E0C` | surfaceContainerHigh `#E0CFBA` | 10.77 | 4.5 / 7.0 | AAA | texto en dialogos |
| onSurface `#281E0C` | surfaceContainerHighest `#DACAB4` | 10.22 | 4.5 / 7.0 | AAA | texto en campos |
| onSurface `#281E0C` | surfaceBright `#F4E3CD` | 13.05 | 4.5 / 7.0 | AAA | texto en surfaceBright |
| onSurface `#281E0C` | surfaceDim `#D2C1AC` | 9.34 | 4.5 / 7.0 | AAA | texto en surfaceDim |
| onSurfaceVariant `#4C3B25` | surface `#EBDAC5` | 7.84 | 4.5 / 7.0 | AAA | texto secundario/iconos |
| onSurfaceVariant `#4C3B25` | surfaceVariant `#DBC3A8` | 6.32 | 4.5 / 7.0 | AA | texto en surfaceVariant |
| onSurfaceVariant `#4C3B25` | surfaceContainerHighest `#DACAB4` | 6.68 | 4.5 / 7.0 | AA | etiqueta de campo |
| inverseOnSurface `#EBDAC5` | inverseSurface `#3E3322` | 9.03 | 4.5 / 7.0 | AAA | snackbar |
| inversePrimary `#FFAD8C` | inverseSurface `#3E3322` | 6.82 | 4.5 / 7.0 | AA | accion de snackbar |
| primary `#833F24` | surface `#EBDAC5` | 5.69 | 4.5 / 7.0 | AA | boton de texto / enlace UI |
| primary `#833F24` | surfaceContainer `#E5D5BF` | 5.40 | 4.5 / 7.0 | AA | icono activo en barra |
| primary `#833F24` | surfaceContainerHighest `#DACAB4` | 4.84 | 3.0 / 4.5 | AA | indicador/switch activo |
| error `#9E2127` | surface `#EBDAC5` | 5.69 | 4.5 / 7.0 | AA | texto de error |
| outline `#746048` | surface `#EBDAC5` | 4.38 | 3.0 / 4.5 | 3:1 | borde de campo/boton (1.4.11) |
| outline `#746048` | surfaceContainer `#E5D5BF` | 4.16 | 3.0 / 4.5 | 3:1 | borde sobre barra |
| outline `#746048` | surfaceContainerHighest `#DACAB4` | 3.73 | 3.0 / 4.5 | 3:1 | borde sobre contenedor alto |
| onPrimaryFixed `#311300` | primaryFixed `#FFDBCD` | 13.29 | 4.5 / 7.0 | AAA | fixed |
| onPrimaryFixedVariant `#753319` | primaryFixed `#FFDBCD` | 7.25 | 4.5 / 7.0 | AAA | fixed variante |
| onSecondaryFixed `#2B1703` | secondaryFixed `#FCDCC8` | 13.22 | 4.5 / 7.0 | AAA | fixed |
| onTertiaryFixed `#1C1D00` | tertiaryFixed `#E3E7AC` | 13.30 | 4.5 / 7.0 | AAA | fixed |

Distincion de resaltados y estados (CIEDE2000; normal / protanopia / deuteranopia / tritanopia):

| Par | Minimo | Normal | Prot. | Deut. | Trit. | Nota |
|---|---:|---:|---:|---:|---:|---|
| readerBackground / ttsSentenceHighlight | 5 | 6.8 | 5.9 | 5.6 | 7.5 | fondo / frase (+ barra lateral) |
| readerBackground / ttsWordHighlight | 15 | 25.4 | 26.8 | 24.2 | 20.9 | fondo / palabra |
| ttsSentenceHighlight / ttsWordHighlight | 15 | 20.7 | 21.5 | 19.2 | 15.0 | frase / palabra |
| readerBackground / selection | 10 | 22.7 | 22.8 | 24.4 | 23.2 | fondo / seleccion (+ tiradores) |
| readerBackground / searchHighlight | 10 | 25.4 | 14.9 | 10.4 | 20.6 | fondo / busqueda (+ contorno) |
| ttsSentenceHighlight / selection | 5 | 25.7 | 26.3 | 28.2 | 28.2 | frase / seleccion |
| ttsSentenceHighlight / searchHighlight | 5 | 22.8 | 13.5 | 7.8 | 14.3 | frase / busqueda |
| selection / searchHighlight | 5 | 25.8 | 18.1 | 24.6 | 28.0 | seleccion / busqueda |
| searchHighlight / searchCurrentHighlight | 8 | 11.2 | 12.9 | 10.0 | 8.4 | busqueda / busqueda actual |
| readerText / readerLink | - | 19.8 | 10.3 | 15.7 | 19.9 | texto / enlace (informativo: enlace va subrayado) |
| primary / error | - | 11.1 | 4.6 | 1.7 | 7.8 | estado activo / error (informativo: error lleva icono y texto) |
| primary / outline | - | 16.0 | 9.5 | 8.5 | 14.6 | activo / inactivo (informativo: cambia forma y relleno) |

Con *aumento de contraste* activado: texto de cuerpo 13.82:1, peor par verificado 3.13:1, fallos: 0.

### Proteccion ocular noche (`proteccionOcularNoche`)

Contraste, pares de lectura:

| Primer plano | Fondo | Ratio | Min / objetivo | Nivel | Uso |
|---|---|---:|---|---|---|
| readerText `#E4BA84` | readerBackground `#1B140F` | 10.10 | 7.0 / 7.0 | AAA | texto de cuerpo |
| readerSecondaryText `#C59F76` | readerBackground `#1B140F` | 7.45 | 7.0 / 7.0 | AAA | notas, cabeceras, numero de pagina |
| readerLink `#F5A273` | readerBackground `#1B140F` | 8.93 | 7.0 / 7.0 | AAA | enlace (siempre subrayado) |
| readerLinkVisited `#E19991` | readerBackground `#1B140F` | 7.95 | 7.0 / 7.0 | AAA | enlace visitado (subrayado) |
| readerText `#E4BA84` | ttsSentenceHighlight `#3E2716` | 7.74 | 4.5 / 7.0 | AAA | frase leida en voz alta |
| readerLink `#F5A273` | ttsSentenceHighlight `#3E2716` | 6.84 | 4.5 / 7.0 | AA | enlace dentro de la frase leida |
| readerSecondaryText `#C59F76` | ttsSentenceHighlight `#3E2716` | 5.70 | 4.5 / 7.0 | AA | texto secundario en frase leida |
| ttsWordText `#1B140F` | ttsWordHighlight `#DDA041` | 7.96 | 4.5 / 7.0 | AAA | palabra que se pronuncia |
| readerText `#E4BA84` | selection `#383D52` | 5.95 | 4.5 / 7.0 | AA | texto seleccionado |
| readerText `#E4BA84` | searchHighlight `#723338` | 5.19 | 4.5 / 7.0 | AA | coincidencia de busqueda |
| searchCurrentText `#1B140F` | searchCurrentHighlight `#DE7B59` | 6.14 | 4.5 / 7.0 | AA | coincidencia actual |
| readerText `#E4BA84` | readerPageBack `#251E1A` | 9.11 | 4.5 / 7.0 | AAA | reverso de pagina (animacion curl) |
| ttsSentenceIndicator `#C78140` | readerBackground `#1B140F` | 5.76 | 3.0 / 3.0 | AA | barra lateral/subrayado de frase |
| ttsSentenceIndicator `#C78140` | ttsSentenceHighlight `#3E2716` | 4.41 | 3.0 / 3.0 | 3:1 | indicador sobre la frase |
| ttsWordIndicator `#763B17` | ttsWordHighlight `#DDA041` | 3.81 | 3.0 / 3.0 | 3:1 | subrayado grueso de palabra |
| searchOutline `#EA7B6A` | readerBackground `#1B140F` | 6.54 | 3.0 / 3.0 | AA | contorno de coincidencia |
| searchOutline `#EA7B6A` | searchHighlight `#723338` | 3.36 | 3.0 / 3.0 | 3:1 | contorno sobre su relleno |
| selectionHandle `#E5A35A` | readerBackground `#1B140F` | 8.42 | 3.0 / 3.0 | AAA | tiradores de seleccion |
| readerBarContent `#D3C4B9` | readerBarBackground `#281D16` | 9.68 | 4.5 / 7.0 | AAA | texto/iconos de barras |
| readerProgressIndicator `#E5A35A` | readerBarBackground `#281D16` | 7.61 | 3.0 / 3.0 | AAA | progreso de lectura |

Contraste, pares Material 3:

| Primer plano | Fondo | Ratio | Min / objetivo | Nivel | Uso |
|---|---|---:|---|---|---|
| onPrimary `#382100` | primary `#E5A35A` | 7.01 | 4.5 / 7.0 | AAA | texto sobre boton relleno |
| onPrimaryContainer `#FFCB97` | primaryContainer `#613900` | 6.80 | 4.5 / 7.0 | AA | texto en contenedor primario |
| onSecondary `#36210E` | secondary `#CAAB93` | 7.07 | 4.5 / 7.0 | AAA | texto sobre secundario |
| onSecondaryContainer `#EFCEB6` | secondaryContainer `#553D29` | 6.80 | 4.5 / 7.0 | AA | chips/tonal button |
| onTertiary `#252800` | tertiary `#B1B57D` | 7.07 | 4.5 / 7.0 | AAA | texto sobre terciario |
| onTertiaryContainer `#D5D89F` | tertiaryContainer `#404614` | 6.73 | 4.5 / 7.0 | AA | contenedor terciario |
| onError `#520500` | error `#FF8A77` | 6.63 | 4.5 / 7.0 | AA | texto sobre error |
| onErrorContainer `#FFC7BD` | errorContainer `#831514` | 6.80 | 4.5 / 7.0 | AA | mensaje de error |
| onBackground `#D3C4B9` | background `#1E1308` | 10.74 | 4.5 / 7.0 | AAA | texto sobre fondo |
| onSurface `#D3C4B9` | surface `#1E1308` | 10.74 | 4.5 / 7.0 | AAA | texto sobre superficie |
| onSurface `#D3C4B9` | surfaceContainerLowest `#180B00` | 11.38 | 4.5 / 7.0 | AAA | texto en contenedor minimo |
| onSurface `#D3C4B9` | surfaceContainerLow `#241912` | 10.11 | 4.5 / 7.0 | AAA | texto en tarjetas |
| onSurface `#D3C4B9` | surfaceContainer `#281D16` | 9.68 | 4.5 / 7.0 | AAA | texto en barras/menus |
| onSurface `#D3C4B9` | surfaceContainerHigh `#31261E` | 8.67 | 4.5 / 7.0 | AAA | texto en dialogos |
| onSurface `#D3C4B9` | surfaceContainerHighest `#3A2E26` | 7.74 | 4.5 / 7.0 | AAA | texto en campos |
| onSurface `#D3C4B9` | surfaceBright `#3E322A` | 7.30 | 4.5 / 7.0 | AAA | texto en surfaceBright |
| onSurface `#D3C4B9` | surfaceDim `#1E1308` | 10.74 | 4.5 / 7.0 | AAA | texto en surfaceDim |
| onSurfaceVariant `#C7AB96` | surface `#1E1308` | 8.42 | 4.5 / 7.0 | AAA | texto secundario/iconos |
| onSurfaceVariant `#C7AB96` | surfaceVariant `#533E2C` | 4.63 | 4.5 / 7.0 | AA | texto en surfaceVariant |
| onSurfaceVariant `#C7AB96` | surfaceContainerHighest `#3A2E26` | 6.06 | 4.5 / 7.0 | AA | etiqueta de campo |
| inverseOnSurface `#2E241C` | inverseSurface `#D3C4B9` | 8.93 | 4.5 / 7.0 | AAA | snackbar |
| inversePrimary `#784700` | inverseSurface `#D3C4B9` | 4.58 | 4.5 / 7.0 | AA | accion de snackbar |
| primary `#E5A35A` | surface `#1E1308` | 8.44 | 4.5 / 7.0 | AAA | boton de texto / enlace UI |
| primary `#E5A35A` | surfaceContainer `#281D16` | 7.61 | 4.5 / 7.0 | AAA | icono activo en barra |
| primary `#E5A35A` | surfaceContainerHighest `#3A2E26` | 6.08 | 3.0 / 4.5 | AA | indicador/switch activo |
| error `#FF8A77` | surface `#1E1308` | 7.95 | 4.5 / 7.0 | AAA | texto de error |
| outline `#997F6B` | surface `#1E1308` | 4.86 | 3.0 / 4.5 | AA | borde de campo/boton (1.4.11) |
| outline `#997F6B` | surfaceContainer `#281D16` | 4.39 | 3.0 / 4.5 | 3:1 | borde sobre barra |
| outline `#997F6B` | surfaceContainerHighest `#3A2E26` | 3.50 | 3.0 / 4.5 | 3:1 | borde sobre contenedor alto |
| onPrimaryFixed `#291800` | primaryFixed `#FFDCBC` | 13.24 | 4.5 / 7.0 | AAA | fixed |
| onPrimaryFixedVariant `#683D00` | primaryFixed `#FFDCBC` | 7.19 | 4.5 / 7.0 | AAA | fixed variante |
| onSecondaryFixed `#2A1700` | secondaryFixed `#FDDCC4` | 13.28 | 4.5 / 7.0 | AAA | fixed |
| onTertiaryFixed `#1C1D00` | tertiaryFixed `#E3E7AC` | 13.30 | 4.5 / 7.0 | AAA | fixed |

Distincion de resaltados y estados (CIEDE2000; normal / protanopia / deuteranopia / tritanopia):

| Par | Minimo | Normal | Prot. | Deut. | Trit. | Nota |
|---|---:|---:|---:|---:|---:|---|
| readerBackground / ttsSentenceHighlight | 5 | 11.8 | 10.3 | 11.1 | 12.2 | fondo / frase (+ barra lateral) |
| readerBackground / ttsWordHighlight | 15 | 58.9 | 55.7 | 60.8 | 57.0 | fondo / palabra |
| ttsSentenceHighlight / ttsWordHighlight | 15 | 51.2 | 48.2 | 53.0 | 49.2 | frase / palabra |
| readerBackground / selection | 10 | 18.8 | 19.2 | 19.5 | 20.6 | fondo / seleccion (+ tiradores) |
| readerBackground / searchHighlight | 10 | 24.4 | 13.6 | 18.1 | 24.1 | fondo / busqueda (+ contorno) |
| ttsSentenceHighlight / selection | 5 | 22.4 | 23.4 | 24.4 | 28.0 | frase / seleccion |
| ttsSentenceHighlight / searchHighlight | 5 | 17.5 | 9.7 | 9.8 | 12.9 | frase / busqueda |
| selection / searchHighlight | 5 | 23.6 | 15.2 | 22.1 | 29.2 | seleccion / busqueda |
| searchHighlight / searchCurrentHighlight | 8 | 33.6 | 30.8 | 34.9 | 31.3 | busqueda / busqueda actual |
| readerText / readerLink | - | 11.9 | 4.5 | 2.9 | 7.7 | texto / enlace (informativo: enlace va subrayado) |
| primary / error | - | 20.6 | 9.9 | 4.8 | 7.0 | estado activo / error (informativo: error lleva icono y texto) |
| primary / outline | - | 19.7 | 18.7 | 20.0 | 18.0 | activo / inactivo (informativo: cambia forma y relleno) |

Con *aumento de contraste* activado: texto de cuerpo 14.14:1, peor par verificado 3.36:1, fallos: 0.

### Luz azul y brillo del fondo de lectura (indicador aproximado)

No es una medida melanopica; con sRGB solo se puede comparar el canal azul lineal emitido por el fondo (que ocupa casi toda la pantalla) y su luminancia relativa Y. Indice 100 = tema claro.

| Tema | Fondo | Y (brillo) | Azul lineal | Azul / (R+G+B) | Indice azul |
|---|---|---:|---:|---:|---:|
| Claro | `#FDFAF6` | 0.959 | 0.922 | 32 % | 100 |
| Oscuro | `#191715` | 0.009 | 0.007 | 29 % | 1 |
| Oscuro AMOLED | `#000000` | 0.000 | 0.000 | 0 % | 0 |
| Proteccion ocular | `#EBDAC5` | 0.718 | 0.558 | 27 % | 61 |
| Proteccion ocular noche | `#1B140F` | 0.008 | 0.005 | 21 % | 1 |

Resultado global: 0 fallos en 5 temas (base y con aumento de contraste).
<!-- SALIDA-SCRIPT-FIN -->

## 9. Decisiones

| Decision | Motivo |
|---|---|
| Oscuro por defecto gris carbon L* 8, no negro | Menos halos y menos fatiga; el negro puro queda como variante AMOLED |
| Texto oscuro a ~13:1 y no 18-21:1 | AAA holgado sin el deslumbramiento del blanco sobre negro |
| Proteccion ocular a L* 88 y no a sepia muy claro | Bajar de verdad brillo y azul (-25 % de luminancia y -39 % de azul lineal frente al claro, medido); un sepia muy claro apenas los reduciria |
| Variante nocturna ambar | Para leer a oscuras: el sepia sigue siendo una pantalla clara |
| Resaltados opacos, no con alfa | Contraste medible y estable; en PDF se usa multiplicar |
| Palabra con color de texto propio | En temas oscuros permite un acento claro con texto oscuro (>= 7:1) |
| Seleccion lavanda tambien en proteccion ocular | Unica forma comprobada de separarla de frase y busqueda en deuteranopia |
| Paletas en LCh(ab) en lugar de HCT | Sin dependencias en el script; el tono (L*) es identico a HCT |
| Roles `*Fixed` de M3 incluidos | Compose Material 3 1.4+ los tiene en `ColorScheme` |
| `outlineVariant` decorativo | M3 lo usa para divisores; con alto contraste pasa a >= 3:1 |
| Indicador de luz azul aproximado | sRGB no permite medir dosis melanopica; se da el canal azul lineal como comparativa honesta |
