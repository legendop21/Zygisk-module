#!/usr/bin/env bash
# Compile minimal floating overlay Java → overlay.dex (no APK)
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_SRC="$ROOT_DIR/module/overlay/java"
OUT_DIR="$ROOT_DIR/dist/overlay"
ANDROID_JAR="${ANDROID_JAR:-}"

if [[ -z "$ANDROID_JAR" ]]; then
  if [[ -n "${ANDROID_HOME:-}" ]] && [[ -d "$ANDROID_HOME/platforms" ]]; then
    ANDROID_JAR="$(ls -d "$ANDROID_HOME"/platforms/android-3*/android.jar 2>/dev/null | sort -V | tail -1)"
  fi
fi

if [[ -z "$ANDROID_JAR" ]] || [[ ! -f "$ANDROID_JAR" ]]; then
  echo "WARN: android.jar not found — skip overlay.dex (floating menu needs SDK)"
  exit 0
fi

D8="$(ls "$ANDROID_HOME/build-tools"/*/d8 2>/dev/null | sort -V | tail -1)"
if [[ -z "$D8" ]] || [[ ! -x "$D8" ]]; then
  echo "WARN: d8 not found — skip overlay.dex"
  exit 0
fi

rm -rf "$OUT_DIR/classes"
mkdir -p "$OUT_DIR/classes"

echo "==> Compiling overlay Java (embedded dex, ~50KB)"
find "$JAVA_SRC" -name '*.java' > "$OUT_DIR/sources.txt"
javac --release 17 -encoding UTF-8 -cp "$ANDROID_JAR" -d "$OUT_DIR/classes" @"$OUT_DIR/sources.txt"

rm -f "$OUT_DIR/overlay.dex"
"$D8" --release --output "$OUT_DIR" "$OUT_DIR/classes/com/hivirtus/zygiskmode/overlay/"*.class

echo "==> overlay.dex ready ($(du -h "$OUT_DIR/overlay.dex" | cut -f1))"
