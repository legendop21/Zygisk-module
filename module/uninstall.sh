#!/system/bin/sh
# KernelSU / Magisk module uninstall cleanup

rm -f /data/local/tmp/hivirtus_module_installed.flag 2>/dev/null
rm -f /data/local/tmp/hivirtus_module_heartbeat.txt 2>/dev/null
rm -f /data/local/tmp/hivirtus_boot_hide_enabled 2>/dev/null
