#include "dex_loader.hpp"
#include "logger.hpp"
#include "zygisk_utils.hpp"

#include <cstring>
#include <fstream>
#include <string>
#include <unistd.h>
#include <sys/stat.h>
#include <vector>

namespace dex_loader {

namespace {

constexpr const char* kDexPath = "/data/adb/modules/hivirtus_zygisk_mode/overlay/overlay.dex";
constexpr const char* kDexOptDir = "/data/local/tmp/hivirtus_dex";
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

bool call_bootstrap(JNIEnv* env, jobject context, jobject loader) {
    jclass class_loader = env->FindClass("java/lang/ClassLoader");
    jmethodID load_class = env->GetMethodID(
        class_loader, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");

    jstring class_name = env->NewStringUTF(kBootstrapClass);
    jobject bootstrap_class = env->CallObjectMethod(loader, load_class, class_name);
    if (!bootstrap_class || env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }

    jclass clazz = (jclass)bootstrap_class;
    jmethodID start = env->GetStaticMethodID(clazz, kBootstrapMethod, kBootstrapSig);
    if (!start) return false;

    env->CallStaticVoidMethod(clazz, start, context);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return true;
}

bool start_from_dex_file(JNIEnv* env, jobject context) {
    mkdir(kDexOptDir, 0755);

    jclass context_class = env->FindClass("android/content/Context");
    jmethodID get_loader = env->GetMethodID(
        context_class, "getClassLoader", "()Ljava/lang/ClassLoader;");
    jobject parent = env->CallObjectMethod(context, get_loader);

    jclass dex_loader_class = env->FindClass("dalvik/system/DexClassLoader");
    if (!dex_loader_class) return false;

    jmethodID ctor = env->GetMethodID(
        dex_loader_class, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V");

    jobject loader = env->NewObject(
        dex_loader_class, ctor,
        zygisk_utils::string_to_jstring(env, kDexPath),
        zygisk_utils::string_to_jstring(env, kDexOptDir),
        nullptr, parent);

    if (!loader || env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }

    return call_bootstrap(env, context, loader);
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

bool start_from_memory(JNIEnv* env, jobject context) {
    const auto dex_bytes = read_file_bytes(kDexPath);
    if (dex_bytes.empty()) return false;

    jbyteArray dex_array = env->NewByteArray(static_cast<jsize>(dex_bytes.size()));
    env->SetByteArrayRegion(dex_array, 0, static_cast<jsize>(dex_bytes.size()),
                            reinterpret_cast<const jbyte*>(dex_bytes.data()));

    jclass byte_buffer_class = env->FindClass("java/nio/ByteBuffer");
    jmethodID wrap = env->GetStaticMethodID(
        byte_buffer_class, "wrap", "([B)Ljava/nio/ByteBuffer;");
    jobject buffer = env->CallStaticObjectMethod(byte_buffer_class, wrap, dex_array);

    jclass loader_class = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    if (!loader_class) return false;

    jclass context_class = env->FindClass("android/content/Context");
    jmethodID get_class_loader = env->GetMethodID(
        context_class, "getClassLoader", "()Ljava/lang/ClassLoader;");
    jobject parent = env->CallObjectMethod(context, get_class_loader);

    jmethodID loader_ctor = env->GetMethodID(
        loader_class, "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
    jobject dex_loader = env->NewObject(loader_class, loader_ctor, buffer, parent);
    if (!dex_loader || env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }

    return call_bootstrap(env, context, dex_loader);
}

}  // namespace

bool start_overlay(JNIEnv* env) {
    jobject context = get_app_context(env);
    if (!context) {
        logger::error("DexLoader", "No application context");
        return false;
    }

    if (access(kDexPath, R_OK) != 0) {
        logger::error("DexLoader", "overlay.dex missing at %s", kDexPath);
        return false;
    }

    if (start_from_dex_file(env, context)) {
        logger::info("DexLoader", "Overlay started via DexClassLoader");
        return true;
    }

    if (start_from_memory(env, context)) {
        logger::info("DexLoader", "Overlay started via InMemoryDexClassLoader");
        return true;
    }

    logger::error("DexLoader", "All dex load methods failed");
    return false;
}

}  // namespace dex_loader
