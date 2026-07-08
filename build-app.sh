#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$ROOT_DIR/overlay-app"
LOCAL_PROPS="$APP_DIR/local.properties"

# Auto-detect Android SDK on Mac
if [[ -z "${ANDROID_HOME:-}" ]]; then
  if [[ -f "$LOCAL_PROPS" ]]; then
    SDK=$(grep -E '^sdk\.dir=' "$LOCAL_PROPS" | cut -d= -f2- | tr -d '\r')
    SDK="${SDK//\\:/:}"
    if [[ -d "$SDK" ]]; then
      export ANDROID_HOME="$SDK"
    fi
  fi
fi

if [[ -z "${ANDROID_HOME:-}" ]]; then
  for CAND in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [[ -d "$CAND" ]]; then
      export ANDROID_HOME="$CAND"
      break
    fi
  done
fi

if [[ -z "${ANDROID_HOME:-}" ]] || [[ ! -d "$ANDROID_HOME" ]]; then
  echo "ERROR: Android SDK not found."
  echo "Mac pe: export ANDROID_HOME=\$HOME/Library/Android/sdk"
  exit 1
fi

echo "==> Using SDK: $ANDROID_HOME"

cd "$APP_DIR"

if [[ ! -f "./gradlew" ]]; then
  echo "Gradle wrapper missing."
  exit 1
fi

./gradlew clean assembleRelease
echo ""
echo "APK ready:"
echo "  $APP_DIR/app/build/outputs/apk/release/app-release.apk"
