package com.hivirtus.zygiskmode.overlay;

import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.io.File;

/** HTML UI ↔ local config.json bridge */
public final class JsBridge {

    public interface MenuActions {
        void onClose();
        void onSaved();
    }

    private final MenuActions actions;
    private JSONObject config;

    public JsBridge(MenuActions actions) {
        this.actions = actions;
        this.config = LocalConfig.load();
    }

    @JavascriptInterface
    public String getConfig() {
        if (config == null) config = LocalConfig.load();
        return config.toString();
    }

    @JavascriptInterface
    public boolean saveConfig(String json) {
        try {
            config = new JSONObject(json);
            boolean ok = LocalConfig.save(config);
            if (ok && actions != null) actions.onSaved();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public String getZygiskStatus() {
        if (new File("/data/local/tmp/hivirtus_zygisk_stopped.flag").exists()) {
            return "zygisk offline · bubble always-on";
        }
        if (new File("/data/local/tmp/hivirtus_zygisk_active.flag").exists()) {
            return "zygisk active · hooks OK";
        }
        if (new File("/data/local/tmp/hivirtus_overlay_alive.flag").exists()) {
            return "overlay alive · zygisk unknown";
        }
        return "module ready · local";
    }

    @JavascriptInterface
    public void closeMenu() {
        if (actions != null) actions.onClose();
    }

    @JavascriptInterface
    public void onSaved() {
        if (actions != null) actions.onSaved();
    }
}
