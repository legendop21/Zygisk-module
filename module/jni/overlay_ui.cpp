#include "overlay_ui.hpp"
#include "config.hpp"
#include "logger.hpp"

#include <android/log.h>
#include <atomic>
#include <pthread.h>
#include <string>
#include <unistd.h>

namespace overlay_ui {

namespace {

// MotaGian-matching palette (overlay-app colors.xml)
constexpr const char* kGoldPrimary = "#FFD700";
constexpr const char* kGoldDark = "#C9A84C";
constexpr const char* kOverlayBg = "#0A0A0A";
constexpr const char* kWhite = "#FFFFFF";
constexpr const char* kBlack = "#000000";
constexpr const char* kTextSecondary = "#B8A882";

JavaVM* g_vm = nullptr;
std::atomic<bool> g_running{false};
std::string g_package;
jobject g_last_activity = nullptr;

jint parse_color(JNIEnv* env, const char* hex) {
    jclass color_class = env->FindClass("android/graphics/Color");
    jmethodID parse = env->GetStaticMethodID(color_class, "parseColor", "(Ljava/lang/String;)I");
    jstring s = env->NewStringUTF(hex);
    const jint c = env->CallStaticIntMethod(color_class, parse, s);
    env->DeleteLocalRef(s);
    return c;
}

jobject rounded_bg(JNIEnv* env, jint color, jfloat radius_dp, jobject activity) {
    jclass gd_class = env->FindClass("android/graphics/drawable/GradientDrawable");
    jmethodID gd_ctor = env->GetMethodID(gd_class, "<init>", "()V");
    jobject gd = env->NewObject(gd_class, gd_ctor);

    jmethodID set_color = env->GetMethodID(gd_class, "setColor", "(I)V");
    env->CallVoidMethod(gd, set_color, color);

    jclass at_class = env->FindClass("android/app/Activity");
    jmethodID get_res = env->GetMethodID(at_class, "getResources", "()Landroid/content/res/Resources;");
    jobject resources = env->CallObjectMethod(activity, get_res);
    jclass res_class = env->FindClass("android/content/res/Resources");
    jmethodID display_metrics = env->GetMethodID(res_class, "getDisplayMetrics", "()Landroid/util/DisplayMetrics;");
    jobject metrics = env->CallObjectMethod(resources, display_metrics);
    jfieldID density_field = env->GetFieldID(env->FindClass("android/util/DisplayMetrics"), "density", "F");
    const jfloat density = env->GetFloatField(metrics, density_field);
    const jfloat radius_px = radius_dp * density;

    jmethodID set_radius = env->GetMethodID(gd_class, "setCornerRadius", "(F)V");
    env->CallVoidMethod(gd, set_radius, radius_px);

    return gd;
}

jobject get_global_ref(JNIEnv* env, jobject obj) {
    if (!obj) return nullptr;
    return env->NewGlobalRef(obj);
}

void clear_global_ref(JNIEnv* env, jobject& ref) {
    if (ref) {
        env->DeleteGlobalRef(ref);
        ref = nullptr;
    }
}

jobject find_resumed_activity(JNIEnv* env) {
    jclass at_class = env->FindClass("android/app/ActivityThread");
    if (!at_class) return nullptr;

    jmethodID current = env->GetStaticMethodID(
        at_class, "currentActivityThread", "()Landroid/app/ActivityThread;");
    if (!current) return nullptr;

    jobject thread = env->CallStaticObjectMethod(at_class, current);
    if (!thread || env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }

    jfieldID activities_field = env->GetFieldID(
        at_class, "mActivities", "Landroid/util/ArrayMap;");
    if (!activities_field) return nullptr;

    jobject activities = env->GetObjectField(thread, activities_field);
    if (!activities) return nullptr;

    jclass map_class = env->FindClass("android/util/ArrayMap");
    jmethodID size_mid = env->GetMethodID(map_class, "size", "()I");
    jmethodID value_at = env->GetMethodID(map_class, "valueAt", "(I)Ljava/lang/Object;");
    const jint count = env->CallIntMethod(activities, size_mid);

    for (jint i = 0; i < count; ++i) {
        jobject record = env->CallObjectMethod(activities, value_at, i);
        if (!record) continue;

        jclass record_class = env->GetObjectClass(record);
        jfieldID paused_field = env->GetFieldID(record_class, "paused", "Z");
        jfieldID activity_field = env->GetFieldID(
            record_class, "activity", "Landroid/app/Activity;");
        if (!paused_field || !activity_field) continue;

        if (env->GetBooleanField(record, paused_field)) continue;

        jobject activity = env->GetObjectField(record, activity_field);
        if (activity && !env->ExceptionCheck()) return activity;
        env->ExceptionClear();
    }
    return nullptr;
}

jobject find_tagged_overlay(JNIEnv* env, jobject decor, const char* tag) {
    jclass view_class = env->FindClass("android/view/View");
    jmethodID find_id = env->GetMethodID(view_class, "findViewWithTag",
                                         "(Ljava/lang/Object;)Landroid/view/View;");
    jstring tag_str = env->NewStringUTF(tag);
    jobject found = env->CallObjectMethod(decor, find_id, tag_str);
    env->DeleteLocalRef(tag_str);
    return found;
}

void set_view_background(JNIEnv* env, jobject view, jobject drawable) {
    jclass view_class = env->FindClass("android/view/View");
    jmethodID set_bg = env->GetMethodID(view_class, "setBackground", "(Landroid/graphics/drawable/Drawable;)V");
    env->CallVoidMethod(view, set_bg, drawable);
}

void set_view_tag(JNIEnv* env, jobject view, const char* tag) {
    jclass view_class = env->FindClass("android/view/View");
    jmethodID set_tag = env->GetMethodID(view_class, "setTag", "(Ljava/lang/Object;)V");
    env->CallVoidMethod(view, set_tag, env->NewStringUTF(tag));
}

jobject make_frame_lp(JNIEnv* env, jint width, jint height, jint gravity) {
    jclass lp_class = env->FindClass("android/widget/FrameLayout$LayoutParams");
    jmethodID lp_ctor = env->GetMethodID(lp_class, "<init>", "(II)V");
    jobject lp = env->NewObject(lp_class, lp_ctor, width, height);
    jfieldID gravity_field = env->GetFieldID(lp_class, "gravity", "I");
    env->SetIntField(lp, gravity_field, gravity);
    return lp;
}

void attach_header_bar(JNIEnv* env, jobject activity, jobject decor) {
    if (find_tagged_overlay(env, decor, "hivirtus_menu_header")) return;

    const jint c_dark = parse_color(env, kOverlayBg);
    const jint c_gold = parse_color(env, kGoldPrimary);

    jclass frame_class = env->FindClass("android/widget/FrameLayout");
    jclass text_class = env->FindClass("android/widget/TextView");
    jmethodID frame_ctor = env->GetMethodID(frame_class, "<init>", "(Landroid/content/Context;)V");
    jmethodID text_ctor = env->GetMethodID(text_class, "<init>", "(Landroid/content/Context;)V");

    jobject header = env->NewObject(frame_class, frame_ctor, activity);
    jobject title = env->NewObject(text_class, text_ctor, activity);

    jobject header_bg = rounded_bg(env, c_dark, 12.0f, activity);
    set_view_background(env, header, header_bg);
    set_view_tag(env, header, "hivirtus_menu_header");

    jmethodID set_text = env->GetMethodID(text_class, "setText", "(Ljava/lang/CharSequence;)V");
    jmethodID set_text_color = env->GetMethodID(text_class, "setTextColor", "(I)V");
    jmethodID set_text_size = env->GetMethodID(text_class, "setTextSize", "(F)V");
    jmethodID set_gravity = env->GetMethodID(text_class, "setGravity", "(I)V");
    jmethodID set_padding = env->GetMethodID(env->FindClass("android/view/View"), "setPadding", "(IIII)V");
    jmethodID add_view = env->GetMethodID(frame_class, "addView", "(Landroid/view/View;)V");

    jstring title_j = env->NewStringUTF("Hivirtus Menu");
    env->CallVoidMethod(title, set_text, title_j);
    env->CallVoidMethod(title, set_text_color, c_gold);
    env->CallVoidMethod(title, set_text_size, 15.0f);
    env->CallVoidMethod(title, set_gravity, 17);
    env->CallVoidMethod(header, set_padding, 20, 14, 20, 14);
    env->CallVoidMethod(header, add_view, title);

    jobject lp = make_frame_lp(env, -1, -2, 0x30);  // TOP
    jclass decor_class = env->GetObjectClass(decor);
    jmethodID add_decor = env->GetMethodID(
        decor_class, "addView", "(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;)V");
    env->CallVoidMethod(decor, add_decor, header, lp);

    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(title_j);
}

void attach_status_pill(JNIEnv* env, jobject activity, jobject decor, const ModuleConfig& config) {
    if (find_tagged_overlay(env, decor, "hivirtus_status_pill")) return;

    const jint c_gold = parse_color(env, kGoldPrimary);
    const jint c_white = parse_color(env, kWhite);
    const jint c_black = parse_color(env, kBlack);

    jclass frame_class = env->FindClass("android/widget/FrameLayout");
    jclass linear_class = env->FindClass("android/widget/LinearLayout");
    jclass text_class = env->FindClass("android/widget/TextView");
    jmethodID frame_ctor = env->GetMethodID(frame_class, "<init>", "(Landroid/content/Context;)V");
    jmethodID linear_ctor = env->GetMethodID(linear_class, "<init>", "(Landroid/content/Context;)V");
    jmethodID text_ctor = env->GetMethodID(text_class, "<init>", "(Landroid/content/Context;)V");

    jobject outer = env->NewObject(frame_class, frame_ctor, activity);
    jobject inner = env->NewObject(linear_class, linear_ctor, activity);
    jobject label = env->NewObject(text_class, text_ctor, activity);

    set_view_background(env, outer, rounded_bg(env, c_gold, 28.0f, activity));
    set_view_background(env, inner, rounded_bg(env, c_white, 22.0f, activity));
    set_view_tag(env, outer, "hivirtus_status_pill");

    std::string status = "Hook Incoming SMS: ";
    status += config.hook_incoming_sms ? "ON" : "OFF";
    if (config.hook_outgoing_sms) {
        status += " · Out: ON";
    }

    jmethodID set_text = env->GetMethodID(text_class, "setText", "(Ljava/lang/CharSequence;)V");
    jmethodID set_text_color = env->GetMethodID(text_class, "setTextColor", "(I)V");
    jmethodID set_text_size = env->GetMethodID(text_class, "setTextSize", "(F)V");
    jmethodID set_typeface = env->GetMethodID(text_class, "setTypeface", "(Landroid/graphics/Typeface;)V");
    jmethodID set_gravity = env->GetMethodID(linear_class, "setGravity", "(I)V");
    jmethodID set_padding = env->GetMethodID(linear_class, "setPadding", "(IIII)V");
    jmethodID add_view_frame = env->GetMethodID(frame_class, "addView", "(Landroid/view/View;)V");
    jmethodID add_view_linear = env->GetMethodID(linear_class, "addView", "(Landroid/view/View;)V");

    jclass typeface_class = env->FindClass("android/graphics/Typeface");
    jmethodID default_bold = env->GetStaticMethodID(typeface_class, "defaultFromStyle", "(I)Landroid/graphics/Typeface;");
    jobject bold = env->CallStaticObjectMethod(typeface_class, default_bold, 1);

    jstring status_j = env->NewStringUTF(status.c_str());
    env->CallVoidMethod(label, set_text, status_j);
    env->CallVoidMethod(label, set_text_color, c_black);
    env->CallVoidMethod(label, set_text_size, 13.0f);
    env->CallVoidMethod(label, set_typeface, bold);
    env->CallVoidMethod(inner, set_gravity, 17);
    env->CallVoidMethod(inner, set_padding, 28, 12, 28, 12);
    env->CallVoidMethod(inner, add_view_linear, label);

    jclass view_class = env->FindClass("android/view/View");
    jmethodID set_padding_outer = env->GetMethodID(view_class, "setPadding", "(IIII)V");
    env->CallVoidMethod(outer, set_padding_outer, 8, 6, 8, 6);
    env->CallVoidMethod(outer, add_view_frame, inner);

    jobject lp = make_frame_lp(env, -1, -2, 0x50);  // BOTTOM
    jclass decor_class = env->GetObjectClass(decor);
    jmethodID add_decor = env->GetMethodID(
        decor_class, "addView", "(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;)V");
    env->CallVoidMethod(decor, add_decor, outer, lp);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        logger::info("OverlayUI", "Failed status pill");
    } else {
        logger::info("OverlayUI", "Hivirtus pill attached in %s", g_package.c_str());
    }
    env->DeleteLocalRef(status_j);
}

void attach_bubble(JNIEnv* env, jobject activity, jobject decor) {
    if (find_tagged_overlay(env, decor, "hivirtus_bubble")) return;

    const jint c_gold = parse_color(env, kGoldPrimary);
    const jint c_black = parse_color(env, kBlack);

    jclass frame_class = env->FindClass("android/widget/FrameLayout");
    jclass text_class = env->FindClass("android/widget/TextView");
    jmethodID frame_ctor = env->GetMethodID(frame_class, "<init>", "(Landroid/content/Context;)V");
    jmethodID text_ctor = env->GetMethodID(text_class, "<init>", "(Landroid/content/Context;)V");

    jobject bubble = env->NewObject(frame_class, frame_ctor, activity);
    jobject label = env->NewObject(text_class, text_ctor, activity);

    set_view_background(env, bubble, rounded_bg(env, c_gold, 24.0f, activity));
    set_view_tag(env, bubble, "hivirtus_bubble");

    jmethodID set_text = env->GetMethodID(text_class, "setText", "(Ljava/lang/CharSequence;)V");
    jmethodID set_text_color = env->GetMethodID(text_class, "setTextColor", "(I)V");
    jmethodID set_text_size = env->GetMethodID(text_class, "setTextSize", "(F)V");
    jmethodID set_gravity = env->GetMethodID(text_class, "setGravity", "(I)V");
    jmethodID add_view = env->GetMethodID(frame_class, "addView", "(Landroid/view/View;)V");

    jstring hv = env->NewStringUTF("HV");
    env->CallVoidMethod(label, set_text, hv);
    env->CallVoidMethod(label, set_text_color, c_black);
    env->CallVoidMethod(label, set_text_size, 11.0f);
    env->CallVoidMethod(label, set_gravity, 17);
    env->CallVoidMethod(bubble, add_view, label);

    jobject lp = make_frame_lp(env, 96, 96, 0x800055);  // END | CENTER_VERTICAL
    jclass decor_class = env->GetObjectClass(decor);
    jmethodID add_decor = env->GetMethodID(
        decor_class, "addView", "(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;)V");
    env->CallVoidMethod(decor, add_decor, bubble, lp);

    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(hv);
}

void attach_overlay(JNIEnv* env, jobject activity, const ModuleConfig& config) {
    if (!activity) return;

    jclass activity_class = env->GetObjectClass(activity);
    jmethodID get_window = env->GetMethodID(activity_class, "getWindow", "()Landroid/view/Window;");
    jobject window = env->CallObjectMethod(activity, get_window);
    if (!window) return;

    jclass window_class = env->FindClass("android/view/Window");
    jmethodID get_decor = env->GetMethodID(window_class, "getDecorView", "()Landroid/view/View;");
    jobject decor = env->CallObjectMethod(window, get_decor);
    if (!decor) return;

    attach_header_bar(env, activity, decor);
    attach_status_pill(env, activity, decor, config);
    attach_bubble(env, activity, decor);
}

void* overlay_worker(void*) {
    JNIEnv* env = nullptr;
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK || !env) return nullptr;

    while (g_running.load()) {
        ConfigManager::instance().reload();
        const auto& config = ConfigManager::instance().get();

        jobject activity = find_resumed_activity(env);
        if (activity) {
            if (!g_last_activity ||
                env->IsSameObject(activity, g_last_activity) == JNI_FALSE) {
                clear_global_ref(env, g_last_activity);
                g_last_activity = get_global_ref(env, activity);
            }
            attach_overlay(env, activity, config);
        }
        usleep(900000);
    }

    clear_global_ref(env, g_last_activity);
    g_vm->DetachCurrentThread();
    return nullptr;
}

}  // namespace

void install(JNIEnv* env, zygisk::Api* api, const std::string& package_name) {
    (void)api;
    if (g_running.exchange(true)) return;

    env->GetJavaVM(&g_vm);
    g_package = package_name;

    pthread_t thread{};
    pthread_create(&thread, nullptr, overlay_worker, nullptr);
    pthread_detach(thread);

    logger::info("OverlayUI", "Hivirtus overlay started for %s", package_name.c_str());
}

}  // namespace overlay_ui
