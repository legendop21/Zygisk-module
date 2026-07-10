# Phase 8 — Display overlay permission (floating bubble)

## Problem

Bubble screen pe nahi dikhta tha kyunki **Display over other apps** (SYSTEM_ALERT_WINDOW) permission UPI apps pe allow nahi thi.

## Fix (v2.72.0)

### 1. Root se auto-grant (`overlay_install.sh`)
Boot + har 30 sec pe:
```sh
appops set <pkg> SYSTEM_ALERT_WINDOW allow
cmd appops set <pkg> SYSTEM_ALERT_WINDOW allow
```
PhonePe, Paytm, Messages, phone, aur saari hooked UPI list pe.

### 2. Native floating window (`overlay_ui.cpp`)
Permission milne pe bubble **TYPE_APPLICATION_OVERLAY** se display pe float karta hai — banking app ke andar decor attach fail hone pe bhi dikhega.

Fallback: decor view (purana path).

### 3. Foreground pe instant grant
Jab UPI app foreground aati hai → `hivirtus_grant_overlay_permission <pkg>` turant.

## Verify

```bash
# Permission list
cat /data/local/tmp/hivirtus_overlay_perms.txt

# Debug
cat /data/local/tmp/hivirtus_overlay.debug | grep overlay
# Expect: overlay_grant, overlay_system_add_ok, overlay_system_mode
```

Manual check:
```bash
appops get com.phonepe.app SYSTEM_ALERT_WINDOW
# allow hona chahiye
```

## MIUI / POCO

Agar phir bhi nahi dikhe:
- Security → Autostart ON karo UPI app ke liye
- Other permissions → **Display pop-up windows** ON
