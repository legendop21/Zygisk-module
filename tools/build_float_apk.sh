#!/usr/bin/env bash
# Minimal overlay APK (~60KB) — APatch/Android 14 floating bubble
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OVERLAY_JAVA="$ROOT_DIR/module/overlay/java"
FLOAT_JAVA="$ROOT_DIR/module/overlay-apk/java"
MANIFEST="$ROOT_DIR/module/overlay-apk/AndroidManifest.xml"
OUT_DIR="$ROOT_DIR/dist/overlay"
BUILD_DIR="$OUT_DIR/apk-build"

if [[ -z "${ANDROID_HOME:-}" ]]; then
  ANDROID_HOME="/tmp/android-sdk"
fi

ANDROID_JAR="$(ls -d "$ANDROID_HOME"/platforms/android-3*/android.jar 2>/dev/null | sort -V | tail -1)"
BT="$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)"
AAPT2="$BT/aapt2"
D8="$BT/d8"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"

for tool in "$ANDROID_JAR" "$AAPT2" "$D8" "$ZIPALIGN"; do
  [[ -e "$tool" ]] || { echo "WARN: missing $tool — skip float APK"; exit 0; }
done

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/out"

echo "==> Compiling float APK sources"
find "$OVERLAY_JAVA" "$FLOAT_JAVA" -name '*.java' > "$BUILD_DIR/sources.txt"
javac --release 17 -encoding UTF-8 -cp "$ANDROID_JAR" -d "$BUILD_DIR/classes" @"$BUILD_DIR/sources.txt"

(
  cd "$BUILD_DIR/classes"
  jar cf "$BUILD_DIR/classes.jar" .
)

"$D8" --lib "$ANDROID_JAR" --min-api 26 --output "$BUILD_DIR/out" "$BUILD_DIR/classes.jar"

echo "==> Linking APK"
"$AAPT2" link -o "$BUILD_DIR/base-unsigned.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$MANIFEST" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  -v

cd "$BUILD_DIR/out"
zip -u "$BUILD_DIR/base-unsigned.apk" classes.dex

"$ZIPALIGN" -f 4 "$BUILD_DIR/base-unsigned.apk" "$OUT_DIR/virtus-float.apk"

if [[ -x "$APKSIGNER" ]]; then
  KEY="$ROOT_DIR/tools/debug.keystore"
  if [[ ! -f "$KEY" ]]; then
    keytool -genkey -v -keystore "$KEY" -storepass android -alias androiddebugkey \
      -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Virtus, OU=Dev, O=Virtus, L=Local, ST=Local, C=IN" 2>/dev/null || true
  fi
  if [[ -f "$KEY" ]]; then
    "$APKSIGNER" sign --ks "$KEY" --ks-pass pass:android --key-pass pass:android \
      --out "$OUT_DIR/virtus-float-signed.apk" "$OUT_DIR/virtus-float.apk"
    mv -f "$OUT_DIR/virtus-float-signed.apk" "$OUT_DIR/virtus-float.apk"
    rm -f "$OUT_DIR/virtus-float-signed.apk.idsig" "$OUT_DIR/base-unsigned.apk"
  fi
fi

rm -rf "$BUILD_DIR"
rm -f "$OUT_DIR/virtus-float-signed.apk.idsig"

echo "==> virtus-float.apk ready ($(du -h "$OUT_DIR/virtus-float.apk" | cut -f1))"
