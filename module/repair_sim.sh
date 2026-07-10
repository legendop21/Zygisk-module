#!/system/bin/sh
# Emergency SIM/Settings repair — Mac: adb shell su -c 'sh /data/adb/modules/hivirtus_zygisk_mode/repair_sim.sh'

MODDIR="${0%/*}"
. "$MODDIR/overlay_install.sh" 2>/dev/null

echo "=== Virtus SIM/Settings REPAIR ==="
hivirtus_repair_sim_settings

echo ""
echo "APatch package_config (phone/settings lines):"
grep -E 'phone|telephony|settings|systemui' /data/adb/ap/package_config 2>/dev/null | head -20

echo ""
echo "Done. REBOOT phone abhi — SIM + Settings theek hone chahiye."
echo "repair_ok:$(date +%s)" >> /data/local/tmp/hivirtus_overlay.debug 2>/dev/null
