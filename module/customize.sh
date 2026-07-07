#!/system/bin/sh
# Magisk module installer

SKIPUNZIP=1

ui_print "- Installing Zygisk SMS OTP Hook"

unzip -o "$ZIPFILE" 'zygisk/*' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'config.json' -d "$MODPATH" >&2

set_perm_recursive "$MODPATH/zygisk" 0 0 0755 0644

if [ ! -f "$MODPATH/config.json" ]; then
  ui_print "- Creating default config"
  cat > "$MODPATH/config.json" << 'EOF'
{
  "hook_incoming_sms": true,
  "hook_outgoing_sms": true,
  "auto_extract_otp": true,
  "auto_forward_token": true,
  "forward_url": "https://your-webhook.example.com/otp",
  "forward_method": "POST",
  "forward_headers": {
    "Content-Type": "application/json",
    "Authorization": "Bearer YOUR_TOKEN"
  },
  "otp_patterns": [
    "\\b(\\d{4,8})\\b.*(?:otp|code|verification|verify|pin)",
    "(?:otp|code|verification|verify|pin)[:\\s]*(\\d{4,8})",
    "Your verification OTP code is (\\d{4,8})"
  ],
  "inject_sender_id": "AD-TEST-S",
  "inject_message_body": "",
  "log_file": "/data/local/tmp/zygisk_sms_otp.log"
}
EOF
fi

ui_print "- Installation complete"
ui_print "- Reboot to activate Zygisk hooks"
