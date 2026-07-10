package com.hivirtus.zygisk;

import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
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
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(SAVE_PATH), StandardCharsets.UTF_8)) {
            w.write(json);
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
