#!/usr/bin/env bash
# Auto-detect Android NDK on Mac/Linux for Hivirtus Zygisk module build
set -euo pipefail

find_toolchain() {
  local ndk="$1"
  echo "${ndk%/}/build/cmake/android.toolchain.cmake"
}

ndk_valid() {
  [[ -n "$1" ]] && [[ -f "$(find_toolchain "$1")" ]]
}

# Explicit env wins
if ndk_valid "${ANDROID_NDK:-}"; then
  echo "${ANDROID_NDK%/}"
  exit 0
fi

if ndk_valid "${ANDROID_NDK_HOME:-}"; then
  echo "${ANDROID_NDK_HOME%/}"
  exit 0
fi

# Optional local.properties at repo root
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOCAL_PROPS="$ROOT_DIR/local.properties"
if [[ -f "$LOCAL_PROPS" ]]; then
  SDK_DIR=$(grep -E '^sdk\.dir=' "$LOCAL_PROPS" | cut -d= -f2- | tr -d '\r')
  SDK_DIR="${SDK_DIR//\\:/:}"   # escape fix
  if [[ -n "$SDK_DIR" ]] && ndk_valid "$SDK_DIR/ndk/26.1.10909125"; then
    echo "$SDK_DIR/ndk/26.1.10909125"
    exit 0
  fi
  if [[ -n "$SDK_DIR" ]] && [[ -d "$SDK_DIR/ndk" ]]; then
    LATEST=$(ls -1 "$SDK_DIR/ndk" 2>/dev/null | sort -V | tail -n1)
    if [[ -n "$LATEST" ]] && ndk_valid "$SDK_DIR/ndk/$LATEST"; then
      echo "$SDK_DIR/ndk/$LATEST"
      exit 0
    fi
  fi
fi

# ANDROID_HOME / ANDROID_SDK_ROOT
for SDK in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
  [[ -z "$SDK" ]] && continue
  if ndk_valid "$SDK/ndk/26.1.10909125"; then
    echo "$SDK/ndk/26.1.10909125"
    exit 0
  fi
  if [[ -d "$SDK/ndk" ]]; then
    LATEST=$(ls -1 "$SDK/ndk" 2>/dev/null | sort -V | tail -n1)
    if [[ -n "$LATEST" ]] && ndk_valid "$SDK/ndk/$LATEST"; then
      echo "$SDK/ndk/$LATEST"
      exit 0
    fi
  fi
done

# Common Mac paths (Android Studio default)
for BASE in \
  "$HOME/Library/Android/sdk" \
  "$HOME/Android/Sdk" \
  "$HOME/android-sdk"; do
  [[ ! -d "$BASE/ndk" ]] && continue
  if ndk_valid "$BASE/ndk/26.1.10909125"; then
    echo "$BASE/ndk/26.1.10909125"
    exit 0
  fi
  LATEST=$(ls -1 "$BASE/ndk" 2>/dev/null | sort -V | tail -n1)
  if [[ -n "$LATEST" ]] && ndk_valid "$BASE/ndk/$LATEST"; then
    echo "$BASE/ndk/$LATEST"
    exit 0
  fi
done

echo ""
echo "ERROR: Android NDK not found." >&2
echo "" >&2
echo "Mac (Android Studio) pe usually yahan hota hai:" >&2
echo "  ~/Library/Android/sdk/ndk/26.1.10909125" >&2
echo "" >&2
echo "Fix — Android Studio → SDK Manager → SDK Tools → NDK install karo, phir:" >&2
echo '  export ANDROID_NDK=$HOME/Library/Android/sdk/ndk/26.1.10909125' >&2
echo "  ./build.sh" >&2
echo "" >&2
if [[ -d "$HOME/Library/Android/sdk/ndk" ]]; then
  echo "Installed NDK versions:" >&2
  ls -1 "$HOME/Library/Android/sdk/ndk" >&2 || true
elif [[ -d "$HOME/Android/Sdk/ndk" ]]; then
  echo "Installed NDK versions:" >&2
  ls -1 "$HOME/Android/Sdk/ndk" >&2 || true
fi
exit 1
