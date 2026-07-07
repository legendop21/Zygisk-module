# Hivirtus Zygisk Mode

Apna complete Zygisk module — **root hide**, **developer hide**, **SIM mock**, **SMS OTP hook**, aur **Telegram forward** — floating popup menu ke saath.

> Sirf apne device par use karein. Doosron ke accounts/SMS intercept karna illegal hai.

## Features

| Feature | Kya karta hai |
|---------|---------------|
| **I am no root** | Root detection bypass — su/magisk paths hide, props spoof |
| **I am not Developer** | Developer mode hide — ro.debuggable=0 |
| **SIM 1/2 Mock** | Country ISO spoof (e.g. `in`) |
| **SMS Hook** | Incoming/outgoing SMS intercept |
| **Auto OTP** | Regex se OTP extract |
| **Telegram Forward** | OTP auto Telegram par bhejo |
| **Floating Bubble** | Screen par HV bubble — tap karke menu khule |
| **Safe Flash** | Bootloop nahi — koi system partition modify nahi |

## UI — Yellow + Purple Theme

- Floating **HV** bubble (draggable) — tap = menu open
- **SYSTEM** tab — SIM mock + root/dev hide toggles
- **MESSAGE** tab — SMS hook + inject
- **TELEGRAM** tab — bot token + chat ID

## Install (3 Steps)

### 1. Module Build
```bash
export ANDROID_NDK=$HOME/Android/Sdk/ndk/26.1.10909125
./build.sh
```
Output: `hivirtus_zygisk_mode-v2.0.0.zip`

### 2. Magisk Flash
1. Magisk Manager → Modules → Install from storage
2. `hivirtus_zygisk_mode-v2.0.0.zip` flash karein
3. **Reboot** — flash ke baad root hide auto apply hoga

### 3. Overlay App
```bash
export ANDROID_HOME=$HOME/Android/Sdk
cd overlay-app && gradle assembleRelease
```
APK install karein → **Start Floating Menu** → overlay permission dein

## Root Hide — Kaise Kaam Karta Hai

Flash ke baad automatically:

```
post-fs-data.sh  →  resetprop (ro.debuggable=0, ro.secure=1, release-keys)
Zygisk hooks     →  su/magisk path hide in every app
Denylist unmount →  Magisk modules hidden from banking apps
```

**Bootloop safe kyunki:**
- System partition touch nahi hoti
- Sirf `resetprop -n` (non-destructive)
- Hooks sirf app processes mein, zygote boot path mein nahi

## Config

`/data/adb/modules/hivirtus_zygisk_mode/config.json`:

```json
{
  "hide_root": true,
  "hide_developer": true,
  "enable_sim1_mock": false,
  "enable_sim2_mock": false,
  "mock_country_iso": "in",
  "telegram_bot_token": "YOUR_BOT_TOKEN",
  "telegram_chat_id": "YOUR_CHAT_ID"
}
```

Overlay app se bhi update hoti hai.

## Floating Menu Use

1. Screen par **HV** bubble dikhega (purple/yellow)
2. **Tap** → full menu khulega
3. **SYSTEM** → toggles set karein → **Save SIM Settings**
4. **Minimize** (-) → wapas bubble
5. **X** → service band

## Telegram Setup

1. @BotFather se bot banao
2. Chat ID nikalo (@userinfobot)
3. TELEGRAM tab mein daalo → Save

## Logs

```bash
adb shell cat /data/local/tmp/hivirtus_zygisk_mode.log
adb logcat -s Hivirtus RootHide SimMock SmsHook
```

## All Devices Support

- arm64-v8a, armeabi-v7a, x86, x86_64 — sab ABIs build hote hain
- Android 8+ (API 26+)
- Magisk 26+ with Zygisk enabled

## Troubleshooting

| Problem | Fix |
|---------|-----|
| Bootloop | Module safe hai — disable from recovery if needed |
| Root still detected | Magisk DenyList mein app add karo + hide root ON |
| Bubble nahi dikhta | Overlay permission check karo |
| OTP nahi aata | MESSAGE tab mein hooks ON karo |

## License

MIT — apne risk par use karein.
