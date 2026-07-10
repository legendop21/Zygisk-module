#include "phone_number_hook.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "telephony_spoof.hpp"
#include "zygisk_utils.hpp"

#include <cstdio>
#include <pthread.h>
#include <unistd.h>
#include <string>

namespace phone_number_hook {

namespace {

zygisk::Api* g_api = nullptr;
std::string g_spoof_phone;

bool spoof_active() {
    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();
    if (config.enable_phone_spoof || config.virtual_sim_active() || config.enable_sim1_mock)
        return true;
    // File me number ho to force ON (Save flag miss pe bhi)
    FILE* f = fopen("/data/local/tmp/hivirtus_spoof_phone.txt", "r");
    if (!f) f = fopen("/data/adb/modules/hivirtus_zygisk_mode/spoof_phone.txt", "r");
    if (!f) return false;
    char buf[64] = {};
    bool ok = fgets(buf, sizeof(buf), f) != nullptr;
    fclose(f);
    if (!ok) return false;
    int digits = 0;
    for (char* p = buf; *p; ++p)
        if (*p >= '0' && *p <= '9') digits++;
    return digits >= 10;
}

std::string resolve_phone() {
    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();
    std::string phone = config.resolve_mock_phone();
    if (!phone.empty()) {
        g_spoof_phone = phone;
        return phone;
    }
    if (!g_spoof_phone.empty()) return g_spoof_phone;
    const auto formats = telephony_spoof::load_formats();
    if (!formats.digits10.empty()) return formats.digits10;
    if (!formats.e164.empty()) return formats.e164;
    // Fallback file
    FILE* f = fopen("/data/local/tmp/hivirtus_spoof_phone.txt", "r");
    if (!f) f = fopen("/data/adb/modules/hivirtus_zygisk_mode/spoof_phone.txt", "r");
    if (f) {
        char buf[64] = {};
        if (fgets(buf, sizeof(buf), f)) {
            std::string p = buf;
            while (!p.empty() && (p.back() == '\n' || p.back() == '\r')) p.pop_back();
            g_spoof_phone = p;
        }
        fclose(f);
    }
    return g_spoof_phone;
}

static jstring (*orig_subinfo_get_number)(JNIEnv*, jobject) = nullptr;

jstring hook_subinfo_get_number(JNIEnv* env, jobject thiz) {
    if (!spoof_active()) {
        return orig_subinfo_get_number ? orig_subinfo_get_number(env, thiz) : nullptr;
    }
    const std::string phone = resolve_phone();
    if (phone.empty()) {
        return orig_subinfo_get_number ? orig_subinfo_get_number(env, thiz) : nullptr;
    }
    logger::info("PhoneHook", "SubscriptionInfo.getNumber -> %s", phone.c_str());
    return zygisk_utils::string_to_jstring(env, phone);
}

static jstring (*orig_get_line1)(JNIEnv*, jobject) = nullptr;
static jstring (*orig_get_line1_sub)(JNIEnv*, jobject, jint) = nullptr;
static jstring (*orig_get_msisdn)(JNIEnv*, jobject, jint) = nullptr;
static jstring (*orig_get_subscriber)(JNIEnv*, jobject) = nullptr;
static jstring (*orig_get_subscriber_sub)(JNIEnv*, jobject, jint) = nullptr;
static jstring (*orig_get_iccid)(JNIEnv*, jobject) = nullptr;

jstring spoof_phone_jstring(JNIEnv* env, jstring (*orig)(JNIEnv*, jobject), jobject thiz,
                            const char* tag) {
    if (!spoof_active()) {
        return orig ? orig(env, thiz) : nullptr;
    }
    const std::string phone = resolve_phone();
    if (phone.empty()) {
        return orig ? orig(env, thiz) : nullptr;
    }
    logger::info("PhoneHook", "%s -> %s", tag, phone.c_str());
    return zygisk_utils::string_to_jstring(env, phone);
}

jstring hook_get_line1_number(JNIEnv* env, jobject thiz) {
    return spoof_phone_jstring(env, orig_get_line1, thiz, "getLine1Number");
}

jstring hook_get_line1_number_sub(JNIEnv* env, jobject thiz, jint sub_id) {
    if (!spoof_active()) {
        return orig_get_line1_sub ? orig_get_line1_sub(env, thiz, sub_id) : nullptr;
    }
    const std::string phone = resolve_phone();
    if (phone.empty()) {
        return orig_get_line1_sub ? orig_get_line1_sub(env, thiz, sub_id) : nullptr;
    }
    logger::info("PhoneHook", "getLine1Number(%d) -> %s", sub_id, phone.c_str());
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

jstring hook_get_subscriber_id(JNIEnv* env, jobject thiz) {
    if (!spoof_active()) {
        return orig_get_subscriber ? orig_get_subscriber(env, thiz) : nullptr;
    }
    // Keep real IMSI if no mock — only spoof when mock_imsi set; else pass-through
    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();
    if (!config.mock_imsi_sim1.empty()) {
        return zygisk_utils::string_to_jstring(env, config.mock_imsi_sim1);
    }
    return orig_get_subscriber ? orig_get_subscriber(env, thiz) : nullptr;
}

jstring hook_get_subscriber_id_sub(JNIEnv* env, jobject thiz, jint sub_id) {
    if (!spoof_active()) {
        return orig_get_subscriber_sub ? orig_get_subscriber_sub(env, thiz, sub_id) : nullptr;
    }
    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();
    if (!config.mock_imsi_sim1.empty()) {
        return zygisk_utils::string_to_jstring(env, config.mock_imsi_sim1);
    }
    return orig_get_subscriber_sub ? orig_get_subscriber_sub(env, thiz, sub_id) : nullptr;
}

jstring hook_get_sim_serial(JNIEnv* env, jobject thiz) {
    if (!spoof_active()) {
        return orig_get_iccid ? orig_get_iccid(env, thiz) : nullptr;
    }
    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();
    if (!config.mock_iccid_sim1.empty()) {
        return zygisk_utils::string_to_jstring(env, config.mock_iccid_sim1);
    }
    return orig_get_iccid ? orig_get_iccid(env, thiz) : nullptr;
}

void try_one_jni(JNIEnv* env, const char* cls, const char* name, const char* sig, void* hook,
                 void** orig_out, const char* tag) {
    if (!g_api || !env || !orig_out) return;
    if (*orig_out) return;
    JNINativeMethod methods[1];
    methods[0].name = name;
    methods[0].signature = sig;
    methods[0].fnPtr = hook;
    g_api->hookJniNativeMethods(env, cls, methods, 1);
    if (methods[0].fnPtr && methods[0].fnPtr != hook) {
        *orig_out = methods[0].fnPtr;
        logger::info("PhoneHook", "%s hooked", tag);
    } else {
        logger::info("PhoneHook", "%s not native / skip", tag);
    }
}

void try_hook_telephony_manager(JNIEnv* env) {
    if (!g_api || !env) return;
    const char* tm = "android/telephony/TelephonyManager";
    try_one_jni(env, tm, "getLine1Number", "()Ljava/lang/String;",
                reinterpret_cast<void*>(hook_get_line1_number),
                reinterpret_cast<void**>(&orig_get_line1), "getLine1Number");
    try_one_jni(env, tm, "getLine1Number", "(I)Ljava/lang/String;",
                reinterpret_cast<void*>(hook_get_line1_number_sub),
                reinterpret_cast<void**>(&orig_get_line1_sub), "getLine1Number(sub)");
    try_one_jni(env, tm, "getMsisdn", "(I)Ljava/lang/String;",
                reinterpret_cast<void*>(hook_get_msisdn),
                reinterpret_cast<void**>(&orig_get_msisdn), "getMsisdn");
    try_one_jni(env, tm, "getSubscriberId", "()Ljava/lang/String;",
                reinterpret_cast<void*>(hook_get_subscriber_id),
                reinterpret_cast<void**>(&orig_get_subscriber), "getSubscriberId");
    try_one_jni(env, tm, "getSubscriberId", "(I)Ljava/lang/String;",
                reinterpret_cast<void*>(hook_get_subscriber_id_sub),
                reinterpret_cast<void**>(&orig_get_subscriber_sub), "getSubscriberId(sub)");
    try_one_jni(env, tm, "getSimSerialNumber", "()Ljava/lang/String;",
                reinterpret_cast<void*>(hook_get_sim_serial),
                reinterpret_cast<void**>(&orig_get_iccid), "getSimSerialNumber");
    // SMSTweaks-style SubscriptionInfo.getNumber
    try_one_jni(env, "android/telephony/SubscriptionInfo", "getNumber", "()Ljava/lang/String;",
                reinterpret_cast<void*>(hook_subinfo_get_number),
                reinterpret_cast<void**>(&orig_subinfo_get_number), "SubscriptionInfo.getNumber");
}

void install_impl(JNIEnv* env, zygisk::Api* api, const char* tag) {
    g_api = api;
    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();
    g_spoof_phone = config.resolve_mock_phone();
    if (g_spoof_phone.empty()) {
        g_spoof_phone = config.mock_phone_sim1;
    }

    // Always install JNI hooks in UPI — spoof_active() gates at call time (Save ke baad bhi)
    try_hook_telephony_manager(env);
    logger::info("PhoneHook", "TelephonyManager hooks ready in %s (active=%d phone=%s)",
                 tag ? tag : "?",
                 spoof_active() ? 1 : 0,
                 g_spoof_phone.empty() ? "none" : g_spoof_phone.c_str());
}

struct DeferredPhoneHook {
    JavaVM* vm = nullptr;
    zygisk::Api* api = nullptr;
    std::string tag;
    int delay_sec = 1;
};

void* deferred_phone_hook_worker(void* arg) {
    auto* job = static_cast<DeferredPhoneHook*>(arg);
    if (job && job->delay_sec > 0) sleep(static_cast<unsigned>(job->delay_sec));
    if (job && job->vm && job->api) {
        JNIEnv* env = nullptr;
        if (job->vm->AttachCurrentThread(&env, nullptr) == JNI_OK && env) {
            install_impl(env, job->api, job->tag.c_str());
            job->vm->DetachCurrentThread();
        }
    }
    delete job;
    return nullptr;
}

void start_deferred_phone_hook(JNIEnv* env, zygisk::Api* api, const char* tag, int delay_sec) {
    if (!env || !api) return;
    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK || !vm) return;
    auto* job = new DeferredPhoneHook();
    job->vm = vm;
    job->api = api;
    job->tag = tag ? tag : "";
    job->delay_sec = delay_sec;
    pthread_t t{};
    pthread_create(&t, nullptr, deferred_phone_hook_worker, job);
    pthread_detach(t);
}

}  // namespace

void schedule_deferred_install(JNIEnv* env, zygisk::Api* api, const char* tag, int delay_sec) {
    start_deferred_phone_hook(env, api, tag, delay_sec);
}

void install(JNIEnv* env, zygisk::Api* api, const char* tag) {
    install_impl(env, api, tag);
}

}  // namespace phone_number_hook
