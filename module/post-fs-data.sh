#!/system/bin/sh
# Multi-root: Magisk | KernelSU | KernelSU Next | APatch | SukiSU Ultra
# Boot pe sirf config sync — resetprop / denylist NAHI (Zygisk Next + LSPosed safe)

MODDIR=${0%/*}
. "$MODDIR/overlay_install.sh" 2>/dev/null

CONFIG="$MODDIR/config.json"
RUNTIME="/data/local/tmp/hivirtus_zygisk_mode_config.json"
ROOT_TYPE_FILE="/data/local/tmp/hivirtus_root_type.txt"
BOOT_HIDE_FLAG="/data/local/tmp/hivirtus_boot_hide_enabled"

if [ ! -f "$RUNTIME" ] && [ -f "$CONFIG" ]; then
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

# Virtual SIM capture DISABLED — cmd phone boot pe SIM disturb kar sakta hai
# REAL_LINE capture removed in v1.0.6 SAFE

# Boot marker — version from module.prop (purana v1.0.6 string hata diya)
VER=$(grep '^version=' "$MODDIR/module.prop" 2>/dev/null | cut -d= -f2)
[ -z "$VER" ] && VER="v1.0.43"
echo "module_boot_${VER}" > /data/local/tmp/hivirtus_overlay.debug
chmod 666 /data/local/tmp/hivirtus_overlay.debug 2>/dev/null
# Inject log: boot line likho, purani safe_inject lines mat mitao completely —
# lekin boot pe fresh start so user clearly dekhe new version
{
  echo "module_boot_${VER}"
  echo "root=${ROOT_TYPE}"
  echo "waiting_for_upi_app_open"
} > /data/local/tmp/hivirtus_inject.log
chmod 666 /data/local/tmp/hivirtus_inject.log 2>/dev/null
echo "$VER" > /data/local/tmp/hivirtus_module_version.txt
chmod 644 /data/local/tmp/hivirtus_module_version.txt 2>/dev/null

# MUST run before any UPI app Save — create TG/save placeholders as 0666
hivirtus_seed_app_writable_files

# Zygisk Next Enforced = no UPI inject (log stuck on waiting_for_upi_app_open)
hivirtus_fix_zn_denylist
hivirtus_apatch_allow_upi_inject

# v1.0.6+: Native overlay only — legacy APK cleanup
if command -v pm >/dev/null 2>&1 && pm path com.hivirtus.zygiskmode >/dev/null 2>&1; then
  pm uninstall com.hivirtus.zygiskmode 2>/dev/null || true
fi

# CRITICAL: repair APatch/Magisk so phone/settings never get Zygisk
hivirtus_repair_sim_settings
hivirtus_boot_activate_overlay
hivirtus_grant_overlay_permission

# Root hide OFF by default — phone crash avoid
HIDE_ROOT="false"
HIDE_DEV="false"
if [ -f "$BOOT_HIDE_FLAG" ]; then
  HIDE_ROOT=$(read_bool "hide_root")
  HIDE_DEV=$(read_bool "hide_developer")
fi

if false; then
  :
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
  fi

  if [ "$HIDE_DEV" = "true" ]; then
    resetprop -n ro.debuggable 0 2>/dev/null
    resetprop -n persist.sys.usb.config none 2>/dev/null
  fi
fi
