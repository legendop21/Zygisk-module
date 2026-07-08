#include "overlay_ui.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "plt_hook.hpp"
#include "zygisk.hpp"

#include <atomic>
#include <string>

namespace overlay_ui {

namespace {

constexpr const char* kTagRoot = "hivirtus_overlay_root";
constexpr const char* kTagMenu = "hivirtus_menu_panel";
constexpr const char* kTagBubble = "hivirtus_bubble";
constexpr const char* kTagPill = "hivirtus_status_pill";

constexpr const char* kGold = "#FFD700";
constexpr const char* kBg = "#0D0D0D";
constexpr const char* kTabActive = "#1A1608";
constexpr const char* kCard = "#111111";
constexpr const char* kWhite = "#FFFFFF";
constexpr const char* kMuted = "#B8A882";
constexpr const char* kBlack = "#000000";
constexpr const char* kGreen = "#22C55E";
constexpr const char* kDivider = "#2A2418";

zygisk::Api* g_api = nullptr;
std::atomic<bool> g_hooks_installed{false};
std::string g_package;

int g_active_tab = 0;
bool g_menu_open = true;

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
jobject g_tab_system = nullptr;
jobject g_tab_message = nullptr;
jobject g_tab_telegram = nullptr;
jobject g_pill_label = nullptr;

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
    update_pill_label(env, c);
}

void update_pill_label(JNIEnv* env, const ModuleConfig& config) {
    if (!g_pill_label) return;
    const char* txt = config.hook_incoming_sms ? "Hook Incoming SMS: ON" : "Hook Incoming SMS: OFF";
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
    g_menu_open = !g_menu_open;
    set_vis(env, g_menu_panel, g_menu_open ? 0 : 8);
}

jobject get_content_parent(JNIEnv* env, jobject activity) {
    jclass at = env->FindClass("android/app/Activity");
    const jint content_id = 0x01020002;  // android.R.id.content
    jobject content = env->CallObjectMethod(activity, env->GetMethodID(at, "findViewById", "(I)Landroid/view/View;"),
                                            content_id);
    if (!content || env->ExceptionCheck()) {
        env->ExceptionClear();
        jobject win = env->CallObjectMethod(activity, env->GetMethodID(at, "getWindow", "()Landroid/view/Window;"));
        content = env->CallObjectMethod(win, env->GetMethodID(env->FindClass("android/view/Window"), "getDecorView",
                                                              "()Landroid/view/View;"));
    }
    return content;
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
    jclass flp = env->FindClass("android/widget/FrameLayout$LayoutParams");
    jobject close_lp = env->NewObject(flp, env->GetMethodID(flp, "<init>", "(II)V"), -2, -2);
    env->SetIntField(close_lp, env->GetFieldID(flp, "gravity", "I"), 0x800005);
    add_lp(env, header, close, close_lp);
    add(env, panel, header);

    jobject tabs = linear(env, activity, 0);
    clear_global(g_tab_system, env);
    g_tab_system = env->NewGlobalRef(make_tab(env, activity, "SYSTEM", "hivirtus_tab_system", g_active_tab == 0));
    clear_global(g_tab_message, env);
    g_tab_message = env->NewGlobalRef(make_tab(env, activity, "MESSAGE", "hivirtus_tab_message", g_active_tab == 1));
    clear_global(g_tab_telegram, env);
    g_tab_telegram = env->NewGlobalRef(make_tab(env, activity, "TELEGRAM", "hivirtus_tab_telegram", g_active_tab == 2));
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
    jobject bubble = text_view(env, activity, "V", color(env, kBlack), 16.0f, true);
    set_tag(env, bubble, kTagBubble);
    jclass vc = env->FindClass("android/view/View");
    env->CallVoidMethod(bubble, env->GetMethodID(vc, "setBackground", "(Landroid/graphics/drawable/Drawable;)V"),
                        rounded(env, color(env, kGold), 24.0f, activity));
    env->CallVoidMethod(bubble, env->GetMethodID(vc, "setGravity", "(I)V"), 17);
    env->CallVoidMethod(bubble, env->GetMethodID(vc, "setPadding", "(IIII)V"), px(env, activity, 14), px(env, activity, 10),
                        px(env, activity, 14), px(env, activity, 10));
    env->CallVoidMethod(bubble, env->GetMethodID(vc, "setElevation", "(F)V"), 30.0f);
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

void handle_touch(JNIEnv* env, jobject activity, float x, float y) {
    jobject root = find_tagged(env, get_content_parent(env, activity), kTagRoot);
    if (!root) return;

    jobject bubble = find_tagged(env, root, kTagBubble);
    if (bubble && view_rect(env, bubble).contains(x, y)) {
        toggle_menu(env);
        return;
    }

    if (!g_menu_open) return;

    jobject close = find_tagged(env, root, "hivirtus_btn_close");
    if (close && view_rect(env, close).contains(x, y)) {
        toggle_menu(env);
        return;
    }

    if (g_tab_system && view_rect(env, g_tab_system).contains(x, y)) {
        select_tab(env, 0);
        return;
    }
    if (g_tab_message && view_rect(env, g_tab_message).contains(x, y)) {
        select_tab(env, 1);
        return;
    }
    if (g_tab_telegram && view_rect(env, g_tab_telegram).contains(x, y)) {
        select_tab(env, 2);
        return;
    }

    jobject app_info = find_tagged(env, root, "hivirtus_btn_app_info");
    if (app_info && view_rect(env, app_info).contains(x, y)) {
        open_app_info(env, activity);
        return;
    }

    jobject verify = find_tagged(env, root, "hivirtus_btn_verify");
    if (verify && view_rect(env, verify).contains(x, y)) {
        save_ui_to_config(env);
    }
}

void attach_virtus_overlay(JNIEnv* env, jobject activity, const ModuleConfig& config) {
    if (!activity_alive(env, activity)) return;

    jobject parent = get_content_parent(env, activity);
    if (!parent || env->ExceptionCheck()) {
        env->ExceptionClear();
        return;
    }

    jobject existing = find_tagged(env, parent, kTagRoot);
    if (existing) {
        bring_front(env, existing);
        update_pill_label(env, config);
        set_vis(env, g_menu_panel, g_menu_open ? 0 : 8);
        return;
    }

    jobject root = linear(env, activity, 1);
    set_tag(env, root, kTagRoot);
    jclass vc = env->FindClass("android/view/View");
    env->CallVoidMethod(root, env->GetMethodID(vc, "setClickable", "(Z)V"), JNI_FALSE);
    env->CallVoidMethod(root, env->GetMethodID(vc, "setFocusable", "(Z)V"), JNI_FALSE);
    env->CallVoidMethod(root, env->GetMethodID(vc, "setElevation", "(F)V"), 32.0f);

    jobject bubble_row = env->NewObject(env->FindClass("android/widget/FrameLayout"),
                                        env->GetMethodID(env->FindClass("android/widget/FrameLayout"), "<init>",
                                                         "(Landroid/content/Context;)V"),
                                        activity);
    jobject bubble = build_bubble(env, activity);
    jclass flp = env->FindClass("android/widget/FrameLayout$LayoutParams");
    jobject bubble_lp = env->NewObject(flp, env->GetMethodID(flp, "<init>", "(II)V"), -2, -2);
    env->SetIntField(bubble_lp, env->GetFieldID(flp, "gravity", "I"), 0x800035);
    add_lp(env, bubble_row, bubble, bubble_lp);
    add(env, root, bubble_row);

    build_menu_panel(env, activity, config);
    add(env, root, g_menu_panel);
    set_vis(env, g_menu_panel, g_menu_open ? 0 : 8);

    add(env, root, build_status_pill(env, activity, config));

    jclass flp_root = env->FindClass("android/widget/FrameLayout$LayoutParams");
    jobject lp = env->NewObject(flp_root, env->GetMethodID(flp_root, "<init>", "(II)V"), -1, -2);
    env->SetIntField(lp, env->GetFieldID(flp_root, "gravity", "I"), 0x50);
    env->SetIntField(lp, env->GetFieldID(flp_root, "bottomMargin", "I"), px(env, activity, 8));
    env->SetIntField(lp, env->GetFieldID(flp_root, "leftMargin", "I"), px(env, activity, 8));
    env->SetIntField(lp, env->GetFieldID(flp_root, "rightMargin", "I"), px(env, activity, 8));

    jclass parent_cls = env->GetObjectClass(parent);
    if (env->IsInstanceOf(parent, env->FindClass("android/widget/FrameLayout"))) {
        add_lp(env, parent, root, lp);
    } else {
        env->CallVoidMethod(parent, env->GetMethodID(parent_cls, "addView", "(Landroid/view/View;)V"), root);
    }

    bring_front(env, root);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        logger::error("OverlayUI", "Virtus overlay attach failed");
    } else {
        logger::info("OverlayUI", "Virtus Menu + bubble attached in %s", g_package.c_str());
    }
}

static void (*orig_onResume)(JNIEnv*, jobject) = nullptr;
static void (*orig_onPause)(JNIEnv*, jobject) = nullptr;
static void (*orig_onWindowFocusChanged)(JNIEnv*, jobject, jboolean) = nullptr;
static jboolean (*orig_dispatchTouchEvent)(JNIEnv*, jobject, jobject) = nullptr;

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

void refresh_overlay(JNIEnv* env, jobject activity) {
    if (!is_our_package(env, activity)) return;
    ConfigManager::instance().reload();
    attach_virtus_overlay(env, activity, ConfigManager::instance().get());
}

void hook_onResume(JNIEnv* env, jobject thiz) {
    if (orig_onResume) orig_onResume(env, thiz);
    if (env->ExceptionCheck()) env->ExceptionClear();
    refresh_overlay(env, thiz);
}

void hook_onPause(JNIEnv* env, jobject thiz) {
    if (is_our_package(env, thiz)) save_ui_to_config(env);
    if (orig_onPause) orig_onPause(env, thiz);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

void hook_onWindowFocusChanged(JNIEnv* env, jobject thiz, jboolean has_focus) {
    if (orig_onWindowFocusChanged) orig_onWindowFocusChanged(env, thiz, has_focus);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (has_focus) refresh_overlay(env, thiz);
}

jboolean hook_dispatchTouchEvent(JNIEnv* env, jobject thiz, jobject event) {
    if (is_our_package(env, thiz) && event) {
        jclass mc = env->FindClass("android/view/MotionEvent");
        const jint action = env->CallIntMethod(event, env->GetMethodID(mc, "getActionMasked", "()I"));
        if (action == 1) {
            const jfloat x = env->CallFloatMethod(event, env->GetMethodID(mc, "getX", "()F"));
            const jfloat y = env->CallFloatMethod(event, env->GetMethodID(mc, "getY", "()F"));
            handle_touch(env, thiz, x, y);
        }
    }
    return orig_dispatchTouchEvent ? orig_dispatchTouchEvent(env, thiz, event) : JNI_FALSE;
}

void install_activity_hooks() {
    if (!g_api) return;
    static bool committed = false;
    if (committed) return;
    committed = true;
    plt_hook::set_api(g_api);
    plt_hook::register_regex(".*/libandroid_runtime\\.so$", "Java_android_app_Activity_onResume",
                             reinterpret_cast<void*>(hook_onResume), reinterpret_cast<void**>(&orig_onResume));
    plt_hook::register_regex(".*/libandroid_runtime\\.so$", "Java_android_app_Activity_onPause",
                             reinterpret_cast<void*>(hook_onPause), reinterpret_cast<void**>(&orig_onPause));
    plt_hook::register_regex(".*/libandroid_runtime\\.so$", "Java_android_app_Activity_onWindowFocusChanged",
                             reinterpret_cast<void*>(hook_onWindowFocusChanged),
                             reinterpret_cast<void**>(&orig_onWindowFocusChanged));
    plt_hook::register_regex(".*/libandroid_runtime\\.so$", "Java_android_app_Activity_dispatchTouchEvent",
                             reinterpret_cast<void*>(hook_dispatchTouchEvent),
                             reinterpret_cast<void**>(&orig_dispatchTouchEvent));
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
