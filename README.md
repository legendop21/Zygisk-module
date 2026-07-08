# Zygisk Security Edu Lab

Legal educational Zygisk module + companion overlay app for learning Android security concepts.

**This build does NOT:**
- Hook or intercept real SMS
- Auto-read OTP from device messages
- Hide root or developer mode from other apps
- Auto-target UPI / banking apps
- Forward intercepted tokens automatically

**This build DOES:**
- Flash as a standard Magisk/KernelSU Zygisk module
- Show a floating bubble + mod menu UI (similar layout for learning)
- Read-only device security status (developer options, root indicators)
- OTP parsing **lab** on sample text you paste manually
- Manual Telegram test + optional manual share of parsed lab OTP

## Install

### 1. Flash module ZIP

```bash
./build.sh
# → hivirtus_zygisk_mode-v3.0.0-edu.zip
```

Magisk / KernelSU → Install from storage → select ZIP → Reboot → Enable Zygisk.

### 2. Install companion APK

```bash
./build-app.sh
# → overlay-app/app/build/outputs/apk/release/app-release.apk
```

Open app → allow Overlay + Notification → **Open Floating Lab Menu**.

## Tabs

| Tab | Purpose |
|-----|---------|
| **SYSTEM** | Module status, read-only security readout, Show App Info |
| **MESSAGE** | Paste sample SMS → parse OTP locally |
| **TELEGRAM** | Save bot token/chat ID → test connection → manual lab OTP share |

## Educational use only

Use only on devices you own, for learning how Zygisk modules load and how apps detect compromise. Do not use to bypass financial app security or intercept real OTPs.

## Requirements

- Rooted device with Zygisk (Magisk, KernelSU, APatch, etc.)
- Android NDK for native build (`ANDROID_NDK` or auto-detect via `tools/find_ndk.sh`)
