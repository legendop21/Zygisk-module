# Phase 2 — Floating Window (HTML UI) — Hinglish

## APK nahi — HTML hai UI

Purana APK: Gradle app, permissions, 3MB+.

Ab:
```
SystemUI (Zygisk inject)
  → overlay.dex (~50KB Java shell)
  → WebView load: file:///.../overlay/ui/index.html
  → app.css + app.js (modern dark UI)
  → VirtusBridge (Java) ↔ config.json
```

## UI files kahan hain?

```
/data/adb/modules/hivirtus_zygisk_mode/overlay/ui/
  index.html   — structure + tabs
  app.css      — dark theme, smooth toggles
  app.js       — load/save logic
```

**Edit kar sakte ho** — phone pe root explorer se HTML/CSS change → reboot ya menu reload.

## Smooth kaise?

| Cheez | Detail |
|-------|--------|
| Hardware accel | WebView `FLAG_HARDWARE_ACCELERATED` |
| Animations | CSS `cubic-bezier` — no janky bounce |
| Chhota dex | Sirf WebView shell + bridge, UI HTML mein |
| Local files | `file://` — network wait nahi |

## Bridge API (HTML ↔ module)

```javascript
VirtusBridge.getConfig()      // JSON string
VirtusBridge.saveConfig(json) // bool
VirtusBridge.closeMenu()
```

Java: `JsBridge.java` → `LocalConfig.save()` → dono paths update.

## Bubble

Side pe **"H"** native bubble (48dp) — tap → HTML menu khulta hai.
Bubble halka rakha taaki drag smooth rahe; asli UI HTML mein.

## Agla phase

→ [Phase 3 — SMS Intercept](phase-03-sms-intercept.md)
