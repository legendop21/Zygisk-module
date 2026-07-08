#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$ROOT_DIR/overlay-app"

if [[ ! -d "$APP_DIR" ]]; then
  echo "ERROR: overlay-app directory missing"
  exit 1
fi

cd "$APP_DIR"
chmod +x gradlew
./gradlew assembleRelease
echo ""
echo "APK: $APP_DIR/app/build/outputs/apk/release/app-release.apk"
