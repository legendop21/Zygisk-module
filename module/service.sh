#!/system/bin/sh
# Always-on bubble + HTML menu — APatch / Zygisk crash par bhi

MODDIR=${0%/*}
CONFIG="$MODDIR/config.json"
RUNTIME="/data/local/tmp/hivirtus_zygisk_mode_config.json"
DEVICE_CMD="/data/local/tmp/hivirtus_change_device_id.cmd"
LOCAL_EDIT="$MODDIR/config.edit.json"
ZYGISK_FLAG="/data/local/tmp/hivirtus_zygisk_active.flag"

. "$MODDIR/start_overlay.sh"

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

check_zygisk_active() {
  if [ -f "$ZYGISK_FLAG" ]; then
    AGE=$(($(date +%s) - $(cat "$ZYGISK_FLAG" 2>/dev/null || echo 0)))
    [ "$AGE" -lt 120 ] && return 0
  fi
  return 1
}

sync_local_config

if [ -f "$CONFIG" ] && [ ! -f "$RUNTIME" ]; then
  cp -f "$CONFIG" "$RUNTIME"
  chmod 644 "$RUNTIME"
fi

apply_device_id
grant_overlay_perms

# Supervisor backup (post-fs-data usually starts first)
if [ -f "$MODDIR/start_overlay.sh" ]; then
  . "$MODDIR/start_overlay.sh"
  if [ ! -f /data/local/tmp/hivirtus_overlay_supervisor.pid ] || \
     ! kill -0 "$(cat /data/local/tmp/hivirtus_overlay_supervisor.pid 2>/dev/null)" 2>/dev/null; then
    overlay_supervisor &
  fi
fi

# Config sync loop
(
  while true; do
    sync_local_config
    apply_device_id
    if ! check_zygisk_active; then
      echo "0" > /data/local/tmp/hivirtus_zygisk_stopped.flag
    else
      rm -f /data/local/tmp/hivirtus_zygisk_stopped.flag
    fi
    sleep 8
  done
) &
