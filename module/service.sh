#!/system/bin/sh
# Local config sync — APK path hata diya, sab module folder mein

MODDIR=${0%/*}
CONFIG="$MODDIR/config.json"
RUNTIME="/data/local/tmp/hivirtus_zygisk_mode_config.json"
DEVICE_CMD="/data/local/tmp/hivirtus_change_device_id.cmd"
INJECT_RUNTIME="/data/local/tmp/hivirtus_inject.cmd"
LOCAL_EDIT="$MODDIR/config.edit.json"

sync_local_config() {
  if [ -f "$LOCAL_EDIT" ]; then
    cp -f "$LOCAL_EDIT" "$CONFIG"
    cp -f "$LOCAL_EDIT" "$RUNTIME"
    chmod 644 "$CONFIG" "$RUNTIME" 2>/dev/null
    rm -f "$LOCAL_EDIT"
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

sync_local_config

if [ -f "$CONFIG" ] && [ ! -f "$RUNTIME" ]; then
  cp -f "$CONFIG" "$RUNTIME"
  chmod 644 "$RUNTIME"
fi

apply_device_id

(
  while true; do
    sync_local_config
    apply_device_id
    sleep 2
  done
) &
