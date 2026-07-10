#!/system/bin/sh
# Virtus Zygisk Mode — SMSTweaks-style hooks + floating HTML menu (no LSPosed)

ui_print "*******************************"
ui_print "   Virtus Zygisk Mode           "
ui_print "        v1.0.2                  "
ui_print "  Floating menu · SMS intercept "
ui_print "  No LSPosed · 32+64 Zygisk     "
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
[ -f "$MODPATH/post-fs-data.sh" ] && set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
[ -f "$MODPATH/service.sh" ] && set_perm "$MODPATH/service.sh" 0 0 0755
[ -f "$MODPATH/customize.sh" ] && set_perm "$MODPATH/customize.sh" 0 0 0755
[ -f "$MODPATH/config.json" ] && set_perm "$MODPATH/config.json" 0 0 0644
[ -f "$MODPATH/module.prop" ] && set_perm "$MODPATH/module.prop" 0 0 0644
[ -f "$MODPATH/overlay_install.sh" ] && set_perm "$MODPATH/overlay_install.sh" 0 0 0755

. "$MODPATH/overlay_install.sh" 2>/dev/null
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

if [ ! -f "$MODPATH/config.json" ]; then
  ui_print "- Creating default config"
  cat > "$MODPATH/config.json" << 'EOF'
{
  "hide_root": true,
  "hide_developer": true,
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
fi

if [ -d /data/adb/ksu ] || [ -f /dev/kernelsu ]; then
  ui_print "- KernelSU detected"
elif [ -f /data/adb/magisk.db ]; then
  ui_print "- Magisk detected"
elif [ -d /data/adb/apatch ]; then
  ui_print "- APatch detected"
fi

echo "1" > /data/local/tmp/hivirtus_module_installed.flag
chmod 644 /data/local/tmp/hivirtus_module_installed.flag 2>/dev/null
echo "1" > /data/local/tmp/hivirtus_zygisk_native.active
chmod 644 /data/local/tmp/hivirtus_zygisk_native.active 2>/dev/null

ui_print ""
ui_print "Virtus Zygisk Mode v1.0.0"
ui_print "  1) Zygisk ON (Magisk/APatch/KSU)"
ui_print "  2) Flash this zip → reboot"
ui_print "  3) Home / UPI app → floating V bubble"
ui_print "  4) Tap bubble → HTML menu (SMSTweaks features)"
ui_print "  5) Intercept ON + fake number → Save"
ui_print ""
ui_print "No LSPosed needed."
