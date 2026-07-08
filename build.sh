#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
MODULE_DIR="$ROOT_DIR/module"
OUTPUT_DIR="$ROOT_DIR/dist"
ABI_LIST="${ABI_LIST:-arm64-v8a armeabi-v7a x86 x86_64}"

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
cp "$MODULE_DIR/uninstall.sh" "$OUTPUT_DIR/"
if [ -d "$MODULE_DIR/META-INF" ]; then
  cp -r "$MODULE_DIR/META-INF" "$OUTPUT_DIR/"
fi

chmod 755 "$OUTPUT_DIR/customize.sh" "$OUTPUT_DIR/service.sh" "$OUTPUT_DIR/post-fs-data.sh"
[ -f "$OUTPUT_DIR/uninstall.sh" ] && chmod 755 "$OUTPUT_DIR/uninstall.sh"

ZIP_NAME="hivirtus-magisk-$(grep '^version=' "$MODULE_DIR/module.prop" | cut -d= -f2).zip"
(cd "$OUTPUT_DIR" && zip -r "$ROOT_DIR/$ZIP_NAME" .)
echo ""
echo "Created $ROOT_DIR/$ZIP_NAME"
echo ""
echo "==> Done. Sirf YE EK ZIP flash karo (Magisk → Modules)."
echo "    Zygisk setting ON → reboot → UPI app kholo."
echo "    Alag Zygisk Next / LSPosed / APK zip ki zaroorat NAHI."
