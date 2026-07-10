package com.hivirtus.zygiskmode.overlay;

import android.content.Context;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * service.sh se app_process ke through start — Zygisk band ho tab bhi bubble chale.
 */
public final class OverlayDaemon {

    private static final String TAG = "VirtusOverlay";

    private OverlayDaemon() {}

    private static Context obtainContext() throws ReflectiveOperationException {
        Class<?> atClass = Class.forName("android.app.ActivityThread");
        Method systemMain = atClass.getDeclaredMethod("systemMain");
        Object thread = systemMain.invoke(null);

        Method getSystemContext = atClass.getDeclaredMethod("getSystemContext");
        Context ctx = (Context) getSystemContext.invoke(thread);
        if (ctx != null) return ctx;

        Method getApplication = atClass.getDeclaredMethod("getApplication");
        return (Context) getApplication.invoke(thread);
    }

    public static void main(String[] args) {
        try {
            Log.i(TAG, "OverlayDaemon starting uid=" + Process.myUid());
            Looper.prepareMainLooper();
            Context ctx = obtainContext();
            if (ctx == null) {
                Log.e(TAG, "No context available");
                return;
            }
            Log.i(TAG, "Context package=" + ctx.getPackageName());
            OverlayBootstrap.start(ctx);
            Log.i(TAG, "OverlayDaemon running — bubble should appear");
            Looper.loop();
        } catch (Throwable t) {
            Log.e(TAG, "OverlayDaemon crash: " + t.getMessage(), t);
        }
    }
}
