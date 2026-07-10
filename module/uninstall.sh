#!/system/bin/sh
# KernelSU / Magisk / APatch module uninstall cleanup

FLOAT_PKG="com.hivirtus.zygiskmode.floatsvc"

pm uninstall "$FLOAT_PKG" 2>/dev/null

rm -f /data/local/tmp/hivirtus_module_installed.flag 2>/dev/null
rm -f /data/local/tmp/hivirtus_module_heartbeat.txt 2>/dev/null
rm -f /data/local/tmp/hivirtus_overlay_alive.flag 2>/dev/null
rm -f /data/local/tmp/hivirtus_overlay_supervisor.pid 2>/dev/null
rm -f /data/local/tmp/hivirtus_boot_ready.flag 2>/dev/null
