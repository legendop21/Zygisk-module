# Hivirtus Zygisk Mode v2.14.0

**Pure Zygisk floating module — koi APK nahi.**

## Features
- Floating bubble + menu (embedded `overlay.dex` in SystemUI)
- SMS intercept + fake success (UPI verify)
- Root hide, phone spoof, UPI hooks
- 100% local `config.json` — offline kaam karta hai

## Install
```bash
./build.sh
# Flash hivirtus_zygisk_mode-v2.14.0.zip → Reboot → Zygisk ON
```

## Docs (Hinglish phases)
1. [Install](docs/phase-01-install.md)
2. [Floating Window](docs/phase-02-floating-window.md)
3. [SMS Intercept](docs/phase-03-sms-intercept.md)
4. [Local Config](docs/phase-04-local-config.md)
5. [Build & Size](docs/phase-05-build-size.md)

## Shell
```bash
hivirtus-menu status
hivirtus-menu toggle intercept_fake_success
```

Works on: Magisk | KernelSU | APatch | SukiSU (Zygisk ON)
