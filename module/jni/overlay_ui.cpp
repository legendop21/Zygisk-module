#include "overlay_ui.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "zygisk.hpp"

#include <atomic>
#include <string>

namespace overlay_ui {

namespace {

constexpr const char* kGoldPrimary = "#FFD700";
constexpr const char* kWhite = "#FFFFFF";
constexpr const char* kBlack = "#000000";

zygisk::Api* g_api = nullptr;
std::atomic<bool> g_hooks_installed{false};
std::string g_package;

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
    const jfloat radius_px = radius_dp * env->GetFloatField(metrics, density_field);

    jmethodID set_radius = env->GetMethodID(gd_class, "setCornerRadius", "(F)V");
    env->CallVoidMethod(gd, set_radius, radius_px);
    return gd;
}

jobject find_tagged(JNIEnv* env, jobject decor, const char* tag) {
    jclass view_class = env->FindClass("android/view/View");
    jmethodID find_id = env->GetMethodID(view_class, "findViewWithTag", "(Ljava/lang/Object;)Landroid/view/View;");
    jstring tag_str = env->NewStringUTF(tag);
    jobject found = env->CallObjectMethod(decor, find_id, tag_str);
    env->DeleteLocalRef(tag_str);
    return found;
}

bool activity_alive(JNIEnv* env, jobject activity) {
    jclass activity_class = env->GetObjectClass(activity);
    jmethodID is_finishing = env->GetMethodID(activity_class, "isFinishing", "()Z");
    jmethodID is_destroyed = env->GetMethodID(activity_class, "isDestroyed", "()Z");
    if (env->CallBooleanMethod(activity, is_finishing)) return false;
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    if (env->CallBooleanMethod(activity, is_destroyed)) return false;
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return true;
}

void attach_status_pill(JNIEnv* env, jobject activity, jobject decor, const ModuleConfig& config) {
    if (!config.hook_incoming_sms) return;
    if (find_tagged(env, decor, "hivirtus_status_pill")) return;

    const jint c_gold = parse_color(env, kGoldPrimary);
    const jint c_white = parse_color(env, kWhite);
    const jint c_black = parse_color(env, kBlack);

    jclass frame_class = env->FindClass("android/widget/FrameLayout");
    jclass linear_class = env->FindClass("android/widget/LinearLayout");
    jclass text_class = env->FindClass("android/widget/TextView");
    jclass image_class = env->FindClass("android/widget/ImageView");
    jmethodID frame_ctor = env->GetMethodID(frame_class, "<init>", "(Landroid/content/Context;)V");
    jmethodID linear_ctor = env->GetMethodID(linear_class, "<init>", "(Landroid/content/Context;)V");
    jmethodID text_ctor = env->GetMethodID(text_class, "<init>", "(Landroid/content/Context;)V");
    jmethodID image_ctor = env->GetMethodID(image_class, "<init>", "(Landroid/content/Context;)V");

    jobject outer = env->NewObject(frame_class, frame_ctor, activity);
    jobject inner = env->NewObject(linear_class, linear_ctor, activity);
    jobject icon = env->NewObject(image_class, image_ctor, activity);
    jobject label = env->NewObject(text_class, text_ctor, activity);

    jclass view_class = env->FindClass("android/view/View");
    jmethodID set_bg = env->GetMethodID(view_class, "setBackground", "(Landroid/graphics/drawable/Drawable;)V");
    jmethodID set_tag = env->GetMethodID(view_class, "setTag", "(Ljava/lang/Object;)V");
    env->CallVoidMethod(outer, set_bg, rounded_bg(env, c_gold, 28.0f, activity));
    env->CallVoidMethod(inner, set_bg, rounded_bg(env, c_white, 22.0f, activity));
    env->CallVoidMethod(outer, set_tag, env->NewStringUTF("hivirtus_status_pill"));

    jclass activity_class = env->GetObjectClass(activity);
    jmethodID get_pm = env->GetMethodID(activity_class, "getPackageManager", "()Landroid/content/pm/PackageManager;");
    jmethodID get_pkg = env->GetMethodID(activity_class, "getPackageName", "()Ljava/lang/String;");
    jobject pm = env->CallObjectMethod(activity, get_pm);
    jstring pkg = (jstring)env->CallObjectMethod(activity, get_pkg);
    jclass pm_class = env->FindClass("android/content/pm/PackageManager");
    jmethodID get_icon = env->GetMethodID(pm_class, "getApplicationIcon", "(Ljava/lang/String;)Landroid/graphics/drawable/Drawable;");
    jobject drawable = env->CallObjectMethod(pm, get_icon, pkg);
    jmethodID set_image = env->GetMethodID(image_class, "setImageDrawable", "(Landroid/graphics/drawable/Drawable;)V");
    if (drawable && !env->ExceptionCheck()) {
        env->CallVoidMethod(icon, set_image, drawable);
    } else {
        env->ExceptionClear();
    }

    jmethodID set_text = env->GetMethodID(text_class, "setText", "(Ljava/lang/CharSequence;)V");
    jmethodID set_text_color = env->GetMethodID(text_class, "setTextColor", "(I)V");
    jmethodID set_text_size = env->GetMethodID(text_class, "setTextSize", "(F)V");
    jmethodID set_gravity = env->GetMethodID(linear_class, "setGravity", "(I)V");
    jmethodID set_orientation = env->GetMethodID(linear_class, "setOrientation", "(I)V");
    jmethodID set_padding = env->GetMethodID(linear_class, "setPadding", "(IIII)V");
    jmethodID add_view_linear = env->GetMethodID(linear_class, "addView", "(Landroid/view/View;)V");
    jmethodID add_view_frame = env->GetMethodID(frame_class, "addView", "(Landroid/view/View;)V");
    jmethodID set_padding_outer = env->GetMethodID(view_class, "setPadding", "(IIII)V");

    jstring status_j = env->NewStringUTF("Hook Incoming SMS: ON");
    env->CallVoidMethod(label, set_text, status_j);
    env->CallVoidMethod(label, set_text_color, c_black);
    env->CallVoidMethod(label, set_text_size, 13.0f);
    env->CallVoidMethod(inner, set_orientation, 0);
    env->CallVoidMethod(inner, set_gravity, 17);
    env->CallVoidMethod(inner, set_padding, 20, 10, 24, 10);
    env->CallVoidMethod(inner, add_view_linear, icon);
    env->CallVoidMethod(inner, add_view_linear, label);
    env->CallVoidMethod(outer, set_padding_outer, 8, 6, 8, 6);
    env->CallVoidMethod(outer, add_view_frame, inner);

    jclass lp_class = env->FindClass("android/widget/FrameLayout$LayoutParams");
    jmethodID lp_ctor = env->GetMethodID(lp_class, "<init>", "(II)V");
    jobject lp = env->NewObject(lp_class, lp_ctor, -1, -2);
    jfieldID gravity_field = env->GetFieldID(lp_class, "gravity", "I");
    env->SetIntField(lp, gravity_field, 0x50);

    jclass decor_class = env->GetObjectClass(decor);
    jmethodID add_decor = env->GetMethodID(
        decor_class, "addView", "(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;)V");
    env->CallVoidMethod(decor, add_decor, outer, lp);

    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(status_j);
}

void attach_overlay(JNIEnv* env, jobject activity, const ModuleConfig& config) {
    if (!activity_alive(env, activity)) return;

    jclass activity_class = env->GetObjectClass(activity);
    jmethodID get_window = env->GetMethodID(activity_class, "getWindow", "()Landroid/view/Window;");
    jobject window = env->CallObjectMethod(activity, get_window);
    if (!window || env->ExceptionCheck()) {
        env->ExceptionClear();
        return;
    }
    jclass window_class = env->FindClass("android/view/Window");
    jmethodID get_decor = env->GetMethodID(window_class, "getDecorView", "()Landroid/view/View;");
    jobject decor = env->CallObjectMethod(window, get_decor);
    if (!decor || env->ExceptionCheck()) {
        env->ExceptionClear();
        return;
    }
    attach_status_pill(env, activity, decor, config);
}

static void (*orig_onResume)(JNIEnv*, jobject) = nullptr;

void hook_onResume(JNIEnv* env, jobject thiz) {
    if (orig_onResume) orig_onResume(env, thiz);
    if (env->ExceptionCheck()) env->ExceptionClear();

    jclass activity_class = env->GetObjectClass(thiz);
    jmethodID get_pkg = env->GetMethodID(activity_class, "getPackageName", "()Ljava/lang/String;");
    jstring pkg_j = (jstring)env->CallObjectMethod(thiz, get_pkg);
    if (!pkg_j) return;
    const char* pkg = env->GetStringUTFChars(pkg_j, nullptr);
    const bool ours = pkg && g_package == pkg;
    env->ReleaseStringUTFChars(pkg_j, pkg);
    if (!ours) return;

    ConfigManager::instance().reload();
    attach_overlay(env, thiz, ConfigManager::instance().get());
}

void install_on_resume_hook() {
    if (!g_api || !g_api->pltHookRegister || !g_api->pltHookCommit) return;
    static bool committed = false;
    if (committed) return;
    committed = true;

    g_api->pltHookRegister(".*libandroid_runtime\\.so$",
                           "Java_android_app_Activity_onResume",
                           reinterpret_cast<void*>(hook_onResume),
                           reinterpret_cast<void**>(&orig_onResume));
    g_api->pltHookCommit();
    logger::info("OverlayUI", "Activity.onResume hook committed for %s", g_package.c_str());
}

}  // namespace

void install(JNIEnv* env, zygisk::Api* api, const std::string& package_name) {
    (void)env;
    if (g_hooks_installed.exchange(true)) return;
    g_api = api;
    g_package = package_name;
    install_on_resume_hook();
}

}  // namespace overlay_ui
