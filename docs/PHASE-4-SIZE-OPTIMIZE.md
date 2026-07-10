# Phase 4 — Size Optimize (Hinglish)

## Pehle ~12MB kyun tha

| Part | Size |
|------|------|
| virtus-overlay.apk | ~6-8 MB |
| 4 ABIs (arm64 + arm32 + x86 + x86_64) | ~4-5 MB |
| Scripts + config | <100 KB |

## Ab ~3MB kaise

| Change | Saving |
|--------|--------|
| APK hata diya | ~6-8 MB |
| Sirf **arm64-v8a** (default) | ~3-4 MB |
| **Total zip** | **~2.5-3.5 MB** |

## Build commands

```bash
# Default — sirf arm64 (phone)
./build.sh

# Sab ABI (emulator)
ABI_LIST="arm64-v8a armeabi-v7a x86 x86_64" ./build.sh
```

## APK folder

`overlay-app/` repo me reference ke liye hai — **zip me bundle nahi hota**.

Purana APK uninstall (optional):
```bash
pm uninstall com.hivirtus.zygiskmode
```

## Future size cuts

- R8/minify agar minimal APK wapas aaye
- `overlay_ui.cpp` optimize / strip debug
- x86 ABIs sirf dev builds me
