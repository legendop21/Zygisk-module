# Phase 2 — Floating Window Logic (Hinglish)

## APK hata diya — ab kya chalta hai?

Pehle: alag `overlay-app` APK sideload → permissions → Start button.

Ab: **Zygisk SystemUI** mein inject → chhota `overlay.dex` load → bubble + menu.

```
Reboot
  → Zygisk module load
  → com.android.systemui process
  → float_overlay.cpp (native)
  → overlay.dex load (InMemoryDexClassLoader)
  → OverlayBootstrap.start()
  → FloatBubble (side pe "H" logo)
  → Tap → FloatMenu (local toggles)
```

## Files ka role

| File | Kaam |
|------|------|
| `module/overlay/java/.../FloatBubble.java` | Draggable bubble — smooth, 48dp |
| `FloatMenu.java` | Chhota menu — switches config.json edit karte hain |
| `LocalConfig.java` | Sirf local JSON read/write |
| `module/jni/dex_loader.cpp` | SystemUI mein dex load |
| `module/jni/float_overlay.cpp` | Boot ke 4 sec baad UI start |

## Config save kahan hoti hai?

```
/data/adb/modules/hivirtus_zygisk_mode/config.json   ← master
/data/local/tmp/hivirtus_zygisk_mode_config.json     ← runtime (hooks yahi padhte hain)
```

Menu se SAVE dabao → dono files update → Zygisk hooks reload config.

## Shell se bina UI

```bash
hivirtus-menu status
hivirtus-menu toggle intercept_fake_success
hivirtus-menu set inject_sender_id JK-AXISBK-S
```

## Smooth kyun?

- Sirf 4 chhoti Java classes — koi Gradle APK nahi
- Native bubble thread alag — UI lag nahi
- `-Oz` compile + `llvm-strip` — `.so` chhota

## Agla phase

→ [Phase 3 — SMS Intercept](phase-03-sms-intercept.md)
