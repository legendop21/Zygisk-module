#!/system/bin/sh
# Multi-root: Magisk | KernelSU | KernelSU Next | APatch | SukiSU Ultra
# Boot pe sirf config sync — resetprop / denylist NAHI (Zygisk Next + LSPosed safe)

MODDIR=${0%/*}
. "$MODDIR/overlay_install.sh" 2>/dev/null

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

# Virtual SIM: spoof phone + capture real SIM line1 for native scrub
read_json_field() {
  grep -o "\"$1\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" "$CONFIG" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/'
}

if [ -f "$CONFIG" ]; then
  SPOOF_PHONE=$(read_json_field "mock_phone_sim1")
  if [ -n "$SPOOF_PHONE" ]; then
    echo "$SPOOF_PHONE" > /data/local/tmp/hivirtus_spoof_phone.txt
    echo "$SPOOF_PHONE" > "$MODDIR/spoof_phone.txt"
    chmod 644 /data/local/tmp/hivirtus_spoof_phone.txt 2>/dev/null
    chmod 644 "$MODDIR/spoof_phone.txt" 2>/dev/null
  fi
fi

REAL_LINE=""
if command -v cmd >/dev/null 2>&1; then
  REAL_LINE=$(cmd phone get-line1-number 2>/dev/null | tr -d '\r\n ')
fi
if [ -z "$REAL_LINE" ]; then
  REAL_LINE=$(getprop persist.radio.line1 2>/dev/null)
fi
if [ -z "$REAL_LINE" ]; then
  REAL_LINE=$(getprop ril.gsm.phone.number 2>/dev/null)
fi
if [ -n "$REAL_LINE" ]; then
  echo "$REAL_LINE" > /data/local/tmp/hivirtus_real_phone.txt
  echo "$REAL_LINE" > "$MODDIR/real_phone.txt"
  chmod 644 /data/local/tmp/hivirtus_real_phone.txt 2>/dev/null
  chmod 644 "$MODDIR/real_phone.txt" 2>/dev/null
fi

echo "module_boot_v2.38.0" > /data/local/tmp/hivirtus_overlay.debug
chmod 644 /data/local/tmp/hivirtus_overlay.debug 2>/dev/null
echo "module_boot_v2.38.0" > /data/local/tmp/hivirtus_inject.log
chmod 644 /data/local/tmp/hivirtus_inject.log 2>/dev/null

# APK module ke andar — boot pe auto install
hivirtus_install_overlay_apk && echo "apk_post_fs_ok" >> /data/local/tmp/hivirtus_overlay.debug
hivirtus_grant_overlay_permission

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
