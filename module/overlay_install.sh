#!/system/bin/sh
# Hivirtus native overlay helpers — APK-free (v2.68+)

hivirtus_native_overlay_ready() {
  echo 1 > /data/local/tmp/hivirtus_native_overlay.active 2>/dev/null
  chmod 644 /data/local/tmp/hivirtus_native_overlay.active 2>/dev/null
  date +%s > /data/local/tmp/hivirtus_module_heartbeat.txt 2>/dev/null
  chmod 644 /data/local/tmp/hivirtus_module_heartbeat.txt 2>/dev/null
}

hivirtus_mark_foreground_upi() {
  pkg="$1"
  [ -z "$pkg" ] && return 1
  echo "$pkg" > /data/local/tmp/hivirtus_active_hook_pkg.txt 2>/dev/null
  chmod 644 /data/local/tmp/hivirtus_active_hook_pkg.txt 2>/dev/null
  echo "foreground:$pkg" >> /data/local/tmp/hivirtus_overlay.debug 2>/dev/null
  hivirtus_native_overlay_ready
}

# Legacy stubs — purane scripts se call na toote
hivirtus_install_overlay_apk() { return 0; }
hivirtus_grant_overlay_permission() { return 0; }
hivirtus_start_overlay_service() { hivirtus_native_overlay_ready; }
hivirtus_boot_activate_overlay() {
  echo "native_overlay_boot" >> /data/local/tmp/hivirtus_overlay.debug 2>/dev/null
  hivirtus_native_overlay_ready
}
