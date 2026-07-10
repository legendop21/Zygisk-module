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
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * Floating bubble + menu — Android 11 (API 30) through Android 16 (API 36).
 *
 * Order (permission-free first so menu always testable):
 * 1) decor/content TOP-LEFT
 * 2) TYPE_APPLICATION_PANEL
 * 3) TYPE_APPLICATION_OVERLAY (if granted)
 */
public class HivirtusUiHelper {
    private static final String TAG_BUBBLE = "_fb";
    private static final String TAG_MENU = "_fm";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static boolean menuOpen = false;
    private static boolean lifecycleRegistered = false;
    private static boolean loggedSdk = false;
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

    public static void ensureLifecycle(Context ctx) {
        if (lifecycleRegistered || ctx == null) return;
        try {
            Context appCtx = ctx.getApplicationContext();
            if (!(appCtx instanceof Application)) return;
            final Application app = (Application) appCtx;
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity a, Bundle b) {}
                @Override public void onActivityStarted(Activity a) { tryAttachSoon(a); }
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
            logEnv(app);
        } catch (Throwable t) {
            writeDebug("ui_lifecycle_fail:" + safeMsg(t));
        }
    }

    private static void logEnv(Context ctx) {
        if (loggedSdk) return;
        loggedSdk = true;
        try {
            String pkg = ctx.getPackageName();
            boolean ov = canOverlay(ctx);
            writeDebug("ui_env:sdk=" + Build.VERSION.SDK_INT
                    + " release=" + Build.VERSION.RELEASE
                    + " pkg=" + pkg
                    + " overlay=" + ov);
        } catch (Throwable t) {
            writeDebug("ui_env_fail:" + safeMsg(t));
        }
    }

    private static void tryAttachSoon(final Activity a) {
        if (a == null) return;
        final int[] delays = new int[] {0, 400, 1200, 2500, 4500, 7000};
        for (final int delay : delays) {
            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try { attach(a); } catch (Throwable t) {
                        writeDebug("ui_attach_fail:" + safeMsg(t));
                    }
                }
            }, delay);
        }
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
                try {
                    if (a.isFinishing()) continue;
                    if (Build.VERSION.SDK_INT >= 17 && a.isDestroyed()) continue;
                } catch (Throwable ignored) {}
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
        logEnv(activity);

        if (hasBubble(activity)) {
            bringBubbleFront(activity);
            return;
        }

        // ONLY decor/content — SYSTEM overlay skip (crash + detection)
        ViewGroup content = null;
        try {
            View c = activity.findViewById(android.R.id.content);
            if (c instanceof ViewGroup) content = (ViewGroup) c;
        } catch (Throwable ignored) {}
        ViewGroup decor = null;
        try {
            View d = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
            if (d instanceof ViewGroup) decor = (ViewGroup) d;
        } catch (Throwable ignored) {}
        // Prefer decor on Android 15/16 — Compose often rebuilds content and drops bubble
        ViewGroup root = decor != null ? decor : content;
        if (root != null && addBubbleToParent(activity, root)) {
            writeDebug("ui_bubble_ok_decor sdk=" + Build.VERSION.SDK_INT);
            return;
        }
        if (content != null && content != root && addBubbleToParent(activity, content)) {
            writeDebug("ui_bubble_ok_content");
            return;
        }

        // Panel fallback (still in-app token — no SYSTEM_ALERT)
        if (addBubbleViaWm(activity)) {
            writeDebug("ui_bubble_ok_wm");
            return;
        }

        // Android 15/16 last resort: SYSTEM_ALERT_WINDOW (root appops allow)
        if (Build.VERSION.SDK_INT >= 35 && canOverlay(activity) && addBubbleSystemOverlay(activity)) {
            writeDebug("ui_bubble_ok_system_a16");
            return;
        }

        writeDebug("ui_bubble_all_failed sdk=" + Build.VERSION.SDK_INT);
    }

    private static boolean canOverlay(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                return Settings.canDrawOverlays(ctx);
            }
            return true;
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

    private static FrameLayout.LayoutParams bubbleLp(Activity activity, int size) {
        float d = activity.getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = (int) (14 * d);
        lp.topMargin = (int) (96 * d);
        return lp;
    }

    private static boolean addBubbleToParent(final Activity activity, ViewGroup root) {
        try {
            float d = activity.getResources().getDisplayMetrics().density;
            int size = (int) (56 * d);
            final View bubble = makeBubbleView(activity, size);
            final FrameLayout menuHost = makeMenuHost(activity);
            FrameLayout.LayoutParams menuLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            bubble.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    safeOpenMenu(activity, menuHost);
                }
            });
            root.addView(bubble, bubbleLp(activity, size));
            root.addView(menuHost, menuLp);
            bubble.bringToFront();
            bubble.setVisibility(View.VISIBLE);
            // NO pre-build menu — tap pe lazy (WebView/early fill crash fix)
            return true;
        } catch (Throwable t) {
            writeDebug("ui_add_parent_fail:" + safeMsg(t));
            return false;
        }
    }

    /** Prefer purple Virtus HTML UI; native purple fallback if WebView crashes */
    private static void safeOpenMenu(final Activity activity, final FrameLayout menuHost) {
        try {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (menuHost.getChildCount() == 0) {
                            try {
                                fillMenuWebView(activity, menuHost);
                            } catch (Throwable t) {
                                writeDebug("ui_webview_fail");
                                menuHost.removeAllViews();
                                fillMenuNative(activity, menuHost);
                            }
                            if (menuHost.getChildCount() == 0) {
                                fillMenuNative(activity, menuHost);
                            }
                        }
                        toggleMenu(menuHost);
                    } catch (Throwable t) {
                        writeDebug("ui_tap_fail:" + safeMsg(t));
                        try {
                            menuHost.removeAllViews();
                            fillMenuNative(activity, menuHost);
                            menuOpen = true;
                            menuHost.setVisibility(View.VISIBLE);
                        } catch (Throwable ignored) {}
                    }
                }
            });
        } catch (Throwable t) {
            writeDebug("ui_tap_post_fail:" + safeMsg(t));
        }
    }

    private static void scheduleMenuFill(final Activity activity, final FrameLayout menuHost) {
        // no-op: menu builds lazily on tap (crash-safe)
    }

    private static boolean addBubbleSystemOverlay(final Activity activity) {
        try {
            if (sBubbleOverlay != null && sBubbleOverlay.getParent() != null) return true;
            float d = activity.getResources().getDisplayMetrics().density;
            int size = (int) (60 * d);
            final View bubble = makeBubbleView(activity, size);
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
                    safeOpenMenu(activity, menuHost);
                }
            });
            wm.addView(menuHost, mlp);
            menuHost.setVisibility(View.GONE);
            wm.addView(bubble, blp);
            sBubbleOverlay = bubble;
            sMenuOverlay = menuHost;
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
                    public void run() { tryAddWm(activity); }
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
            int size = (int) (60 * d);
            final View bubble = makeBubbleView(activity, size);
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
                    safeOpenMenu(activity, menuHost);
                }
            });
            wm.addView(menuHost, mlp);
            menuHost.setVisibility(View.GONE);
            wm.addView(bubble, blp);
            sBubbleWm = bubble;
            sMenuWm = menuHost;
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
            int size = (int) (60 * d);
            View bubble = makeBubbleView(activity, size);
            content.addView(bubble, bubbleLp(activity, size));
            writeDebug("ui_minimal_ok");
        } catch (Throwable t) {
            writeDebug("ui_minimal_fail:" + safeMsg(t));
        }
    }

    private static Bitmap sLogoBmp;

    private static Bitmap loadLogoBitmap() {
        if (sLogoBmp != null && !sLogoBmp.isRecycled()) return sLogoBmp;

        // 1) Embedded in dex — always works (no file permission)
        try {
            byte[] png = LogoAsset.png();
            if (png != null && png.length > 100) {
                Bitmap b = BitmapFactory.decodeByteArray(png, 0, png.length);
                if (b != null) {
                    if (b.getWidth() > 128) {
                        Bitmap scaled = Bitmap.createScaledBitmap(b, 128, 128, true);
                        if (scaled != b) b.recycle();
                        b = scaled;
                    }
                    sLogoBmp = b;
                    writeDebug("ui_logo_ok:embedded");
                    return sLogoBmp;
                }
            }
        } catch (Throwable t) {
            writeDebug("ui_logo_embed_fail");
        }

        // 2) File fallback
        String[] paths = {
                "/data/local/tmp/hivirtus_ui/bubble_logo_128.png",
                "/data/adb/modules/hivirtus_zygisk_mode/ui/bubble_logo_128.png",
                "/data/local/tmp/hivirtus_ui/bubble_logo.png",
                "/data/adb/modules/hivirtus_zygisk_mode/ui/bubble_logo.png",
        };
        for (String path : paths) {
            try {
                File f = new File(path);
                if (!f.canRead()) continue;
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap b = BitmapFactory.decodeFile(path, opts);
                if (b != null) {
                    if (b.getWidth() > 128) {
                        Bitmap scaled = Bitmap.createScaledBitmap(b, 128, 128, true);
                        if (scaled != b) b.recycle();
                        b = scaled;
                    }
                    sLogoBmp = b;
                    writeDebug("ui_logo_ok:" + path);
                    return sLogoBmp;
                }
            } catch (Throwable t) {
                writeDebug("ui_logo_fail:" + path);
            }
        }
        writeDebug("ui_logo_missing");
        return null;
    }

    /** Madara logo bubble — face-centered circular ImageView (no "V") */
    private static View makeBubbleView(Context ctx, int size) {
        ImageView bubble = new ImageView(ctx);
        bubble.setTag(TAG_BUBBLE);
        bubble.setClickable(true);
        bubble.setFocusable(true);
        bubble.setElevation(48f);
        bubble.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bubble.setAdjustViewBounds(false);
        try {
            bubble.setClipToOutline(true);
            android.graphics.drawable.GradientDrawable clip =
                    new android.graphics.drawable.GradientDrawable();
            clip.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            clip.setColor(0xFF111111);
            bubble.setBackground(clip);
            bubble.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
        } catch (Throwable ignored) {}

        Bitmap logo = loadLogoBitmap();
        if (logo != null) {
            bubble.setImageBitmap(logo);
        } else {
            // Fallback gold circle if logo missing
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xFFFFD700);
            bg.setCornerRadius(size);
            bubble.setBackground(bg);
            bubble.setImageDrawable(null);
            // tiny text fallback via content description only
            bubble.setContentDescription("V");
        }
        return bubble;
    }

    private static FrameLayout makeMenuHost(Context ctx) {
        final FrameLayout menuHost = new FrameLayout(ctx);
        menuHost.setTag(TAG_MENU);
        menuHost.setVisibility(View.GONE);
        menuHost.setClickable(true);
        // Dim backdrop — card beech me WebView se
        menuHost.setBackgroundColor(0x99000000);
        menuHost.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                menuOpen = false;
                menuHost.setVisibility(View.GONE);
                writeDebug("ui_menu_close_backdrop");
            }
        });
        return menuHost;
    }

    /** Reference size: ~88% x ~55% portrait, centered (MotaGian-style card) */
    private static FrameLayout.LayoutParams centeredCardLp(Context ctx) {
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int sw = dm.widthPixels;
        int sh = dm.heightPixels;
        int w;
        int h;
        if (sw > sh) {
            // landscape
            w = (int) (sw * 0.52f);
            h = (int) (sh * 0.82f);
        } else {
            w = (int) (sw * 0.88f);
            h = (int) (sh * 0.55f);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.gravity = Gravity.CENTER;
        return lp;
    }

    private static void toggleMenu(FrameLayout menuHost) {
        menuOpen = !menuOpen;
        menuHost.setVisibility(menuOpen ? View.VISIBLE : View.GONE);
        if (menuOpen) menuHost.bringToFront();
        writeDebug(menuOpen ? "ui_menu_open" : "ui_menu_close");
    }

    private static String readUtf8(String path) {
        try {
            File f = new File(path);
            if (!f.canRead()) return null;
            InputStreamReader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8);
            char[] buf = new char[16384];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            r.close();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void addCenteredChild(FrameLayout menuHost, View child) {
        child.setClickable(true);
        child.setFocusable(true);
        menuHost.addView(child, centeredCardLp(menuHost.getContext()));
    }

    private static void fillMenuWebView(Activity activity, FrameLayout menuHost) {
        if (menuHost.getChildCount() > 0) return;
        WebView web = new WebView(activity);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            } catch (Throwable ignored) {}
        }
        try {
            if (Build.VERSION.SDK_INT < 30) {
                Method m1 = WebSettings.class.getMethod("setAllowFileAccessFromFileURLs", boolean.class);
                m1.invoke(ws, true);
                Method m2 = WebSettings.class.getMethod("setAllowUniversalAccessFromFileURLs", boolean.class);
                m2.invoke(ws, true);
            }
        } catch (Throwable ignored) {}
        try {
            HivirtusJsBridge.attach(web);
        } catch (Throwable ignored) {}

        String baseApp = null;
        try {
            File cc = activity.getCodeCacheDir();
            if (cc != null) {
                File ui = new File(new File(cc, "hivirtus"), "ui");
                if (new File(ui, "index.html").canRead()) baseApp = ui.getAbsolutePath() + "/";
            }
            if (baseApp == null) {
                File files = activity.getFilesDir();
                if (files != null) {
                    File ui = new File(new File(files, "hivirtus"), "ui");
                    if (new File(ui, "index.html").canRead()) baseApp = ui.getAbsolutePath() + "/";
                }
            }
        } catch (Throwable ignored) {}
        String baseTmp = "/data/local/tmp/hivirtus_ui/";
        String baseMod = "/data/adb/modules/hivirtus_zygisk_mode/ui/";
        String html = null;
        String base = baseTmp;
        if (baseApp != null) {
            html = readUtf8(baseApp + "index.html");
            if (html != null) base = baseApp;
        }
        if (html == null) {
            html = readUtf8(baseTmp + "index.html");
            base = baseTmp;
        }
        if (html == null) {
            html = readUtf8(baseMod + "index.html");
            base = baseMod;
        }
        if (html != null) {
            String css = readUtf8(base + "style.css");
            String js = readUtf8(base + "app.js");
            if (css != null) {
                html = html.replace("<link rel=\"stylesheet\" href=\"style.css\" />",
                        "<style>" + css + "</style>");
            }
            if (js != null) {
                html = html.replace("<script src=\"app.js\"></script>",
                        "<script>" + js + "</script>");
            }
            // Madara logo in header (embedded)
            try {
                byte[] png = LogoAsset.png();
                if (png != null) {
                    String b64 = android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP);
                    String img = "<img class=\"mark-img\" alt=\"logo\" src=\"data:image/png;base64," + b64 + "\" />";
                    html = html.replace("<span class=\"mark\" id=\"brandMark\">V</span>", img);
                    html = html.replace("<span class=\"mark\">V</span>", img);
                }
            } catch (Throwable ignored) {}
            web.loadDataWithBaseURL("file://" + base, html, "text/html", "utf-8", null);
            addCenteredChild(menuHost, web);
            writeDebug("ui_webview_ok_centered");
            return;
        }

        // Full purple Virtus embedded HTML if files missing
        String embedded = EMBEDDED_MENU_HTML;
        try {
            byte[] png = LogoAsset.png();
            if (png != null) {
                String b64 = android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP);
                String img = "<img class=mark-img alt=logo src=\"data:image/png;base64," + b64 + "\" />";
                embedded = embedded.replace("<span class=mark>V</span>", img);
            }
        } catch (Throwable ignored) {}
        web.loadDataWithBaseURL(null, embedded, "text/html", "utf-8", null);
        addCenteredChild(menuHost, web);
        writeDebug("ui_webview_ok_embedded_centered");
    }

    private static GradientDrawable roundBg(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** Purple Virtus-style native fallback (same look as HTML) */
    private static void fillMenuNative(final Activity activity, FrameLayout menuHost) {
        if (menuHost.getChildCount() > 0) return;
        try {
            final float d = activity.getResources().getDisplayMetrics().density;
            int pad = (int) (14 * d);
            int gap = (int) (10 * d);

            LinearLayout shell = new LinearLayout(activity);
            shell.setOrientation(LinearLayout.VERTICAL);
            shell.setPadding(pad, pad, pad, pad);
            shell.setBackground(roundBg(0xFF090B12, 18 * d));

            // Header
            LinearLayout header = new LinearLayout(activity);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);

            ImageView logo = new ImageView(activity);
            int logoSz = (int) (40 * d);
            Bitmap bmp = loadLogoBitmap();
            if (bmp != null) logo.setImageBitmap(bmp);
            logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
            try {
                logo.setClipToOutline(true);
                logo.setBackground(roundBg(0xFF1A237E, logoSz));
                logo.setOutlineProvider(new android.view.ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, android.graphics.Outline outline) {
                        outline.setOval(0, 0, view.getWidth(), view.getHeight());
                    }
                });
            } catch (Throwable ignored) {}
            LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(logoSz, logoSz);
            logoLp.rightMargin = (int) (12 * d);
            header.addView(logo, logoLp);

            LinearLayout titles = new LinearLayout(activity);
            titles.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(activity);
            name.setText("Virtus");
            name.setTextColor(Color.WHITE);
            name.setTextSize(18f);
            name.getPaint().setFakeBoldText(true);
            TextView sub = new TextView(activity);
            sub.setText("Intercept + spoof ready");
            sub.setTextColor(0xFF00C896);
            sub.setTextSize(12f);
            titles.addView(name);
            titles.addView(sub);
            header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView close = new TextView(activity);
            close.setText("✕");
            close.setTextColor(0xFF4A5068);
            close.setTextSize(16f);
            close.setPadding((int) (10 * d), (int) (8 * d), (int) (10 * d), (int) (8 * d));
            close.setBackground(roundBg(0xFF13151F, 12 * d));
            close.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    menuOpen = false;
                    menuHost.setVisibility(View.GONE);
                }
            });
            header.addView(close);
            shell.addView(header);

            // Tabs row (visual)
            LinearLayout tabs = new LinearLayout(activity);
            tabs.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(-1, -2);
            tabsLp.topMargin = (int) (14 * d);
            tabsLp.bottomMargin = gap;
            String[] tabNames = {"Basic", "Advanced", "Telegram"};
            for (int i = 0; i < tabNames.length; i++) {
                TextView tab = new TextView(activity);
                tab.setText(tabNames[i]);
                tab.setGravity(Gravity.CENTER);
                tab.setTextSize(12f);
                tab.getPaint().setFakeBoldText(true);
                tab.setPadding(0, (int) (10 * d), 0, (int) (10 * d));
                if (i == 0) {
                    tab.setTextColor(Color.WHITE);
                    tab.setBackground(roundBg(0xFF6C63FF, 12 * d));
                } else {
                    tab.setTextColor(0xFF4A5068);
                    tab.setBackground(roundBg(0x00000000, 12 * d));
                }
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
                tlp.rightMargin = i < 2 ? (int) (6 * d) : 0;
                tabs.addView(tab, tlp);
            }
            shell.addView(tabs, tabsLp);

            ScrollView scroll = new ScrollView(activity);
            LinearLayout cards = new LinearLayout(activity);
            cards.setOrientation(LinearLayout.VERTICAL);

            // Card: Fake phone
            LinearLayout card1 = new LinearLayout(activity);
            card1.setOrientation(LinearLayout.VERTICAL);
            card1.setPadding(pad, pad, pad, pad);
            card1.setBackground(roundBg(0xFF13151F, 14 * d));
            LinearLayout row1 = new LinearLayout(activity);
            row1.setOrientation(LinearLayout.HORIZONTAL);
            row1.setGravity(Gravity.CENTER_VERTICAL);
            TextView c1t = new TextView(activity);
            c1t.setText("📱  Fake Phone Number\nOverride number shown to apps");
            c1t.setTextColor(Color.WHITE);
            c1t.setTextSize(13f);
            final Switch swFake = new Switch(activity);
            try { swFake.setChecked(false); } catch (Throwable ignored) {}
            row1.addView(c1t, new LinearLayout.LayoutParams(0, -2, 1f));
            row1.addView(swFake);
            card1.addView(row1);
            final EditText etPhone = new EditText(activity);
            etPhone.setHint("+91 XXXXX XXXXX");
            etPhone.setTextColor(Color.WHITE);
            etPhone.setHintTextColor(0xFF4A5068);
            etPhone.setBackground(roundBg(0xFF090B12, 10 * d));
            etPhone.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(-1, -2);
            etLp.topMargin = gap;
            card1.addView(etPhone, etLp);
            cards.addView(card1);

            // Card: SMS intercept
            LinearLayout card2 = new LinearLayout(activity);
            card2.setOrientation(LinearLayout.VERTICAL);
            card2.setPadding(pad, pad, pad, pad);
            card2.setBackground(roundBg(0xFF13151F, 14 * d));
            LinearLayout.LayoutParams c2lp = new LinearLayout.LayoutParams(-1, -2);
            c2lp.topMargin = gap;
            LinearLayout row2 = new LinearLayout(activity);
            row2.setOrientation(LinearLayout.HORIZONTAL);
            row2.setGravity(Gravity.CENTER_VERTICAL);
            TextView c2t = new TextView(activity);
            c2t.setText("✉️  SMS Intercept + Fake Success\nBlock OTP SMS & fake success");
            c2t.setTextColor(Color.WHITE);
            c2t.setTextSize(13f);
            final Switch swIntercept = new Switch(activity);
            try { swIntercept.setChecked(true); } catch (Throwable ignored) {}
            row2.addView(c2t, new LinearLayout.LayoutParams(0, -2, 1f));
            row2.addView(swIntercept);
            card2.addView(row2);
            cards.addView(card2, c2lp);

            scroll.addView(cards);
            LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1f);
            shell.addView(scroll, scrollLp);

            Button save = new Button(activity);
            save.setText("Save Settings");
            save.setAllCaps(false);
            save.setTextColor(Color.WHITE);
            save.setBackground(roundBg(0xFF6C63FF, 14 * d));
            LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(-1, (int) (48 * d));
            saveLp.topMargin = (int) (12 * d);
            save.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        String phone = etPhone.getText() != null ? etPhone.getText().toString().trim() : "";
                        boolean fakeOn = swFake.isChecked();
                        boolean interceptOn = swIntercept.isChecked();
                        String json = "{"
                                + "\"hook_outgoing_sms\":" + interceptOn + ","
                                + "\"intercept_fake_success\":" + interceptOn + ","
                                + "\"intercept_enabled\":" + interceptOn + ","
                                + "\"enable_sim1_mock\":" + fakeOn + ","
                                + "\"enable_phone_spoof\":" + fakeOn + ","
                                + "\"enable_virtual_sim\":" + (fakeOn && phone.length() >= 10) + ","
                                + "\"fake_number_enabled\":" + fakeOn + ","
                                + "\"mock_phone_sim1\":\"" + phone.replace("\"", "") + "\","
                                + "\"prefix_enabled\":false,"
                                + "\"override_incoming_sender\":false,"
                                + "\"hook_all_upi_apps\":true,"
                                + "\"auto_hook_foreground\":true"
                                + "}";
                        java.io.FileWriter w = new java.io.FileWriter("/data/local/tmp/hivirtus_ui_save.json");
                        w.write(json);
                        w.close();
                        java.io.FileWriter w2 = new java.io.FileWriter("/data/local/tmp/hivirtus_zygisk_mode_config.json");
                        w2.write(json);
                        w2.close();
                        if (fakeOn && phone.length() >= 10) {
                            java.io.FileWriter w3 = new java.io.FileWriter("/data/local/tmp/hivirtus_spoof_phone.txt");
                            w3.write(phone + "\n");
                            w3.close();
                        }
                        writeDebug("ui_native_save_ok");
                    } catch (Throwable t) {
                        writeDebug("ui_native_save_fail:" + safeMsg(t));
                    }
                }
            });
            shell.addView(save, saveLp);

            TextView credit = new TextView(activity);
            credit.setText("Virtus Zygisk Mode • no LSPosed • @hivirtus");
            credit.setTextColor(0xFF4A5068);
            credit.setTextSize(11f);
            credit.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(-1, -2);
            crLp.topMargin = (int) (8 * d);
            shell.addView(credit, crLp);

            addCenteredChild(menuHost, shell);
            writeDebug("ui_native_menu_ok_centered");
        } catch (Throwable t) {
            fillMenuFallback(activity, menuHost);
        }
    }

    private static void fillMenuFallback(Activity activity, FrameLayout menuHost) {
        if (menuHost.getChildCount() > 0) return;
        TextView tv = new TextView(activity);
        tv.setText("Virtus Zygisk Mode\n\nTap logo / outside to close.\n@Hivirtus");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(16f);
        tv.setPadding(48, 48, 48, 48);
        tv.setBackground(roundBg(0xFF090B12, 18));
        addCenteredChild(menuHost, tv);
        writeDebug("ui_fallback_menu_centered");
    }

    private static final String EMBEDDED_MENU_HTML =
            "<!DOCTYPE html><html><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'>"
            + "<style>"
            + "*{box-sizing:border-box;margin:0;padding:0}"
            + "body{height:100%;background:#090b12;color:#eaeaea;font-family:sans-serif;padding:14px}"
            + ".shell{height:100%;display:flex;flex-direction:column}"
            + ".top{display:flex;align-items:center;justify-content:space-between;margin-bottom:14px}"
            + ".name{font-weight:700;font-size:18px}.sub{color:#00c896;font-size:12px}"
            + ".brand{display:flex;gap:12px;align-items:center}"
            + ".mark,.mark-img{width:40px;height:40px;border-radius:50%;object-fit:cover;display:grid;place-items:center;"
            + "background:linear-gradient(145deg,#5c6bc0,#1a237e);color:#fff;font-weight:700;box-shadow:0 8px 20px rgba(108,99,255,.4)}"
            + ".tabs{display:grid;grid-template-columns:1fr 1fr 1fr;gap:6px;margin-bottom:12px}"
            + ".tab{padding:10px;border-radius:12px;border:1px solid #1c1f2e;background:transparent;color:#4a5068;font-weight:600}"
            + ".tab.on{background:#6c63ff;border-color:#6c63ff;color:#fff}"
            + ".card{background:#13151f;border-radius:14px;padding:14px;margin-bottom:10px}"
            + "h3{font-size:14px;margin-bottom:4px}p{color:#4a5068;font-size:12px}"
            + "input[type=text],input[type=tel]{width:100%;margin-top:10px;padding:12px;border-radius:10px;border:0;background:#090b12;color:#fff}"
            + ".row{display:flex;align-items:center;justify-content:space-between;gap:10px}"
            + ".primary{margin-top:auto;width:100%;padding:14px;border:0;border-radius:14px;background:#6c63ff;color:#fff;font-weight:700}"
            + ".credit{display:block;text-align:center;margin-top:8px;color:#4a5068;font-size:11px}"
            + "</style></head><body><div class=shell>"
            + "<div class=top><div class=brand><span class=mark>V</span>"
            + "<div><div class=name>Virtus</div><div class=sub>Intercept + spoof ready</div></div></div></div>"
            + "<div class=tabs><button class='tab on'>Basic</button><button class=tab>Advanced</button><button class=tab>Telegram</button></div>"
            + "<div class=card><div class=row><div><h3>Fake Phone Number</h3><p>Override number shown to apps</p></div>"
            + "<input type=checkbox id=swF></div><input type=tel id=phone placeholder='+91 XXXXX XXXXX'></div>"
            + "<div class=card><div class=row><div><h3>SMS Intercept + Fake Success</h3><p>Block OTP SMS & fake success</p></div>"
            + "<input type=checkbox id=swI checked></div></div>"
            + "<button class=primary id=save>Save Settings</button>"
            + "<span class=credit>Virtus Zygisk Mode • no LSPosed • @hivirtus</span></div>"
            + "<script>(function(){var H=window.Hivirtus;"
            + "document.getElementById('save').onclick=function(){var j=JSON.stringify({"
            + "hook_outgoing_sms:document.getElementById('swI').checked,"
            + "intercept_fake_success:document.getElementById('swI').checked,"
            + "enable_sim1_mock:document.getElementById('swF').checked,"
            + "enable_phone_spoof:document.getElementById('swF').checked,"
            + "mock_phone_sim1:document.getElementById('phone').value||''});"
            + "try{if(H)H.saveConfig(j);}catch(e){}};})();</script>"
            + "</body></html>";

    private static String safeMsg(Throwable t) {
        return t == null ? "?" : String.valueOf(t.getMessage());
    }

    private static void writeDebug(String msg) {
        if (msg == null) return;
        // Always log bubble success/fail + A16 env (tmp may be blocked — also write app files)
        try {
            appendDebugFile(new java.io.File("/data/local/tmp/hivirtus_overlay.debug"), msg);
        } catch (Throwable ignored) {}
        try {
            Context app = currentApp();
            if (app != null) {
                File dir = new File(app.getCodeCacheDir(), "hivirtus");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                appendDebugFile(new File(dir, "overlay.debug"), msg);
            }
        } catch (Throwable ignored) {}
    }

    private static void appendDebugFile(java.io.File f, String msg) throws Exception {
        java.io.FileWriter w = new java.io.FileWriter(f, true);
        w.write(msg + "\n");
        w.close();
    }
}
