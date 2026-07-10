#include "phone_number_hook.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "telephony_spoof.hpp"
#include "zygisk_utils.hpp"

#include <string>

namespace phone_number_hook {

namespace {

zygisk::Api* g_api = nullptr;
std::string g_spoof_phone;

bool spoof_active() {
    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();
    return config.enable_phone_spoof || config.virtual_sim_active();
}

std::string resolve_phone() {
    if (!g_spoof_phone.empty()) return g_spoof_phone;
    const auto formats = telephony_spoof::load_formats();
    if (!formats.digits10.empty()) return formats.digits10;
    if (!formats.e164.empty()) return formats.e164;
    return g_spoof_phone;
}

static jstring (*orig_get_line1)(JNIEnv*, jobject) = nullptr;
static jstring (*orig_get_msisdn)(JNIEnv*, jobject, jint) = nullptr;

jstring hook_get_line1_number(JNIEnv* env, jobject thiz) {
    if (!spoof_active()) {
        return orig_get_line1 ? orig_get_line1(env, thiz) : nullptr;
    }
    const std::string phone = resolve_phone();
    if (phone.empty()) {
        return orig_get_line1 ? orig_get_line1(env, thiz) : nullptr;
    }
    logger::info("PhoneHook", "getLine1Number -> %s", phone.c_str());
    return zygisk_utils::string_to_jstring(env, phone);
}

jstring hook_get_msisdn(JNIEnv* env, jobject thiz, jint sub_id) {
    if (!spoof_active()) {
        return orig_get_msisdn ? orig_get_msisdn(env, thiz, sub_id) : nullptr;
    }
    const std::string phone = resolve_phone();
    if (phone.empty()) {
        return orig_get_msisdn ? orig_get_msisdn(env, thiz, sub_id) : nullptr;
    }
    logger::info("PhoneHook", "getMsisdn(%d) -> %s", sub_id, phone.c_str());
    return zygisk_utils::string_to_jstring(env, phone);
}

void try_hook_telephony_manager(JNIEnv* env) {
    if (!g_api || !spoof_active()) return;

    JNINativeMethod line1_methods[] = {
        {"getLine1Number", "()Ljava/lang/String;",
         reinterpret_cast<void*>(hook_get_line1_number)},
    };
    g_api->hookJniNativeMethods(env, "android/telephony/TelephonyManager", line1_methods, 1);
    if (line1_methods[0].fnPtr) {
        orig_get_line1 = reinterpret_cast<decltype(orig_get_line1)>(line1_methods[0].fnPtr);
        logger::info("PhoneHook", "getLine1Number native hook installed");
    }

    JNINativeMethod msisdn_methods[] = {
        {"getMsisdn", "(I)Ljava/lang/String;", reinterpret_cast<void*>(hook_get_msisdn)},
    };
    g_api->hookJniNativeMethods(env, "android/telephony/TelephonyManager", msisdn_methods, 1);
    if (msisdn_methods[0].fnPtr) {
        orig_get_msisdn = reinterpret_cast<decltype(orig_get_msisdn)>(msisdn_methods[0].fnPtr);
        logger::info("PhoneHook", "getMsisdn native hook installed");
    }
}

}  // namespace

void install(JNIEnv* env, zygisk::Api* api, const char* tag) {
    g_api = api;
    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();
    g_spoof_phone = config.mock_phone_sim1;
    if (g_spoof_phone.empty()) {
        char buf[96] = {};
        FILE* f = fopen("/data/local/tmp/hivirtus_spoof_phone.txt", "r");
        if (f) {
            if (fgets(buf, sizeof(buf), f)) g_spoof_phone = buf;
            fclose(f);
            if (!g_spoof_phone.empty() && g_spoof_phone.back() == '\n') g_spoof_phone.pop_back();
        }
    }

    if (!spoof_active()) return;

    try_hook_telephony_manager(env);
    logger::info("PhoneHook", "Fake phone active in %s (binder + native)", tag ? tag : "?");
}

}  // namespace phone_number_hook
