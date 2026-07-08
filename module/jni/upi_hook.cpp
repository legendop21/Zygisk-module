#include "upi_hook.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "sender_spoof.hpp"
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
    if (!api || g_bonus_ms <= 0) return;

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
    (void)env;
    (void)api;
    if (package_name.empty()) return;

    FILE* f = fopen("/data/local/tmp/hivirtus_active_upi.txt", "a");
    if (f) {
        fprintf(f, "%s\n", package_name.c_str());
        fclose(f);
        chmod("/data/local/tmp/hivirtus_active_upi.txt", 0644);
    }
    logger::info("UpiHook", "UPI scoped (overlay only) in %s", package_name.c_str());
}

}  // namespace upi_hook
