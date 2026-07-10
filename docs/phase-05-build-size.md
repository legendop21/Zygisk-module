# Phase 5 — Build & Size Optimize (Hinglish)

## Build command

```bash
export ANDROID_NDK=$HOME/Library/Android/sdk/ndk/26.1.10909125
export ANDROID_HOME=$HOME/Library/Android/sdk
./build.sh
```

Output: `hivirtus_zygisk_mode-v2.14.0.zip`

## Size breakdown (target)

| Part | Approx size |
|------|-------------|
| `zygisk/arm64-v8a.so` | ~800KB–1.2MB (stripped) |
| `overlay/ui/*.html` | ~15KB | Modern menu UI |
| Scripts + config | ~20KB |
| **Total ZIP** | **~1–2MB** |

## Size kam karne ke tricks (already applied)

1. **Default sirf arm64** — `ABI_LIST=arm64-v8a`
2. **`-Oz` + `--gc-sections`** — native compile optimize
3. **`llvm-strip --strip-unneeded`** — debug symbols hatao
4. **`zip -9`** — max compression
5. **APK hata diya** — 3MB+ bach gaya
6. **4 ABI → 1 ABI** — ~3x chhota

## Sab ABIs chahiye?

```bash
ABI_LIST="arm64-v8a armeabi-v7a" ./build.sh
```

## overlay.dex build fail?

SDK chahiye:
```bash
export ANDROID_HOME=...
chmod +x tools/build_overlay_dex.sh
./tools/build_overlay_dex.sh
```

Warn aaye to pre-built `module/overlay/overlay.dex` daal sakte ho.

## Phase summary (yaad rakhne ke liye)

| Phase | Topic |
|-------|--------|
| [1](phase-01-install.md) | Flash ZIP, no APK |
| [2](phase-02-floating-window.md) | SystemUI bubble + dex |
| [3](phase-03-sms-intercept.md) | Fake SMS success |
| [4](phase-04-local-config.md) | config.json local |
| [5](phase-05-build-size.md) | Build chhota zip |
