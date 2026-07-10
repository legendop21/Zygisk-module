package com.hivirtus.zygiskmode.overlay;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/** HTML floating panel — modern UI, local file, no APK */
public final class FloatMenu {

    public interface OnCloseListener { void onClose(); }

    private static final String UI_INDEX = LocalConfig.MODULE_UI + "/index.html";

    private final Context ctx;
    private final OnCloseListener onClose;
    private WindowManager wm;
    private FrameLayout root;
    private WebView webView;
    private JsBridge bridge;

    public FloatMenu(Context context, OnCloseListener listener) {
        this.ctx = context.getApplicationContext();
        this.onClose = listener;
    }

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    public void show() {
        if (root != null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                int w = dp(340);
                int h = dp(520);

                root = new FrameLayout(ctx);
                root.setBackgroundColor(Color.TRANSPARENT);

                webView = new WebView(ctx);
                webView.setBackgroundColor(Color.TRANSPARENT);
                WebSettings ws = webView.getSettings();
                ws.setJavaScriptEnabled(true);
                ws.setDomStorageEnabled(true);
                ws.setAllowFileAccess(true);
                ws.setAllowFileAccessFromFileURLs(true);
                ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);
                }

                bridge = new JsBridge(new JsBridge.MenuActions() {
                    @Override public void onClose() { hide(); }
                    @Override public void onSaved() { /* config synced */ }
                });
                webView.addJavascriptInterface(bridge, "VirtusBridge");
                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, String url) {
                        return !url.startsWith("file://");
                    }
                });

                webView.loadUrl("file://" + UI_INDEX);
                root.addView(webView, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));

                int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;

                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        w, h, type,
                        WindowManager.LayoutParams.FLAG_DIM_BEHIND
                                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
                lp.dimAmount = 0.5f;
                lp.gravity = Gravity.CENTER;

                wm.addView(root, lp);
            } catch (Exception ignored) {}
        });
    }

    public void hide() {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (webView != null) {
                    webView.removeJavascriptInterface("VirtusBridge");
                    webView.destroy();
                }
                if (root != null && wm != null) wm.removeView(root);
            } catch (Exception ignored) {}
            webView = null;
            root = null;
            bridge = null;
            if (onClose != null) onClose.onClose();
        });
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
