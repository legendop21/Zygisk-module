#!/system/bin/sh
# Virtus bubble diagnostic — Magisk/APatch su shell me chalao
# Mac se: adb shell su -c 'sh /data/adb/modules/hivirtus_zygisk_mode/diag_bubble.sh'

OUT=/data/local/tmp/hivirtus_diag.txt
: > "$OUT"
chmod 666 "$OUT" 2>/dev/null

log() { echo "$1" | tee -a "$OUT"; }

log "===== VIRTUS BUBBLE DIAG $(date) ====="
log "android=$(getprop ro.build.version.release) sdk=$(getprop ro.build.version.sdk)"
log "device=$(getprop ro.product.model) $(getprop ro.product.device)"

MOD=/data/adb/modules/hivirtus_zygisk_mode
log ""
log "--- module ---"
if [ -d "$MOD" ]; then
  log "module_dir=OK"
  ls -la "$MOD/module.prop" "$MOD/bridge.dex" "$MOD/zygisk/" 2>&1 | tee -a "$OUT"
  [ -f "$MOD/module.prop" ] && grep -E 'version|description' "$MOD/module.prop" | tee -a "$OUT"
else
  log "module_dir=MISSING"
fi

log ""
log "--- bridge / ui copies ---"
ls -la /data/local/tmp/hivirtus_bridge.dex /data/local/tmp/hivirtus_ui/ 2>&1 | tee -a "$OUT"

log ""
log "--- inject log (last 30) ---"
if [ -f /data/local/tmp/hivirtus_inject.log ]; then
  tail -n 30 /data/local/tmp/hivirtus_inject.log | tee -a "$OUT"
else
  log "inject.log=MISSING (module is app me inject nahi hua)"
fi

log ""
log "--- overlay.debug (last 60) ---"
if [ -f /data/local/tmp/hivirtus_overlay.debug ]; then
  tail -n 60 /data/local/tmp/hivirtus_overlay.debug | tee -a "$OUT"
else
  log "overlay.debug=MISSING"
fi

log ""
log "--- foreground ---"
FG=$(dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity=ActivityRecord' | head -n1)
[ -z "$FG" ] && FG=$(dumpsys activity activities 2>/dev/null | grep -E 'mResumedActivity' | head -n1)
log "fg=$FG"
PKG=$(echo "$FG" | sed -n 's/.* u0 \([a-zA-Z0-9._]*\)\/.*/\1/p')
[ -z "$PKG" ] && PKG=$(echo "$FG" | sed -n 's/.* \([a-zA-Z0-9._]*\)\/[a-zA-Z0-9._]*.*/\1/p')
log "pkg=$PKG"
if [ -n "$PKG" ]; then
  log "overlay_op=$(appops get "$PKG" SYSTEM_ALERT_WINDOW 2>/dev/null | head -n3)"
fi

log ""
log "--- flags ---"
ls -la /data/local/tmp/hivirtus_*.active /data/local/tmp/hivirtus_module_heartbeat.txt 2>&1 | tee -a "$OUT"

log ""
log "===== DONE → also saved: $OUT ====="
log "Mac pe copy: adb shell su -c 'cat /data/local/tmp/hivirtus_diag.txt'"
