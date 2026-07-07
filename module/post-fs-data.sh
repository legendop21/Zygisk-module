#!/system/bin/sh
# Safe boot-time prop reset — no system partition modify, no bootloop risk

MODDIR=${0%/*}
CONFIG="$MODDIR/config.json"
RUNTIME="/data/local/tmp/hivirtus_zygisk_mode_config.json"

if [ -f "$CONFIG" ]; then
  cp -f "$CONFIG" "$RUNTIME"
  chmod 644 "$RUNTIME"
fi

read_bool() {
  grep -o "\"$1\"[[:space:]]*:[[:space:]]*[a-z]*" "$CONFIG" 2>/dev/null | grep -o 'true\|false' | head -n1
}

HIDE_ROOT=$(read_bool "hide_root")
HIDE_DEV=$(read_bool "hide_developer")

if [ "$HIDE_ROOT" = "true" ]; then
  resetprop -n ro.debuggable 0 2>/dev/null
  resetprop -n ro.secure 1 2>/dev/null
  resetprop -n ro.adb.secure 1 2>/dev/null
  resetprop -n ro.build.selinux 1 2>/dev/null
  resetprop -n ro.build.tags release-keys 2>/dev/null
fi

if [ "$HIDE_DEV" = "true" ]; then
  resetprop -n ro.debuggable 0 2>/dev/null
  resetprop -n persist.sys.usb.config none 2>/dev/null
fi

# Magisk denylist unmount helper — safe, Magisk built-in
if [ -f /data/adb/magisk.db ]; then
  magisk --denylist enable 2>/dev/null
fi
