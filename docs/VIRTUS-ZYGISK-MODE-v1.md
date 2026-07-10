# Virtus Zygisk Mode v1.0.0

SMSTweaksFinal.apk ka **poora logic** — ab **Zygisk** pe (bina LSPosed).

## Features (SMSTweaks 1:1)

| Feature | Kaise |
|---------|--------|
| Fake Phone Number | `TelephonyManager.getLine1Number` / `getMsisdn` JNI hook |
| Outgoing SMS Prefix | Body pe prefix before block/send |
| Incoming Sender ID | `SmsMessage.getOriginatingAddress` hook |
| Intercept & Fake Success | ISms BinderProxy block + fake OK reply + insert sent SMS |
| Telegram Forward | `service.sh` curl → bot |

## Floating menu

- Flash zip → reboot
- Home / UPI app pe **navy V bubble** (left)
- Tap → modern HTML menu (Basic / Advanced / Telegram)
- Save → config sync

## ABIs

- `zygisk/arm64-v8a.so` (64-bit)
- `zygisk/armeabi-v7a.so` (32-bit)

## No LSPosed

Pure Magisk / APatch / KernelSU **Zygisk** module.
