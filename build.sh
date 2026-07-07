#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
MODULE_DIR="$ROOT_DIR/module"
OUTPUT_DIR="$ROOT_DIR/dist"
ABI_LIST="${ABI_LIST:-arm64-v8a armeabi-v7a x86 x86_64}"

if [[ -z "${ANDROID_NDK:-}" ]]; then
  echo "ERROR: ANDROID_NDK environment variable set karein"
  echo "Example: export ANDROID_NDK=\$HOME/Android/Sdk/ndk/26.1.10909125"
  exit 1
fi

echo "==> Building Zygisk native module"
rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR/zygisk"

for ABI in $ABI_LIST; do
  BUILD_DIR="$ROOT_DIR/build/$ABI"
  mkdir -p "$BUILD_DIR"

  cmake -S "$MODULE_DIR/jni" -B "$BUILD_DIR" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=android-26 \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DCMAKE_BUILD_TYPE=Release

  cmake --build "$BUILD_DIR" --config Release

  LIB_PATH="$BUILD_DIR/libzygisk_sms_otp.so"
  if [[ ! -f "$LIB_PATH" ]]; then
    echo "ERROR: Native library build failed for $ABI"
    exit 1
  fi

  cp "$LIB_PATH" "$OUTPUT_DIR/zygisk/$ABI.so"
  echo "Built $ABI"
done

echo "==> Packaging Magisk module zip"
cp "$MODULE_DIR/module.prop" "$OUTPUT_DIR/"
cp "$MODULE_DIR/customize.sh" "$OUTPUT_DIR/"
cp "$MODULE_DIR/service.sh" "$OUTPUT_DIR/"
cp "$MODULE_DIR/config.json" "$OUTPUT_DIR/"

chmod 755 "$OUTPUT_DIR/customize.sh" "$OUTPUT_DIR/service.sh"

ZIP_NAME="zygisk_sms_otp-$(grep '^version=' "$MODULE_DIR/module.prop" | cut -d= -f2).zip"
(cd "$OUTPUT_DIR" && zip -r "$ROOT_DIR/$ZIP_NAME" .)
echo "Created $ROOT_DIR/$ZIP_NAME"

echo "==> Done. Flash zip in Magisk Manager and reboot."
