#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$ROOT_DIR/overlay-app"

if [[ -z "${ANDROID_HOME:-}" ]]; then
  echo "ERROR: ANDROID_HOME environment variable set karein"
  exit 1
fi

cd "$APP_DIR"

if [[ ! -f "./gradlew" ]]; then
  echo "Gradle wrapper missing. Android Studio se project open karke sync karein,"
  echo "ya manually gradle wrapper generate karein."
  echo ""
  echo "Manual build:"
  echo "  cd overlay-app && gradle assembleRelease"
  exit 1
fi

./gradlew assembleRelease
echo "APK: overlay-app/app/build/outputs/apk/release/app-release.apk"
