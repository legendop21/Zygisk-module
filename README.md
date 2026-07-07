# Hivirtus Zygisk Mode v2.1

Complete Zygisk module — **universal root hide**, **fake number spoof**, **SMS OTP**, **Telegram token forward** — floating popup menu ke saath.

> Sirf apne device par use karein.

## Supported Root Apps (Sab mein kaam karta hai)

| Root | Support |
|------|---------|
| **Magisk** | Denylist + path hide + props |
| **KernelSU** | resetprop + path hide |
| **KernelSU Next** | ksud compatible |
| **APatch** | apd resetprop + path hide |
| **SukiSU Ultra** | Universal path hide |

Module flash karte hi auto-detect karta hai — koi alag zip nahi chahiye.

## Features

| Feature | Description |
|---------|-------------|
| Hide Magisk / KSU / APatch / SukiSU | Per-root toggles |
| Hide ALL Root Apps | Universal hide sab apps mein |
| I am no root / not Developer | Master hide toggles |
| Fake Phone Number Spoof | SIM 1/2 fake number show |
| SMS Hook + Auto OTP | OTP extract |
| Telegram Forward | OTP + Token + Phone auto bhejo |
| Floating HV Bubble | Tap → menu open |

## Install

```bash
export ANDROID_NDK=$HOME/Android/Sdk/ndk/26.1.10909125
./build.sh
# → hivirtus_zygisk_mode-v2.1.0.zip
```

Flash in **Magisk / KernelSU / APatch** modules section → Reboot.

Overlay APK: `overlay-app/` → install → Start Floating Menu.

## SYSTEM Tab

**Root Hide:**
- Hide Magisk / KernelSU / APatch / SukiSU Ultra
- Hide ALL Root Apps (Universal)
- I am no root / I am not Developer

**Fake Number Spoof:**
- Enable Fake Number Spoof
- SIM 1: `+919876543210`
- SIM 2: `+919876543211`
- Save SIM Settings

## TELEGRAM Tab

1. Bot Token + Chat ID daalo
2. Auto Forward OTP + Token ON
3. Test Telegram Forward dabao
4. OTP aate hi message jayega:
```
Hivirtus Token Forward
OTP: 918204
Token: 918204
Sender: AD-TEST-S
Phone: +919876543210
Root: Magisk Hidden
```

## Safe — No Bootloop

- System partition touch nahi
- Sirf `resetprop -n`
- Hooks app processes mein only

## Logs

```bash
adb shell cat /data/local/tmp/hivirtus_zygisk_mode.log
adb shell cat /data/local/tmp/hivirtus_root_type.txt
```

## License

MIT
