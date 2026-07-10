# Phase 1 — Architecture (Hinglish)

## Pehle kya tha (APK wala)

```
ZIP (~12MB)
├── zygisk/*.so        ← hooks (SMS, SIM, UPI)
├── virtus-overlay.apk ← 6-8MB alag app (bubble + menu)
└── service.sh         ← APK start + telegram curl
```

Problem: APK alag install, permission, battery drain, size bada.

## Ab kya hai (APK-free v2.68+)

```
ZIP (~3MB arm64)
├── zygisk/arm64-v8a.so  ← hooks + native floating UI
├── service.sh           ← local telegram + SMS block (curl)
├── config.json
└── NO APK
```

## 3 layer kaam kaun karta hai

| Layer | File | Kaam |
|-------|------|------|
| **Zygisk native** | `module/jni/*.cpp` | UPI/phone/Messages me inject — SMS block, mock SIM, **bubble menu** |
| **Shell daemon** | `module/service.sh` | Boot pe chalta hai — config sync, telegram forward, appops SMS deny |
| **Config files** | `config.json` + `/data/local/tmp/*` | Dono layers yahi se settings padhte hain |

## Shared files (dimaag yahi hai)

| Path | Kaam |
|------|------|
| `/data/adb/modules/hivirtus_zygisk_mode/config.json` | Permanent config |
| `/data/local/tmp/hivirtus_zygisk_mode_config.json` | Runtime config (native + shell) |
| `/data/local/tmp/hivirtus_spoof_phone.txt` | Mock number |
| `/data/local/tmp/hivirtus_outgoing_blocked.json` | Blocked UPI token → telegram |
| `/data/local/tmp/hivirtus_telegram_credentials.json` | Bot token + chat ID |
| `/data/local/tmp/hivirtus_active_hook_pkg.txt` | Kaunsi UPI app foreground me hai |

## Flow diagram

```
User UPI app kholta hai
        ↓
Zygisk .so inject (us app ke andar)
        ↓
overlay_ui.cpp → gold V bubble + menu (Activity pe chipka)
        ↓
Verify SMS block → outgoing_sms_hook.cpp
        ↓
/data/local/tmp/hivirtus_outgoing_blocked.json
        ↓
service.sh (har 2 sec) → curl → Telegram
```

**Firebase auto-relay removed (v2.69+)** — sirf local Telegram + manual SMS.

## Banking apps (YesPay etc.)

Fragile apps = **phone-only mode** — UPI ke andar hook nahi, sirf `com.android.phone` se SMS block + spoof.

Menu ke liye: **Google Messages** kholo — wahan bhi native overlay lagta hai.
