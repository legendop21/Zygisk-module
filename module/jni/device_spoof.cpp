#include "device_spoof.hpp"
#include "logger.hpp"
#include "plt_hook.hpp"
#include "zygisk_utils.hpp"

#include <cstring>
#include <cstdio>
#include <string>
#include <sys/stat.h>

namespace device_spoof {

namespace {

const ModuleConfig* g_config = nullptr;
std::string g_spoof_id;

static int (*orig___system_property_get)(const char*, char*) = nullptr;
static jstring (*orig_SystemProperties_get)(JNIEnv*, jclass, jstring, jstring) = nullptr;

bool key_is_android_id(const char* key) {
    if (!key) return false;
    return strstr(key, "android_id") != nullptr || strstr(key, "ANDROID_ID") != nullptr ||
           strstr(key, "ssaid") != nullptr;
}

int hook___system_property_get(const char* name, char* value) {
    int result = orig___system_property_get ? orig___system_property_get(name, value) : 0;
    if (!name || !value || g_spoof_id.empty()) return result;

    if (strcmp(name, "ro.serialno") == 0 || strcmp(name, "ro.boot.serialno") == 0 ||
        strcmp(name, "ril.serialnumber") == 0 || key_is_android_id(name)) {
        strncpy(value, g_spoof_id.c_str(), 91);
        value[91] = '\0';
        return static_cast<int>(strlen(value));
    }
    return result;
}

jstring hook_SystemProperties_get(JNIEnv* env, jclass clazz, jstring key_j, jstring def_j) {
    jstring result = orig_SystemProperties_get ? orig_SystemProperties_get(env, clazz, key_j, def_j)
                                               : def_j;
    if (g_spoof_id.empty() || !key_j) return result;

    const std::string key = zygisk_utils::jstring_to_string(env, key_j);
  std::string lower = key;
    for (char& c : lower) {
        if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
    }
    if (lower.find("android_id") != std::string::npos ||
        lower.find("serialno") != std::string::npos) {
        return zygisk_utils::string_to_jstring(env, g_spoof_id);
    }
    return result;
}

static jstring (*orig_Settings_Secure_getString)(JNIEnv*, jclass, jobject, jstring) =
    nullptr;

jstring hook_Settings_Secure_getString(JNIEnv* env, jclass clazz, jobject resolver,
                                       jstring name_j) {
    jstring result =
        orig_Settings_Secure_getString
            ? orig_Settings_Secure_getString(env, clazz, resolver, name_j)
            : nullptr;
    if (g_spoof_id.empty() || !name_j) return result;

    const std::string name = zygisk_utils::jstring_to_string(env, name_j);
    if (name == "android_id") {
        return zygisk_utils::string_to_jstring(env, g_spoof_id);
    }
    return result;
}

void write_runtime_marker(const std::string& id) {
    const char* paths[] = {
        "/data/adb/modules/hivirtus_zygisk_mode/spoof_android_id.txt",
        "/data/local/tmp/hivirtus_spoof_android_id.txt",
        nullptr
    };
    for (const char** p = paths; *p; ++p) {
        FILE* f = fopen(*p, "w");
        if (f) {
            fprintf(f, "%s\n", id.c_str());
            fclose(f);
            chmod(*p, 0644);
        }
    }
}

std::string read_persisted_id() {
    const char* paths[] = {
        "/data/adb/modules/hivirtus_zygisk_mode/spoof_android_id.txt",
        "/data/local/tmp/hivirtus_spoof_android_id.txt",
        nullptr
    };
    char buf[64] = {};
    for (const char** p = paths; *p; ++p) {
        FILE* f = fopen(*p, "r");
        if (!f) continue;
        if (fgets(buf, sizeof(buf), f)) {
            fclose(f);
            std::string id = buf;
            if (!id.empty() && id.back() == '\n') id.pop_back();
            if (!id.empty()) return id;
        } else {
            fclose(f);
        }
    }
    return {};
}

void install_property_hooks(zygisk::Api* api) {
    if (!api) return;
    plt_hook::set_api(api);
    plt_hook::register_regex(".*/libc\\.so$", "__system_property_get",
                             reinterpret_cast<void*>(hook___system_property_get),
                             reinterpret_cast<void**>(&orig___system_property_get));
    plt_hook::commit();
}

void install_system_properties_jni(zygisk::Api* api, JNIEnv* env) {
    if (!api) return;

    JNINativeMethod methods[] = {
        {"get", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
         reinterpret_cast<void*>(hook_SystemProperties_get)},
    };
    api->hookJniNativeMethods(env, "android/os/SystemProperties", methods, 1);
    orig_SystemProperties_get =
        reinterpret_cast<decltype(orig_SystemProperties_get)>(methods[0].fnPtr);
}

void install_settings_jni(zygisk::Api* api, JNIEnv* env) {
    if (!api) return;

    jclass secure = env->FindClass("android/provider/Settings$Secure");
    if (!secure) return;

    JNINativeMethod methods[] = {
        {"getString", "(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;",
         reinterpret_cast<void*>(hook_Settings_Secure_getString)},
    };
    api->hookJniNativeMethods(env, "android/provider/Settings$Secure", methods, 1);
    if (methods[0].fnPtr) {
        orig_Settings_Secure_getString =
            reinterpret_cast<decltype(orig_Settings_Secure_getString)>(methods[0].fnPtr);
        logger::info("DeviceSpoof", "Settings.Secure.getString hook installed");
    }
}

}  // namespace

void install(JNIEnv* env, const ModuleConfig& config, zygisk::Api* api) {
    g_config = &config;
    g_spoof_id = config.spoof_android_id;
    if (g_spoof_id.empty()) {
        g_spoof_id = read_persisted_id();
    }
    if (g_spoof_id.empty()) return;

    write_runtime_marker(g_spoof_id);
    install_property_hooks(api);
    install_system_properties_jni(api, env);
    install_settings_jni(api, env);
    logger::info("DeviceSpoof", "Android ID spoof active: %s", g_spoof_id.c_str());
}

}  // namespace device_spoof
