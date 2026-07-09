#!/system/bin/sh
# Shared Virtus overlay APK installer — module ke andar bundled APK

OVERLAY_PKG="com.hivirtus.zygiskmode"
OVERLAY_SERVICE="$OVERLAY_PKG/.OverlayService"
OVERLAY_ACTION="com.hivirtus.zygiskmode.SHOW_BUBBLE"

hivirtus_overlay_apk_path() {
  if [ -n "${MODDIR:-}" ] && [ -f "$MODDIR/virtus-overlay.apk" ]; then
    echo "$MODDIR/virtus-overlay.apk"
    return 0
  fi
  if [ -f "/data/adb/modules/hivirtus_zygisk_mode/virtus-overlay.apk" ]; then
    echo "/data/adb/modules/hivirtus_zygisk_mode/virtus-overlay.apk"
    return 0
  fi
  return 1
}

hivirtus_install_overlay_apk() {
  APK=$(hivirtus_overlay_apk_path) || return 1
  if pm path "$OVERLAY_PKG" >/dev/null 2>&1; then
    pm install -r -g -d "$APK" >/dev/null 2>&1 || pm install -r -g "$APK" >/dev/null 2>&1
  else
    pm install -g -d "$APK" >/dev/null 2>&1 || pm install -g "$APK" >/dev/null 2>&1
  fi
  pm path "$OVERLAY_PKG" >/dev/null 2>&1
}

hivirtus_grant_overlay_permission() {
  appops set "$OVERLAY_PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
  cmd appops set "$OVERLAY_PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
}

hivirtus_start_overlay_service() {
  hivirtus_install_overlay_apk || return 1
  hivirtus_grant_overlay_permission
  am start-foreground-service -n "$OVERLAY_SERVICE" -a "$OVERLAY_ACTION" >/dev/null 2>&1 || \
    am startservice -n "$OVERLAY_SERVICE" -a "$OVERLAY_ACTION" >/dev/null 2>&1 || \
    cmd activity start-foreground-service -n "$OVERLAY_SERVICE" -a "$OVERLAY_ACTION" >/dev/null 2>&1
}

hivirtus_boot_activate_overlay() {
  echo "overlay_boot_start" >> /data/local/tmp/hivirtus_overlay.debug 2>/dev/null
  sleep 20
  n=0
  while [ "$n" -lt 8 ]; do
    if hivirtus_install_overlay_apk; then
      echo "apk_installed_ok" >> /data/local/tmp/hivirtus_overlay.debug 2>/dev/null
      break
    fi
    n=$((n + 1))
    sleep 12
  done
  hivirtus_grant_overlay_permission
  hivirtus_start_overlay_service
  echo "overlay_service_started" >> /data/local/tmp/hivirtus_overlay.debug 2>/dev/null
}
