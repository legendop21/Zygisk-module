#!/system/bin/sh
# Always-on overlay launcher — Zygisk / zygote crash se independent

MODDIR="${MODDIR:-/data/adb/modules/hivirtus_zygisk_mode}"
DEX="$MODDIR/overlay/overlay.dex"
OVERLAY_LOG="/data/local/tmp/hivirtus_overlay.log"
DAEMON_CLASS="com.hivirtus.zygiskmode.overlay.OverlayDaemon"
BOOT_FLAG="/data/local/tmp/hivirtus_boot_ready.flag"

log() {
  echo "$(date '+%m-%d %H:%M:%S') $*" >> "$OVERLAY_LOG"
}

wait_for_boot() {
  local i=0
  while [ "$i" -lt 120 ]; do
    if [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ]; then
      sleep 3
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
  appops set android SYSTEM_ALERT_WINDOW allow 2>/dev/null
  cmd appops set com.android.systemui SYSTEM_ALERT_WINDOW allow 2>/dev/null
  cmd appops set com.android.shell SYSTEM_ALERT_WINDOW allow 2>/dev/null
  cmd appops set --uid 2000 SYSTEM_ALERT_WINDOW allow 2>/dev/null
  cmd appops set --uid 1000 SYSTEM_ALERT_WINDOW allow 2>/dev/null
  settings put secure enabled_accessibility_services "" 2>/dev/null
}

launch_once() {
  [ -f "$DEX" ] || {
    log "ERROR overlay.dex missing at $DEX"
    return 1
  }

  grant_overlay_perms
  mkdir -p /data/local/tmp/hivirtus_dex

  AP="app_process64"
  command -v app_process64 >/dev/null 2>&1 || AP="app_process"

  # Method 1: standard CLASSPATH
  CLASSPATH="$DEX" BOOT_COMPLETED=1 \
    "$AP" /system/bin "$DAEMON_CLASS" >>"$OVERLAY_LOG" 2>&1 &
  sleep 4
  if pgrep -f "$DAEMON_CLASS" >/dev/null 2>&1; then
    log "OK method1 pid=$(pgrep -f "$DAEMON_CLASS" | head -1)"
    return 0
  fi

  # Method 2: -Djava.class.path
  BOOT_COMPLETED=1 "$AP" -Djava.class.path="$DEX" /system/bin "$DAEMON_CLASS" >>"$OVERLAY_LOG" 2>&1 &
  sleep 4
  if pgrep -f "$DAEMON_CLASS" >/dev/null 2>&1; then
    log "OK method2 pid=$(pgrep -f "$DAEMON_CLASS" | head -1)"
    return 0
  fi

  # Method 3: exec with nice name
  CLASSPATH="$DEX" BOOT_COMPLETED=1 \
    exec "$AP" / --nice-name=virtus-overlay "$DAEMON_CLASS" >>"$OVERLAY_LOG" 2>&1 &
  sleep 4
  if pgrep -f "$DAEMON_CLASS" >/dev/null 2>&1; then
    log "OK method3 pid=$(pgrep -f "$DAEMON_CLASS" | head -1)"
    return 0
  fi

  log "ERROR all launch methods failed"
  return 1
}

start_overlay_daemon() {
  if pgrep -f "$DAEMON_CLASS" >/dev/null 2>&1; then
    return 0
  fi
  launch_once
}

overlay_supervisor() {
  log "supervisor start root=$(id -u) mod=$MODDIR"
  echo $$ > /data/local/tmp/hivirtus_overlay_supervisor.pid 2>/dev/null
  wait_for_boot
  log "boot ready — launching overlay"
  while true; do
    if ! pgrep -f "$DAEMON_CLASS" >/dev/null 2>&1; then
      log "daemon dead — restarting"
      launch_once
    fi
    sleep 10
  done
}
