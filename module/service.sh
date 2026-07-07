#!/system/bin/sh
# Runs after boot — sync runtime config + device ID change watcher

MODDIR=${0%/*}
CONFIG="$MODDIR/config.json"
RUNTIME="/data/local/tmp/hivirtus_zygisk_mode_config.json"
DEVICE_CMD="/data/local/tmp/hivirtus_change_device_id.cmd"

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

if [ -f "$CONFIG" ]; then
  cp -f "$CONFIG" "$RUNTIME"
  chmod 644 "$RUNTIME"
fi

apply_device_id

# Live watcher — tap "Change Device ID" in overlay triggers cmd file
(
  while true; do
    apply_device_id
    sleep 3
  done
) &
