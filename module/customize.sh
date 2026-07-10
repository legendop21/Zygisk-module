#!/system/bin/sh
# Virtus Zygisk Mode — SAFE install (v1.0.6)

ui_print "*******************************"
ui_print "   Virtus Zygisk Mode           "
ui_print "     v1.0.15                    "
ui_print "  FIX: BinderProxy transactNative"
ui_print "  GPay SMS block + TG + phone   "
ui_print "  UPI-only · No phone hook      "
ui_print "  @Hivirtus                     "
ui_print "*******************************"

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

# Always refresh safe defaults (don't enable phone spoof / root hide)
cat > "$MODPATH/config.json" << 'EOF'
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
  "telegram_bot_token": "",
  "telegram_chat_id": "",
  "hook_all_upi_apps": true,
  "auto_hook_foreground": true,
  "log_file": "/data/local/tmp/virtus_zygisk_mode.log"
}
EOF
set_perm "$MODPATH/config.json" 0 0 0644
# Wipe runtime spoof that triggers bad paths
rm -f /data/local/tmp/hivirtus_spoof_phone.txt 2>/dev/null
rm -f /data/local/tmp/hivirtus_hooked_pkgs.txt 2>/dev/null
cp -f "$MODPATH/config.json" /data/local/tmp/hivirtus_zygisk_mode_config.json 2>/dev/null
chmod 644 /data/local/tmp/hivirtus_zygisk_mode_config.json 2>/dev/null

if [ -d /data/adb/ksu ] || [ -f /dev/kernelsu ]; then
  ui_print "- KernelSU detected"
elif [ -f /data/adb/magisk.db ]; then
  ui_print "- Magisk detected"
elif [ -d /data/adb/ap ] || [ -d /data/adb/apatch ]; then
  ui_print "- APatch detected — phone/settings excluded"
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
