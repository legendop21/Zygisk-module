package com.hivirtus.zygiskmode.overlay;

import android.content.Context;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

/**
 * service.sh se app_process — Zygisk / zygote crash par bhi bubble + HTML menu.
 */
public final class OverlayDaemon {

    private static final String TAG = "VirtusOverlay";
    private static final String ALIVE = "/data/local/tmp/hivirtus_overlay_alive.flag";

    private OverlayDaemon() {}

    private static Context obtainContext() {
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method systemMain = atClass.getDeclaredMethod("systemMain");
            Object thread = systemMain.invoke(null);

            try {
                Method attach = atClass.getDeclaredMethod("attach", boolean.class, long.class);
                attach.invoke(thread, false, 0L);
            } catch (Throwable ignored) {}

            Context ctx = invokeContext(atClass, thread, "getSystemUiContext");
            if (ctx != null) return ctx;

            ctx = invokeContext(atClass, thread, "getSystemContext");
            if (ctx != null) return ctx;

            ctx = invokeContext(atClass, thread, "getApplication");
            if (ctx != null) return ctx;

            Method current = atClass.getDeclaredMethod("currentActivityThread");
            Object cur = current.invoke(null);
            if (cur != null) {
                ctx = invokeContext(atClass, cur, "getSystemContext");
                if (ctx != null) return ctx;
            }
        } catch (Throwable t) {
            Log.e(TAG, "obtainContext failed: " + t.getMessage(), t);
        }
        return null;
    }

    private static Context invokeContext(Class<?> atClass, Object thread, String method)
            throws ReflectiveOperationException {
        Method m = atClass.getDeclaredMethod(method);
        Object result = m.invoke(thread);
        return result instanceof Context ? (Context) result : null;
    }

    private static void touchAlive() {
        try (FileOutputStream out = new FileOutputStream(ALIVE)) {
            out.write(String.valueOf(System.currentTimeMillis()).getBytes());
        } catch (Throwable ignored) {}
    }

    public static void main(String[] args) {
        try {
            Log.i(TAG, "OverlayDaemon uid=" + Process.myUid() + " pid=" + Process.myPid());
            Looper.prepareMainLooper();

            int wait = 0;
            while (wait < 90) {
                String boot = System.getenv("BOOT_COMPLETED");
                if ("1".equals(boot)) break;
                try {
                    File bootFile = new File("/data/local/tmp/hivirtus_boot_ready.flag");
                    if (bootFile.exists()) break;
                } catch (Throwable ignored) {}
                if (wait == 0 || wait == 15 || wait == 30) {
                    Log.i(TAG, "Waiting for boot ready... " + wait + "s");
                }
                Thread.sleep(1000);
                wait++;
            }

            Context ctx = obtainContext();
            if (ctx == null) {
                Log.e(TAG, "No context — retry in 5s");
                Thread.sleep(5000);
                ctx = obtainContext();
            }
            if (ctx == null) {
                Log.e(TAG, "Context still null, exiting");
                return;
            }

            Log.i(TAG, "Context pkg=" + ctx.getPackageName());
            touchAlive();
            OverlayBootstrap.start(ctx);

            handlerAliveLoop();
            Looper.loop();
        } catch (Throwable t) {
            Log.e(TAG, "OverlayDaemon crash: " + t.getMessage(), t);
        }
    }

    private static void handlerAliveLoop() {
        new android.os.Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                touchAlive();
                OverlayBootstrap.ensureVisible();
                new android.os.Handler(Looper.getMainLooper()).postDelayed(this, 10000);
            }
        }, 10000);
    }
}
