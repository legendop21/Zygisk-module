# Phase 1 — Install (Hinglish)

## Kya hai ye module?

**Hivirtus Zygisk Mode** ek **sirf ZIP** wala Magisk/KernelSU module hai.
- ❌ Koi alag APK install **nahi**
- ✅ Flash → Reboot → floating bubble khud aayega
- ✅ Sab config **local** file mein (`config.json`)

## Install steps

1. `./build.sh` chalao (Mac/Linux + NDK)
2. `hivirtus_zygisk_mode-v2.15.0.zip` milega (~1–2MB arm64)
3. Magisk / KernelSU → Install from storage → ZIP select
4. **Reboot**
5. Root manager mein **Zygisk ON** karo
6. 5–10 sec baad screen pe **H bubble** dikhna chahiye

## Size kam kaise?

| Pehle | Ab |
|-------|-----|
| 4 ABIs + APK ~12MB | Sirf `arm64-v8a` default ~1–2MB |
| Alag overlay APK | Embedded `overlay.dex` ~50KB |

Emulator ke liye: `ABI_LIST="x86_64" ./build.sh`

## Check karo install sahi hua?

```bash
adb shell hivirtus-menu status
```

Ya manually:
- `/data/adb/modules/hivirtus_zygisk_mode/zygisk/arm64-v8a.so` — native hooks
- `/data/adb/modules/hivirtus_zygisk_mode/overlay/overlay.dex` — floating UI
- `/data/local/tmp/hivirtus_module_heartbeat.txt` — module alive

## Agla phase

→ [Phase 2 — Floating Window](phase-02-floating-window.md)
