# Hivirtus Zygisk Mode v2.15.0

**Pure Zygisk floating module — koi APK nahi. UI = local HTML.**

## Features
- Draggable bubble + **HTML/CSS WebView menu** (modern, smooth, offline)
- SMS intercept + fake success
- Root hide, phone spoof, UPI hooks
- 100% local `config.json`

## Install
```bash
export ANDROID_HOME=... ANDROID_NDK=...
./build.sh
# Flash zip → Reboot → Zygisk ON → bubble tap
```

## Docs (Hinglish)
1. [Install](docs/phase-01-install.md)
2. [Floating HTML UI](docs/phase-02-floating-window.md)
3. [SMS Intercept](docs/phase-03-sms-intercept.md)
4. [Local Config](docs/phase-04-local-config.md)
5. [Build & Size](docs/phase-05-build-size.md)
6. [HTML UI Edit](docs/phase-06-html-ui.md)

## Shell
```bash
hivirtus-menu status
hivirtus-menu toggle intercept_fake_success
```

Works on: Magisk | KernelSU | APatch | SukiSU (Zygisk ON)
