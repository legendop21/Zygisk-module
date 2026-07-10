#include "overlay_ui.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "plt_hook.hpp"
#include "zygisk.hpp"

#include <atomic>
#include <cstdio>
#include <pthread.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace overlay_ui {

namespace {

constexpr const char* kTagRoot = "hivirtus_overlay_root";
constexpr const char* kTagMenu = "hivirtus_menu_panel";
constexpr const char* kTagBubble = "hivirtus_bubble";
constexpr const char* kTagPill = "hivirtus_status_pill";

constexpr const char* kGold = "#FFD700";
constexpr const char* kBg = "#0D0D0D";
constexpr const char* kBubbleNavy = "#1A237E";
constexpr const char* kBubbleRing = "#3949AB";
constexpr const char* kTabActive = "#1A1608";
constexpr const char* kCard = "#111111";
constexpr const char* kWhite = "#FFFFFF";
constexpr const char* kMuted = "#B8A882";
constexpr const char* kBlack = "#000000";
constexpr const char* kGreen = "#22C55E";
constexpr const char* kDivider = "#2A2418";

zygisk::Api* g_api = nullptr;
JavaVM* g_vm = nullptr;
std::atomic<bool> g_hooks_installed{false};
std::atomic<bool> g_plt_ready{false};
std::string g_package;

int g_active_tab = 0;
bool g_menu_open = false;
bool g_overlay_attached = false;
bool g_system_overlay_mode = false;

jobject g_bubble_view = nullptr;
jobject g_pill_view = nullptr;

jobject g_sw_hide_dev = nullptr;
jobject g_sw_hide_root = nullptr;
jobject g_sw_incoming = nullptr;
jobject g_sw_outgoing = nullptr;
jobject g_et_token = nullptr;
jobject g_et_chat = nullptr;
jobject g_panel_system = nullptr;
jobject g_panel_message = nullptr;
jobject g_panel_telegram = nullptr;
jobject g_menu_panel = nullptr;
jobject g_webview = nullptr;
jobject g_tab_system = nullptr;
jobject g_tab_message = nullptr;
jobject g_tab_telegram = nullptr;
jobject g_pill_label = nullptr;

void debug_marker(const char* msg);

struct Rect {
    int l = 0, t = 0, r = 0, b = 0;
    bool contains(float x, float y) const { return x >= l && x <= r && y >= t && y <= b; }
};

void update_pill_label(JNIEnv* env, const ModuleConfig& config);

jfloat density(JNIEnv* env, jobject ctx) {
    jclass at = env->FindClass("android/app/Activity");
    jobject res = env->CallObjectMethod(ctx, env->GetMethodID(at, "getResources", "()Landroid/content/res/Resources;"));
    jobject metrics = env->CallObjectMethod(res, env->GetMethodID(env->FindClass("android/content/res/Resources"),
                                                                    "getDisplayMetrics", "()Landroid/util/DisplayMetrics;"));
    return env->GetFloatField(metrics, env->GetFieldID(env->FindClass("android/util/DisplayMetrics"), "density", "F"));
}

jint px(JNIEnv* env, jobject ctx, jfloat dp) {
    return static_cast<jint>(dp * density(env, ctx) + 0.5f);
}

jint color(JNIEnv* env, const char* hex) {
    jclass c = env->FindClass("android/graphics/Color");
    jstring s = env->NewStringUTF(hex);
    const jint v = env->CallStaticIntMethod(c, env->GetStaticMethodID(c, "parseColor", "(Ljava/lang/String;)I"), s);
    env->DeleteLocalRef(s);
    return v;
}

jobject rounded(JNIEnv* env, jint col, jfloat rdp, jobject ctx) {
    jclass gd = env->FindClass("android/graphics/drawable/GradientDrawable");
    jobject o = env->NewObject(gd, env->GetMethodID(gd, "<init>", "()V"));
    env->CallVoidMethod(o, env->GetMethodID(gd, "setColor", "(I)V"), col);
    env->CallVoidMethod(o, env->GetMethodID(gd, "setCornerRadius", "(F)V"), static_cast<jfloat>(px(env, ctx, rdp)));
    return o;
}

jobject linear(JNIEnv* env, jobject ctx, int orientation) {
    jclass ll = env->FindClass("android/widget/LinearLayout");
    jobject v = env->NewObject(ll, env->GetMethodID(ll, "<init>", "(Landroid/content/Context;)V"));
    env->CallVoidMethod(v, env->GetMethodID(ll, "setOrientation", "(I)V"), orientation);
    return v;
}

jobject text_view(JNIEnv* env, jobject ctx, const char* txt, jint col, jfloat sp, bool bold) {
    jclass tv = env->FindClass("android/widget/TextView");
    jobject v = env->NewObject(tv, env->GetMethodID(tv, "<init>", "(Landroid/content/Context;)V"));
    jstring jt = env->NewStringUTF(txt);
    env->CallVoidMethod(v, env->GetMethodID(tv, "setText", "(Ljava/lang/CharSequence;)V"), jt);
    env->CallVoidMethod(v, env->GetMethodID(tv, "setTextColor", "(I)V"), col);
    env->CallVoidMethod(v, env->GetMethodID(tv, "setTextSize", "(F)V"), sp);
    if (bold) {
        env->CallVoidMethod(v, env->GetMethodID(tv, "setTypeface", "(Landroid/graphics/Typeface;I)V"), nullptr, 1);
    }
    env->DeleteLocalRef(jt);
    return v;
}

void style_switch(JNIEnv* env, jobject ctx, jobject sw) {
    const jint gold = color(env, kGold);
    jclass csl = env->FindClass("android/content/res/ColorStateList");
    jobject gold_list = env->CallStaticObjectMethod(csl, env->GetStaticMethodID(csl, "valueOf", "(I)Landroid/content/res/ColorStateList;"), gold);
    jclass swc = env->FindClass("android/widget/Switch");
    env->CallVoidMethod(sw, env->GetMethodID(swc, "setThumbTintList", "(Landroid/content/res/ColorStateList;)V"), gold_list);
    env->CallVoidMethod(sw, env->GetMethodID(swc, "setTrackTintList", "(Landroid/content/res/ColorStateList;)V"), gold_list);
    env->CallVoidMethod(sw, env->GetMethodID(swc, "setTextColor", "(I)V"), color(env, kWhite));
    env->CallVoidMethod(sw, env->GetMethodID(swc, "setPadding", "(IIII)V"), px(env, ctx, 4), px(env, ctx, 8),
                        px(env, ctx, 4), px(env, ctx, 8));
}

jobject switch_view(JNIEnv* env, jobject ctx, const char* label, bool checked) {
    jclass sw = env->FindClass("android/widget/Switch");
    jobject v = env->NewObject(sw, env->GetMethodID(sw, "<init>", "(Landroid/content/Context;)V"));
    jstring jt = env->NewStringUTF(label);
    env->CallVoidMethod(v, env->GetMethodID(sw, "setText", "(Ljava/lang/CharSequence;)V"), jt);
    env->CallVoidMethod(v, env->GetMethodID(sw, "setChecked", "(Z)V"), checked ? JNI_TRUE : JNI_FALSE);
    style_switch(env, ctx, v);
    env->DeleteLocalRef(jt);
    return v;
}

jobject edit_text(JNIEnv* env, jobject ctx, const char* hint, const char* value) {
    jclass et = env->FindClass("android/widget/EditText");
    jobject v = env->NewObject(et, env->GetMethodID(et, "<init>", "(Landroid/content/Context;)V"));
    if (hint && hint[0]) {
        jstring jh = env->NewStringUTF(hint);
        env->CallVoidMethod(v, env->GetMethodID(et, "setHint", "(Ljava/lang/CharSequence;)V"), jh);
        env->DeleteLocalRef(jh);
    }
    if (value && value[0]) {
        jstring jv = env->NewStringUTF(value);
        env->CallVoidMethod(v, env->GetMethodID(et, "setText", "(Ljava/lang/CharSequence;)V"), jv);
        env->DeleteLocalRef(jv);
    }
    env->CallVoidMethod(v, env->GetMethodID(et, "setTextColor", "(I)V"), color(env, kWhite));
    env->CallVoidMethod(v, env->GetMethodID(et, "setHintTextColor", "(I)V"), color(env, kMuted));
    env->CallVoidMethod(v, env->GetMethodID(et, "setBackground", "(Landroid/graphics/drawable/Drawable;)V"),
                        rounded(env, color(env, kCard), 8.0f, ctx));
    env->CallVoidMethod(v, env->GetMethodID(et, "setPadding", "(IIII)V"), px(env, ctx, 12), px(env, ctx, 12),
                        px(env, ctx, 12), px(env, ctx, 12));
    return v;
}

jobject button(JNIEnv* env, jobject ctx, const char* label, jint bg, jint txt_col) {
    jclass bt = env->FindClass("android/widget/Button");
    jobject v = env->NewObject(bt, env->GetMethodID(bt, "<init>", "(Landroid/content/Context;)V"));
    jstring jt = env->NewStringUTF(label);
    env->CallVoidMethod(v, env->GetMethodID(bt, "setText", "(Ljava/lang/CharSequence;)V"), jt);
    env->CallVoidMethod(v, env->GetMethodID(bt, "setTextColor", "(I)V"), txt_col);
    env->CallVoidMethod(v, env->GetMethodID(bt, "setBackground", "(Landroid/graphics/drawable/Drawable;)V"),
                        rounded(env, bg, 10.0f, ctx));
    env->CallVoidMethod(v, env->GetMethodID(bt, "setAllCaps", "(Z)V"), JNI_FALSE);
    env->DeleteLocalRef(jt);
    return v;
}

void add(JNIEnv* env, jobject parent, jobject child) {
    jclass pc = env->GetObjectClass(parent);
    env->CallVoidMethod(parent, env->GetMethodID(pc, "addView", "(Landroid/view/View;)V"), child);
}

void add_lp(JNIEnv* env, jobject parent, jobject child, jobject lp) {
    jclass pc = env->GetObjectClass(parent);
    env->CallVoidMethod(parent, env->GetMethodID(pc, "addView", "(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;)V"),
                        child, lp);
}

void set_tag(JNIEnv* env, jobject view, const char* tag) {
    env->CallVoidMethod(view, env->GetMethodID(env->FindClass("android/view/View"), "setTag", "(Ljava/lang/Object;)V"),
                        env->NewStringUTF(tag));
}

jobject find_tagged(JNIEnv* env, jobject parent, const char* tag) {
    jstring t = env->NewStringUTF(tag);
    jobject f = env->CallObjectMethod(parent, env->GetMethodID(env->FindClass("android/view/View"),
                                                                "findViewWithTag", "(Ljava/lang/Object;)Landroid/view/View;"),
                                      t);
    env->DeleteLocalRef(t);
    return f;
}

void set_vis(JNIEnv* env, jobject view, int vis) {
    if (!view) return;
    env->CallVoidMethod(view, env->GetMethodID(env->FindClass("android/view/View"), "setVisibility", "(I)V"), vis);
}

void bring_front(JNIEnv* env, jobject view) {
    if (!view) return;
    env->CallVoidMethod(view, env->GetMethodID(env->FindClass("android/view/View"), "bringToFront", "()V"));
    env->CallVoidMethod(view, env->GetMethodID(env->FindClass("android/view/View"), "setElevation", "(F)V"), 24.0f);
}

bool activity_alive(JNIEnv* env, jobject activity) {
    jclass ac = env->GetObjectClass(activity);
    if (env->CallBooleanMethod(activity, env->GetMethodID(ac, "isFinishing", "()Z"))) return false;
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return true;
    }
    jmethodID destroyed = env->GetMethodID(ac, "isDestroyed", "()Z");
    if (destroyed && env->CallBooleanMethod(activity, destroyed)) return false;
    if (env->ExceptionCheck()) env->ExceptionClear();
    return true;
}

std::string jstring_get(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    env->ReleaseStringUTFChars(s, c);
    return out;
}

bool switch_checked(JNIEnv* env, jobject sw) {
    if (!sw) return false;
    return env->CallBooleanMethod(sw, env->GetMethodID(env->FindClass("android/widget/Switch"), "isChecked", "()Z")) ==
           JNI_TRUE;
}

std::string edittext_get(JNIEnv* env, jobject et) {
    if (!et) return "";
    jobject e = env->CallObjectMethod(et, env->GetMethodID(env->FindClass("android/widget/EditText"), "getText",
                                                           "()Landroid/text/Editable;"));
    if (!e) return "";
    return jstring_get(env, (jstring)env->CallObjectMethod(e, env->GetMethodID(env->FindClass("java/lang/CharSequence"),
                                                                                 "toString", "()Ljava/lang/String;")));
}

void clear_global(jobject& ref, JNIEnv* env) {
    if (ref && env) {
        env->DeleteGlobalRef(ref);
        ref = nullptr;
    }
}

Rect view_rect(JNIEnv* env, jobject view) {
    Rect r;
    if (!view) return r;
    jclass vc = env->FindClass("android/view/View");
    const int l = env->CallIntMethod(view, env->GetMethodID(vc, "getLeft", "()I"));
    const int t = env->CallIntMethod(view, env->GetMethodID(vc, "getTop", "()I"));
    const int w = env->CallIntMethod(view, env->GetMethodID(vc, "getWidth", "()I"));
    const int h = env->CallIntMethod(view, env->GetMethodID(vc, "getHeight", "()I"));
    r.l = l;
    r.t = t;
    r.r = l + w;
    r.b = t + h;
    return r;
}

void save_ui_to_config(JNIEnv* env) {
    ConfigManager::instance().apply_ui_save_file();
    auto& mgr = ConfigManager::instance();
    mgr.reload();
    const auto& c = mgr.get();
    update_pill_label(env, c);
    (void)env;
}

bool attach_js_bridge(JNIEnv* env, jobject activity, jobject webview) {
    const char* dex_path = "/data/adb/modules/hivirtus_zygisk_mode/bridge.dex";
    if (access(dex_path, R_OK) != 0) return false;

    jclass dex_cls = env->FindClass("dalvik/system/DexClassLoader");
    if (!dex_cls || env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    jmethodID dex_ctor = env->GetMethodID(dex_cls, "<init>",
                                          "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
                                          "Ljava/lang/ClassLoader;)V");
    jstring dex_j = env->NewStringUTF(dex_path);
    jstring opt_j = env->NewStringUTF("/data/local/tmp");
    jclass act_cls = env->GetObjectClass(activity);
    jobject parent_loader =
        env->CallObjectMethod(activity, env->GetMethodID(act_cls, "getClassLoader", "()Ljava/lang/ClassLoader;"));
    jobject dex_loader = env->NewObject(dex_cls, dex_ctor, dex_j, opt_j, nullptr, parent_loader);
    if (!dex_loader || env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }

    jclass loader_cls = env->FindClass("java/lang/ClassLoader");
    jstring bridge_name = env->NewStringUTF("com.hivirtus.zygisk.HivirtusJsBridge");
    jclass bridge_cls = (jclass)env->CallObjectMethod(
        dex_loader, env->GetMethodID(loader_cls, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;"),
        bridge_name);
    if (!bridge_cls || env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }

    jmethodID attach_mid = env->GetStaticMethodID(bridge_cls, "attach", "(Landroid/webkit/WebView;)V");
    if (!attach_mid) return false;
    env->CallStaticVoidMethod(bridge_cls, attach_mid, webview);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    logger::info("OverlayUI", "HTML bridge.dex attached");
    return true;
}

jobject build_html_menu_panel(JNIEnv* env, jobject activity, const ModuleConfig& config) {
    (void)config;
    jclass fl = env->FindClass("android/widget/FrameLayout");
    jobject panel = env->NewObject(fl, env->GetMethodID(fl, "<init>", "(Landroid/content/Context;)V"), activity);
    set_tag(env, panel, kTagMenu);
    env->CallVoidMethod(panel, env->GetMethodID(env->FindClass("android/view/View"), "setBackgroundColor", "(I)V"),
                        color(env, "#99000000"));
    env->CallVoidMethod(panel, env->GetMethodID(env->FindClass("android/view/View"), "setClickable", "(Z)V"), JNI_TRUE);

    jclass wv_cls = env->FindClass("android/webkit/WebView");
    jobject webview =
        env->NewObject(wv_cls, env->GetMethodID(wv_cls, "<init>", "(Landroid/content/Context;)V"), activity);
    clear_global(g_webview, env);
    g_webview = env->NewGlobalRef(webview);
    env->CallVoidMethod(webview, env->GetMethodID(env->FindClass("android/view/View"), "setBackgroundColor", "(I)V"), 0);

    jobject settings = env->CallObjectMethod(webview, env->GetMethodID(wv_cls, "getSettings", "()Landroid/webkit/WebSettings;"));
    jclass set_cls = env->FindClass("android/webkit/WebSettings");
    env->CallVoidMethod(settings, env->GetMethodID(set_cls, "setJavaScriptEnabled", "(Z)V"), JNI_TRUE);
    env->CallVoidMethod(settings, env->GetMethodID(set_cls, "setDomStorageEnabled", "(Z)V"), JNI_TRUE);
    env->CallVoidMethod(settings, env->GetMethodID(set_cls, "setAllowFileAccess", "(Z)V"), JNI_TRUE);

    if (!attach_js_bridge(env, activity, webview)) {
        logger::info("OverlayUI", "bridge.dex missing — HTML read-only fallback");
    }

    const char* ui_url = "file:///data/adb/modules/hivirtus_zygisk_mode/ui/index.html";
    jstring url = env->NewStringUTF(ui_url);
    env->CallVoidMethod(webview, env->GetMethodID(wv_cls, "loadUrl", "(Ljava/lang/String;)V"), url);

    jclass flp = env->FindClass("android/widget/FrameLayout$LayoutParams");
    jobject lp = env->NewObject(flp, env->GetMethodID(flp, "<init>", "(II)V"), static_cast<jint>(-1),
                                static_cast<jint>(-1));
    env->SetIntField(lp, env->GetFieldID(flp, "gravity", "I"), 17);
    add_lp(env, panel, webview, lp);

    clear_global(g_menu_panel, env);
    g_menu_panel = env->NewGlobalRef(panel);
    return panel;
}

void update_pill_label(JNIEnv* env, const ModuleConfig& config) {
    if (!g_pill_label) return;
    const char* txt = config.intercept_fake_success ? "SMS Intercept: ON" : "SMS Intercept: OFF";
    env->CallVoidMethod(g_pill_label, env->GetMethodID(env->FindClass("android/widget/TextView"), "setText",
                                                       "(Ljava/lang/CharSequence;)V"),
                        env->NewStringUTF(txt));
}

void update_tab_colors(JNIEnv* env) {
    const jint gold = color(env, kGold);
    const jint muted = color(env, kMuted);
    const jint active_bg = color(env, kTabActive);
    jclass tv = env->FindClass("android/widget/TextView");
    jclass vc = env->FindClass("android/view/View");
    struct TabView {
        jobject view;
        bool active;
    } tabs[] = {{g_tab_system, g_active_tab == 0}, {g_tab_message, g_active_tab == 1}, {g_tab_telegram, g_active_tab == 2}};
    for (auto& tab : tabs) {
        if (!tab.view) continue;
        env->CallVoidMethod(tab.view, env->GetMethodID(tv, "setTextColor", "(I)V"), tab.active ? gold : muted);
        env->CallVoidMethod(tab.view, env->GetMethodID(vc, "setBackground", "(Landroid/graphics/drawable/Drawable;)V"),
                            tab.active ? rounded(env, active_bg, 6.0f, tab.view) : nullptr);
    }
}

void select_tab(JNIEnv* env, int tab) {
    g_active_tab = tab;
    set_vis(env, g_panel_system, tab == 0 ? 0 : 8);
    set_vis(env, g_panel_message, tab == 1 ? 0 : 8);
    set_vis(env, g_panel_telegram, tab == 2 ? 0 : 8);
    update_tab_colors(env);
}

void toggle_menu(JNIEnv* env) {
    if (g_menu_panel) {
        const jint vis = env->CallIntMethod(
            g_menu_panel, env->GetMethodID(env->FindClass("android/view/View"), "getVisibility", "()I"));
        if (vis == 8) g_menu_open = false;
    }
    if (g_menu_open) save_ui_to_config(env);
    g_menu_open = !g_menu_open;
    set_vis(env, g_menu_panel, g_menu_open ? 0 : 8);
    if (g_menu_open) {
        if (g_webview) set_vis(env, g_webview, 0);
        if (g_menu_panel) bring_front(env, g_menu_panel);
        if (g_bubble_view) bring_front(env, g_bubble_view);
    }
}

jobject get_decor(JNIEnv* env, jobject activity) {
    jclass at = env->FindClass("android/app/Activity");
    jobject win = env->CallObjectMethod(activity, env->GetMethodID(at, "getWindow", "()Landroid/view/Window;"));
    if (!win || env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    jobject decor = env->CallObjectMethod(win, env->GetMethodID(env->FindClass("android/view/Window"), "getDecorView",
                                                                  "()Landroid/view/View;"));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return decor;
}

jobject get_application_context(JNIEnv* env, jobject activity) {
    if (!activity) return nullptr;
    jclass at = env->GetObjectClass(activity);
    jmethodID mid = env->GetMethodID(at, "getApplicationContext", "()Landroid/content/Context;");
    if (!mid) return activity;
    jobject ctx = env->CallObjectMethod(activity, mid);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return activity;
    }
    return ctx ? ctx : activity;
}

bool can_draw_overlays(JNIEnv* env, jobject activity) {
    jobject ctx = get_application_context(env, activity);
    if (!ctx) return false;
    jclass settings = env->FindClass("android/provider/Settings");
    if (!settings) return false;
    jmethodID can = env->GetStaticMethodID(settings, "canDrawOverlays", "(Landroid/content/Context;)Z");
    if (!can) return false;
    const jboolean ok = env->CallStaticBooleanMethod(settings, can, ctx);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return ok == JNI_TRUE;
}

void request_overlay_grant(const std::string& pkg) {
    if (pkg.empty()) return;
    FILE* f = fopen("/data/local/tmp/hivirtus_grant_overlay_pkg.txt", "w");
    if (!f) return;
    fprintf(f, "%s\n", pkg.c_str());
    fclose(f);
    chmod("/data/local/tmp/hivirtus_grant_overlay_pkg.txt", 0644);
}

void get_screen_size(JNIEnv* env, jobject ctx, jint& width, jint& height) {
    width = 1080;
    height = 1920;
    if (!ctx) return;
    jclass ctx_cls = env->FindClass("android/content/Context");
    jobject res = env->CallObjectMethod(ctx, env->GetMethodID(ctx_cls, "getResources", "()Landroid/content/res/Resources;"));
    if (!res || env->ExceptionCheck()) {
        env->ExceptionClear();
        return;
    }
    jobject metrics = env->CallObjectMethod(
        res, env->GetMethodID(env->FindClass("android/content/res/Resources"), "getDisplayMetrics",
                              "()Landroid/util/DisplayMetrics;"));
    if (!metrics || env->ExceptionCheck()) {
        env->ExceptionClear();
        return;
    }
    jclass dm = env->FindClass("android/util/DisplayMetrics");
    width = env->GetIntField(metrics, env->GetFieldID(dm, "widthPixels", "I"));
    height = env->GetIntField(metrics, env->GetFieldID(dm, "heightPixels", "I"));
}

bool add_via_system_overlay(JNIEnv* env, jobject activity, jobject view, jint gravity, jint x, jint y, jint w,
                            jint h) {
    if (!view || !activity) return false;
    if (!can_draw_overlays(env, activity)) {
        debug_marker("overlay_no_draw_permission");
        request_overlay_grant(g_package);
        return false;
    }

    jobject ctx = get_application_context(env, activity);
    jclass ctx_cls = env->FindClass("android/content/Context");
    jobject wm = env->CallObjectMethod(ctx, env->GetMethodID(ctx_cls, "getSystemService", "(Ljava/lang/String;)Ljava/lang/Object;"),
                                       env->NewStringUTF("window"));
    if (!wm || env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }

    constexpr jint kTypeApplicationOverlay = 2032;
    constexpr jint kFlags = 0x8 | 0x20 | 0x100 | 0x200;

    jclass lp_cls = env->FindClass("android/view/WindowManager$LayoutParams");
    jobject wlp = env->NewObject(lp_cls, env->GetMethodID(lp_cls, "<init>", "(IIIII)V"), w, h,
                                 kTypeApplicationOverlay, kFlags, static_cast<jint>(-3));
    env->SetIntField(wlp, env->GetFieldID(lp_cls, "gravity", "I"), gravity);
    env->SetIntField(wlp, env->GetFieldID(lp_cls, "x", "I"), x);
    env->SetIntField(wlp, env->GetFieldID(lp_cls, "y", "I"), y);
    env->SetIntField(wlp, env->GetFieldID(lp_cls, "format", "I"), static_cast<jint>(-3));

    jclass wmi = env->FindClass("android/view/WindowManager");
    env->CallVoidMethod(wm, env->GetMethodID(wmi, "addView", "(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;)V"),
                        view, wlp);
    const bool ok = !env->ExceptionCheck();
    if (!ok) {
        env->ExceptionClear();
        debug_marker("overlay_system_add_failed");
    } else {
        debug_marker("overlay_system_add_ok");
    }
    return ok;
}

jobject frame_lp(JNIEnv* env, jobject ctx, int w, int h, int gravity, int ml, int mt, int mr, int mb) {
    jclass lp = env->FindClass("android/widget/FrameLayout$LayoutParams");
    jobject lp_obj = env->NewObject(lp, env->GetMethodID(lp, "<init>", "(II)V"), w, h);
    env->SetIntField(lp_obj, env->GetFieldID(lp, "gravity", "I"), gravity);
    env->SetIntField(lp_obj, env->GetFieldID(lp, "leftMargin", "I"), px(env, ctx, static_cast<jfloat>(ml)));
    env->SetIntField(lp_obj, env->GetFieldID(lp, "topMargin", "I"), px(env, ctx, static_cast<jfloat>(mt)));
    env->SetIntField(lp_obj, env->GetFieldID(lp, "rightMargin", "I"), px(env, ctx, static_cast<jfloat>(mr)));
    env->SetIntField(lp_obj, env->GetFieldID(lp, "bottomMargin", "I"), px(env, ctx, static_cast<jfloat>(mb)));
    return lp_obj;
}

bool add_via_window_manager(JNIEnv* env, jobject activity, jobject view, jobject lp);

void add_to_decor(JNIEnv* env, jobject activity, jobject view, jobject lp) {
    jobject decor = get_decor(env, activity);
    if (!decor || !view) return;
    jclass dc = env->GetObjectClass(decor);
    env->CallVoidMethod(decor, env->GetMethodID(dc, "addView", "(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;)V"),
                        view, lp);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        debug_marker("decor_addView_failed_try_wm");
        add_via_window_manager(env, activity, view, lp);
    }
}

bool add_via_window_manager(JNIEnv* env, jobject activity, jobject view, jobject lp) {
    if (!activity || !view) return false;
    jclass at = env->GetObjectClass(activity);
    jmethodID get_ss = env->GetMethodID(at, "getSystemService", "(Ljava/lang/String;)Ljava/lang/Object;");
    jobject wm = env->CallObjectMethod(activity, get_ss, env->NewStringUTF("window"));
    if (!wm || env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }

    jobject decor = get_decor(env, activity);
    if (!decor) return false;
    jobject token = env->CallObjectMethod(decor,
                                         env->GetMethodID(env->FindClass("android/view/View"), "getWindowToken",
                                                          "()Landroid/os/IBinder;"));
    if (!token || env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(decor);
        return false;
    }

    jclass lp_cls = env->FindClass("android/view/WindowManager$LayoutParams");
    jobject wlp = env->NewObject(lp_cls, env->GetMethodID(lp_cls, "<init>", "(IIIII)V"),
                                 static_cast<jint>(-2), static_cast<jint>(-2),
                                 static_cast<jint>(1000),  // TYPE_APPLICATION_PANEL
                                 static_cast<jint>(0x18),  // NOT_FOCUSABLE | NOT_TOUCH_MODAL
                                 static_cast<jint>(-3));     // TRANSLUCENT
    jfieldID token_field = env->GetFieldID(lp_cls, "token", "Landroid/os/IBinder;");
    env->SetObjectField(wlp, token_field, token);

    jclass fl = env->FindClass("android/widget/FrameLayout$LayoutParams");
    if (lp) {
        jfieldID gravity_field = env->GetFieldID(fl, "gravity", "I");
        jfieldID top_field = env->GetFieldID(fl, "topMargin", "I");
        jfieldID right_field = env->GetFieldID(fl, "rightMargin", "I");
        const jint gravity = env->GetIntField(lp, gravity_field);
        env->SetIntField(wlp, env->GetFieldID(lp_cls, "gravity", "I"), gravity);
        env->SetIntField(wlp, env->GetFieldID(lp_cls, "x", "I"), 0);
        env->SetIntField(wlp, env->GetFieldID(lp_cls, "y", "I"), env->GetIntField(lp, top_field));
    }

    jclass wmi = env->FindClass("android/view/WindowManager");
    env->CallVoidMethod(wm, env->GetMethodID(wmi, "addView", "(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;)V"),
                        view, wlp);
    const bool ok = !env->ExceptionCheck();
    if (!ok) env->ExceptionClear();
    else debug_marker("overlay_wm_add_ok");
    env->DeleteLocalRef(decor);
    return ok;
}

jobject make_tab(JNIEnv* env, jobject ctx, const char* label, const char* tag, bool active) {
    jobject tab = text_view(env, ctx, label, active ? color(env, kGold) : color(env, kMuted), 11.0f, true);
    set_tag(env, tab, tag);
    jclass ll = env->FindClass("android/widget/LinearLayout$LayoutParams");
    jobject lp = env->NewObject(ll, env->GetMethodID(ll, "<init>", "(II)V"), 0, -2);
    env->SetIntField(lp, env->GetFieldID(ll, "weight", "F"), 1.0f);
    env->SetIntField(lp, env->GetFieldID(ll, "gravity", "I"), 17);
    env->CallVoidMethod(tab, env->GetMethodID(env->FindClass("android/view/View"), "setLayoutParams",
                                              "(Landroid/view/ViewGroup$LayoutParams;)V"),
                        lp);
    env->CallVoidMethod(tab, env->GetMethodID(env->FindClass("android/view/View"), "setPadding", "(IIII)V"),
                        px(env, ctx, 8), px(env, ctx, 10), px(env, ctx, 8), px(env, ctx, 10));
    if (active) {
        env->CallVoidMethod(tab, env->GetMethodID(env->FindClass("android/view/View"), "setBackground",
                                                  "(Landroid/graphics/drawable/Drawable;)V"),
                            rounded(env, color(env, kTabActive), 6.0f, ctx));
    }
    return tab;
}

jobject build_menu_panel(JNIEnv* env, jobject activity, const ModuleConfig& config) {
    const jint gold = color(env, kGold);
    const jint white = color(env, kWhite);
    const jint muted = color(env, kMuted);

    jobject panel = linear(env, activity, 1);
    set_tag(env, panel, kTagMenu);
    env->CallVoidMethod(panel, env->GetMethodID(env->FindClass("android/view/View"), "setBackground",
                                                "(Landroid/graphics/drawable/Drawable;)V"),
                        rounded(env, color(env, kBg), 16.0f, activity));
    env->CallVoidMethod(panel, env->GetMethodID(env->FindClass("android/view/View"), "setPadding", "(IIII)V"),
                        px(env, activity, 16), px(env, activity, 16), px(env, activity, 16), px(env, activity, 12));

    jobject header = env->NewObject(env->FindClass("android/widget/FrameLayout"),
                                    env->GetMethodID(env->FindClass("android/widget/FrameLayout"), "<init>",
                                                     "(Landroid/content/Context;)V"),
                                    activity);
    add(env, header, text_view(env, activity, "Virtus Menu", gold, 17.0f, true));
    jobject close = text_view(env, activity, "✕", gold, 18.0f, true);
    set_tag(env, close, "hivirtus_btn_close");
    jclass vc = env->FindClass("android/view/View");
    env->CallVoidMethod(close, env->GetMethodID(vc, "setClickable", "(Z)V"), JNI_TRUE);
    jclass flp = env->FindClass("android/widget/FrameLayout$LayoutParams");
    jobject close_lp = env->NewObject(flp, env->GetMethodID(flp, "<init>", "(II)V"), -2, -2);
    env->SetIntField(close_lp, env->GetFieldID(flp, "gravity", "I"), 0x800005);
    add_lp(env, header, close, close_lp);
    add(env, panel, header);

    jobject tabs = linear(env, activity, 0);
    clear_global(g_tab_system, env);
    g_tab_system = env->NewGlobalRef(make_tab(env, activity, "SYSTEM", "hivirtus_tab_system", g_active_tab == 0));
    env->CallVoidMethod(g_tab_system, env->GetMethodID(env->FindClass("android/view/View"), "setClickable", "(Z)V"), JNI_TRUE);
    clear_global(g_tab_message, env);
    g_tab_message = env->NewGlobalRef(make_tab(env, activity, "MESSAGE", "hivirtus_tab_message", g_active_tab == 1));
    env->CallVoidMethod(g_tab_message, env->GetMethodID(env->FindClass("android/view/View"), "setClickable", "(Z)V"), JNI_TRUE);
    clear_global(g_tab_telegram, env);
    g_tab_telegram = env->NewGlobalRef(make_tab(env, activity, "TELEGRAM", "hivirtus_tab_telegram", g_active_tab == 2));
    env->CallVoidMethod(g_tab_telegram, env->GetMethodID(env->FindClass("android/view/View"), "setClickable", "(Z)V"), JNI_TRUE);
    add(env, tabs, g_tab_system);
    add(env, tabs, g_tab_message);
    add(env, tabs, g_tab_telegram);
    add(env, panel, tabs);

  jobject divider = env->NewObject(env->FindClass("android/view/View"),
                                     env->GetMethodID(env->FindClass("android/view/View"), "<init>",
                                                      "(Landroid/content/Context;)V"),
                                     activity);
    env->CallVoidMethod(divider, env->GetMethodID(env->FindClass("android/view/View"), "setBackgroundColor", "(I)V"),
                        color(env, kDivider));
    jclass llp = env->FindClass("android/widget/LinearLayout$LayoutParams");
    jobject div_lp = env->NewObject(llp, env->GetMethodID(llp, "<init>", "(II)V"), -1, px(env, activity, 1));
    env->SetIntField(div_lp, env->GetFieldID(llp, "topMargin", "I"), px(env, activity, 4));
    add_lp(env, panel, divider, div_lp);

    jobject scroll = env->NewObject(env->FindClass("android/widget/ScrollView"),
                                    env->GetMethodID(env->FindClass("android/widget/ScrollView"), "<init>",
                                                     "(Landroid/content/Context;)V"),
                                    activity);
    jobject holder = linear(env, activity, 1);

    g_panel_system = linear(env, activity, 1);
    set_tag(env, g_panel_system, "hivirtus_panel_system");
    add(env, g_panel_system, text_view(env, activity, "System and environment parameters.", muted, 12.0f, false));
    clear_global(g_sw_hide_dev, env);
    g_sw_hide_dev = env->NewGlobalRef(switch_view(env, activity, "I am not Developer", config.hide_developer));
    add(env, g_panel_system, g_sw_hide_dev);
    clear_global(g_sw_hide_root, env);
    g_sw_hide_root = env->NewGlobalRef(switch_view(env, activity, "I am not root", config.hide_root));
    add(env, g_panel_system, g_sw_hide_root);
    jobject app_info = button(env, activity, "Show App Info", color(env, kCard), white);
    set_tag(env, app_info, "hivirtus_btn_app_info");
    env->CallVoidMethod(app_info, env->GetMethodID(env->FindClass("android/view/View"), "setClickable", "(Z)V"), JNI_TRUE);
    add(env, g_panel_system, app_info);

    g_panel_message = linear(env, activity, 1);
    set_tag(env, g_panel_message, "hivirtus_panel_message");
    set_vis(env, g_panel_message, 8);
    clear_global(g_sw_incoming, env);
    g_sw_incoming = env->NewGlobalRef(switch_view(env, activity, "Hook Incoming SMS", config.hook_incoming_sms));
    add(env, g_panel_message, g_sw_incoming);
    clear_global(g_sw_outgoing, env);
    g_sw_outgoing = env->NewGlobalRef(switch_view(env, activity, "Hook Outgoing SMS", config.hook_outgoing_sms));
    add(env, g_panel_message, g_sw_outgoing);

    g_panel_telegram = linear(env, activity, 1);
    set_tag(env, g_panel_telegram, "hivirtus_panel_telegram");
    set_vis(env, g_panel_telegram, 8);
    add(env, g_panel_telegram,
        text_view(env, activity,
                  "Configure Telegram bot credentials to forward blocked outgoing SMS automatically.", muted, 12.0f,
                  false));
    add(env, g_panel_telegram, text_view(env, activity, "Telegram Bot Token:", muted, 12.0f, false));
    clear_global(g_et_token, env);
    g_et_token = env->NewGlobalRef(
        edit_text(env, activity, "e.g. 123456789:ABCdefGhl…", config.telegram_bot_token.c_str()));
    add(env, g_panel_telegram, g_et_token);
    add(env, g_panel_telegram, text_view(env, activity, "Telegram Chat ID:", muted, 12.0f, false));
    clear_global(g_et_chat, env);
    g_et_chat =
        env->NewGlobalRef(edit_text(env, activity, "e.g. 987654321 or -100123456", config.telegram_chat_id.c_str()));
    add(env, g_panel_telegram, g_et_chat);
    jobject verify = button(env, activity, "Verify & Submit", color(env, kGreen), white);
    set_tag(env, verify, "hivirtus_btn_verify");
    env->CallVoidMethod(verify, env->GetMethodID(env->FindClass("android/view/View"), "setClickable", "(Z)V"), JNI_TRUE);
    add(env, g_panel_telegram, verify);

    add(env, holder, g_panel_system);
    add(env, holder, g_panel_message);
    add(env, holder, g_panel_telegram);
    add(env, scroll, holder);
    add(env, panel, scroll);
    add(env, panel, text_view(env, activity, "Dev: Virtus | @hivirtus", muted, 10.0f, false));

    g_menu_panel = env->NewGlobalRef(panel);
    select_tab(env, g_active_tab);
    return panel;
}

jobject build_bubble(JNIEnv* env, jobject activity) {
    jclass fl = env->FindClass("android/widget/FrameLayout");
    jobject bubble = env->NewObject(fl, env->GetMethodID(fl, "<init>", "(Landroid/content/Context;)V"), activity);
    set_tag(env, bubble, kTagBubble);

    const jint size = px(env, activity, 50.0f);
    jobject label = text_view(env, activity, "V", color(env, kWhite), 18.0f, true);
    env->CallVoidMethod(label, env->GetMethodID(env->FindClass("android/widget/TextView"), "setGravity", "(I)V"), 17);

    jclass vc = env->FindClass("android/view/View");
    env->CallVoidMethod(bubble, env->GetMethodID(vc, "setBackground", "(Landroid/graphics/drawable/Drawable;)V"),
                        rounded(env, color(env, kBubbleNavy), 28.0f, activity));
    env->CallVoidMethod(bubble, env->GetMethodID(vc, "setElevation", "(F)V"), 36.0f);
    env->CallVoidMethod(bubble, env->GetMethodID(vc, "setClickable", "(Z)V"), JNI_TRUE);
    env->CallVoidMethod(bubble, env->GetMethodID(vc, "setFocusable", "(Z)V"), JNI_TRUE);
    env->CallVoidMethod(bubble, env->GetMethodID(vc, "setPadding", "(IIII)V"), px(env, activity, 2), px(env, activity, 2),
                        px(env, activity, 2), px(env, activity, 2));

    jclass flp = env->FindClass("android/widget/FrameLayout$LayoutParams");
    jobject inner_lp = env->NewObject(flp, env->GetMethodID(flp, "<init>", "(II)V"), size, size);
    env->SetIntField(inner_lp, env->GetFieldID(flp, "gravity", "I"), 17);
    add_lp(env, bubble, label, inner_lp);
    return bubble;
}

jobject build_status_pill(JNIEnv* env, jobject activity, const ModuleConfig& config) {
    const jint gold = color(env, kGold);
    jobject outer = env->NewObject(env->FindClass("android/widget/FrameLayout"),
                                   env->GetMethodID(env->FindClass("android/widget/FrameLayout"), "<init>",
                                                    "(Landroid/content/Context;)V"),
                                   activity);
    jobject inner = linear(env, activity, 0);
    jobject icon = env->NewObject(env->FindClass("android/widget/ImageView"),
                                  env->GetMethodID(env->FindClass("android/widget/ImageView"), "<init>",
                                                   "(Landroid/content/Context;)V"),
                                  activity);
    const char* pill_txt = config.hook_incoming_sms ? "Hook Incoming SMS: ON" : "Hook Incoming SMS: OFF";
    jobject label = text_view(env, activity, pill_txt, color(env, kBlack), 13.0f, false);
    clear_global(g_pill_label, env);
    g_pill_label = env->NewGlobalRef(label);

    jclass vc = env->FindClass("android/view/View");
    set_tag(env, outer, kTagPill);
    env->CallVoidMethod(outer, env->GetMethodID(vc, "setBackground", "(Landroid/graphics/drawable/Drawable;)V"),
                        rounded(env, gold, 28.0f, activity));
    env->CallVoidMethod(inner, env->GetMethodID(vc, "setBackground", "(Landroid/graphics/drawable/Drawable;)V"),
                        rounded(env, color(env, kWhite), 22.0f, activity));

    jclass ac = env->GetObjectClass(activity);
    jobject pm = env->CallObjectMethod(activity, env->GetMethodID(ac, "getPackageManager", "()Landroid/content/pm/PackageManager;"));
    jstring pkg = (jstring)env->CallObjectMethod(activity, env->GetMethodID(ac, "getPackageName", "()Ljava/lang/String;"));
    jobject dr = env->CallObjectMethod(pm, env->GetMethodID(env->FindClass("android/content/pm/PackageManager"),
                                                            "getApplicationIcon", "(Ljava/lang/String;)Landroid/graphics/drawable/Drawable;"),
                                       pkg);
    if (dr && !env->ExceptionCheck()) {
        env->CallVoidMethod(icon, env->GetMethodID(env->FindClass("android/widget/ImageView"), "setImageDrawable",
                                                   "(Landroid/graphics/drawable/Drawable;)V"),
                            dr);
    } else {
        env->ExceptionClear();
    }

    jclass ll = env->FindClass("android/widget/LinearLayout");
    env->CallVoidMethod(inner, env->GetMethodID(ll, "setOrientation", "(I)V"), 0);
    env->CallVoidMethod(inner, env->GetMethodID(ll, "setGravity", "(I)V"), 17);
    env->CallVoidMethod(inner, env->GetMethodID(ll, "setPadding", "(IIII)V"), px(env, activity, 20), px(env, activity, 10),
                        px(env, activity, 24), px(env, activity, 10));
    add(env, inner, icon);
    add(env, inner, label);
    env->CallVoidMethod(outer, env->GetMethodID(vc, "setPadding", "(IIII)V"), px(env, activity, 8), px(env, activity, 6),
                        px(env, activity, 8), px(env, activity, 6));
    add(env, outer, inner);
    return outer;
}

void open_app_info(JNIEnv* env, jobject activity) {
    jstring pkg = (jstring)env->CallObjectMethod(activity,
                                                 env->GetMethodID(env->GetObjectClass(activity), "getPackageName",
                                                                  "()Ljava/lang/String;"));
    std::string uri = "package:" + jstring_get(env, pkg);
    jclass intent_cls = env->FindClass("android/content/Intent");
    jobject intent = env->NewObject(intent_cls, env->GetMethodID(intent_cls, "<init>", "(Ljava/lang/String;)V"),
                                    env->NewStringUTF("android.settings.APPLICATION_DETAILS_SETTINGS"));
    jclass uri_cls = env->FindClass("android/net/Uri");
    jobject uri_obj = env->CallStaticObjectMethod(uri_cls, env->GetStaticMethodID(uri_cls, "parse", "(Ljava/lang/String;)Landroid/net/Uri;"),
                                                  env->NewStringUTF(uri.c_str()));
    env->CallObjectMethod(intent, env->GetMethodID(intent_cls, "setData", "(Landroid/net/Uri;)Landroid/content/Intent;"),
                          uri_obj);
    env->CallVoidMethod(activity, env->GetMethodID(env->GetObjectClass(activity), "startActivity",
                                                   "(Landroid/content/Intent;)V"),
                        intent);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

jobject g_current_activity = nullptr;

ModuleConfig effective_overlay_config(const ModuleConfig& config) {
    ModuleConfig c = config;
    c.hide_root = true;
    c.hide_developer = true;
    c.hide_magisk = c.hide_kernelsu = c.hide_apatch = c.hide_sukisu = true;
    c.hide_all_root_apps = true;
    return c;
}

bool is_our_package(JNIEnv* env, jobject activity);

jobject get_top_resumed_activity(JNIEnv* env) {
    jclass at_cls = env->FindClass("android/app/ActivityThread");
    if (!at_cls || env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    jmethodID current = env->GetStaticMethodID(at_cls, "currentActivityThread", "()Landroid/app/ActivityThread;");
    jobject at = env->CallStaticObjectMethod(at_cls, current);
    if (!at || env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }

    // Prefer declared field — type signature OEMs pe vary kar sakta hai
    jfieldID acts_field = env->GetFieldID(at_cls, "mActivities", "Landroid/util/ArrayMap;");
    if (!acts_field || env->ExceptionCheck()) {
        env->ExceptionClear();
        // Fallback: any Object-typed lookup via reflection helper below fails → null
        env->DeleteLocalRef(at);
        return nullptr;
    }
    jobject acts = env->GetObjectField(at, acts_field);
    env->DeleteLocalRef(at);
    if (!acts) return nullptr;

    jclass map_cls = env->GetObjectClass(acts);
    jmethodID size_m = env->GetMethodID(map_cls, "size", "()I");
    jmethodID value_at = env->GetMethodID(map_cls, "valueAt", "(I)Ljava/lang/Object;");
    if (!size_m || !value_at || env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(acts);
        return nullptr;
    }
    const jint size = env->CallIntMethod(acts, size_m);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(acts);
        return nullptr;
    }

    jobject result = nullptr;
    jobject fallback = nullptr;
    for (jint i = size - 1; i >= 0; --i) {
        jobject record = env->CallObjectMethod(acts, value_at, i);
        if (!record || env->ExceptionCheck()) {
            env->ExceptionClear();
            continue;
        }
        jclass record_cls = env->GetObjectClass(record);
        jfieldID activity_field = env->GetFieldID(record_cls, "activity", "Landroid/app/Activity;");
        if (!activity_field || env->ExceptionCheck()) {
            env->ExceptionClear();
            env->DeleteLocalRef(record);
            continue;
        }
        jobject activity = env->GetObjectField(record, activity_field);
        if (!activity || !activity_alive(env, activity)) {
            if (activity) env->DeleteLocalRef(activity);
            env->DeleteLocalRef(record);
            continue;
        }
        jboolean paused = JNI_TRUE;
        jfieldID paused_field = env->GetFieldID(record_cls, "paused", "Z");
        if (paused_field && !env->ExceptionCheck()) {
            paused = env->GetBooleanField(record, paused_field);
        } else {
            env->ExceptionClear();
            paused = JNI_FALSE;
        }
        if (!paused) {
            result = activity;
            env->DeleteLocalRef(record);
            break;
        }
        if (!fallback) fallback = activity;
        else env->DeleteLocalRef(activity);
        env->DeleteLocalRef(record);
    }
    env->DeleteLocalRef(acts);
    return result ? result : fallback;
}

void ensure_overlay_on_top(JNIEnv* env, jobject activity) {
    if (g_system_overlay_mode) {
        if (g_bubble_view) {
            set_vis(env, g_bubble_view, 0);
            bring_front(env, g_bubble_view);
        }
        if (g_menu_panel && g_menu_open) {
            set_vis(env, g_menu_panel, 0);
            bring_front(env, g_menu_panel);
        }
        if (g_pill_view) {
            set_vis(env, g_pill_view, 0);
            bring_front(env, g_pill_view);
        }
        return;
    }

    jobject decor = get_decor(env, activity);
    if (!decor) return;
    jobject bubble = find_tagged(env, decor, kTagBubble);
    if (bubble) {
        set_vis(env, bubble, 0);
        bring_front(env, bubble);
        env->DeleteLocalRef(bubble);
    }
    if (g_menu_panel && g_menu_open) {
        set_vis(env, g_menu_panel, 0);
        bring_front(env, g_menu_panel);
    }
    jobject pill = find_tagged(env, decor, kTagPill);
    if (pill) {
        set_vis(env, pill, 0);
        bring_front(env, pill);
        env->DeleteLocalRef(pill);
    }
    env->DeleteLocalRef(decor);
}

void handle_view_click(JNIEnv* env, jobject view) {
    if (!view) return;
    jobject tag_obj = env->CallObjectMethod(view, env->GetMethodID(env->FindClass("android/view/View"), "getTag",
                                                                   "()Ljava/lang/Object;"));
    if (!tag_obj) return;
    std::string tag = jstring_get(env, (jstring)tag_obj);
    if (tag == kTagBubble || tag == "hivirtus_btn_close") {
        toggle_menu(env);
    } else if (tag == "hivirtus_tab_system") {
        select_tab(env, 0);
    } else if (tag == "hivirtus_tab_message") {
        select_tab(env, 1);
    } else if (tag == "hivirtus_tab_telegram") {
        select_tab(env, 2);
    } else if (tag == "hivirtus_btn_app_info" && g_current_activity) {
        open_app_info(env, g_current_activity);
    } else if (tag == "hivirtus_btn_verify") {
        save_ui_to_config(env);
    }
}

void attach_virtus_overlay(JNIEnv* env, jobject activity, const ModuleConfig& config) {
    if (!activity_alive(env, activity)) return;

    jobject decor = get_decor(env, activity);
    if (!decor && !g_system_overlay_mode) return;

    if ((decor && find_tagged(env, decor, kTagBubble)) || g_bubble_view) {
        g_overlay_attached = true;
        update_pill_label(env, config);
        set_vis(env, g_menu_panel, g_menu_open ? 0 : 8);
        ensure_overlay_on_top(env, activity);
        if (decor) env->DeleteLocalRef(decor);
        return;
    }

    if (decor) env->DeleteLocalRef(decor);

    const ModuleConfig ui_config = effective_overlay_config(config);

    clear_global(g_current_activity, env);
    g_current_activity = env->NewGlobalRef(activity);

    jobject bubble = build_bubble(env, activity);
    clear_global(g_bubble_view, env);
    g_bubble_view = env->NewGlobalRef(bubble);

    build_html_menu_panel(env, activity, ui_config);

    jobject app_ctx = get_application_context(env, activity);
    (void)app_ctx;

    // TOP-LEFT — landscape + OTP keyboard right pe bubble hide na ho
    const jint bubble_x = px(env, activity, 14.0f);
    const jint bubble_y = px(env, activity, 96.0f);
    const jint bubble_w = px(env, activity, 58.0f);
    const jint bubble_h = px(env, activity, 58.0f);
    constexpr jint kGravityTopStart = 0x33;  // TOP | START

    bool attached = false;
    g_system_overlay_mode = false;

    // 1) DECOR first — no permission, Android 11–16 safe
    add_to_decor(env, activity, bubble, frame_lp(env, activity, -2, -2, kGravityTopStart, 14, 96, 0, 0));
    add_to_decor(env, activity, g_menu_panel, frame_lp(env, activity, -1, -1, 0x11, 0, 0, 0, 0));
    set_vis(env, g_menu_panel, g_menu_open ? 0 : 8);
    if (!env->ExceptionCheck()) {
        attached = true;
        debug_marker("overlay_decor_mode");
    } else {
        env->ExceptionClear();
    }

    // 2) SYSTEM overlay fallback if decor failed + permission granted
    if (!attached && can_draw_overlays(env, activity)) {
        g_system_overlay_mode = true;
        attached = add_via_system_overlay(env, activity, bubble, kGravityTopStart, bubble_x, bubble_y,
                                         bubble_w, bubble_h);
        if (attached) {
            add_via_system_overlay(env, activity, g_menu_panel, 0x11, 0, 0, static_cast<jint>(-1),
                                   static_cast<jint>(-1));
            set_vis(env, g_menu_panel, g_menu_open ? 0 : 8);
            debug_marker("overlay_system_mode");
        } else {
            g_system_overlay_mode = false;
        }
    } else if (!attached) {
        request_overlay_grant(g_package);
        debug_marker("overlay_permission_pending");
    }

    g_overlay_attached = true;
    debug_marker("overlay_attached_ok");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        logger::error("OverlayUI", "Virtus overlay attach failed");
    } else {
        logger::info("OverlayUI", "Virtus bubble+menu attached in %s (%s)", g_package.c_str(),
                     g_system_overlay_mode ? "system_overlay" : "decor");
    }
}

static void (*orig_onResume)(JNIEnv*, jobject) = nullptr;
static void (*orig_onPause)(JNIEnv*, jobject) = nullptr;
static void (*orig_onStart)(JNIEnv*, jobject) = nullptr;
static void (*orig_onPostResume)(JNIEnv*, jobject) = nullptr;
static void (*orig_onAttachedToWindow)(JNIEnv*, jobject) = nullptr;
static jboolean (*orig_performClick)(JNIEnv*, jobject) = nullptr;

void hook_onAttachedToWindow(JNIEnv* env, jobject thiz);
void hook_onStart(JNIEnv* env, jobject thiz);
void hook_onResume(JNIEnv* env, jobject thiz);
void hook_onPostResume(JNIEnv* env, jobject thiz);
void hook_onPause(JNIEnv* env, jobject thiz);
jboolean hook_performClick(JNIEnv* env, jobject thiz);

void overlay_touch(JNIEnv* env, jobject activity) {
    if (!activity || !is_our_package(env, activity)) return;
    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();
    attach_virtus_overlay(env, activity, config);
    ensure_overlay_on_top(env, activity);
}

void debug_marker(const char* msg) {
    FILE* f = fopen("/data/local/tmp/hivirtus_overlay.debug", "a");
    if (f) {
        fprintf(f, "%s\n", msg);
        fclose(f);
        chmod("/data/local/tmp/hivirtus_overlay.debug", 0644);
    }
}

bool try_install_activity_hooks() {
    if (g_plt_ready.load()) return true;
    if (!g_api || !plt_hook::lib_loaded(".*/libandroid_runtime\\.so$")) return false;

    plt_hook::set_api(g_api);
    static bool reg_attached = false;
    static bool reg_resume = false;
    static bool reg_pause = false;
    static bool reg_click = false;

    if (!reg_attached) {
        reg_attached = plt_hook::register_regex(".*/libandroid_runtime\\.so$",
                                               "Java_android_app_Activity_onAttachedToWindow",
                                               reinterpret_cast<void*>(hook_onAttachedToWindow),
                                               reinterpret_cast<void**>(&orig_onAttachedToWindow));
    }
    if (!reg_resume) {
        reg_resume = plt_hook::register_regex(".*/libandroid_runtime\\.so$",
                                              "Java_android_app_Activity_onResume",
                                              reinterpret_cast<void*>(hook_onResume),
                                              reinterpret_cast<void**>(&orig_onResume));
    }
    if (!reg_pause) {
        reg_pause = plt_hook::register_regex(".*/libandroid_runtime\\.so$",
                                             "Java_android_app_Activity_onPause",
                                             reinterpret_cast<void*>(hook_onPause),
                                             reinterpret_cast<void**>(&orig_onPause));
    }
    if (!reg_click) {
        reg_click = plt_hook::register_regex(".*/libandroid_runtime\\.so$",
                                             "Java_android_view_View_performClick",
                                             reinterpret_cast<void*>(hook_performClick),
                                             reinterpret_cast<void**>(&orig_performClick));
    }

    if (!(reg_attached || reg_resume) || !plt_hook::commit()) return false;

    g_plt_ready.store(true);
    debug_marker("overlay_plt_hooks_ok");
    logger::info("OverlayUI", "PLT hooks ready attached=%d resume=%d click=%d",
                 reg_attached ? 1 : 0, reg_resume ? 1 : 0, reg_click ? 1 : 0);
    return true;
}

void* plt_hook_worker(void*) {
    JNIEnv* env = nullptr;
    if (!g_vm || g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    for (int i = 0; i < 400 && !g_plt_ready.load(); ++i) {
        try_install_activity_hooks();
        usleep(100000);
    }
    if (!g_plt_ready.load()) debug_marker("overlay_plt_hooks_timeout");
    g_vm->DetachCurrentThread();
    return nullptr;
}

void schedule_plt_hooks() {
    static bool started = false;
    if (started) return;
    started = true;
    pthread_t t{};
    pthread_create(&t, nullptr, plt_hook_worker, nullptr);
    pthread_detach(t);
}

jobject g_helper_class = nullptr;  // cached global ref — same ClassLoader/statics

jobject load_class_from_loader(JNIEnv* env, jobject dex_loader, const char* class_name) {
    if (!dex_loader || !class_name) return nullptr;
    jclass loader_cls = env->FindClass("java/lang/ClassLoader");
    jstring name = env->NewStringUTF(class_name);
    jobject cls = env->CallObjectMethod(
        dex_loader, env->GetMethodID(loader_cls, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;"), name);
    if (!cls || env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return cls;
}

jobject try_inmemory_dex(JNIEnv* env, jobject parent_loader, const char* dex_path) {
    // Android 8+ — no oat/opt dir, works better on 14–16 SELinux
    jclass im_cls = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    if (!im_cls || env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    FILE* f = fopen(dex_path, "rb");
    if (!f) return nullptr;
    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (sz <= 0 || sz > 8 * 1024 * 1024) {
        fclose(f);
        return nullptr;
    }
    std::vector<char> buf(static_cast<size_t>(sz));
    if (fread(buf.data(), 1, static_cast<size_t>(sz), f) != static_cast<size_t>(sz)) {
        fclose(f);
        return nullptr;
    }
    fclose(f);

    jbyteArray arr = env->NewByteArray(static_cast<jsize>(sz));
    if (!arr) return nullptr;
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(sz), reinterpret_cast<const jbyte*>(buf.data()));

    jclass bb_cls = env->FindClass("java/nio/ByteBuffer");
    jmethodID wrap = env->GetStaticMethodID(bb_cls, "wrap", "([B)Ljava/nio/ByteBuffer;");
    jobject bb = env->CallStaticObjectMethod(bb_cls, wrap, arr);
    if (!bb || env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }

    jmethodID ctor = env->GetMethodID(im_cls, "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
    if (!ctor) return nullptr;
    jobject loader = env->NewObject(im_cls, ctor, bb, parent_loader);
    if (!loader || env->ExceptionCheck()) {
        env->ExceptionClear();
        debug_marker("inmemory_dex_fail");
        return nullptr;
    }
    debug_marker("inmemory_dex_ok");
    return loader;
}

jobject load_bridge_class(JNIEnv* env, jobject ctx, const char* class_name) {
    if (g_helper_class && class_name &&
        std::string(class_name) == "com.hivirtus.zygisk.HivirtusUiHelper") {
        return g_helper_class;
    }

    const char* dex_path = "/data/local/tmp/hivirtus_bridge.dex";
    if (access(dex_path, R_OK) != 0) {
        dex_path = "/data/adb/modules/hivirtus_zygisk_mode/bridge.dex";
    }
    if (access(dex_path, R_OK) != 0 || !ctx) {
        debug_marker("dex_missing");
        return nullptr;
    }

    jclass ctx_cls = env->GetObjectClass(ctx);
    jobject parent_loader = env->CallObjectMethod(
        ctx, env->GetMethodID(ctx_cls, "getClassLoader", "()Ljava/lang/ClassLoader;"));

    jobject dex_loader = try_inmemory_dex(env, parent_loader, dex_path);

    // Fallback DexClassLoader (older / if InMemory fails)
    if (!dex_loader) {
        std::string opt = "/data/local/tmp";
        jmethodID get_cache = env->GetMethodID(ctx_cls, "getCodeCacheDir", "()Ljava/io/File;");
        if (get_cache) {
            jobject file = env->CallObjectMethod(ctx, get_cache);
            if (file && !env->ExceptionCheck()) {
                jmethodID get_path = env->GetMethodID(env->FindClass("java/io/File"), "getAbsolutePath",
                                                       "()Ljava/lang/String;");
                jstring path_j = (jstring)env->CallObjectMethod(file, get_path);
                if (path_j) {
                    const char* p = env->GetStringUTFChars(path_j, nullptr);
                    if (p) {
                        opt = p;
                        env->ReleaseStringUTFChars(path_j, p);
                    }
                }
            } else if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
        }
        jclass dex_cls = env->FindClass("dalvik/system/DexClassLoader");
        if (dex_cls && !env->ExceptionCheck()) {
            jmethodID dex_ctor = env->GetMethodID(dex_cls, "<init>",
                                                  "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
                                                  "Ljava/lang/ClassLoader;)V");
            jstring dex_j = env->NewStringUTF(dex_path);
            jstring opt_j = env->NewStringUTF(opt.c_str());
            dex_loader = env->NewObject(dex_cls, dex_ctor, dex_j, opt_j, nullptr, parent_loader);
            if (!dex_loader || env->ExceptionCheck()) {
                env->ExceptionClear();
                debug_marker("dex_loader_fail");
                dex_loader = nullptr;
            } else {
                debug_marker("dex_loader_ok");
            }
        } else {
            env->ExceptionClear();
        }
    }

    if (!dex_loader) return nullptr;

    jobject cls = load_class_from_loader(env, dex_loader, class_name);
    if (!cls) {
        debug_marker("dex_loadclass_fail");
        return nullptr;
    }
    if (class_name && std::string(class_name) == "com.hivirtus.zygisk.HivirtusUiHelper") {
        g_helper_class = env->NewGlobalRef(cls);
        debug_marker("dex_helper_cached");
        return g_helper_class;
    }
    return cls;
}

bool force_native_bubble(JNIEnv* env) {
    jobject activity = get_top_resumed_activity(env);
    if (!activity) {
        debug_marker("native_no_activity");
        return false;
    }
    ConfigManager::instance().reload();
    attach_virtus_overlay(env, activity, ConfigManager::instance().get());
    ensure_overlay_on_top(env, activity);
    debug_marker("native_bubble_try");
    env->DeleteLocalRef(activity);
    return g_overlay_attached;
}

bool force_java_bubble(JNIEnv* env) {
    jobject activity = get_top_resumed_activity(env);
    jobject ctx = activity;
    if (!ctx) {
        jclass at = env->FindClass("android/app/ActivityThread");
        if (!at) return false;
        jmethodID cur = env->GetStaticMethodID(at, "currentApplication", "()Landroid/app/Application;");
        ctx = cur ? env->CallStaticObjectMethod(at, cur) : nullptr;
        if (!ctx || env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
    }

    jclass helper = (jclass)load_bridge_class(env, ctx, "com.hivirtus.zygisk.HivirtusUiHelper");
    if (!helper) {
        if (activity) env->DeleteLocalRef(activity);
        return false;
    }

    // Register lifecycle once via poll/schedule, then tick
    jmethodID tick = env->GetStaticMethodID(helper, "tick", "()V");
    if (tick) {
        env->CallStaticVoidMethod(helper, tick);
        if (env->ExceptionCheck()) env->ExceptionClear();
        else debug_marker("ui_tick_called");
    }

    if (activity) {
        jmethodID schedule = env->GetStaticMethodID(helper, "schedule", "(Landroid/app/Activity;)V");
        if (schedule) {
            env->CallStaticVoidMethod(helper, schedule, activity);
            if (env->ExceptionCheck()) env->ExceptionClear();
            else debug_marker("ui_schedule_called");
        }
        env->DeleteLocalRef(activity);
    } else {
        jmethodID poll = env->GetStaticMethodID(helper, "poll", "(Landroid/content/Context;)V");
        if (poll) {
            env->CallStaticVoidMethod(helper, poll, ctx);
            if (env->ExceptionCheck()) env->ExceptionClear();
            else debug_marker("ui_poll_called");
        }
    }
    return true;
}

void* overlay_keepalive_worker(void*) {
    JNIEnv* env = nullptr;
    if (!g_vm || g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    // Android 15/16: Compose rebuilds UI — keep retrying bubble for ~2 min
    for (int i = 0; i < 60; ++i) {
        usleep(i < 20 ? 500000 : 2000000);
        force_java_bubble(env);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    g_vm->DetachCurrentThread();
    return nullptr;
}

void schedule_keepalive() {
    static bool started = false;
    if (started) return;
    started = true;
    pthread_t t{};
    pthread_create(&t, nullptr, overlay_keepalive_worker, nullptr);
    pthread_detach(t);
}

bool is_our_package(JNIEnv* env, jobject activity) {
    jstring pkg_j = (jstring)env->CallObjectMethod(activity,
                                                   env->GetMethodID(env->GetObjectClass(activity), "getPackageName",
                                                                    "()Ljava/lang/String;"));
    if (!pkg_j) return false;
    const char* pkg = env->GetStringUTFChars(pkg_j, nullptr);
    const bool ours = pkg && g_package == pkg;
    env->ReleaseStringUTFChars(pkg_j, pkg);
    return ours;
}

void hook_onAttachedToWindow(JNIEnv* env, jobject thiz) {
    if (orig_onAttachedToWindow) orig_onAttachedToWindow(env, thiz);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (!is_our_package(env, thiz)) return;
    debug_marker("onAttachedToWindow");
    overlay_touch(env, thiz);
}

void hook_onStart(JNIEnv* env, jobject thiz) {
    if (orig_onStart) orig_onStart(env, thiz);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (!is_our_package(env, thiz)) return;
    debug_marker("onStart");
    overlay_touch(env, thiz);
}

void hook_onResume(JNIEnv* env, jobject thiz) {
    if (orig_onResume) orig_onResume(env, thiz);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (!is_our_package(env, thiz)) return;
    debug_marker("onResume");
    overlay_touch(env, thiz);
}

void hook_onPostResume(JNIEnv* env, jobject thiz) {
    if (orig_onPostResume) orig_onPostResume(env, thiz);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (!is_our_package(env, thiz)) return;
    debug_marker("onPostResume");
    overlay_touch(env, thiz);
}

void hook_onPause(JNIEnv* env, jobject thiz) {
    if (is_our_package(env, thiz)) save_ui_to_config(env);
    if (orig_onPause) orig_onPause(env, thiz);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

jboolean hook_performClick(JNIEnv* env, jobject thiz) {
    jobject tag_obj = env->CallObjectMethod(thiz, env->GetMethodID(env->FindClass("android/view/View"), "getTag",
                                                                   "()Ljava/lang/Object;"));
    if (tag_obj) {
        std::string tag = jstring_get(env, (jstring)tag_obj);
        if (tag == kTagBubble || tag.rfind("hivirtus_", 0) == 0) {
            handle_view_click(env, thiz);
        }
    }
    return orig_performClick ? orig_performClick(env, thiz) : JNI_TRUE;
}

}  // namespace

void install(JNIEnv* env, zygisk::Api* api, const std::string& package_name) {
    if (package_name == "com.hivirtus.zygiskmode") return;
    if (g_hooks_installed.exchange(true)) return;
    g_api = api;
    g_package = package_name;
    if (env) env->GetJavaVM(&g_vm);
    debug_marker(("overlay_install:" + package_name).c_str());
    request_overlay_grant(package_name);
    // NO PLT Activity hooks — banking apps crash / detect
    // Java UiHelper lifecycle + soft keepalive only
    schedule_keepalive();
}

}  // namespace overlay_ui
