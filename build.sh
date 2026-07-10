#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
MODULE_DIR="$ROOT_DIR/module"
OUTPUT_DIR="$ROOT_DIR/dist"
# v2.68+: arm64-only default (~3MB zip). Multi-ABI: ABI_LIST="arm64-v8a armeabi-v7a" ./build.sh
ABI_LIST="${ABI_LIST:-arm64-v8a}"

# Auto-detect NDK (Mac: ~/Library/Android/sdk/ndk/...)
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
  echo "ERROR: NDK toolchain missing:"
  echo "  $TOOLCHAIN"
  echo ""
  echo "Mac pe sahi path usually:"
  echo "  export ANDROID_NDK=\$HOME/Library/Android/sdk/ndk/26.1.10909125"
  exit 1
fi

echo "==> Building Hivirtus Magisk module (native lib → zygisk/ folder)"
rm -rf "$OUTPUT_DIR" "$ROOT_DIR/build"
mkdir -p "$OUTPUT_DIR/zygisk"

for ABI in $ABI_LIST; do
  BUILD_DIR="$ROOT_DIR/build/$ABI"
  mkdir -p "$BUILD_DIR"

  echo "==> Configuring $ABI ..."
  cmake -S "$MODULE_DIR/jni" -B "$BUILD_DIR" \
    -G "Unix Makefiles" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=android-28 \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DCMAKE_BUILD_TYPE=Release

  cmake --build "$BUILD_DIR" --config Release -j"$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 4)"

  LIB_PATH="$BUILD_DIR/libhivirtus_zygisk_mode.so"
  if [[ ! -f "$LIB_PATH" ]]; then
    echo "ERROR: Native library build failed for $ABI"
    exit 1
  fi

  cp "$LIB_PATH" "$OUTPUT_DIR/zygisk/$ABI.so"
  echo "Built $ABI"
done

echo "==> Packaging Magisk/KernelSU module zip"
cp "$MODULE_DIR/module.prop" "$OUTPUT_DIR/"
cp "$MODULE_DIR/customize.sh" "$OUTPUT_DIR/"
cp "$MODULE_DIR/service.sh" "$OUTPUT_DIR/"
cp "$MODULE_DIR/post-fs-data.sh" "$OUTPUT_DIR/"
cp "$MODULE_DIR/config.json" "$OUTPUT_DIR/"
[ -f "$MODULE_DIR/apatch_package_config_full.csv" ] && cp "$MODULE_DIR/apatch_package_config_full.csv" "$OUTPUT_DIR/"
cp "$MODULE_DIR/uninstall.sh" "$OUTPUT_DIR/"
cp "$MODULE_DIR/overlay_install.sh" "$OUTPUT_DIR/"
if [ -d "$MODULE_DIR/META-INF" ]; then
  cp -r "$MODULE_DIR/META-INF" "$OUTPUT_DIR/"
fi

chmod 755 "$OUTPUT_DIR/customize.sh" "$OUTPUT_DIR/service.sh" "$OUTPUT_DIR/post-fs-data.sh"
[ -f "$OUTPUT_DIR/overlay_install.sh" ] && chmod 755 "$OUTPUT_DIR/overlay_install.sh"
[ -f "$OUTPUT_DIR/uninstall.sh" ] && chmod 755 "$OUTPUT_DIR/uninstall.sh"

# v2.70+: HTML WebView UI + bridge.dex
if [ -d "$MODULE_DIR/ui" ]; then
  cp -r "$MODULE_DIR/ui" "$OUTPUT_DIR/"
  echo "==> Bundled ui/ (HTML overlay)"
fi

build_bridge_dex() {
  local jar="${ANDROID_JAR:-$ROOT_DIR/.ndk/android.jar}"
  local src="$MODULE_DIR/bridge/HivirtusJsBridge.java"
  local out="$ROOT_DIR/build/bridge"
  [ -f "$src" ] || return 0

  if [ ! -f "$jar" ]; then
    mkdir -p "$ROOT_DIR/.ndk"
    if [ ! -f "$ROOT_DIR/.ndk/platform.zip" ]; then
      echo "==> Downloading android.jar for bridge.dex..."
      curl -fsSL -o "$ROOT_DIR/.ndk/platform.zip" \
        "https://dl.google.com/android/repository/platform-34-ext7_r03.zip" || return 0
    fi
    unzip -qo -j "$ROOT_DIR/.ndk/platform.zip" "android-34/android.jar" -d "$ROOT_DIR/.ndk" 2>/dev/null || true
    [ -f "$ROOT_DIR/.ndk/android.jar" ] || unzip -qo -j "$ROOT_DIR/.ndk/platform.zip" "android.jar" -d "$ROOT_DIR/.ndk" 2>/dev/null || true
    jar="$ROOT_DIR/.ndk/android.jar"
  fi
  [ -f "$jar" ] || { echo "==> WARNING: android.jar missing — bridge.dex skip"; return 0; }

  command -v javac >/dev/null 2>&1 || { echo "==> WARNING: javac missing — bridge.dex skip"; return 0; }

  mkdir -p "$out"
  javac -source 8 -target 8 -bootclasspath "$jar" -d "$out" "$src" 2>/dev/null || return 0

  if command -v d8 >/dev/null 2>&1; then
    d8 --output "$ROOT_DIR/build" "$out/com/hivirtus/zygisk/HivirtusJsBridge.class"
  elif [ -x "${ANDROID_SDK_ROOT:-$ROOT_DIR/.android/sdk}/build-tools/34.0.0/d8" ]; then
    "${ANDROID_SDK_ROOT:-$ROOT_DIR/.android/sdk}/build-tools/34.0.0/d8" --output "$ROOT_DIR/build" \
      "$out/com/hivirtus/zygisk/HivirtusJsBridge.class"
  elif [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/build-tools/34.0.0/d8" ]; then
    "$ANDROID_HOME/build-tools/34.0.0/d8" --output "$ROOT_DIR/build" "$out/com/hivirtus/zygisk/HivirtusJsBridge.class"
  else
    echo "==> WARNING: d8 missing — bridge.dex skip (HTML read-only)"
    return 0
  fi
  if [ -f "$ROOT_DIR/build/classes.dex" ]; then
    mv -f "$ROOT_DIR/build/classes.dex" "$ROOT_DIR/build/bridge.dex"
  fi
  echo "==> Built bridge.dex (WebView JS bridge)"
}
build_bridge_dex
if [ -f "$ROOT_DIR/build/bridge.dex" ]; then
  cp "$ROOT_DIR/build/bridge.dex" "$OUTPUT_DIR/bridge.dex"
fi

if [ -d "$ROOT_DIR/docs" ]; then
  cp -r "$ROOT_DIR/docs" "$OUTPUT_DIR/"
  echo "==> Bundled docs/ (Hinglish phase guides)"
fi

ZIP_NAME="hivirtus-zygisk-hook-$(grep '^version=' "$MODULE_DIR/module.prop" | cut -d= -f2).zip"
(cd "$OUTPUT_DIR" && zip -r "$ROOT_DIR/$ZIP_NAME" .)
echo ""
echo "Created $ROOT_DIR/$ZIP_NAME"
echo ""
echo "==> Done. hivirtus-zygisk-hook ZIP — native floating overlay (no APK)."
echo "    Zygisk ON → flash zip → reboot → UPI app / Messages → gold V bubble"
echo "    Docs: module/docs/README.md (Hinglish)"
