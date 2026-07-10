#include "dex_loader.hpp"
#include "logger.hpp"
#include "zygisk_utils.hpp"

#include <cstring>
#include <fstream>
#include <string>
#include <vector>

namespace dex_loader {

namespace {

constexpr const char* kDexPath = "/data/adb/modules/hivirtus_zygisk_mode/overlay/overlay.dex";
constexpr const char* kBootstrapClass = "com.hivirtus.zygiskmode.overlay.OverlayBootstrap";
constexpr const char* kBootstrapMethod = "start";
constexpr const char* kBootstrapSig = "(Landroid/content/Context;)V";

jobject get_app_context(JNIEnv* env) {
    jclass activity_thread = env->FindClass("android/app/ActivityThread");
    if (!activity_thread) return nullptr;

    jmethodID current_app = env->GetStaticMethodID(
        activity_thread, "currentApplication", "()Landroid/app/Application;");
    if (!current_app) return nullptr;

    return env->CallStaticObjectMethod(activity_thread, current_app);
}

std::vector<uint8_t> read_file_bytes(const char* path) {
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file.is_open()) return {};
    const auto size = file.tellg();
    if (size <= 0) return {};
    file.seekg(0);
    std::vector<uint8_t> buf(static_cast<size_t>(size));
    file.read(reinterpret_cast<char*>(buf.data()), size);
    return buf;
}

jobject load_dex_class(JNIEnv* env, jobject context, const std::vector<uint8_t>& dex_bytes,
                       const char* class_name) {
    if (dex_bytes.empty() || !context) return nullptr;

    jbyteArray dex_array = env->NewByteArray(static_cast<jsize>(dex_bytes.size()));
    env->SetByteArrayRegion(dex_array, 0, static_cast<jsize>(dex_bytes.size()),
                            reinterpret_cast<const jbyte*>(dex_bytes.data()));

    jclass byte_buffer_class = env->FindClass("java/nio/ByteBuffer");
    jmethodID wrap = env->GetStaticMethodID(
        byte_buffer_class, "wrap", "([B)Ljava/nio/ByteBuffer;");
    jobject buffer = env->CallStaticObjectMethod(byte_buffer_class, wrap, dex_array);

    jclass loader_class = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    if (!loader_class) {
        logger::error("DexLoader", "InMemoryDexClassLoader not found");
        return nullptr;
    }

    jclass context_class = env->FindClass("android/content/Context");
    jmethodID get_class_loader = env->GetMethodID(
        context_class, "getClassLoader", "()Ljava/lang/ClassLoader;");
    jobject parent = env->CallObjectMethod(context, get_class_loader);

    jmethodID loader_ctor = env->GetMethodID(
        loader_class, "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
    jobject dex_loader = env->NewObject(loader_class, loader_ctor, buffer, parent);
    if (!dex_loader || env->ExceptionCheck()) {
        env->ExceptionClear();
        logger::error("DexLoader", "Failed to create InMemoryDexClassLoader");
        return nullptr;
    }

    jmethodID load_class = env->GetMethodID(loader_class, "loadClass",
                                            "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring class_j = env->NewStringUTF(class_name);
    return env->CallObjectMethod(dex_loader, load_class, class_j);
}

}  // namespace

bool start_overlay(JNIEnv* env) {
    jobject context = get_app_context(env);
    if (!context) {
        logger::error("DexLoader", "No application context in SystemUI");
        return false;
    }

    const auto dex_bytes = read_file_bytes(kDexPath);
    if (dex_bytes.empty()) {
        logger::error("DexLoader", "overlay.dex missing at %s", kDexPath);
        return false;
    }

    jobject bootstrap_class = load_dex_class(env, context, dex_bytes, kBootstrapClass);
    if (!bootstrap_class) {
        logger::error("DexLoader", "OverlayBootstrap class load failed");
        return false;
    }

    jclass clazz = (jclass)bootstrap_class;
    jmethodID start = env->GetStaticMethodID(clazz, kBootstrapMethod, kBootstrapSig);
    if (!start) {
        logger::error("DexLoader", "OverlayBootstrap.start not found");
        return false;
    }

    env->CallStaticVoidMethod(clazz, start, context);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        logger::error("DexLoader", "OverlayBootstrap.start threw");
        return false;
    }

    logger::info("DexLoader", "Floating overlay started from embedded dex");
    return true;
}

}  // namespace dex_loader
