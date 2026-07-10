# Phase 6 — HTML Floating UI (Hinglish)

## Kya hai

Native gold **V bubble** (smooth, chhota) + menu **HTML WebView** se.

APK nahi — sab module zip ke andar:

```
/data/adb/modules/hivirtus_zygisk_mode/
├── ui/index.html
├── ui/style.css
├── ui/app.js
└── bridge.dex          ← JS ↔ config file bridge
```

## Kaise kaam karta hai

1. `overlay_ui.cpp` → Activity pe bubble chipkata hai
2. Bubble tap → WebView menu open
3. HTML `Hivirtus.readConfig()` / `Hivirtus.saveConfig()` via `bridge.dex`
4. Save → `/data/local/tmp/hivirtus_ui_save.json`
5. Native `ConfigManager::apply_ui_save_file()` → runtime config + spoof phone file

## Design

- Dark zinc background, muted gold accent
- System / SMS / Telegram tabs
- No generic purple AI gradient slop

## Disable overlay

```bash
touch /data/local/tmp/hivirtus_disable_native_overlay
```

## Agar bridge.dex missing

HTML read-only dikhega — config `config.json` se edit karo.
