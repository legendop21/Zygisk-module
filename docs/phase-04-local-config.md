# Phase 4 — Local Config (Hinglish)

## Rule: sab kuch phone pe, koi cloud nahi

| Cheez | Path |
|-------|------|
| Master config | `/data/adb/modules/hivirtus_zygisk_mode/config.json` |
| Runtime copy | `/data/local/tmp/hivirtus_zygisk_mode_config.json` |
| OTP last | `/data/local/tmp/hivirtus_last_otp.json` |
| SMS inject cmd | `/data/local/tmp/hivirtus_inject.cmd` |
| Log | `/data/local/tmp/hivirtus_zygisk_mode.log` |

## Edit karne ke 3 tareeke

### 1. Floating menu (recommended)
Bubble tap → switches → **SAVE**

### 2. Shell
```bash
hivirtus-menu toggle hook_incoming_sms
hivirtus-menu set inject_sender_id VM-PHONEPE-S
```

### 3. Direct file (root explorer / adb)
```bash
adb shell su -c "nano /data/adb/modules/hivirtus_zygisk_mode/config.json"
```

Phir runtime sync:
```bash
adb shell su -c "cp /data/adb/modules/hivirtus_zygisk_mode/config.json /data/local/tmp/hivirtus_zygisk_mode_config.json"
```

## Important keys

```json
{
  "intercept_fake_success": true,
  "hook_incoming_sms": true,
  "hook_outgoing_sms": true,
  "inject_sender_id": "AD-TEST-S",
  "enable_phone_spoof": false,
  "mock_phone_sim1": "+919876543210",
  "telegram_bot_token": "",
  "telegram_chat_id": "",
  "hooked_upi_apps": { "com.phonepe.app": true }
}
```

## service.sh kya karta hai?

Har 2 sec:
- `config.edit.json` agar hai → merge karke save
- Device ID change command apply
- **Koi APK folder sync nahi** (purana flow hata diya)

## Agla phase

→ [Phase 5 — Build & Size](phase-05-build-size.md)
