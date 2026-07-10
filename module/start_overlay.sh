#!/system/bin/sh
# ZIP-only overlay — embedded overlay.dex via app_process (koi APK nahi)

MODDIR="${MODDIR:-/data/adb/modules/hivirtus_zygisk_mode}"
DEX_MOD="$MODDIR/overlay/overlay.dex"
DEX_TMP="/data/local/tmp/hivirtus_overlay/overlay.dex"
OVERLAY_LOG="/data/local/tmp/hivirtus_overlay.log"
DAEMON_CLASS="com.hivirtus.zygiskmode.overlay.OverlayDaemon"
BOOT_FLAG="/data/local/tmp/hivirtus_boot_ready.flag"
OPT_DIR="/data/local/tmp/hivirtus_overlay"

log() {
  echo "$(date '+%m-%d %H:%M:%S') $*" >> "$OVERLAY_LOG"
}

sync_overlay_assets() {
  mkdir -p "$OPT_DIR" /data/local/tmp/hivirtus_dex
  if [ -f "$DEX_MOD" ]; then
    cp -f "$DEX_MOD" "$DEX_TMP" 2>/dev/null
    cp -f "$DEX_MOD" /data/local/tmp/hivirtus_dex/overlay.dex 2>/dev/null
  fi
  if [ -d "$MODDIR/overlay/ui" ]; then
    mkdir -p "$OPT_DIR/ui"
    cp -rf "$MODDIR/overlay/ui/"* "$OPT_DIR/ui/" 2>/dev/null
  fi
  chmod -R 755 "$OPT_DIR" 2>/dev/null
  chmod 644 "$DEX_TMP" /data/local/tmp/hivirtus_dex/overlay.dex 2>/dev/null
  chcon -R u:object_r:shell_data_file:s0 "$OPT_DIR" 2>/dev/null
  chcon u:object_r:shell_data_file:s0 /data/local/tmp/hivirtus_dex/overlay.dex 2>/dev/null
}

resolve_dex() {
  if [ -f "$DEX_TMP" ]; then echo "$DEX_TMP"; return; fi
  if [ -f "$DEX_MOD" ]; then echo "$DEX_MOD"; return; fi
  echo ""
}

wait_for_boot() {
  local i=0
  while [ "$i" -lt 120 ]; do
    if [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ]; then
      sleep 5
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
  cmd appops set --uid 0 SYSTEM_ALERT_WINDOW allow 2>/dev/null
}

spawn_daemon() {
  _DEX="$1"
  _AP="$2"
  _ARG="$3"
  log "spawn $_AP $_ARG dex=$_DEX"
  (
    export CLASSPATH="$_DEX"
    export BOOT_COMPLETED=1
    if [ "$(id -u)" = "0" ]; then
      exec $_AP $_ARG "$DAEMON_CLASS"
    else
      su 0 sh -c "export CLASSPATH='$_DEX'; export BOOT_COMPLETED=1; exec $_AP $_ARG $DAEMON_CLASS"
    fi
  ) >>"$OVERLAY_LOG" 2>&1 &
}

try_app_process() {
  DEX="$1"
  AP="$2"
  ARG="$3"
  spawn_daemon "$DEX" "$AP" "$ARG"
  sleep 5
  pgrep -f "$DAEMON_CLASS" >/dev/null 2>&1
}

launch_dex_daemon() {
  sync_overlay_assets
  DEX="$(resolve_dex)"
  [ -n "$DEX" ] || { log "ERROR overlay.dex missing"; return 1; }

  grant_overlay_perms

  AP64="app_process64"
  AP32="app_process"
  command -v app_process64 >/dev/null 2>&1 || AP64="/system/bin/app_process64"
  command -v app_process >/dev/null 2>&1 || AP32="/system/bin/app_process"

  if try_app_process "$DEX" "$AP64" "/system/bin"; then
    log "OK method1 pid=$(pgrep -f OverlayDaemon | head -1)"
    return 0
  fi
  if try_app_process "$DEX" "$AP64" "/ --nice-name=virtus_overlay"; then
    log "OK method2 pid=$(pgrep -f OverlayDaemon | head -1)"
    return 0
  fi
  if try_app_process "$DEX" "$AP64" "-Djava.class.path=$DEX /system/bin"; then
    log "OK method3 pid=$(pgrep -f OverlayDaemon | head -1)"
    return 0
  fi
  if try_app_process "$DEX" "$AP32" "/system/bin"; then
    log "OK method4 pid=$(pgrep -f OverlayDaemon | head -1)"
    return 0
  fi

  log "ERROR all app_process methods failed"
  return 1
}

start_overlay_daemon() {
  if pgrep -f "$DAEMON_CLASS" >/dev/null 2>&1; then return 0; fi
  launch_dex_daemon
}

overlay_supervisor() {
  log "supervisor ZIP-only uid=$(id -u) mod=$MODDIR"
  echo $$ > /data/local/tmp/hivirtus_overlay_supervisor.pid 2>/dev/null
  sync_overlay_assets
  wait_for_boot
  log "boot ready"
  while true; do
    if ! pgrep -f "$DAEMON_CLASS" >/dev/null 2>&1; then
      log "daemon dead — restart"
      launch_dex_daemon
    fi
    sleep 6
  done
}
