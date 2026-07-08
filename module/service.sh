#!/system/bin/sh
# Zygisk-only runtime — auto UPI detect, config sync, hook restart (no APK)

MODDIR=${0%/*}
CONFIG="$MODDIR/config.json"
RUNTIME="/data/local/tmp/hivirtus_zygisk_mode_config.json"
ACTIVE_PKG="/data/local/tmp/hivirtus_active_hook_pkg.txt"
RESTART_FLAG="/data/local/tmp/hivirtus_restart_upi.flag"

# Built-in UPI / loan packages (match native upi_registry.cpp)
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
  if [ -f "$CONFIG" ]; then
    cp -f "$CONFIG" "$RUNTIME"
    chmod 644 "$RUNTIME" 2>/dev/null
  fi
}

get_foreground_pkg() {
  dumpsys activity activities 2>/dev/null | grep mResumedActivity | head -n1 | sed -n 's/.* \([a-zA-Z0-9._]*\)\/[a-zA-Z0-9._]*.*/\1/p'
}

mark_active() {
  echo "$1" > "$ACTIVE_PKG"
  chmod 644 "$ACTIVE_PKG" 2>/dev/null
  date +%s > /data/local/tmp/hivirtus_module_heartbeat.txt
}

sync_config

(
  LAST=""
  while true; do
    sync_config
    FG=$(get_foreground_pkg)
    if [ -n "$FG" ] && is_upi_pkg "$FG"; then
      if [ "$FG" != "$LAST" ]; then
        mark_active "$FG"
        # First open after boot — restart app so Zygisk hooks load in process
        if [ ! -f "$RESTART_FLAG/$FG" ]; then
          mkdir -p "$RESTART_FLAG" 2>/dev/null
          touch "$RESTART_FLAG/$FG" 2>/dev/null
          am force-stop "$FG" 2>/dev/null
          monkey -p "$FG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
        fi
        LAST="$FG"
      fi
    fi
    sleep 2
  done
) &
