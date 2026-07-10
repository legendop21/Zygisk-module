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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Switch;
import android.widget.EditText;
import android.widget.Button;
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
    private static final String TAG_BUBBLE = "hivirtus_bubble_v";
    private static final String TAG_MENU = "hivirtus_menu_v";
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
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try { attach(a); } catch (Throwable t) {
                    writeDebug("ui_attach_fail:" + safeMsg(t));
                }
            }
        });
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                try { attach(a); } catch (Throwable ignored) {}
            }
        }, 500);
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                try { attach(a); } catch (Throwable ignored) {}
            }
        }, 1500);
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

        // 1) DECOR first — no overlay permission, works Android 11–16
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
        ViewGroup root = content != null ? content : decor;
        if (root != null && addBubbleToParent(activity, root)) {
            writeDebug("ui_bubble_ok_decor");
            return;
        }

        // 2) Activity panel (token)
        if (addBubbleViaWm(activity)) {
            writeDebug("ui_bubble_ok_wm");
            return;
        }

        // 3) SYSTEM overlay if granted
        if (canOverlay(activity) && addBubbleSystemOverlay(activity)) {
            writeDebug("ui_bubble_ok_overlay");
            return;
        }

        writeDebug("ui_bubble_all_failed");
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
            int size = (int) (58 * d);
            final TextView bubble = makeBubbleView(activity, size);
            final FrameLayout menuHost = makeMenuHost(activity);
            FrameLayout.LayoutParams menuLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            bubble.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { toggleMenu(menuHost); }
            });
            root.addView(bubble, bubbleLp(activity, size));
            root.addView(menuHost, menuLp);
            bubble.bringToFront();
            bubble.setVisibility(View.VISIBLE);
            bubble.setAlpha(1f);
            try { bubble.setZ(999f); } catch (Throwable ignored) {}
            scheduleMenuFill(activity, menuHost);
            return true;
        } catch (Throwable t) {
            writeDebug("ui_add_parent_fail:" + safeMsg(t));
            return false;
        }
    }

    private static void scheduleMenuFill(final Activity activity, final FrameLayout menuHost) {
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    fillMenuWebView(activity, menuHost);
                } catch (Throwable t) {
                    writeDebug("ui_webview_fail:" + safeMsg(t));
                    fillMenuNative(activity, menuHost);
                }
            }
        }, 300);
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
            scheduleMenuFill(activity, menuHost);
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
            scheduleMenuFill(activity, menuHost);
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

        String baseTmp = "/data/local/tmp/hivirtus_ui/";
        String baseMod = "/data/adb/modules/hivirtus_zygisk_mode/ui/";
        String html = readUtf8(baseTmp + "index.html");
        String base = baseTmp;
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
            web.loadDataWithBaseURL("file://" + base, html, "text/html", "utf-8", null);
            addCenteredChild(menuHost, web);
            writeDebug("ui_webview_ok_centered");
            return;
        }

        web.loadDataWithBaseURL(null, EMBEDDED_MENU_HTML, "text/html", "utf-8", null);
        addCenteredChild(menuHost, web);
        writeDebug("ui_webview_ok_embedded_centered");
    }

    /** Native menu — WebView crash pe bhi test possible */
    private static void fillMenuNative(final Activity activity, FrameLayout menuHost) {
        if (menuHost.getChildCount() > 0) return;
        try {
            float d = activity.getResources().getDisplayMetrics().density;
            ScrollView scroll = new ScrollView(activity);
            scroll.setBackgroundColor(0xFF090B12);
            LinearLayout col = new LinearLayout(activity);
            col.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) (20 * d);
            col.setPadding(pad, pad, pad, pad);

            TextView title = new TextView(activity);
            title.setText("Virtus Zygisk Mode");
            title.setTextColor(0xFFFFD700);
            title.setTextSize(20f);
            title.setPadding(0, 0, 0, pad);
            col.addView(title);

            TextView sub = new TextView(activity);
            sub.setText("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\nTap V / outside to close.");
            sub.setTextColor(0xFFB8A882);
            sub.setTextSize(13f);
            col.addView(sub);

            final Switch swIntercept = new Switch(activity);
            swIntercept.setText("SMS Intercept + Fake Success");
            swIntercept.setTextColor(Color.WHITE);
            swIntercept.setChecked(true);
            col.addView(swIntercept);

            final Switch swFake = new Switch(activity);
            swFake.setText("Fake Phone Number");
            swFake.setTextColor(Color.WHITE);
            col.addView(swFake);

            final EditText etPhone = new EditText(activity);
            etPhone.setHint("+91XXXXXXXXXX");
            etPhone.setTextColor(Color.WHITE);
            etPhone.setHintTextColor(0xFF888888);
            col.addView(etPhone);

            Button save = new Button(activity);
            save.setText("Save");
            save.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        String phone = etPhone.getText() != null ? etPhone.getText().toString() : "";
                        String json = "{"
                                + "\"hook_outgoing_sms\":" + swIntercept.isChecked() + ","
                                + "\"intercept_fake_success\":" + swIntercept.isChecked() + ","
                                + "\"enable_sim1_mock\":" + swFake.isChecked() + ","
                                + "\"enable_phone_spoof\":" + swFake.isChecked() + ","
                                + "\"mock_phone_sim1\":\"" + phone.replace("\"", "") + "\""
                                + "}";
                        java.io.FileWriter w = new java.io.FileWriter("/data/local/tmp/hivirtus_ui_save.json");
                        w.write(json);
                        w.close();
                        writeDebug("ui_native_save_ok");
                    } catch (Throwable t) {
                        writeDebug("ui_native_save_fail:" + safeMsg(t));
                    }
                }
            });
            col.addView(save);

            scroll.addView(col);
            addCenteredChild(menuHost, scroll);
            writeDebug("ui_native_menu_ok_centered");
        } catch (Throwable t) {
            fillMenuFallback(activity, menuHost);
        }
    }

    private static void fillMenuFallback(Activity activity, FrameLayout menuHost) {
        if (menuHost.getChildCount() > 0) return;
        TextView tv = new TextView(activity);
        tv.setText("Virtus Zygisk Mode\n\nTap V / outside to close.\nSDK " + Build.VERSION.SDK_INT);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(16f);
        tv.setPadding(48, 48, 48, 48);
        tv.setBackgroundColor(0xFF090B12);
        addCenteredChild(menuHost, tv);
        writeDebug("ui_fallback_menu_centered");
    }

    private static final String EMBEDDED_MENU_HTML =
            "<!DOCTYPE html><html><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'>"
            + "<style>body{margin:0;background:#090B12;color:#fff;font-family:sans-serif;padding:20px}"
            + "h1{color:#FFD700;font-size:22px}label{display:block;margin:14px 0}"
            + "input,button{width:100%;padding:12px;margin-top:6px;border-radius:8px;border:0}"
            + "button{background:#FFD700;color:#111;font-weight:700}</style></head><body>"
            + "<h1>Virtus Zygisk Mode</h1><p id=s>Loading…</p>"
            + "<label><input type=checkbox id=swI checked> SMS Intercept + Fake Success</label>"
            + "<label><input type=checkbox id=swF> Fake Phone Number</label>"
            + "<input id=phone placeholder='+91XXXXXXXXXX'>"
            + "<button id=save>Save</button>"
            + "<script>(function(){var H=window.Hivirtus;var s=document.getElementById('s');"
            + "try{s.textContent=H?'Bridge OK — ready to test':'Bridge missing — still OK';}catch(e){s.textContent='OK'};"
            + "document.getElementById('save').onclick=function(){var j=JSON.stringify({"
            + "hook_outgoing_sms:document.getElementById('swI').checked,"
            + "intercept_fake_success:document.getElementById('swI').checked,"
            + "enable_sim1_mock:document.getElementById('swF').checked,"
            + "enable_phone_spoof:document.getElementById('swF').checked,"
            + "mock_phone_sim1:document.getElementById('phone').value||''});"
            + "try{if(H)H.saveConfig(j);s.textContent='Saved';}catch(e){s.textContent='Save fail'};};})();</script>"
            + "</body></html>";

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
