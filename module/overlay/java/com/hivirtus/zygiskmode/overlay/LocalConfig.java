package com.hivirtus.zygiskmode.overlay;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Module config — sirf local files, koi APK / cloud nahi */
public final class LocalConfig {

    public static final String MODULE_CONFIG =
            "/data/adb/modules/hivirtus_zygisk_mode/config.json";
    public static final String RUNTIME_CONFIG =
            "/data/local/tmp/hivirtus_zygisk_mode_config.json";
    public static final String MODULE_UI = resolveUiPath();

    private static String resolveUiPath() {
        final String tmp = "/data/local/tmp/hivirtus_overlay/ui";
        if (new File(tmp, "index.html").canRead()) return tmp;
        return "/data/adb/modules/hivirtus_zygisk_mode/overlay/ui";
    }

    private LocalConfig() {}

    public static JSONObject load() {
        File f = new File(MODULE_CONFIG);
        if (!f.canRead()) f = new File(RUNTIME_CONFIG);
        if (!f.canRead()) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = in.read(buf);
            if (n <= 0) return new JSONObject();
            return new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static boolean save(JSONObject json) {
        try {
            String text = json.toString(2);
            writeFile(new File(MODULE_CONFIG), text);
            writeFile(new File(RUNTIME_CONFIG), text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean getBool(JSONObject j, String key, boolean def) {
        return j != null && j.optBoolean(key, def);
    }

    public static void putBool(JSONObject j, String key, boolean val) {
        try { j.put(key, val); } catch (Exception ignored) {}
    }

    public static String getString(JSONObject j, String key, String def) {
        return j == null ? def : j.optString(key, def);
    }

    public static void putString(JSONObject j, String key, String val) {
        try { j.put(key, val == null ? "" : val); } catch (Exception ignored) {}
    }

    private static void writeFile(File f, String text) throws Exception {
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        try { Runtime.getRuntime().exec(new String[]{"chmod", "644", f.getAbsolutePath()}); }
        catch (Exception ignored) {}
    }
}
