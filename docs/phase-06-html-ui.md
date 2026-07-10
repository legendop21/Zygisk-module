# Phase 6 — HTML UI Customize (Hinglish)

## UI edit kaise karein

Module flash ke baad path:
```
/data/adb/modules/hivirtus_zygisk_mode/overlay/ui/
```

### Colors badalna
`app.css` mein `:root` variables:
```css
--accent: #00d4aa;   /* primary button / toggle */
--bg: #0c0c0e;       /* panel background */
--warn: #ff8c42;     /* fake success highlight */
```

### Naya toggle add karna
1. `index.html` — row + `data-key="tumhara_key"`
2. `config.json` mein default value
3. Native hook agar chahiye → `config.cpp` mein parse

### Tab add karna
`index.html` → nav button `data-tab="naya"`
→ section `id="panel-naya"`
→ `app.js` tabs already handle generic `data-tab`

## AI-slop se bachne ke rules (jo follow kiye)

- System font stack — koi fake Google Font CDN nahi (offline)
- Kam colors — ek accent, ek warn
- Short labels — "Fake success" not "Enable Advanced SMS Interception Module"
- No lorem, no emoji spam
- Real config keys — jo Zygisk padhta hai wahi `data-key`

## Rebuild

HTML change ke baad **reboot zaroori nahi** — sirf menu band karke dubara bubble tap.

Dex/Java change ho to `./build.sh` dubara.

## Wapas phase list

| Phase | File |
|-------|------|
| 1 | [Install](phase-01-install.md) |
| 2 | [Floating HTML](phase-02-floating-window.md) |
| 3 | [SMS](phase-03-sms-intercept.md) |
| 4 | [Config](phase-04-local-config.md) |
| 5 | [Build size](phase-05-build-size.md) |
| 6 | Ye file — UI edit |
