#!/system/bin/sh
# Hivirtus native overlay helpers — APK-free (v2.68+)

hivirtus_native_overlay_ready() {
  echo 1 > /data/local/tmp/hivirtus_native_overlay.active 2>/dev/null
  chmod 644 /data/local/tmp/hivirtus_native_overlay.active 2>/dev/null
  date +%s > /data/local/tmp/hivirtus_module_heartbeat.txt 2>/dev/null
  chmod 644 /data/local/tmp/hivirtus_module_heartbeat.txt 2>/dev/null
}

hivirtus_mark_foreground_upi() {
  pkg="$1"
  [ -z "$pkg" ] && return 1
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
  hivirtus_pkg_installed "$pkg" || return 0

  appops set "$pkg" SYSTEM_ALERT_WINDOW allow 2>/dev/null
  cmd appops set "$pkg" SYSTEM_ALERT_WINDOW allow 2>/dev/null
  appops set "$pkg" android:system_alert_window allow 2>/dev/null
  cmd appops set "$pkg" android:system_alert_window allow 2>/dev/null
  # OP code 24 — kuch ROMs pe sirf number se allow hota hai
  appops set "$pkg" 24 allow 2>/dev/null
  cmd appops set "$pkg" 24 allow 2>/dev/null

  echo "$pkg" >> /data/local/tmp/hivirtus_overlay_perms.txt 2>/dev/null
}

hivirtus_overlay_core_pkgs() {
  cat <<'PKGS'
com.google.android.apps.nexuslauncher
com.android.launcher3
com.android.launcher
com.miui.home
com.mi.android.globallauncher
com.sec.android.app.launcher
com.oppo.launcher
com.realme.launcher
com.oneplus.launcher
com.android.phone
com.android.providers.telephony
com.google.android.apps.messaging
com.android.mms
com.android.mms.service
com.samsung.android.messaging
com.phonepe.app
net.one97.paytm
com.google.android.apps.nbu.paisa.user
com.yespay.next
com.yesbank.yespay
com.yesbank.yespaynext
com.snapmint.customerapp
com.kreditbee.android
com.whizdm.moneyview.loans
com.whiz.credit
com.stashfin.android
com.snapmint.customerapp
com.csam.icici.bank.imobile
com.hdfcbank.payzapp
com.axis.mobile
com.sbi.lotusintouch
com.groww.app
com.nextbillion.groww
com.herofincorp.diyjourneys
com.herofincorp.simplycash
com.customer.herofincorp
com.fampay.in
com.mobikwik_new
com.freecharge.android
com.bharatpe.app
com.amazon.mShop.android.shopping
com.myairtelapp
com.jio.myjio
com.mpokket.app
com.kissht.android
com.nira.finance
com.lazypay.app
com.earlysalary.android
com.naviapp
com.navi.moneymanager
PKGS
}

hivirtus_grant_overlay_permission() {
  # Optional: sirf ek pkg (foreground change pe)
  EXTRA_PKG="$1"

  : > /data/local/tmp/hivirtus_overlay_perms.txt 2>/dev/null
  chmod 644 /data/local/tmp/hivirtus_overlay_perms.txt 2>/dev/null

  hivirtus_overlay_core_pkgs | while read -r pkg; do
    [ -n "$pkg" ] && hivirtus_grant_one_overlay "$pkg"
  done

  for f in /data/local/tmp/hivirtus_hooked_pkgs.txt \
    /data/local/tmp/hivirtus_active_upi_all.txt \
    /data/local/tmp/hivirtus_active_hook_pkg.txt \
    /data/local/tmp/hivirtus_grant_overlay_pkg.txt; do
    [ -f "$f" ] || continue
    while read -r pkg; do
      pkg=$(echo "$pkg" | tr -d '\r\n ')
      [ -n "$pkg" ] && hivirtus_grant_one_overlay "$pkg"
    done < "$f"
  done

  [ -n "$EXTRA_PKG" ] && hivirtus_grant_one_overlay "$EXTRA_PKG"

  MODDIR="${MODDIR:-/data/adb/modules/hivirtus_zygisk_mode}"
  if [ -f "$MODDIR/apatch_package_config_full.csv" ]; then
    awk -F, 'NR>1 && $2==0 && $1 ~ /^com\./ {print $1}' "$MODDIR/apatch_package_config_full.csv" 2>/dev/null \
      | grep -vE '^(com\.android\.|bin\.|org\.)' | while read -r pkg; do
        [ -n "$pkg" ] && hivirtus_grant_one_overlay "$pkg"
      done
  fi

  echo "overlay_grant:$(date +%s)" >> /data/local/tmp/hivirtus_overlay.debug 2>/dev/null
  chmod 644 /data/local/tmp/hivirtus_overlay.debug 2>/dev/null
}

# Legacy stubs — purane scripts se call na toote
hivirtus_install_overlay_apk() { return 0; }
hivirtus_start_overlay_service() { hivirtus_native_overlay_ready; }
hivirtus_boot_activate_overlay() {
  hivirtus_native_overlay_ready
  : > /data/local/tmp/hivirtus_overlay.debug 2>/dev/null
  chmod 666 /data/local/tmp/hivirtus_overlay.debug 2>/dev/null
  echo "boot_overlay:$(date +%s)" >> /data/local/tmp/hivirtus_overlay.debug 2>/dev/null
  # bridge.dex copy — app uid se readable (prefer /data/local/tmp)
  MOD="${MODDIR:-${MODPATH:-/data/adb/modules/hivirtus_zygisk_mode}}"
  if [ -f "$MOD/bridge.dex" ]; then
    cp -f "$MOD/bridge.dex" /data/local/tmp/hivirtus_bridge.dex 2>/dev/null
    chmod 644 /data/local/tmp/hivirtus_bridge.dex 2>/dev/null
    chown root:root /data/local/tmp/hivirtus_bridge.dex 2>/dev/null
    echo "bridge_dex_copied" >> /data/local/tmp/hivirtus_overlay.debug 2>/dev/null
  else
    echo "bridge_dex_missing:$MOD" >> /data/local/tmp/hivirtus_overlay.debug 2>/dev/null
  fi
  hivirtus_grant_overlay_permission
}
