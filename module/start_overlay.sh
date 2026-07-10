#!/system/bin/sh
# Shared overlay daemon launcher — service.sh + post-fs-data dono use karte hain

MODDIR="${MODDIR:-/data/adb/modules/hivirtus_zygisk_mode}"
DEX="$MODDIR/overlay/overlay.dex"
OVERLAY_LOG="/data/local/tmp/hivirtus_overlay.log"
DAEMON_CLASS="com.hivirtus.zygiskmode.overlay.OverlayDaemon"

grant_overlay_perms() {
  appops set com.android.systemui SYSTEM_ALERT_WINDOW allow 2>/dev/null
  appops set com.android.shell SYSTEM_ALERT_WINDOW allow 2>/dev/null
  cmd appops set com.android.systemui SYSTEM_ALERT_WINDOW allow 2>/dev/null
  cmd appops set com.android.shell SYSTEM_ALERT_WINDOW allow 2>/dev/null
}

start_overlay_daemon() {
  [ -f "$DEX" ] || {
    echo "$(date) overlay.dex missing at $DEX" >> "$OVERLAY_LOG"
    return 1
  }

  if pgrep -f "$DAEMON_CLASS" >/dev/null 2>&1; then
    return 0
  fi

  grant_overlay_perms
  mkdir -p /data/local/tmp/hivirtus_dex

  AP="app_process64"
  command -v app_process64 >/dev/null 2>&1 || AP="app_process"

  CLASSPATH="$DEX" \
  nohup "$AP" /system/bin "$DAEMON_CLASS" >>"$OVERLAY_LOG" 2>&1 &

  echo "$(date) started OverlayDaemon via $AP pid=$!" >> "$OVERLAY_LOG"
}
