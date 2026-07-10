# Phase 5 — SMS Intercept Fix (Hinglish)

## Problem kya tha

Telegram pe token aa raha tha **lekin real SIM se bhi SMS chala jata tha**.

Matlab: block **partial** tha — `pipeline_outgoing` chal gaya, par `orig_BinderProxy_transact` bhi call ho gaya (ya doosri path se send).

## Root cause

1. **Parse fail = no block** — `read_isms_outgoing` fail → SMS allow
2. **Encrypted token** — keyword list me nahi milta (YESPRO, UPI, etc.)
3. **Fragile banking (YesPay)** — UPI ke andar **zero hooks** → sirf phone process pe depend
4. **appops on com.android.phone** — ineffective, confuse karta tha

## Fix (v2.70)

| Change | File |
|--------|------|
| `intercept_fake_success` ON → **har ISms transact block** (parse fail bhi) | `outgoing_sms_hook.cpp` → `try_block_isms()` |
| Encrypted/base64 body detect | `body_has_verify_token()` |
| Fragile UPI → **SMS-only in-app BinderProxy hook** | `main.cpp`, `virtual_sim.cpp` |
| Phone appops deny hataaya | `service.sh` |
| Fake success → app ko OK, real radio nahi | `fake_success.cpp` (pehle se) |

## Flow ab

```
UPI app → ISms BinderProxy.transact
    ↓
try_block_isms() → intercept_fake_success?
    ↓ YES
pipeline_outgoing → blocked.json + Telegram
write_ok_reply → app ko success
orig transact NAHI call
    ↓
Real SIM se SMS NAHI
```

## Config zaroori

```json
{
  "intercept_fake_success": true,
  "hook_outgoing_sms": true,
  "mock_phone_sim1": "8279999125",
  "enable_sim1_mock": true
}
```

Menu se **Fake success (intercept)** ON rakho.

## Debug

```bash
# Block hua?
cat /data/local/tmp/hivirtus_outgoing_blocked.json

# Log
cat /data/local/tmp/hivirtus_zygisk_mode.log | grep OutgoingSms
```

`Intercept-all ISms block` = fix lag gaya.
