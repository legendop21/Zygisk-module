#!/system/bin/sh
# Multi-root: Magisk | KernelSU | KernelSU Next | APatch | SukiSU Ultra
# Boot pe sirf config sync — resetprop / denylist NAHI (Zygisk Next + LSPosed safe)

MODDIR=${0%/*}
CONFIG="$MODDIR/config.json"
RUNTIME="/data/local/tmp/hivirtus_zygisk_mode_config.json"
ROOT_TYPE_FILE="/data/local/tmp/hivirtus_root_type.txt"
BOOT_HIDE_FLAG="/data/local/tmp/hivirtus_boot_hide_enabled"

if [ -f "$CONFIG" ]; then
  cp -f "$CONFIG" "$RUNTIME"
  chmod 644 "$RUNTIME"
fi

read_bool() {
  grep -o "\"$1\"[[:space:]]*:[[:space:]]*[a-z]*" "$CONFIG" 2>/dev/null | grep -o 'true\|false' | head -n1
}

ROOT_TYPE="Unknown"
if [ -f /data/adb/magisk.db ] || [ -x /data/adb/magisk/magisk ] || command -v magisk >/dev/null 2>&1; then
  ROOT_TYPE="Magisk"
elif [ -d /data/adb/apatch ] || [ -x /data/adb/apd/apd ] || command -v apd >/dev/null 2>&1; then
  ROOT_TYPE="APatch"
elif [ -d /data/adb/sukisu ] || [ -d /data/adb/suki ] || [ -f /data/adb/sukisu/sukisu ]; then
  ROOT_TYPE="SukiSU Ultra"
elif [ -d /data/adb/ksu ] || [ -x /data/adb/ksu/bin/ksud ] || [ -f /dev/kernelsu ]; then
  if [ -f /data/adb/ksu/bin/ksud ]; then
    ROOT_TYPE="KernelSU Next"
  else
    ROOT_TYPE="KernelSU"
  fi
fi

echo "$ROOT_TYPE" > "$ROOT_TYPE_FILE"
chmod 644 "$ROOT_TYPE_FILE"

date +%s > /data/local/tmp/hivirtus_module_heartbeat.txt
chmod 644 /data/local/tmp/hivirtus_module_heartbeat.txt 2>/dev/null
echo "1" > /data/local/tmp/hivirtus_module_installed.flag
chmod 644 /data/local/tmp/hivirtus_module_installed.flag 2>/dev/null
echo "1" > /data/local/tmp/hivirtus_zygisk_native.active
chmod 644 /data/local/tmp/hivirtus_zygisk_native.active 2>/dev/null

# Root hide sirf jab user ne app se ON kiya ho — warna Zygisk Next / LSPosed boot pe break ho jate hain
HIDE_ROOT=$(read_bool "hide_root")
HIDE_DEV=$(read_bool "hide_developer")
if [ ! -f "$BOOT_HIDE_FLAG" ]; then
  HIDE_ROOT="false"
  HIDE_DEV="false"
fi

if [ "$HIDE_ROOT" = "true" ] || [ "$HIDE_DEV" = "true" ]; then
  apply_props() {
    resetprop -n ro.debuggable 0 2>/dev/null
    resetprop -n ro.secure 1 2>/dev/null
    resetprop -n ro.adb.secure 1 2>/dev/null
    resetprop -n ro.build.selinux 1 2>/dev/null
    resetprop -n ro.build.tags release-keys 2>/dev/null
    resetprop -n ro.build.type user 2>/dev/null
  }

  if [ "$HIDE_ROOT" = "true" ]; then
    apply_props
    if [ "$ROOT_TYPE" = "Magisk" ]; then
      magisk --denylist enable 2>/dev/null
      magisk --denylist add com.topjohnwu.magisk 2>/dev/null
    fi
  fi

  if [ "$HIDE_DEV" = "true" ]; then
    resetprop -n ro.debuggable 0 2>/dev/null
    resetprop -n persist.sys.usb.config none 2>/dev/null
  fi
fi
