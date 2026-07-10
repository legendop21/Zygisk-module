package com.hivirtus.zygiskmode.overlay;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

/**
 * Bubble + HTML menu — Zygisk / zygote crash se independent, hamesha visible.
 */
public final class OverlayBootstrap {

    private static final String TAG = "VirtusOverlay";
    private static final long WATCHDOG_MS = 12000;

    private static FloatBubble bubble;
    private static FloatMenu menu;
    private static Context appCtx;
    private static boolean started;
    private static final Handler handler = new Handler(Looper.getMainLooper());

    private OverlayBootstrap() {}

    public static void start(Context context) {
        if (context == null) return;
        appCtx = context.getApplicationContext();
        if (!started) {
            started = true;
            Log.i(TAG, "OverlayBootstrap start — always-on mode");
        }
        scheduleShow(0);
        scheduleWatchdog();
    }

    public static void stop(Context context) {
        started = false;
        handler.removeCallbacksAndMessages(null);
        if (bubble != null) bubble.hide();
        if (menu != null) menu.hide();
        bubble = null;
        menu = null;
        appCtx = null;
    }

    public static void ensureVisible() {
        if (appCtx == null) return;
        try {
            if (bubble == null || !bubble.isShowing()) {
                if (bubble != null) bubble.hide();
                bubble = new FloatBubble(appCtx, OverlayBootstrap::openMenu);
                bubble.show();
                Log.i(TAG, "Watchdog: bubble restored");
            }
            if (alwaysShowMenu() && (menu == null || !menu.isShowing())) {
                openMenu();
                Log.i(TAG, "Watchdog: menu restored");
            }
        } catch (Throwable t) {
            Log.e(TAG, "ensureVisible failed: " + t.getMessage(), t);
        }
    }

    private static void scheduleShow(long delayMs) {
        handler.postDelayed(() -> {
            ensureVisible();
            handler.postDelayed(OverlayBootstrap::ensureVisible, 3000);
            handler.postDelayed(OverlayBootstrap::ensureVisible, 8000);
        }, delayMs);
    }

    private static void scheduleWatchdog() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!started || appCtx == null) return;
                ensureVisible();
                handler.postDelayed(this, WATCHDOG_MS);
            }
        }, WATCHDOG_MS);
    }

    private static void openMenu() {
        if (appCtx == null) return;
        if (menu != null && menu.isShowing()) return;
        if (menu != null) menu.hide();
        menu = new FloatMenu(appCtx, () -> {
            menu = null;
            if (alwaysShowMenu()) {
                handler.postDelayed(OverlayBootstrap::openMenu, 2500);
            }
        });
        menu.show();
    }

    private static boolean alwaysShowMenu() {
        try {
            JSONObject cfg = LocalConfig.load();
            return LocalConfig.getBool(cfg, "always_show_menu", true);
        } catch (Throwable t) {
            return true;
        }
    }
}
