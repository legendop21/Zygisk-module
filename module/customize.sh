#!/system/bin/sh
# Zygisk Security Edu Lab — Magisk / KernelSU installer (educational build)

ui_print "*******************************"
ui_print "  Zygisk Security Edu Lab v3.0  "
ui_print "  Legal educational build only  "
ui_print "*******************************"

if [ -z "$MODPATH" ]; then
  ui_print "! ERROR: MODPATH not set"
  abort "Installation failed"
fi

ui_print "- Installing to $MODPATH"

set_perm_recursive "$MODPATH/zygisk" 0 0 0755 0644
[ -f "$MODPATH/post-fs-data.sh" ] && set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
[ -f "$MODPATH/service.sh" ] && set_perm "$MODPATH/service.sh" 0 0 0755
[ -f "$MODPATH/customize.sh" ] && set_perm "$MODPATH/customize.sh" 0 0 0755
[ -f "$MODPATH/config.json" ] && set_perm "$MODPATH/config.json" 0 0 0644
[ -f "$MODPATH/module.prop" ] && set_perm "$MODPATH/module.prop" 0 0 0644

if [ ! -f "$MODPATH/zygisk/arm64-v8a.so" ] && [ ! -f "$MODPATH/zygisk/armeabi-v7a.so" ]; then
  ui_print "! WARNING: No Zygisk native libs in module"
  ui_print "! Rebuild zip with ./build.sh"
fi

echo "1" > /data/local/tmp/hivirtus_module_installed.flag
chmod 644 /data/local/tmp/hivirtus_module_installed.flag 2>/dev/null

ui_print "- Educational module installed"
ui_print "- No SMS hook / root hide / UPI bypass in this build"
ui_print "- Enable Zygisk, reboot, then open companion APK"
