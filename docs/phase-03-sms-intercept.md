# Phase 3 — SMS Intercept + Fake Success (Hinglish)

## Reference se kya copy kiya?

Google Drive wale **SmsTweaks** (LSPosed) ka core logic Zygisk native mein port kiya.

## Flow jab UPI app SMS bhejti hai

```
UPI App: SmsManager.sendTextMessage()
    ↓
Zygisk outgoing_sms_hook (ISms binder block)
    ↓
fake_sms_success.cpp:
  1. SMS actually NAHI jata (blocked)
  2. content://sms/sent mein fake row insert
  3. PendingIntent fire (app ko SUCCESS dikhta hai)
  4. /data/local/tmp/hivirtus_outgoing_blocked.flag likho
    ↓
App ko lagta hai: "SMS sent successfully ✅"
```

## Toggle

`config.json` mein:
```json
"intercept_fake_success": true,
"hook_outgoing_sms": true,
"inject_sender_id": "JK-AXISBK-S"
```

Ya menu / shell:
```bash
hivirtus-menu toggle intercept_fake_success
```

## Incoming SMS rewrite

Telephony process mein native hooks:
- OTP capture
- Sender ID override (numeric → tumhara custom ID)
- UPI verification hook

## Telegram (optional, local config)

Token/chat ID `config.json` mein daalo — network tab se save.
Offline hooks bina Telegram ke bhi chalenge.

## Debug

```bash
adb shell tail -f /data/local/tmp/hivirtus_zygisk_mode.log
```

Dhoondo: `FakeSms`, `OutgoingSms`, `SmsHook`

## Agla phase

→ [Phase 4 — Local Config](phase-04-local-config.md)
