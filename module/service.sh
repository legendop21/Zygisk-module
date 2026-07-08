#!/system/bin/sh
# Runs after boot — sync runtime config + device ID change watcher

MODDIR=${0%/*}
CONFIG="$MODDIR/config.json"
RUNTIME="/data/local/tmp/hivirtus_zygisk_mode_config.json"
DEVICE_CMD="/data/local/tmp/hivirtus_change_device_id.cmd"
INJECT_RUNTIME="/data/local/tmp/hivirtus_inject.cmd"
APP_DIR="/sdcard/Android/data/com.hivirtus.zygiskmode/files"
APP_CONFIG="$APP_DIR/hivirtus_zygisk_mode_config.json"
APP_INJECT="$APP_DIR/hivirtus_inject.cmd"
APP_OTP="$APP_DIR/hivirtus_last_otp.json"

sync_app_config() {
  if [ -f "$APP_CONFIG" ]; then
    cp -f "$APP_CONFIG" "$RUNTIME"
    cp -f "$APP_CONFIG" "$CONFIG"
    chmod 644 "$RUNTIME" 2>/dev/null
    chmod 644 "$CONFIG" 2>/dev/null
  fi
  if [ -f "$APP_INJECT" ]; then
    cp -f "$APP_INJECT" "$INJECT_RUNTIME"
    chmod 644 "$INJECT_RUNTIME" 2>/dev/null
  fi
  if [ -f "$APP_OTP" ]; then
    cp -f "$APP_OTP" "/data/local/tmp/hivirtus_last_otp.json"
    chmod 644 "/data/local/tmp/hivirtus_last_otp.json" 2>/dev/null
  fi
}

apply_device_id() {
  [ -f "$DEVICE_CMD" ] || return 0
  NEW_ID=$(grep -o 'CHANGE_ID|.*' "$DEVICE_CMD" 2>/dev/null | cut -d'|' -f2 | tr -d '[:space:]')
  rm -f "$DEVICE_CMD"
  [ -n "$NEW_ID" ] || return 0

  settings put secure android_id "$NEW_ID" 2>/dev/null
  resetprop ro.serialno "$NEW_ID" 2>/dev/null
  resetprop ro.boot.serialno "$NEW_ID" 2>/dev/null
  echo "$NEW_ID" > /data/local/tmp/hivirtus_spoof_android_id.txt
  chmod 644 /data/local/tmp/hivirtus_spoof_android_id.txt 2>/dev/null
}

sync_app_config

if [ -f "$CONFIG" ] && [ ! -f "$RUNTIME" ]; then
  cp -f "$CONFIG" "$RUNTIME"
  chmod 644 "$RUNTIME"
fi

apply_device_id

# Live watcher — overlay saves + device ID cmd
(
  while true; do
    sync_app_config
    apply_device_id
    sleep 2
  done
) &
