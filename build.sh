#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
MODULE_DIR="$ROOT_DIR/module"
OUTPUT_DIR="$ROOT_DIR/dist"
# Sirf arm64 default — zip chhota (~1–2MB). Emulator: ABI_LIST="x86_64" ./build.sh
ABI_LIST="${ABI_LIST:-arm64-v8a}"

if [[ -z "${ANDROID_NDK:-}" ]] || [[ ! -f "${ANDROID_NDK}/build/cmake/android.toolchain.cmake" ]]; then
  if ANDROID_NDK=$("$ROOT_DIR/tools/find_ndk.sh"); then
    export ANDROID_NDK
    echo "==> Using NDK: $ANDROID_NDK"
  else
    exit 1
  fi
else
  echo "==> Using NDK: $ANDROID_NDK"
fi

TOOLCHAIN="$ANDROID_NDK/build/cmake/android.toolchain.cmake"
if [[ ! -f "$TOOLCHAIN" ]]; then
  echo "ERROR: NDK toolchain missing: $TOOLCHAIN"
  exit 1
fi

echo "==> Building Zygisk native module (ABIs: $ABI_LIST)"
rm -rf "$OUTPUT_DIR" "$ROOT_DIR/build"
mkdir -p "$OUTPUT_DIR/zygisk"

for ABI in $ABI_LIST; do
  BUILD_DIR="$ROOT_DIR/build/$ABI"
  mkdir -p "$BUILD_DIR"

  echo "==> Configuring $ABI ..."
  cmake -S "$MODULE_DIR/jni" -B "$BUILD_DIR" \
    -G "Unix Makefiles" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=android-26 \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DCMAKE_BUILD_TYPE=Release

  cmake --build "$BUILD_DIR" --config Release -j"$(nproc 2>/dev/null || echo 4)"

  LIB_PATH="$BUILD_DIR/libhivirtus_zygisk_mode.so"
  if [[ ! -f "$LIB_PATH" ]]; then
    echo "ERROR: Native library build failed for $ABI"
    exit 1
  fi

  STRIP="$ANDROID_NDK/toolchains/llvm/prebuilt/*/bin/llvm-strip"
  STRIP_BIN=$(ls $STRIP 2>/dev/null | head -1)
  if [[ -n "$STRIP_BIN" ]]; then
    "$STRIP_BIN" --strip-unneeded "$LIB_PATH" 2>/dev/null || true
  fi

  cp "$LIB_PATH" "$OUTPUT_DIR/zygisk/$ABI.so"
  echo "Built $ABI ($(du -h "$OUTPUT_DIR/zygisk/$ABI.so" | cut -f1))"
done

echo "==> Building embedded floating overlay dex"
chmod +x "$ROOT_DIR/tools/build_overlay_dex.sh"
"$ROOT_DIR/tools/build_overlay_dex.sh" || true

echo "==> Packaging Zygisk module zip (ZIP only — no APK)"
cp "$MODULE_DIR/module.prop" "$OUTPUT_DIR/"
cp "$MODULE_DIR/customize.sh" "$OUTPUT_DIR/"
cp "$MODULE_DIR/service.sh" "$OUTPUT_DIR/"
cp "$MODULE_DIR/start_overlay.sh" "$OUTPUT_DIR/"
cp "$MODULE_DIR/post-fs-data.sh" "$OUTPUT_DIR/"
cp "$MODULE_DIR/config.json" "$OUTPUT_DIR/"
cp "$MODULE_DIR/uninstall.sh" "$OUTPUT_DIR/"
if [[ -d "$MODULE_DIR/META-INF" ]]; then
  cp -r "$MODULE_DIR/META-INF" "$OUTPUT_DIR/"
fi
if [[ -d "$MODULE_DIR/system" ]]; then
  cp -r "$MODULE_DIR/system" "$OUTPUT_DIR/"
fi
mkdir -p "$OUTPUT_DIR/overlay"
if [[ -d "$MODULE_DIR/overlay/ui" ]]; then
  mkdir -p "$OUTPUT_DIR/overlay/ui"
  cp -r "$MODULE_DIR/overlay/ui/"* "$OUTPUT_DIR/overlay/ui/"
fi
rm -rf "$OUTPUT_DIR/overlay/classes" "$OUTPUT_DIR/overlay/sources.txt" "$OUTPUT_DIR/overlay/overlay.jar" "$OUTPUT_DIR/overlay/classes.dex" "$OUTPUT_DIR/overlay/virtus-float.apk" "$OUTPUT_DIR/overlay/apk-build"
if [[ ! -f "$OUTPUT_DIR/overlay/overlay.dex" ]] && [[ -f "$MODULE_DIR/overlay/overlay.dex" ]]; then
  cp "$MODULE_DIR/overlay/overlay.dex" "$OUTPUT_DIR/overlay/"
fi

chmod 755 "$OUTPUT_DIR/customize.sh" "$OUTPUT_DIR/service.sh" "$OUTPUT_DIR/post-fs-data.sh"
[ -f "$OUTPUT_DIR/start_overlay.sh" ] && chmod 755 "$OUTPUT_DIR/start_overlay.sh"
[[ -f "$OUTPUT_DIR/uninstall.sh" ]] && chmod 755 "$OUTPUT_DIR/uninstall.sh"
[[ -f "$OUTPUT_DIR/system/bin/hivirtus-menu" ]] && chmod 755 "$OUTPUT_DIR/system/bin/hivirtus-menu"
[[ -f "$OUTPUT_DIR/system/bin/hivirtus-overlay" ]] && chmod 755 "$OUTPUT_DIR/system/bin/hivirtus-overlay"

ZIP_NAME="hivirtus_zygisk_mode-$(grep '^version=' "$MODULE_DIR/module.prop" | cut -d= -f2).zip"
(cd "$OUTPUT_DIR" && zip -r -9 "$ROOT_DIR/$ZIP_NAME" .)
echo ""
echo "Created $ROOT_DIR/$ZIP_NAME ($(du -h "$ROOT_DIR/$ZIP_NAME" | cut -f1))"
echo ""
echo "==> Sirf ye ZIP flash karo — koi APK install nahi. Reboot ke baad OTP bubble aayega."
