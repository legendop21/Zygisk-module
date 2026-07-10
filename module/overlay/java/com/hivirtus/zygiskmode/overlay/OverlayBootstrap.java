package com.hivirtus.zygiskmode.overlay;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * Zygisk SystemUI se call hota hai — embedded overlay.dex se.
 * Koi alag APK install nahi.
 */
public final class OverlayBootstrap {

    private static FloatBubble bubble;
    private static FloatMenu menu;
    private static boolean started;

    private OverlayBootstrap() {}

    public static void start(Context context) {
        if (context == null || started) return;
        started = true;
        Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            bubble = new FloatBubble(app, () -> {
                if (menu != null) menu.hide();
                menu = new FloatMenu(app, () -> menu = null);
                menu.show();
            });
            bubble.show();
        }, 2500);
    }

    public static void stop(Context context) {
        started = false;
        if (bubble != null) bubble.hide();
        if (menu != null) menu.hide();
        bubble = null;
        menu = null;
    }
}
