package com.hivirtus.zygisk;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Force-show floating bubble.
 * - ActivityLifecycleCallbacks (Android 14+ reliable)
 * - TOP-LEFT position (keyboard/landscape me right side hide hota tha)
 * - SYSTEM_ALERT_WINDOW overlay fallback
 */
public class HivirtusUiHelper {
    private static final String TAG_BUBBLE = "hivirtus_bubble_v";
    private static final String TAG_MENU = "hivirtus_menu_v";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static boolean menuOpen = false;
    private static boolean lifecycleRegistered = false;
    private static View sBubbleWm;
    private static View sMenuWm;
    private static WindowManager sWm;
    private static View sBubbleOverlay;
    private static View sMenuOverlay;
    private static WindowManager sOverlayWm;

    public static void schedule(final Activity activity) {
        if (activity == null) return;
        try {
            if (activity.isFinishing()) return;
        } catch (Throwable ignored) {
        }
        ensureLifecycle(activity.getApplicationContext());
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    attach(activity);
                } catch (Throwable t) {
                    writeDebug("ui_fail:" + safeMsg(t));
                    tryAttachMinimal(activity);
                }
            }
        });
    }

    public static void poll(final Context appCtx) {
        if (appCtx == null) return;
        ensureLifecycle(appCtx);
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                Activity act = findResumedActivity();
                if (act != null) {
                    writeDebug("ui_poll_found:" + act.getClass().getName());
                    attach(act);
                } else {
                    writeDebug("ui_poll_no_activity");
                }
            }
        });
    }

    /** Called repeatedly from native keepalive */
    public static void tick() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Context app = currentApp();
                    if (app != null) ensureLifecycle(app);
                    Activity act = findResumedActivity();
                    if (act != null) attach(act);
                } catch (Throwable t) {
                    writeDebug("ui_tick_fail:" + safeMsg(t));
                }
            }
        });
    }

    private static Context currentApp() {
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Object app = atCls.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) return (Context) app;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Register once — har onResume pe bubble force */
    public static void ensureLifecycle(Context ctx) {
        if (lifecycleRegistered || ctx == null) return;
        try {
            Context appCtx = ctx.getApplicationContext();
            if (!(appCtx instanceof Application)) return;
            final Application app = (Application) appCtx;
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity a, Bundle b) {}
                @Override public void onActivityStarted(Activity a) {
                    tryAttachSoon(a);
                }
                @Override public void onActivityResumed(Activity a) {
                    writeDebug("ui_lifecycle_resume:" + a.getClass().getSimpleName());
                    tryAttachSoon(a);
                }
                @Override public void onActivityPaused(Activity a) {}
                @Override public void onActivityStopped(Activity a) {}
                @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                @Override public void onActivityDestroyed(Activity a) {}
            });
            lifecycleRegistered = true;
            writeDebug("ui_lifecycle_ok");
        } catch (Throwable t) {
            writeDebug("ui_lifecycle_fail:" + safeMsg(t));
        }
    }

    private static void tryAttachSoon(final Activity a) {
        if (a == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    attach(a);
                } catch (Throwable t) {
                    writeDebug("ui_attach_fail:" + safeMsg(t));
                }
            }
        });
        // Window token late aata hai — 2nd pass
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    attach(a);
                } catch (Throwable ignored) {
                }
            }
        }, 600);
    }

    private static Activity findResumedActivity() {
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Object at = atCls.getMethod("currentActivityThread").invoke(null);
            if (at == null) return null;

            Field actsField = atCls.getDeclaredField("mActivities");
            actsField.setAccessible(true);
            Object map = actsField.get(at);
            if (map == null) return null;

            Method sizeM = map.getClass().getMethod("size");
            Method valueAt = map.getClass().getMethod("valueAt", int.class);
            int size = (Integer) sizeM.invoke(map);

            Activity fallback = null;
            for (int i = size - 1; i >= 0; i--) {
                Object record = valueAt.invoke(map, i);
                if (record == null) continue;
                Field actF = record.getClass().getDeclaredField("activity");
                actF.setAccessible(true);
                Object act = actF.get(record);
                if (!(act instanceof Activity)) continue;
                Activity a = (Activity) act;
                if (a.isFinishing()) continue;
                boolean paused = true;
                try {
                    Field pausedF = record.getClass().getDeclaredField("paused");
                    pausedF.setAccessible(true);
                    paused = pausedF.getBoolean(record);
                } catch (Throwable ignored) {
                    paused = false;
                }
                if (!paused) return a;
                if (fallback == null) fallback = a;
            }
            return fallback;
        } catch (Throwable t) {
            writeDebug("ui_find_fail:" + safeMsg(t));
        }
        return null;
    }

    public static void attach(Activity activity) {
        if (activity == null) return;
        try {
            if (activity.isFinishing()) return;
        } catch (Throwable ignored) {
        }

        if (hasBubble(activity)) {
            bringBubbleFront(activity);
            return;
        }

        // 0) SYSTEM overlay — keyboard ke upar, landscape me bhi dikhe
        if (canOverlay(activity) && addBubbleSystemOverlay(activity)) {
            writeDebug("ui_bubble_ok_overlay");
            return;
        }

        // 1) content / decor
        ViewGroup content = null;
        try {
            View c = activity.findViewById(android.R.id.content);
            if (c instanceof ViewGroup) content = (ViewGroup) c;
        } catch (Throwable ignored) {
        }
        ViewGroup decor = null;
        try {
            View d = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
            if (d instanceof ViewGroup) decor = (ViewGroup) d;
        } catch (Throwable ignored) {
        }
        ViewGroup root = content != null ? content : decor;
        if (root != null && addBubbleToParent(activity, root)) {
            writeDebug("ui_bubble_ok_decor");
            return;
        }

        // 2) TYPE_APPLICATION_PANEL
        if (addBubbleViaWm(activity)) {
            writeDebug("ui_bubble_ok_wm");
            return;
        }

        writeDebug("ui_bubble_all_failed");
    }

    private static boolean canOverlay(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                return Settings.canDrawOverlays(ctx);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean hasBubble(Activity activity) {
        try {
            if (sBubbleOverlay != null && sBubbleOverlay.getParent() != null) return true;
            if (sBubbleWm != null && sBubbleWm.getParent() != null) return true;
            View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
            if (decor != null && decor.findViewWithTag(TAG_BUBBLE) != null) return true;
            View content = activity.findViewById(android.R.id.content);
            if (content != null && content.findViewWithTag(TAG_BUBBLE) != null) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void bringBubbleFront(Activity activity) {
        try {
            View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
            View b = decor != null ? decor.findViewWithTag(TAG_BUBBLE) : null;
            if (b == null) {
                View content = activity.findViewById(android.R.id.content);
                if (content != null) b = content.findViewWithTag(TAG_BUBBLE);
            }
            if (b != null) {
                b.setVisibility(View.VISIBLE);
                b.bringToFront();
                b.setAlpha(1f);
            }
            if (sBubbleOverlay != null) sBubbleOverlay.setVisibility(View.VISIBLE);
            if (sBubbleWm != null) sBubbleWm.setVisibility(View.VISIBLE);
        } catch (Throwable ignored) {
        }
    }

    /** TOP-LEFT — OTP keyboard right pe hota hai, bubble left pe dikhe */
    private static FrameLayout.LayoutParams bubbleLp(Activity activity, int size) {
        float d = activity.getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = (int) (14 * d);
        lp.topMargin = (int) (96 * d); // status/cutout ke neeche
        return lp;
    }

    private static boolean addBubbleToParent(final Activity activity, ViewGroup root) {
        try {
            float d = activity.getResources().getDisplayMetrics().density;
            int size = (int) (58 * d);

            final TextView bubble = makeBubbleView(activity, size);
            final FrameLayout menuHost = makeMenuHost(activity);

            FrameLayout.LayoutParams menuLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

            bubble.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleMenu(menuHost);
                }
            });

            root.addView(bubble, bubbleLp(activity, size));
            root.addView(menuHost, menuLp);
            bubble.bringToFront();
            bubble.setVisibility(View.VISIBLE);
            bubble.setAlpha(1f);
            try {
                bubble.setZ(999f);
            } catch (Throwable ignored) {
            }

            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        fillMenuWebView(activity, menuHost);
                    } catch (Throwable t) {
                        writeDebug("ui_webview_fail:" + safeMsg(t));
                        fillMenuFallback(activity, menuHost);
                    }
                }
            }, 400);
            return true;
        } catch (Throwable t) {
            writeDebug("ui_add_parent_fail:" + safeMsg(t));
            return false;
        }
    }

    private static boolean addBubbleSystemOverlay(final Activity activity) {
        try {
            if (sBubbleOverlay != null && sBubbleOverlay.getParent() != null) return true;
            float d = activity.getResources().getDisplayMetrics().density;
            int size = (int) (58 * d);
            final TextView bubble = makeBubbleView(activity, size);
            final FrameLayout menuHost = makeMenuHost(activity);

            WindowManager wm = (WindowManager) activity.getApplicationContext()
                    .getSystemService(Context.WINDOW_SERVICE);
            sOverlayWm = wm;

            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            WindowManager.LayoutParams blp = new WindowManager.LayoutParams(
                    size, size, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            // TOP-LEFT — keyboard se door
            blp.gravity = Gravity.TOP | Gravity.START;
            blp.x = (int) (14 * d);
            blp.y = (int) (96 * d);
            if (Build.VERSION.SDK_INT >= 28) {
                blp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }

            WindowManager.LayoutParams mlp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            mlp.gravity = Gravity.CENTER;

            bubble.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    menuOpen = !menuOpen;
                    menuHost.setVisibility(menuOpen ? View.VISIBLE : View.GONE);
                    writeDebug(menuOpen ? "ui_menu_open" : "ui_menu_close");
                }
            });

            wm.addView(menuHost, mlp);
            menuHost.setVisibility(View.GONE);
            wm.addView(bubble, blp);
            sBubbleOverlay = bubble;
            sMenuOverlay = menuHost;

            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        fillMenuWebView(activity, menuHost);
                    } catch (Throwable t) {
                        fillMenuFallback(activity, menuHost);
                    }
                }
            }, 400);
            return true;
        } catch (Throwable t) {
            writeDebug("ui_overlay_fail:" + safeMsg(t));
            return false;
        }
    }

    private static boolean addBubbleViaWm(final Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            android.os.IBinder token = decor.getWindowToken();
            if (token == null) {
                decor.post(new Runnable() {
                    @Override
                    public void run() {
                        tryAddWm(activity);
                    }
                });
                writeDebug("ui_wm_wait_token");
                return false;
            }
            return tryAddWm(activity);
        } catch (Throwable t) {
            writeDebug("ui_wm_fail:" + safeMsg(t));
            return false;
        }
    }

    private static boolean tryAddWm(final Activity activity) {
        try {
            if (sBubbleWm != null && sBubbleWm.getParent() != null) return true;
            float d = activity.getResources().getDisplayMetrics().density;
            int size = (int) (58 * d);
            final TextView bubble = makeBubbleView(activity, size);
            final FrameLayout menuHost = makeMenuHost(activity);

            WindowManager wm = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
            sWm = wm;

            WindowManager.LayoutParams blp = new WindowManager.LayoutParams(
                    size, size,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            blp.gravity = Gravity.TOP | Gravity.START;
            blp.x = (int) (14 * d);
            blp.y = (int) (96 * d);
            blp.token = activity.getWindow().getDecorView().getWindowToken();
            if (Build.VERSION.SDK_INT >= 28) {
                blp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }

            WindowManager.LayoutParams mlp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            mlp.token = blp.token;
            mlp.gravity = Gravity.CENTER;

            bubble.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    menuOpen = !menuOpen;
                    menuHost.setVisibility(menuOpen ? View.VISIBLE : View.GONE);
                    writeDebug(menuOpen ? "ui_menu_open" : "ui_menu_close");
                }
            });

            wm.addView(menuHost, mlp);
            menuHost.setVisibility(View.GONE);
            wm.addView(bubble, blp);
            sBubbleWm = bubble;
            sMenuWm = menuHost;

            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        fillMenuWebView(activity, menuHost);
                    } catch (Throwable t) {
                        fillMenuFallback(activity, menuHost);
                    }
                }
            }, 400);
            return true;
        } catch (Throwable t) {
            writeDebug("ui_try_wm_fail:" + safeMsg(t));
            return false;
        }
    }

    private static void tryAttachMinimal(Activity activity) {
        try {
            ViewGroup content = (ViewGroup) activity.findViewById(android.R.id.content);
            if (content == null) return;
            float d = activity.getResources().getDisplayMetrics().density;
            int size = (int) (58 * d);
            TextView bubble = makeBubbleView(activity, size);
            content.addView(bubble, bubbleLp(activity, size));
            writeDebug("ui_minimal_ok");
        } catch (Throwable t) {
            writeDebug("ui_minimal_fail:" + safeMsg(t));
        }
    }

    private static TextView makeBubbleView(Context ctx, int size) {
        TextView bubble = new TextView(ctx);
        bubble.setTag(TAG_BUBBLE);
        bubble.setText("V");
        bubble.setTextSize(22f);
        bubble.setGravity(Gravity.CENTER);
        bubble.setElevation(48f);
        bubble.setClickable(true);
        bubble.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFD700);
        bg.setCornerRadius(size);
        bg.setStroke((int) (2 * ctx.getResources().getDisplayMetrics().density), 0xFF1A1408);
        bubble.setBackground(bg);
        bubble.setTextColor(0xFF1A1408);
        bubble.getPaint().setFakeBoldText(true);
        return bubble;
    }

    private static FrameLayout makeMenuHost(Context ctx) {
        FrameLayout menuHost = new FrameLayout(ctx);
        menuHost.setTag(TAG_MENU);
        menuHost.setVisibility(View.GONE);
        menuHost.setClickable(true);
        menuHost.setBackgroundColor(0xCC000000);
        return menuHost;
    }

    private static void toggleMenu(FrameLayout menuHost) {
        menuOpen = !menuOpen;
        menuHost.setVisibility(menuOpen ? View.VISIBLE : View.GONE);
        if (menuOpen) menuHost.bringToFront();
        writeDebug(menuOpen ? "ui_menu_open" : "ui_menu_close");
    }

    private static void fillMenuWebView(Activity activity, FrameLayout menuHost) {
        if (menuHost.getChildCount() > 0) return;
        WebView web = new WebView(activity);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        try {
            HivirtusJsBridge.attach(web);
        } catch (Throwable ignored) {
        }
        String ui = "/data/adb/modules/hivirtus_zygisk_mode/ui/index.html";
        if (new File(ui).canRead()) {
            web.loadUrl("file://" + ui);
        } else {
            fillMenuFallback(activity, menuHost);
            return;
        }
        menuHost.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        writeDebug("ui_webview_ok");
    }

    private static void fillMenuFallback(Activity activity, FrameLayout menuHost) {
        if (menuHost.getChildCount() > 0) return;
        TextView tv = new TextView(activity);
        tv.setText("Virtus Zygisk Mode\n\nTap V again to close.\nRe-flash if menu HTML missing.");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(16f);
        tv.setPadding(48, 48, 48, 48);
        tv.setBackgroundColor(0xFF090B12);
        menuHost.addView(tv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        writeDebug("ui_fallback_menu");
    }

    private static String safeMsg(Throwable t) {
        return t == null ? "?" : String.valueOf(t.getMessage());
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
