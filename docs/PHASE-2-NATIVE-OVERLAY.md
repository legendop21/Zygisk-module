# Phase 2 — Native Floating Overlay (Hinglish)

## Kya hai

`overlay_ui.cpp` — **APK ke bina** UPI/Messages app ke andar gold bubble + menu banata hai.

- Bubble: chhota gold **V** button
- Menu: SYSTEM / MESSAGE / TELEGRAM tabs (subset)
- Status pill: top pe hook status

## Kahan inject hota hai

| Process | Overlay? |
|---------|----------|
| Hooked UPI (PhonePe, Paytm, …) | ✅ Haan (deferred 3-5s) |
| YesPay / fragile banking | ❌ Nahi (crash avoid) — Messages use karo |
| Google Messages | ✅ Haan |
| com.android.phone | ❌ (koi Activity UI nahi) |

## Enable / disable

| Flag file | Effect |
|-----------|--------|
| `/data/local/tmp/hivirtus_disable_native_overlay` | Overlay band |
| `/data/local/tmp/hivirtus_overlay_only` | Sirf overlay, root hide skip |

Default: **ON** (v2.68+)

## Kaise kaam karta hai (technical)

1. `main.cpp` → `overlay_ui::install()` call
2. PLT hook: `Activity.onResume`, `onAttachedToWindow`, `View.performClick`
3. `overlay_touch()` → decor view pe bubble attach
4. Click → menu panel expand
5. Toggle save → `config.cpp` → JSON file

## APK menu vs native menu

| Feature | APK (purana) | Native (ab) |
|---------|--------------|---------------|
| Bubble | WindowManager global | UPI Activity ke andar |
| Mock SIM field | Text field | config.json edit |
| Telegram verify | Button | config.json + shell |

## Debug

```bash
cat /data/local/tmp/hivirtus_overlay.debug
cat /data/local/tmp/hivirtus_inject.log
```

`overlay_install:com.phonepe.app` jaisi lines = overlay hook lag gaya.
