package com.hivirtus.zygiskmode.overlay;

import android.webkit.JavascriptInterface;

import org.json.JSONObject;

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
    public void closeMenu() {
        if (actions != null) actions.onClose();
    }

    @JavascriptInterface
    public void onSaved() {
        if (actions != null) actions.onSaved();
    }
}
