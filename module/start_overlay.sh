#!/system/bin/sh
# Overlay launcher — APK service (primary) + app_process (fallback)

MODDIR="${MODDIR:-/data/adb/modules/hivirtus_zygisk_mode}"
DEX="$MODDIR/overlay/overlay.dex"
APK="$MODDIR/overlay/virtus-float.apk"
OVERLAY_LOG="/data/local/tmp/hivirtus_overlay.log"
DAEMON_CLASS="com.hivirtus.zygiskmode.overlay.OverlayDaemon"
FLOAT_PKG="com.hivirtus.zygiskmode.floatsvc"
FLOAT_SVC="$FLOAT_PKG/.FloatService"
BOOT_FLAG="/data/local/tmp/hivirtus_boot_ready.flag"

log() {
  echo "$(date '+%m-%d %H:%M:%S') $*" >> "$OVERLAY_LOG"
}

wait_for_boot() {
  local i=0
  while [ "$i" -lt 120 ]; do
    if [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ]; then
      sleep 4
      date +%s > "$BOOT_FLAG" 2>/dev/null
      chmod 644 "$BOOT_FLAG" 2>/dev/null
      return 0
    fi
    i=$((i + 1))
    sleep 1
  done
  date +%s > "$BOOT_FLAG" 2>/dev/null
  return 0
}

grant_overlay_perms() {
  appops set com.android.systemui SYSTEM_ALERT_WINDOW allow 2>/dev/null
  appops set com.android.shell SYSTEM_ALERT_WINDOW allow 2>/dev/null
  appops set "$FLOAT_PKG" SYSTEM_ALERT_WINDOW allow 2>/dev/null
  cmd appops set com.android.systemui SYSTEM_ALERT_WINDOW allow 2>/dev/null
  cmd appops set com.android.shell SYSTEM_ALERT_WINDOW allow 2>/dev/null
  cmd appops set "$FLOAT_PKG" SYSTEM_ALERT_WINDOW allow 2>/dev/null
  cmd appops set --uid 2000 SYSTEM_ALERT_WINDOW allow 2>/dev/null
  pm grant "$FLOAT_PKG" android.permission.SYSTEM_ALERT_WINDOW 2>/dev/null
}

start_apk_overlay() {
  [ -f "$APK" ] || {
    log "APK missing at $APK"
    return 1
  }

  grant_overlay_perms

  if ! pm path "$FLOAT_PKG" >/dev/null 2>&1; then
    log "Installing virtus-float.apk"
    pm install -r -g -d "$APK" >>"$OVERLAY_LOG" 2>&1 || pm install -r "$APK" >>"$OVERLAY_LOG" 2>&1
  fi

  if ! pm path "$FLOAT_PKG" >/dev/null 2>&1; then
    log "APK install failed"
    return 1
  fi

  grant_overlay_perms

  am start-foreground-service -n "$FLOAT_SVC" >>"$OVERLAY_LOG" 2>&1 || \
  am startservice -n "$FLOAT_SVC" >>"$OVERLAY_LOG" 2>&1 || \
  am start-service -n "$FLOAT_SVC" >>"$OVERLAY_LOG" 2>&1

  sleep 3
  if pgrep -f "$FLOAT_PKG" >/dev/null 2>&1; then
    log "OK APK FloatService pid=$(pgrep -f FloatService | head -1)"
    return 0
  fi
  log "APK service start failed"
  return 1
}

launch_dex_daemon() {
  [ -f "$DEX" ] || return 1
  grant_overlay_perms
  mkdir -p /data/local/tmp/hivirtus_dex

  AP="app_process64"
  command -v app_process64 >/dev/null 2>&1 || AP="app_process"

  CLASSPATH="$DEX" BOOT_COMPLETED=1 \
    "$AP" /system/bin "$DAEMON_CLASS" >>"$OVERLAY_LOG" 2>&1 &
  sleep 4
  pgrep -f "$DAEMON_CLASS" >/dev/null 2>&1
}

launch_once() {
  if start_apk_overlay; then return 0; fi
  log "APK failed — trying dex daemon"
  if launch_dex_daemon; then
    log "OK dex daemon"
    return 0
  fi
  log "ERROR all overlay methods failed"
  return 1
}

start_overlay_daemon() {
  if pgrep -f "$FLOAT_PKG" >/dev/null 2>&1; then return 0; fi
  if pgrep -f "$DAEMON_CLASS" >/dev/null 2>&1; then return 0; fi
  launch_once
}

overlay_supervisor() {
  log "supervisor start uid=$(id -u) mod=$MODDIR"
  echo $$ > /data/local/tmp/hivirtus_overlay_supervisor.pid 2>/dev/null
  wait_for_boot
  log "boot ready"
  while true; do
    if ! pgrep -f "$FLOAT_PKG" >/dev/null 2>&1 && ! pgrep -f "$DAEMON_CLASS" >/dev/null 2>&1; then
      log "overlay dead — restart"
      launch_once
    fi
    sleep 8
  done
}
