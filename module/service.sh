#!/system/bin/sh
# Runs after boot — keeps config symlink fresh

MODDIR=${0%/*}
CONFIG="$MODDIR/config.json"
RUNTIME="/data/local/tmp/zygisk_sms_otp_config.json"

if [ -f "$CONFIG" ]; then
  cp -f "$CONFIG" "$RUNTIME"
  chmod 644 "$RUNTIME"
fi
