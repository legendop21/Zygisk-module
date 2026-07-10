#!/system/bin/sh
# Bundled floating overlay — install APK, grant root perms, start bubble service

MODDIR="${1:-${0%/*}}"
PKG="com.hivirtus.zygiskmode"
APK="$MODDIR/overlay/hivirtus_overlay.apk"
LOG="/data/local/tmp/hivirtus_overlay_install.log"

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG"
}

install_overlay() {
  [ -f "$APK" ] || {
    log "APK missing: $APK"
    return 1
  }

  if pm path "$PKG" >/dev/null 2>&1; then
    log "Overlay already installed"
    return 0
  fi

  log "Installing floating overlay from module"
  if pm install -r -g "$APK" 2>>"$LOG"; then
    log "Installed with -g"
    return 0
  fi
  if pm install -r "$APK" 2>>"$LOG"; then
    log "Installed without -g"
    return 0
  fi

  log "pm install failed"
  return 1
}

grant_overlay_perms() {
  pm path "$PKG" >/dev/null 2>&1 || return 1

  appops set "$PKG" SYSTEM_ALERT_WINDOW allow 2>/dev/null
  pm grant "$PKG" android.permission.READ_SMS 2>/dev/null
  pm grant "$PKG" android.permission.RECEIVE_SMS 2>/dev/null
  pm grant "$PKG" android.permission.READ_PHONE_STATE 2>/dev/null
  pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null
  dumpsys deviceidle whitelist +"$PKG" 2>/dev/null
  cmd appops set "$PKG" RUN_IN_BACKGROUND allow 2>/dev/null
  cmd appops set "$PKG" RUN_ANY_IN_BACKGROUND allow 2>/dev/null
  log "Permissions granted"
}

start_floating_overlay() {
  pm path "$PKG" >/dev/null 2>&1 || return 1

  am broadcast -a "$PKG.BOOT_START" -p "$PKG" 2>/dev/null
  if [ $? -eq 0 ]; then
    log "Boot broadcast sent"
    return 0
  fi

  if am start-foreground-service -n "$PKG/.OverlayService" 2>/dev/null; then
    log "Foreground service started"
    return 0
  fi

  if am startservice -n "$PKG/.OverlayService" 2>/dev/null; then
    log "Service started"
    return 0
  fi

  log "Could not start overlay service"
  return 1
}

ACTION="${2:-all}"

case "$ACTION" in
  install)
    install_overlay
    ;;
  perms)
    grant_overlay_perms
    ;;
  start)
    start_floating_overlay
    ;;
  all)
    install_overlay && grant_overlay_perms
  ;;
esac
