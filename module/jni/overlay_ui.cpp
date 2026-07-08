#include "overlay_ui.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "zygisk_utils.hpp"

#include <android/log.h>
#include <atomic>
#include <pthread.h>
#include <string>
#include <unistd.h>

namespace overlay_ui {

namespace {

JavaVM* g_vm = nullptr;
std::atomic<bool> g_running{false};
std::string g_package;
jobject g_last_activity = nullptr;

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

        const jboolean paused = env->GetBooleanField(record, paused_field);
        if (paused) continue;

        jobject activity = env->GetObjectField(record, activity_field);
        if (activity && !env->ExceptionCheck()) {
            return activity;
        }
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

void run_on_ui(JNIEnv* env, jobject activity, jobject runnable) {
    jclass activity_class = env->GetObjectClass(activity);
    jmethodID run_ui = env->GetMethodID(activity_class, "runOnUiThread", "(Ljava/lang/Runnable;)V");
    if (!run_ui) return;
    env->CallVoidMethod(activity, run_ui, runnable);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

jobject make_status_runnable(JNIEnv* env, const ModuleConfig& config) {
    jclass runnable_class = env->FindClass("java/lang/Runnable");
    // Use anonymous class via proxy - simpler: direct attach on UI thread inline below
    (void)runnable_class;
    (void)config;
    return nullptr;
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

    if (find_tagged_overlay(env, decor, "hivirtus_status_pill")) return;

    jclass frame_class = env->FindClass("android/widget/FrameLayout");
    jclass linear_class = env->FindClass("android/widget/LinearLayout");
    jclass text_class = env->FindClass("android/widget/TextView");
    jclass lp_class = env->FindClass("android/widget/FrameLayout$LayoutParams");
    jclass color_class = env->FindClass("android/graphics/Color");

    jmethodID frame_ctor = env->GetMethodID(frame_class, "<init>", "(Landroid/content/Context;)V");
    jmethodID linear_ctor = env->GetMethodID(linear_class, "<init>", "(Landroid/content/Context;)V");
    jmethodID text_ctor = env->GetMethodID(text_class, "<init>", "(Landroid/content/Context;)V");
    jmethodID lp_ctor = env->GetMethodID(lp_class, "<init>", "(II)V");
    jmethodID set_tag = env->GetMethodID(env->FindClass("android/view/View"), "setTag",
                                           "(Ljava/lang/Object;)V");
    jmethodID set_bg = env->GetMethodID(env->FindClass("android/view/View"), "setBackgroundColor", "(I)V");
    jmethodID set_text = env->GetMethodID(text_class, "setText", "(Ljava/lang/CharSequence;)V");
    jmethodID set_text_color = env->GetMethodID(text_class, "setTextColor", "(I)V");
    jmethodID set_text_size = env->GetMethodID(text_class, "setTextSize", "(F)V");
    jmethodID set_gravity = env->GetMethodID(linear_class, "setGravity", "(I)V");
    jmethodID set_padding = env->GetMethodID(linear_class, "setPadding", "(IIII)V");
    jmethodID add_view = env->GetMethodID(frame_class, "addView", "(Landroid/view/View;)V");
    jmethodID add_view_lp = env->GetMethodID(
        frame_class, "addView", "(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;)V");

    jmethodID parse_color = env->GetStaticMethodID(color_class, "parseColor", "(Ljava/lang/String;)I");
    jstring yellow = env->NewStringUTF("#FFC107");
    jstring white = env->NewStringUTF("#FFFFFF");
    jstring black = env->NewStringUTF("#000000");
    const jint c_yellow = env->CallStaticIntMethod(color_class, parse_color, yellow);
    const jint c_white = env->CallStaticIntMethod(color_class, parse_color, white);
    const jint c_black = env->CallStaticIntMethod(color_class, parse_color, black);

    jobject outer = env->NewObject(frame_class, frame_ctor, activity);
    jobject inner = env->NewObject(linear_class, linear_ctor, activity);
    jobject label = env->NewObject(text_class, text_ctor, activity);

    std::string status = "Hook Incoming SMS: ";
    status += config.hook_incoming_sms ? "ON" : "OFF";
    if (config.hook_outgoing_sms) {
        status += " · Out: ON";
    }
    jstring status_j = env->NewStringUTF(status.c_str());

    env->CallVoidMethod(outer, set_tag, env->NewStringUTF("hivirtus_status_pill"));
    env->CallVoidMethod(outer, set_bg, c_yellow);
    env->CallVoidMethod(inner, set_bg, c_white);
    env->CallVoidMethod(inner, set_gravity, 17);  // CENTER
    env->CallVoidMethod(inner, set_padding, 24, 16, 24, 16);
    env->CallVoidMethod(label, set_text, status_j);
    env->CallVoidMethod(label, set_text_color, c_black);
    env->CallVoidMethod(label, set_text_size, 13.0f);

    env->CallVoidMethod(inner, add_view, label);
    env->CallVoidMethod(outer, add_view, inner);

    jobject lp = env->NewObject(lp_class, lp_ctor, -1, -2);  // MATCH_PARENT, WRAP_CONTENT
    jfieldID gravity_field = env->GetFieldID(lp_class, "gravity", "I");
    env->SetIntField(lp, gravity_field, 0x50);  // BOTTOM

    jclass decor_class = env->GetObjectClass(decor);
    jmethodID add_decor = env->GetMethodID(
        decor_class, "addView", "(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;)V");
    env->CallVoidMethod(decor, add_decor, outer, lp);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        logger::info("OverlayUI", "Failed to attach status pill");
    } else {
        logger::info("OverlayUI", "Status pill attached in %s", g_package.c_str());
    }

    env->DeleteLocalRef(status_j);
    env->DeleteLocalRef(yellow);
    env->DeleteLocalRef(white);
    env->DeleteLocalRef(black);
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

    logger::info("OverlayUI", "Native overlay worker started for %s", package_name.c_str());
}

}  // namespace overlay_ui
