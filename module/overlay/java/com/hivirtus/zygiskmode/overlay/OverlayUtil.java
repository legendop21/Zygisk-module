package com.hivirtus.zygiskmode.overlay;

import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

/** Overlay window type fallback — shell / SystemUI / app_process sab par kaam kare */
public final class OverlayUtil {

    private static final String TAG = "VirtusOverlay";

    private OverlayUtil() {}

    public static int primaryOverlayType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    public static void addView(WindowManager wm, View root, WindowManager.LayoutParams lp) {
        lp.type = primaryOverlayType();
        try {
            wm.addView(root, lp);
            Log.i(TAG, "Overlay added (type=" + lp.type + ")");
            return;
        } catch (Throwable e) {
            Log.w(TAG, "Primary overlay type failed: " + e.getMessage());
        }

        final int[] fallbacks = {
                WindowManager.LayoutParams.TYPE_PRIORITY_PHONE,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.TYPE_PHONE
        };
        for (int type : fallbacks) {
            try {
                lp.type = type;
                wm.addView(root, lp);
                Log.i(TAG, "Overlay added with fallback type=" + type);
                return;
            } catch (Throwable e) {
                Log.w(TAG, "Fallback type " + type + " failed: " + e.getMessage());
            }
        }
        Log.e(TAG, "All overlay window types failed");
    }
}
