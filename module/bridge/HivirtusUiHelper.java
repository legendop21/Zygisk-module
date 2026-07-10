package com.hivirtus.zygisk;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.File;

/**
 * Force floating bubble on Android 14+ (Activity PLT hooks don't exist).
 * Called from Zygisk native via DexClassLoader.
 */
public class HivirtusUiHelper {
    private static final String TAG_BUBBLE = "hivirtus_bubble";
    private static final String TAG_MENU = "hivirtus_menu_panel";
    private static final int BUBBLE_ID = 0x48565201;
    private static final int MENU_ID = 0x48565202;
    private static boolean menuOpen = false;

    public static void schedule(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        try {
            if (activity.isDestroyed()) return;
        } catch (Throwable ignored) {
        }
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    attach(activity);
                } catch (Throwable t) {
                    writeDebug("ui_helper_fail:" + t.getMessage());
                }
            }
        });
    }

    public static void attach(Activity activity) {
        if (activity == null) return;
        View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
        if (!(decor instanceof ViewGroup)) {
            writeDebug("ui_helper_no_decor");
            return;
        }
        ViewGroup root = (ViewGroup) decor;
        if (root.findViewWithTag(TAG_BUBBLE) != null) {
            writeDebug("ui_helper_bubble_exists");
            return;
        }

        float d = activity.getResources().getDisplayMetrics().density;
        int size = (int) (52 * d);

        TextView bubble = new TextView(activity);
        bubble.setTag(TAG_BUBBLE);
        bubble.setId(BUBBLE_ID);
        bubble.setText("V");
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(18f);
        bubble.setGravity(Gravity.CENTER);
        bubble.setElevation(30f);
        bubble.setClickable(true);
        bubble.setFocusable(true);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1A237E);
        bg.setCornerRadius(size);
        bubble.setBackground(bg);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = (int) (12 * d);
        lp.topMargin = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.28f);

        final FrameLayout menuHost = new FrameLayout(activity);
        menuHost.setTag(TAG_MENU);
        menuHost.setId(MENU_ID);
        menuHost.setVisibility(View.GONE);
        menuHost.setClickable(true);
        menuHost.setBackgroundColor(0x99000000);
        FrameLayout.LayoutParams menuLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        WebView web = new WebView(activity);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        HivirtusJsBridge.attach(web);
        String ui = "/data/adb/modules/hivirtus_zygisk_mode/ui/index.html";
        if (new File(ui).canRead()) {
            web.loadUrl("file://" + ui);
        } else {
            web.loadData("<html><body style='background:#090b12;color:#fff;padding:24px;font-family:sans-serif'>"
                    + "<h2>Virtus Zygisk Mode</h2><p>UI files missing. Re-flash module.</p></body></html>",
                    "text/html", "utf-8");
        }
        menuHost.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        bubble.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                menuOpen = !menuOpen;
                menuHost.setVisibility(menuOpen ? View.VISIBLE : View.GONE);
                writeDebug(menuOpen ? "ui_helper_menu_open" : "ui_helper_menu_close");
            }
        });

        root.addView(menuHost, menuLp);
        root.addView(bubble, lp);
        bubble.bringToFront();
        writeDebug("ui_helper_bubble_ok");
    }

    public static void poll(final Context appCtx) {
        if (appCtx == null) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Object at = Class.forName("android.app.ActivityThread")
                            .getMethod("currentActivityThread").invoke(null);
                    if (at == null) return;
                    Object map = at.getClass().getDeclaredField("mActivities").get(at);
                    if (map == null) return;
                    int size = (Integer) map.getClass().getMethod("size").invoke(map);
                    for (int i = size - 1; i >= 0; i--) {
                        Object record = map.getClass().getMethod("valueAt", int.class).invoke(map, i);
                        if (record == null) continue;
                        boolean paused = record.getClass().getDeclaredField("paused").getBoolean(record);
                        if (paused) continue;
                        Object act = record.getClass().getDeclaredField("activity").get(record);
                        if (act instanceof Activity) {
                            attach((Activity) act);
                            return;
                        }
                    }
                } catch (Throwable t) {
                    writeDebug("ui_helper_poll_fail:" + t.getMessage());
                }
            }
        });
    }

    private static void writeDebug(String msg) {
        try {
            java.io.FileWriter w = new java.io.FileWriter("/data/local/tmp/hivirtus_overlay.debug", true);
            w.write(msg + "\n");
            w.close();
        } catch (Throwable ignored) {
        }
    }
}
