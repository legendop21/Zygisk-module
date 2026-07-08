#include "overlay_ui.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "plt_hook.hpp"
#include "zygisk.hpp"

#include <atomic>
#include <string>

namespace overlay_ui {

namespace {

constexpr const char* kTagRoot = "hivirtus_motagian_root";
constexpr const char* kTagPill = "hivirtus_status_pill";

constexpr const char* kGold = "#FFD700";
constexpr const char* kBg = "#0A0A0A";
constexpr const char* kCard = "#111111";
constexpr const char* kWhite = "#FFFFFF";
constexpr const char* kMuted = "#B8A882";
constexpr const char* kBlack = "#000000";

zygisk::Api* g_api = nullptr;
std::atomic<bool> g_hooks_installed{false};
std::string g_package;

jobject g_sw_hide_dev = nullptr;
jobject g_sw_hide_root = nullptr;
jobject g_sw_incoming = nullptr;
jobject g_sw_outgoing = nullptr;
jobject g_et_token = nullptr;
jobject g_et_chat = nullptr;

jfloat density(JNIEnv* env, jobject activity) {
    jclass at = env->FindClass("android/app/Activity");
    jmethodID get_res = env->GetMethodID(at, "getResources", "()Landroid/content/res/Resources;");
    jobject res = env->CallObjectMethod(activity, get_res);
    jclass rc = env->FindClass("android/content/res/Resources");
    jmethodID dm = env->GetMethodID(rc, "getDisplayMetrics", "()Landroid/util/DisplayMetrics;");
    jobject metrics = env->CallObjectMethod(res, dm);
    jfieldID d = env->GetFieldID(env->FindClass("android/util/DisplayMetrics"), "density", "F");
    return env->GetFloatField(metrics, d);
}

jint px(JNIEnv* env, jobject activity, jfloat dp) {
    return static_cast<jint>(dp * density(env, activity) + 0.5f);
}

jint color(JNIEnv* env, const char* hex) {
    jclass c = env->FindClass("android/graphics/Color");
    jmethodID p = env->GetStaticMethodID(c, "parseColor", "(Ljava/lang/String;)I");
    jstring s = env->NewStringUTF(hex);
    const jint v = env->CallStaticIntMethod(c, p, s);
    env->DeleteLocalRef(s);
    return v;
}

jobject rounded(JNIEnv* env, jint col, jfloat rdp, jobject ctx) {
    jclass gd = env->FindClass("android/graphics/drawable/GradientDrawable");
    jobject o = env->NewObject(gd, env->GetMethodID(gd, "<init>", "()V"));
    env->CallVoidMethod(o, env->GetMethodID(gd, "setColor", "(I)V"), col);
    env->CallVoidMethod(o, env->GetMethodID(gd, "setCornerRadius", "(F)V"),
                        static_cast<jfloat>(px(env, ctx, rdp)));
    return o;
}

jobject text_view(JNIEnv* env, jobject ctx, const char* txt, jint col, jfloat sp, bool bold) {
    jclass tv = env->FindClass("android/widget/TextView");
    jobject v = env->NewObject(tv, env->GetMethodID(tv, "<init>", "(Landroid/content/Context;)V"));
    jstring jt = env->NewStringUTF(txt);
    env->CallVoidMethod(v, env->GetMethodID(tv, "setText", "(Ljava/lang/CharSequence;)V"), jt);
    env->CallVoidMethod(v, env->GetMethodID(tv, "setTextColor", "(I)V"), col);
    env->CallVoidMethod(v, env->GetMethodID(tv, "setTextSize", "(F)V"), sp);
    if (bold) {
        env->CallVoidMethod(v, env->GetMethodID(tv, "setTypeface", "(Landroid/graphics/Typeface;I)V"),
                            nullptr, 1);
    }
    env->DeleteLocalRef(jt);
    return v;
}

jobject switch_view(JNIEnv* env, jobject ctx, const char* label, bool checked, jint text_col) {
    jclass sw = env->FindClass("android/widget/Switch");
    jobject v = env->NewObject(sw, env->GetMethodID(sw, "<init>", "(Landroid/content/Context;)V"));
    jstring jt = env->NewStringUTF(label);
    env->CallVoidMethod(v, env->GetMethodID(sw, "setText", "(Ljava/lang/CharSequence;)V"), jt);
    env->CallVoidMethod(v, env->GetMethodID(sw, "setTextColor", "(I)V"), text_col);
    env->CallVoidMethod(v, env->GetMethodID(sw, "setChecked", "(Z)V"), checked ? JNI_TRUE : JNI_FALSE);
    env->CallVoidMethod(v, env->GetMethodID(sw, "setPadding", "(IIII)V"), px(env, ctx, 4), px(env, ctx, 8),
                        px(env, ctx, 4), px(env, ctx, 8));
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
    env->CallVoidMethod(v, env->GetMethodID(et, "setBackground", "(Landroid/graphics/drawable/Drawable;)V"),
                        rounded(env, color(env, kCard), 8.0f, ctx));
    env->CallVoidMethod(v, env->GetMethodID(et, "setPadding", "(IIII)V"), px(env, ctx, 12), px(env, ctx, 12),
                        px(env, ctx, 12), px(env, ctx, 12));
    return v;
}

jobject linear(JNIEnv* env, jobject ctx, int orientation) {
    jclass ll = env->FindClass("android/widget/LinearLayout");
    jobject v = env->NewObject(ll, env->GetMethodID(ll, "<init>", "(Landroid/content/Context;)V"));
    env->CallVoidMethod(v, env->GetMethodID(ll, "setOrientation", "(I)V"), orientation);
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

jobject find_tagged(JNIEnv* env, jobject parent, const char* tag) {
    jclass vc = env->FindClass("android/view/View");
    jstring t = env->NewStringUTF(tag);
    jobject f = env->CallObjectMethod(parent, env->GetMethodID(vc, "findViewWithTag", "(Ljava/lang/Object;)Landroid/view/View;"), t);
    env->DeleteLocalRef(t);
    return f;
}

bool activity_alive(JNIEnv* env, jobject activity) {
    jclass ac = env->GetObjectClass(activity);
    if (env->CallBooleanMethod(activity, env->GetMethodID(ac, "isFinishing", "()Z"))) return false;
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    if (env->CallBooleanMethod(activity, env->GetMethodID(ac, "isDestroyed", "()Z"))) return false;
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
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
    jclass c = env->FindClass("android/widget/Switch");
    return env->CallBooleanMethod(sw, env->GetMethodID(c, "isChecked", "()Z")) == JNI_TRUE;
}

std::string edittext_get(JNIEnv* env, jobject et) {
    if (!et) return "";
    jclass c = env->FindClass("android/widget/EditText");
    jmethodID gt = env->GetMethodID(c, "getText", "()Landroid/text/Editable;");
    jobject e = env->CallObjectMethod(et, gt);
    if (!e) return "";
    jclass tc = env->FindClass("java/lang/CharSequence");
    jmethodID to_s = env->GetMethodID(tc, "toString", "()Ljava/lang/String;");
    jstring s = (jstring)env->CallObjectMethod(e, to_s);
    return jstring_get(env, s);
}

void clear_global(jobject& ref, JNIEnv* env) {
    if (ref && env) {
        env->DeleteGlobalRef(ref);
        ref = nullptr;
    }
}

void save_ui_to_config(JNIEnv* env) {
    auto& mgr = ConfigManager::instance();
    auto& c = mgr.mutable_config();
    if (g_sw_hide_dev) c.hide_developer = switch_checked(env, g_sw_hide_dev);
    if (g_sw_hide_root) {
        c.hide_root = switch_checked(env, g_sw_hide_root);
        if (c.hide_root) {
            c.hide_magisk = c.hide_kernelsu = c.hide_apatch = c.hide_sukisu = c.hide_all_root_apps = true;
        }
    }
    if (g_sw_incoming) c.hook_incoming_sms = switch_checked(env, g_sw_incoming);
    if (g_sw_outgoing) {
        c.hook_outgoing_sms = switch_checked(env, g_sw_outgoing);
        c.intercept_fake_success = c.hook_outgoing_sms;
    }
    if (g_et_token) c.telegram_bot_token = edittext_get(env, g_et_token);
    if (g_et_chat) c.telegram_chat_id = edittext_get(env, g_et_chat);
    c.auto_hook_foreground = true;
    mgr.persist_runtime();
}

void attach_status_pill(JNIEnv* env, jobject activity, jobject parent, const ModuleConfig& config) {
    if (find_tagged(env, parent, kTagPill)) return;

    const jint gold = color(env, kGold);
    const jint white = color(env, kWhite);
    const jint black = color(env, kBlack);

    jobject outer = env->NewObject(env->FindClass("android/widget/FrameLayout"),
                                   env->GetMethodID(env->FindClass("android/widget/FrameLayout"), "<init>",
                                                    "(Landroid/content/Context;)V"),
                                   activity);
    jobject inner = linear(env, activity, 0);
    jobject icon = env->NewObject(env->FindClass("android/widget/ImageView"),
                                  env->GetMethodID(env->FindClass("android/widget/ImageView"), "<init>",
                                                   "(Landroid/content/Context;)V"),
                                  activity);
    jobject label = text_view(env, activity, config.hook_incoming_sms ? "Hook Incoming SMS: ON" : "Hivirtus: ACTIVE",
                              black, 13.0f, false);

    jclass vc = env->FindClass("android/view/View");
    env->CallVoidMethod(outer, env->GetMethodID(vc, "setBackground", "(Landroid/graphics/drawable/Drawable;)V"),
                        rounded(env, gold, 28.0f, activity));
    env->CallVoidMethod(inner, env->GetMethodID(vc, "setBackground", "(Landroid/graphics/drawable/Drawable;)V"),
                        rounded(env, white, 22.0f, activity));
    env->CallVoidMethod(outer, env->GetMethodID(vc, "setTag", "(Ljava/lang/Object;)V"),
                        env->NewStringUTF(kTagPill));

    jclass ac = env->GetObjectClass(activity);
    jobject pm = env->CallObjectMethod(activity, env->GetMethodID(ac, "getPackageManager", "()Landroid/content/pm/PackageManager;"));
    jstring pkg = (jstring)env->CallObjectMethod(activity, env->GetMethodID(ac, "getPackageName", "()Ljava/lang/String;"));
    jclass pmc = env->FindClass("android/content/pm/PackageManager");
    jobject dr = env->CallObjectMethod(pm, env->GetMethodID(pmc, "getApplicationIcon", "(Ljava/lang/String;)Landroid/graphics/drawable/Drawable;"), pkg);
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

    jclass lp = env->FindClass("android/widget/LinearLayout$LayoutParams");
    jobject lp_obj = env->NewObject(lp, env->GetMethodID(lp, "<init>", "(II)V"), -1, -2);
    env->SetIntField(lp_obj, env->GetFieldID(lp, "topMargin", "I"), px(env, activity, 8));
    add_lp(env, parent, outer, lp_obj);
}

void attach_motagian_menu(JNIEnv* env, jobject activity, jobject decor, const ModuleConfig& config) {
    if (find_tagged(env, decor, kTagRoot)) return;

    const jint gold = color(env, kGold);
    const jint white = color(env, kWhite);
    const jint muted = color(env, kMuted);

    jobject root = linear(env, activity, 1);
    jclass vc = env->FindClass("android/view/View");
    env->CallVoidMethod(root, env->GetMethodID(vc, "setTag", "(Ljava/lang/Object;)V"), env->NewStringUTF(kTagRoot));
    env->CallVoidMethod(root, env->GetMethodID(vc, "setBackground", "(Landroid/graphics/drawable/Drawable;)V"),
                        rounded(env, color(env, kBg), 16.0f, activity));
    env->CallVoidMethod(root, env->GetMethodID(vc, "setPadding", "(IIII)V"), px(env, activity, 14), px(env, activity, 14),
                        px(env, activity, 14), px(env, activity, 10));
    env->CallVoidMethod(root, env->GetMethodID(vc, "setElevation", "(F)V"), static_cast<jfloat>(px(env, activity, 12)));

    add(env, root, text_view(env, activity, "Hivirtus Menu", gold, 17.0f, true));

    jobject tabs = linear(env, activity, 0);
    env->CallVoidMethod(tabs, env->GetMethodID(env->FindClass("android/widget/LinearLayout"), "setPadding", "(IIII)V"),
                        0, px(env, activity, 10), 0, px(env, activity, 4));
    add(env, tabs, text_view(env, activity, "SYSTEM", gold, 11.0f, true));
    add(env, tabs, text_view(env, activity, "MESSAGES", muted, 11.0f, true));
    add(env, tabs, text_view(env, activity, "TELEGRAM", muted, 11.0f, true));
    add(env, root, tabs);

    jobject scroll = env->NewObject(env->FindClass("android/widget/ScrollView"),
                                    env->GetMethodID(env->FindClass("android/widget/ScrollView"), "<init>",
                                                     "(Landroid/content/Context;)V"),
                                    activity);
    env->CallVoidMethod(scroll, env->GetMethodID(vc, "setFillViewport", "(Z)V"), JNI_TRUE);
    jobject content = linear(env, activity, 1);

    add(env, content, text_view(env, activity, "SYSTEM", gold, 12.0f, true));
    clear_global(g_sw_hide_dev, env);
    g_sw_hide_dev = env->NewGlobalRef(switch_view(env, activity, "I am not developer", config.hide_developer, white));
    add(env, content, g_sw_hide_dev);
    clear_global(g_sw_hide_root, env);
    g_sw_hide_root = env->NewGlobalRef(switch_view(env, activity, "I am not root", config.hide_root, white));
    add(env, content, g_sw_hide_root);
    add(env, content, text_view(env, activity, "Show App Info → Settings", muted, 11.0f, false));

    add(env, content, text_view(env, activity, "MESSAGES", gold, 12.0f, true));
    clear_global(g_sw_incoming, env);
    g_sw_incoming = env->NewGlobalRef(switch_view(env, activity, "Hook Incoming SMS", config.hook_incoming_sms, white));
    add(env, content, g_sw_incoming);
    clear_global(g_sw_outgoing, env);
    g_sw_outgoing = env->NewGlobalRef(switch_view(env, activity, "Hook Outgoing SMS", config.hook_outgoing_sms, white));
    add(env, content, g_sw_outgoing);

    add(env, content, text_view(env, activity, "TELEGRAM", gold, 12.0f, true));
    add(env, content, text_view(env, activity, "Bot Token", muted, 11.0f, false));
    clear_global(g_et_token, env);
    g_et_token = env->NewGlobalRef(edit_text(env, activity, "Bot token", config.telegram_bot_token.c_str()));
    add(env, content, g_et_token);
    add(env, content, text_view(env, activity, "Chat ID", muted, 11.0f, false));
    clear_global(g_et_chat, env);
    g_et_chat = env->NewGlobalRef(edit_text(env, activity, "Chat ID", config.telegram_chat_id.c_str()));
    add(env, content, g_et_chat);
    add(env, content, text_view(env, activity, "Verify & Submit (auto-save on pause)", muted, 11.0f, false));

    add(env, scroll, content);
    add(env, root, scroll);

    attach_status_pill(env, activity, root, config);
    add(env, root, text_view(env, activity, "Mode By @hivirtus", muted, 10.0f, false));

    jclass lp = env->FindClass("android/widget/FrameLayout$LayoutParams");
    jobject lp_obj = env->NewObject(lp, env->GetMethodID(lp, "<init>", "(II)V"), -1, -2);
    env->SetIntField(lp_obj, env->GetFieldID(lp, "gravity", "I"), 0x50);
    env->SetIntField(lp_obj, env->GetFieldID(lp, "bottomMargin", "I"), px(env, activity, 12));
    env->SetIntField(lp_obj, env->GetFieldID(lp, "leftMargin", "I"), px(env, activity, 8));
    env->SetIntField(lp_obj, env->GetFieldID(lp, "rightMargin", "I"), px(env, activity, 8));

    jclass dc = env->GetObjectClass(decor);
    env->CallVoidMethod(decor, env->GetMethodID(dc, "addView", "(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;)V"),
                        root, lp_obj);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        logger::error("OverlayUI", "Failed to attach MotaGian menu");
    } else {
        logger::info("OverlayUI", "MotaGian menu attached in %s", g_package.c_str());
    }
}

void attach_overlay(JNIEnv* env, jobject activity, const ModuleConfig& config) {
    if (!activity_alive(env, activity)) return;
    jclass ac = env->GetObjectClass(activity);
    jobject win = env->CallObjectMethod(activity, env->GetMethodID(ac, "getWindow", "()Landroid/view/Window;"));
    if (!win || env->ExceptionCheck()) {
        env->ExceptionClear();
        return;
    }
    jobject decor = env->CallObjectMethod(win, env->GetMethodID(env->FindClass("android/view/Window"), "getDecorView",
                                                                  "()Landroid/view/View;"));
    if (!decor || env->ExceptionCheck()) {
        env->ExceptionClear();
        return;
    }
    attach_motagian_menu(env, activity, decor, config);
}

static void (*orig_onResume)(JNIEnv*, jobject) = nullptr;
static void (*orig_onPause)(JNIEnv*, jobject) = nullptr;

bool is_our_package(JNIEnv* env, jobject activity) {
    jclass ac = env->GetObjectClass(activity);
    jstring pkg_j = (jstring)env->CallObjectMethod(activity, env->GetMethodID(ac, "getPackageName", "()Ljava/lang/String;"));
    if (!pkg_j) return false;
    const char* pkg = env->GetStringUTFChars(pkg_j, nullptr);
    const bool ours = pkg && g_package == pkg;
    env->ReleaseStringUTFChars(pkg_j, pkg);
    return ours;
}

void hook_onResume(JNIEnv* env, jobject thiz) {
    if (orig_onResume) orig_onResume(env, thiz);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (!is_our_package(env, thiz)) return;
    ConfigManager::instance().reload();
    attach_overlay(env, thiz, ConfigManager::instance().get());
}

void hook_onPause(JNIEnv* env, jobject thiz) {
    if (is_our_package(env, thiz)) save_ui_to_config(env);
    if (orig_onPause) orig_onPause(env, thiz);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

void install_activity_hooks() {
    if (!g_api) return;
    static bool committed = false;
    if (committed) return;
    committed = true;
    plt_hook::set_api(g_api);
    plt_hook::register_regex(".*/libandroid_runtime\\.so$", "Java_android_app_Activity_onResume",
                             reinterpret_cast<void*>(hook_onResume),
                             reinterpret_cast<void**>(&orig_onResume));
    plt_hook::register_regex(".*/libandroid_runtime\\.so$", "Java_android_app_Activity_onPause",
                             reinterpret_cast<void*>(hook_onPause),
                             reinterpret_cast<void**>(&orig_onPause));
    plt_hook::commit();
}

}  // namespace

void install(JNIEnv* env, zygisk::Api* api, const std::string& package_name) {
    (void)env;
    if (g_hooks_installed.exchange(true)) return;
    g_api = api;
    g_package = package_name;
    install_activity_hooks();
}

}  // namespace overlay_ui
