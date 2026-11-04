# Plan reanudacion - bugs Carl (core/text)

Estado: DIAGNOSTICO HECHO. Sin cambios en ficheros todavia. Nada que commitear.
Rama: worktree-agent-a4b339aeacef10c78

## BUG 1 (ChapterStructure.kt:179 addLine)
Causa confirmada: `'\n' !in line && isPageNumber(line) -> SKIP`. isPageNumber("3000")=true => numero suelto descartado.
Arreglo elegido (robusto):
- Dividir isPageNumber en: numero DECORADO ("Pag 12", "12 de 300", "- 12 -", romano "xii") => SKIP en cualquier posicion; y numero PELADO (solo digitos) => contenido (TEXT).
- Nuevos helpers: `isBareNumber(line)` (trim, todo ascii-digit) y `isDecoratedPageNumber(line)` = isPageNumber && !isBareNumber.
- En addLine cambiar a: `'\n' !in line && isDecoratedPageNumber(line) -> SKIP`. El numero pelado cae a TEXT (ninguna otra rama lo captura).
- Red de seguridad borde: `skipBorderPageNumbers(text, paras)` que marca SKIP el primer y ultimo parrafo de contenido SOLO si es numero pelado Y content.size>=2 (asi "3000" solo, repro, SI se lee -> "tres mil."). Llamar en fromPlain y fromDocument tras markVerseRuns.
- KDoc justificando. PdfPageFlow ya quita numeros de pagina de borde en PDF, por eso un numero suelto a mitad es contenido.

## BUG 2 (SpanishNormalizer.kt:141-154 acronym; 810 isCapsSentence)
Causa confirmada: "DESBLOQUEADO" no pronounceable por cluster "SBL" (cruza silaba) => se deletrea; isCapsSentence>=3 no cubre 2 palabras.
Arreglo elegido (minimo y seguro): NO tocar isCapsSentence (tocarlo rompe "-10 PV": PV debe deletrearse "pe uve"). NO tocar pronounceable.
- Añadir helper `isWordLikeCaps(w)`: w.length>=5 && vowels*100>=length*25 && vowels<length (vocales = VOWELS).
- En acronym cambiar: `val spell = w in spellAcronyms || (!capsSentence && !isRoman(w) && !pronounceable(w) && !isWordLikeCaps(w))`.
- Verificado a mano: LOGRO(pronounceable)+DESBLOQUEADO(wordlike) OK; "DESBLOQUEADO" solo OK; "NIVEL DE PELIGRO MAXIMO"/"ESTADISTICAS DEL JUGADOR" OK (ya capsSentence>=3 o pronounceable). Regresiones OK: RTVE/DNI/BCX deletrean (en spellAcronyms o cortas sin vocal), ONU/UNESCO/FAO palabra, romanos sin cambio. spellAcronyms todas <=4 letras => no chocan con wordLikeCaps(>=5).

## Extras pedidos (ya funcionan, añadir tests): 
"+5"->"más cinco" (SYMBOL_WORDS '+'); "-10 PV"->"menos diez pe uve"; "Nivel 3"->"Nivel tres"; "NIVEL 3" (isAllCaps->HEADING, "NIVEL tres"); "3 vales...", frases con «» y parentesis se leen. Verificar que ningun trozo corto se descarta.

## Tests nuevos a crear (TDD, sinteticos):
- Nuevo fichero core/text/src/test/.../GameCardTest.kt (o añadir a UtteranceBuilderTest + SpanishNormalizerTest):
  - build(ChapterText(0,"3000",null,"es")) no vacio, spoken contiene "tres mil".
  - tarjeta multi-linea: "3000\n+5 de fuerza\n-10 PV\nNIVEL 3\n3 vales por una copa (club desperado).\ncanjear en el club desperado por una «copa» gratis." -> todas las lineas producen utterance (ninguna SKIP), sin deletreo.
  - SpanishNormalizer: "LOGRO DESBLOQUEADO"->"LOGRO DESBLOQUEADO"; "DESBLOQUEADO"->"DESBLOQUEADO"; "NIVEL DE PELIGRO MAXIMO"; "ESTADISTICAS DEL JUGADOR".
  - Regresion numero pagina: capitulo "12\nContenido real aqui largo.\n- 300 -" el borde 12 y "-300-" se descartan (content.size>=2). Y "Pagina 12 de 300" SKIP en medio.
  - Regresion siglas: "RTVE"/"DNI" deletrean.
- Orden TDD: escribir tests -> correr (fallan BUG1/BUG2) -> implementar -> correr verde.

## Compilar:
source tools/entorno.sh && timeout 1500 nice -n 10 ./gradlew --max-workers=2 :core:text:test :format:epub:test > <scratchpad>/g-carl-N.log 2>&1
Tambien :app:compileDebugKotlin :tts:compileDebugKotlin si toco API (no deberia: todo internal/private salvo nada publico nuevo). NO ./gradlew --stop. Ultima ejecucion --no-daemon.
