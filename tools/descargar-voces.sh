#!/usr/bin/env bash
# Descarga la voz neuronal incluida en el APK (Supertonic 3 int8, 10 locutores, 31
# idiomas) y la deja en tts/src/main/assets/voces/<id>/ (ruta excluida de Git).
#
# Uso:  tools/descargar-voces.sh
#       LECTORVOZ_CACHE=/ruta tools/descargar-voces.sh   (reutiliza .tar.bz2 ya bajados)
#
# Origen: releases oficiales de k2-fsa/sherpa-onnx (etiqueta tts-models). El SHA-256 se
# calculo el 28-10-2026 sobre la descarga oficial (tamano igual al Content-Length del
# servidor). Si cambia, el script se detiene: no se usa nada sin verificar.
# Debe coincidir con VoiceCatalog.kt. Piper y espeak-ng-data ya no se usan (decision del
# usuario, 28-10-2026: "suenan muy robot"); si quedan de una version anterior se borran.
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
DESTINO="$RAIZ/tts/src/main/assets/voces"
CACHE="${LECTORVOZ_CACHE:-$RAIZ/tools/cache}"
BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
mkdir -p "$DESTINO" "$CACHE"

# id_destino | fichero | sha256 | directorio raiz del tar
VOCES=(
  "supertonic-3-int8|sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2|82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427|sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
)

# Restos de versiones anteriores (Piper + espeak-ng-data): fuera del APK.
rm -rf "$DESTINO/espeak-ng-data" "$DESTINO"/piper-*

descargar() {
  local fichero="$1" sha="$2" ruta="$CACHE/$1"
  if [[ -f "$ruta" ]] && echo "$sha  $ruta" | sha256sum -c --status; then
    echo "en cache: $fichero"
    return
  fi
  echo "descargando $fichero"
  timeout 1800 nice -n 10 curl -fL --retry 3 -C - -o "$ruta.part" "$BASE/$fichero"
  mv "$ruta.part" "$ruta"
  if ! echo "$sha  $ruta" | sha256sum -c --status; then
    echo "ERROR: SHA-256 no coincide para $fichero" >&2
    rm -f "$ruta"
    exit 1
  fi
}

for v in "${VOCES[@]}"; do
  IFS='|' read -r id fichero sha raiz <<< "$v"
  descargar "$fichero" "$sha"
  if [[ -f "$DESTINO/$id/.sha256" ]] && [[ "$(cat "$DESTINO/$id/.sha256")" == "$sha" ]]; then
    echo "ya instalada: $id"
    continue
  fi
  rm -rf "$DESTINO/$id"
  mkdir -p "$DESTINO/$id"
  nice -n 10 tar xjf "$CACHE/$fichero" -C "$DESTINO/$id" --strip-components=1 \
    --exclude="$raiz/README.md" --exclude="$raiz/*.py" --exclude="$raiz/*.sh" --exclude="$raiz/*.wav"
  echo "$sha" > "$DESTINO/$id/.sha256"
  echo "instalada: $id ($(du -sh "$DESTINO/$id" | cut -f1))"
done

# Estimador optimizado para movil (30-10-2026): el vector_estimator.int8 oficial deja en fp32
# las Conv1d puntuales (~85 % de su tiempo); esta version (WilburDev/supertonic-3-mobile,
# OpenRAIL-M como Supertonic 3, script conv2mm.py publicado) las reescribe como MatMul con
# int8 por canal: misma calidad (UTMOS 4,51 frente a 4,52), ~2,5x mas rapido en ARM y -43 %
# de CPU medido en el PC. El resto de ficheros son identicos a los oficiales. Revision fijada
# y SHA-256 verificado: si cambia, el script se detiene.
MOVIL_REV="2d53d752a6e9a1db1bd0f63fe36806dc6d1a3ee5"
MOVIL_SHA="6fd0f1637a0192ec2ec160a899045d8138395100a3c25414134adf76abd3f57b"
MOVIL_URL="https://huggingface.co/WilburDev/supertonic-3-mobile/resolve/$MOVIL_REV/vector_estimator.int8.onnx"
MOVIL_CACHE="$CACHE/supertonic-3-mobile-vector_estimator-$MOVIL_REV.onnx"
MOVIL_DEST="$DESTINO/supertonic-3-int8/vector_estimator.int8.onnx"
if echo "$MOVIL_SHA  $MOVIL_DEST" | sha256sum -c --status 2>/dev/null; then
  echo "estimador optimizado ya instalado"
else
  if ! { [[ -f "$MOVIL_CACHE" ]] && echo "$MOVIL_SHA  $MOVIL_CACHE" | sha256sum -c --status; }; then
    echo "descargando estimador optimizado para movil"
    timeout 1800 nice -n 10 curl -fL --retry 3 -o "$MOVIL_CACHE.part" "$MOVIL_URL"
    mv "$MOVIL_CACHE.part" "$MOVIL_CACHE"
    if ! echo "$MOVIL_SHA  $MOVIL_CACHE" | sha256sum -c --status; then
      echo "ERROR: SHA-256 no coincide para el estimador optimizado" >&2
      rm -f "$MOVIL_CACHE"
      exit 1
    fi
  fi
  cp "$MOVIL_CACHE" "$MOVIL_DEST"
  echo "estimador optimizado instalado"
fi
echo "listo: $DESTINO"
