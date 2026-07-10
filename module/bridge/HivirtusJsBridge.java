package com.hivirtus.zygisk;

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

/** Tiny bridge — WebView HTML UI ↔ module config files (no APK). */
public class HivirtusJsBridge {
    private static final String SAVE_PATH = "/data/local/tmp/hivirtus_ui_save.json";
    private static final String RUNTIME_CFG = "/data/local/tmp/hivirtus_zygisk_mode_config.json";
    private static final String MODULE_CFG = "/data/adb/modules/hivirtus_zygisk_mode/config.json";
    private static final String SPOOF_PHONE = "/data/local/tmp/hivirtus_spoof_phone.txt";

    @JavascriptInterface
    public String readConfig() {
        File f = new File(RUNTIME_CFG);
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
        // 1) Pending save for native apply_ui_save_file()
        writeUtf8(SAVE_PATH, json);
        // 2) Immediate runtime config so hooks pick up on next reload()
        try {
            JSONObject incoming = new JSONObject(json);
            JSONObject merged = new JSONObject(readConfig());
            java.util.Iterator<String> keys = incoming.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                merged.put(k, incoming.get(k));
            }
            // SMSTweaks → Virtus mirrors
            if (merged.optBoolean("enable_sim1_mock", false)
                    || merged.optBoolean("fake_number_enabled", false)) {
                merged.put("enable_sim1_mock", true);
                merged.put("enable_phone_spoof", true);
                String phone = merged.optString("mock_phone_sim1", "");
                if (phone != null && phone.replaceAll("[^0-9]", "").length() >= 10) {
                    merged.put("enable_virtual_sim", true);
                    writeUtf8(SPOOF_PHONE, phone.trim() + "\n");
                }
            }
            if (merged.optBoolean("intercept_enabled", false)
                    || merged.optBoolean("intercept_fake_success", false)) {
                merged.put("intercept_fake_success", true);
                merged.put("hook_outgoing_sms", true);
            }
            if (merged.optBoolean("sender_id_enabled", false)
                    || merged.optBoolean("override_incoming_sender", false)) {
                merged.put("override_incoming_sender", true);
            }
            String sid = merged.optString("inject_sender_id", "");
            if ("AD-TEST-S".equals(sid)) merged.put("inject_sender_id", "");
            writeUtf8(RUNTIME_CFG, merged.toString(2));
            writeUtf8(SAVE_PATH, merged.toString(2));
        } catch (Exception e) {
            // Fallback: raw JSON already written to SAVE_PATH
            writeUtf8(RUNTIME_CFG, json);
        }
    }

    private static void writeUtf8(String path, String data) {
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(path), StandardCharsets.UTF_8)) {
            w.write(data);
        } catch (Exception ignored) {
        }
    }

    public static void attach(WebView webView) {
        if (webView == null) return;
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
