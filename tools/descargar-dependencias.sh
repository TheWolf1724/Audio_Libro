#!/usr/bin/env bash
# Dependencias nativas opcionales para pruebas en el PC (no van al APK).
#
# - sherpa-onnx 2.0.1 JNI para Linux x86_64: permite que la prueba JVM SherpaJvmTest
#   cargue de verdad Supertonic 3 (las .so de Android x86_64 del AAR enlazan con bionic y
#   no cargan en una JVM de Linux). Se deja en
#   tools/cache/sherpa-onnx-v2.0.1-linux-x64-jni/lib (excluido de Git).
#
# El AAR de Android NO se descarga aqui: lo resuelve Gradle desde JitPack con version
# exacta (gradle/libs.versions.toml, bloque ":tts").
# SHA-256 tomado del campo "digest" de la API de GitHub Releases (29-10-2026) y
# comprobado sobre la descarga.
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${LECTORVOZ_CACHE:-$RAIZ/tools/cache}"
mkdir -p "$CACHE" "$RAIZ/tools/cache"

VERSION="2.0.1"
FICHERO="sherpa-onnx-v$VERSION-linux-x64-jni.tar.bz2"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VERSION/$FICHERO"
SHA="7d5634b2031c7c47745bfb101878da91a188b43fc80c37c58b93ac2780f453d7"

if ! { [[ -f "$CACHE/$FICHERO" ]] && echo "$SHA  $CACHE/$FICHERO" | sha256sum -c --status; }; then
  echo "descargando $FICHERO"
  timeout 1800 nice -n 10 curl -fL --retry 3 -C - -o "$CACHE/$FICHERO.part" "$URL"
  mv "$CACHE/$FICHERO.part" "$CACHE/$FICHERO"
  echo "$SHA  $CACHE/$FICHERO" | sha256sum -c --status || { echo "ERROR: SHA-256 no coincide" >&2; rm -f "$CACHE/$FICHERO"; exit 1; }
fi
nice -n 10 tar xjf "$CACHE/$FICHERO" -C "$RAIZ/tools/cache" "sherpa-onnx-v$VERSION-linux-x64-jni/lib"
echo "listo: $RAIZ/tools/cache/sherpa-onnx-v$VERSION-linux-x64-jni/lib"
