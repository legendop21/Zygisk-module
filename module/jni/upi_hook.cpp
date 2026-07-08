#include "upi_hook.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "zygisk.hpp"

#include <cstdio>
#include <sys/stat.h>

namespace upi_hook {

namespace {

int g_bonus_ms = 0;
static jlong (*orig_System_currentTimeMillis)(JNIEnv*, jclass) = nullptr;

jlong hook_System_currentTimeMillis(JNIEnv* env, jclass clazz) {
    jlong now = orig_System_currentTimeMillis ? orig_System_currentTimeMillis(env, clazz) : 0;
    if (g_bonus_ms > 0 && now > g_bonus_ms) {
        return now - static_cast<jlong>(g_bonus_ms);
    }
    return now;
}

void install_timer_hook(JNIEnv* env, zygisk::Api* api) {
    if (!api || !api->hookJniNativeMethods || g_bonus_ms <= 0) return;

    JNINativeMethod methods[] = {
        {"currentTimeMillis", "()J", reinterpret_cast<void*>(hook_System_currentTimeMillis)},
    };
    api->hookJniNativeMethods(env, "java/lang/System", methods, 1);
    orig_System_currentTimeMillis =
        reinterpret_cast<decltype(orig_System_currentTimeMillis)>(methods[0].fnPtr);
    logger::info("UpiHook", "UPI verify timer +%d sec (Paytm/PhonePe)", g_bonus_ms / 1000);
}

}  // namespace

void install(JNIEnv* env, zygisk::Api* api, const std::string& package_name) {
    if (package_name.empty()) return;

    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();
    g_bonus_ms = config.upi_timer_bonus_seconds * 1000;
    if (g_bonus_ms <= 0) g_bonus_ms = 20000;

    install_timer_hook(env, api);

    jclass sms_message = env->FindClass("android/telephony/SmsMessage");
    jclass sms_retriever = env->FindClass("com/google/android/gms/auth/api/phone/SmsRetrieverClient");
    if (sms_message || sms_retriever) {
        logger::info("UpiHook", "UPI hook active in %s (timer bonus %ds)",
                     package_name.c_str(), g_bonus_ms / 1000);
    }

    FILE* f = fopen("/data/local/tmp/hivirtus_active_upi.txt", "a");
    if (f) {
        fprintf(f, "%s\n", package_name.c_str());
        fclose(f);
        chmod("/data/local/tmp/hivirtus_active_upi.txt", 0644);
    }
}

}  // namespace upi_hook
