#!/usr/bin/env bash
#
# Fetches the large binary assets that are not committed to git (they exceed GitHub's file-size
# limit): the sherpa-onnx Android native libraries and the Kokoro int8 English TTS model.
#
# Run once after cloning, and in CI before building:
#   ./scripts/fetch-assets.sh
#
set -euo pipefail

SHERPA_VERSION="v1.13.2"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNI_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"
ASSETS_DIR="$ROOT/app/src/main/assets"
MODEL_DIR="$ASSETS_DIR/kokoro-int8-en-v0_19"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "==> Fetching sherpa-onnx $SHERPA_VERSION Android libraries"
if [ ! -f "$JNI_DIR/libsherpa-onnx-jni.so" ] || [ ! -f "$JNI_DIR/libonnxruntime.so" ]; then
  mkdir -p "$JNI_DIR"
  curl -fsSL -o "$TMP/sherpa.tar.bz2" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION-android.tar.bz2"
  tar -xjf "$TMP/sherpa.tar.bz2" -C "$TMP"
  cp "$TMP/jniLibs/arm64-v8a/libsherpa-onnx-jni.so" "$JNI_DIR/"
  cp "$TMP/jniLibs/arm64-v8a/libonnxruntime.so" "$JNI_DIR/"
  echo "    native libs -> $JNI_DIR"
else
  echo "    already present, skipping"
fi

echo "==> Fetching Kokoro int8 English model"
if [ ! -f "$MODEL_DIR/model.int8.onnx" ]; then
  mkdir -p "$ASSETS_DIR"
  curl -fsSL -o "$TMP/kokoro.tar.bz2" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-en-v0_19.tar.bz2"
  tar -xjf "$TMP/kokoro.tar.bz2" -C "$ASSETS_DIR"
  echo "    model -> $MODEL_DIR"
else
  echo "    already present, skipping"
fi

echo "==> Assets ready."
