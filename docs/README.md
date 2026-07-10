# Hivirtus Zygisk — Docs (Hinglish)

APK-free floating window module ka logic yahan phase-wise likha hai. Bhoolne se pehle yahi padhna.

| Phase | File | Kya hai |
|-------|------|---------|
| 1 | [PHASE-1-ARCHITECTURE.md](./PHASE-1-ARCHITECTURE.md) | Poora system — Zygisk + shell + native UI |
| 2 | [PHASE-2-NATIVE-OVERLAY.md](./PHASE-2-NATIVE-OVERLAY.md) | Floating bubble/menu kaise kaam karta hai |
| 3 | [PHASE-3-LOCAL-SHELL.md](./PHASE-3-LOCAL-SHELL.md) | Bina APK — Telegram, SMS block, config |
| 4 | [PHASE-4-SIZE-OPTIMIZE.md](./PHASE-4-SIZE-OPTIMIZE.md) | Chota zip (~3MB arm64) |
| 5 | [PHASE-5-SMS-INTERCEPT-FIX.md](./PHASE-5-SMS-INTERCEPT-FIX.md) | Token real SIM se kyun jaata tha + fix |
| 6 | [PHASE-6-HTML-OVERLAY.md](./PHASE-6-HTML-OVERLAY.md) | WebView HTML menu |
| 7 | [PHASE-7-ZYGISK-ZYGOTE-FIX.md](./PHASE-7-ZYGISK-ZYGOTE-FIX.md) | APatch+ZN zygote crash + inject fix |
| 8 | [PHASE-8-OVERLAY-PERMISSION.md](./PHASE-8-OVERLAY-PERMISSION.md) | Display overlay permission + floating bubble |

## Quick start

1. Zygisk ON → flash `hivirtus-zygisk-hook-*.zip` → reboot
2. Koi **UPI app** kholo → gold **V bubble** dikhega (native overlay)
3. Config: `/data/adb/modules/hivirtus_zygisk_mode/config.json`
4. Telegram: `telegram_bot_token` + `telegram_chat_id` config me daalo

**APK install ki zaroorat nahi** — v2.68+ pure Zygisk module.
