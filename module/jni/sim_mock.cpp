#include "sim_mock.hpp"
#include "logger.hpp"
#include "zygisk_utils.hpp"

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstring>
#include <sys/stat.h>

namespace sim_mock {

namespace {

zygisk::Api* g_api = nullptr;
bool g_sim1 = false;
bool g_sim2 = false;
bool g_phone_spoof = false;
std::string g_country_iso = "in";
std::string g_phone_sim1;
std::string g_phone_sim2;

std::string active_phone() {
    if (!g_phone_spoof) return "";
    if (g_sim1 && !g_phone_sim1.empty()) return g_phone_sim1;
    if (g_sim2 && !g_phone_sim2.empty()) return g_phone_sim2;
    if (!g_phone_sim1.empty()) return g_phone_sim1;
    if (!g_phone_sim2.empty()) return g_phone_sim2;
    return "";
}

void write_spoof_status(const std::string& phone) {
    if (phone.empty()) return;
    FILE* f = fopen("/data/local/tmp/hivirtus_spoof_phone.txt", "w");
    if (f) {
        fprintf(f, "%s\n", phone.c_str());
        fclose(f);
        chmod("/data/local/tmp/hivirtus_spoof_phone.txt", 0644);
    }
}

bool should_spoof_property_key(const std::string& key) {
    std::string lower = key;
    std::transform(lower.begin(), lower.end(), lower.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    if (lower.find("line1") != std::string::npos) return true;
    if (lower.find("phone") != std::string::npos) return true;
    if (lower.find("msisdn") != std::string::npos) return true;
    if (lower.find("subscriber") != std::string::npos) return true;
    if (lower.find("simnum") != std::string::npos) return true;
    if (lower.find("phone_number") != std::string::npos) return true;
    if (lower.find("operator.numeric") != std::string::npos) return true;
    if (lower.find("operator.alpha") != std::string::npos) return true;
    if (lower.find("icc_id") != std::string::npos) return true;
    if (lower.find("imsi") != std::string::npos) return true;
    return false;
}

bool should_spoof_country_key(const std::string& key) {
    std::string lower = key;
    std::transform(lower.begin(), lower.end(), lower.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return lower.find("country") != std::string::npos && lower.find("iso") != std::string::npos;
}

static jstring (*orig_SystemProperties_get)(JNIEnv*, jclass, jstring, jstring) = nullptr;

jstring hook_SystemProperties_get(JNIEnv* env, jclass clazz, jstring key_j, jstring def_j) {
    const std::string key = zygisk_utils::jstring_to_string(env, key_j);
    const std::string phone = active_phone();

    if (g_phone_spoof && !phone.empty() && should_spoof_property_key(key)) {
        logger::info("SimMock", "Spoof prop %s -> %s", key.c_str(), phone.c_str());
        return zygisk_utils::string_to_jstring(env, phone);
    }

    if (g_phone_spoof && !g_country_iso.empty() && should_spoof_country_key(key)) {
        return zygisk_utils::string_to_jstring(env, g_country_iso);
    }

    std::string android_id;
    FILE* idf = fopen("/data/local/tmp/hivirtus_spoof_android_id.txt", "r");
    if (idf) {
        char buf[64] = {};
        if (fgets(buf, sizeof(buf), idf)) android_id = buf;
        fclose(idf);
        if (!android_id.empty() && android_id.back() == '\n') android_id.pop_back();
    }
    if (!android_id.empty()) {
        std::string lower = key;
        std::transform(lower.begin(), lower.end(), lower.begin(),
                       [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
        if (lower.find("android_id") != std::string::npos) {
            return zygisk_utils::string_to_jstring(env, android_id);
        }
    }

    return orig_SystemProperties_get ? orig_SystemProperties_get(env, clazz, key_j, def_j)
                                     : def_j;
}

static jlong (*orig_SystemProperties_getLong)(JNIEnv*, jclass, jstring, jlong) = nullptr;

jlong hook_SystemProperties_getLong(JNIEnv* env, jclass clazz, jstring key_j, jlong def) {
    const std::string key = zygisk_utils::jstring_to_string(env, key_j);
    const std::string phone = active_phone();
    if (g_phone_spoof && !phone.empty() && should_spoof_property_key(key)) {
        std::string digits;
        for (char c : phone) {
            if (std::isdigit(static_cast<unsigned char>(c))) digits += c;
        }
        if (!digits.empty()) {
            return static_cast<jlong>(strtoll(digits.c_str(), nullptr, 10));
        }
    }
    return orig_SystemProperties_getLong ? orig_SystemProperties_getLong(env, clazz, key_j, def) : def;
}

void install_system_properties_hooks(JNIEnv* env) {
    if (!g_api) return;

    JNINativeMethod get_methods[] = {
        {"get", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
         reinterpret_cast<void*>(hook_SystemProperties_get)},
    };
    g_api->hookJniNativeMethods(env, "android/os/SystemProperties", get_methods, 1);
    orig_SystemProperties_get = reinterpret_cast<decltype(orig_SystemProperties_get)>(get_methods[0].fnPtr);

    JNINativeMethod long_methods[] = {
        {"getLong", "(Ljava/lang/String;J)J", reinterpret_cast<void*>(hook_SystemProperties_getLong)},
    };
    g_api->hookJniNativeMethods(env, "android/os/SystemProperties", long_methods, 1);
    orig_SystemProperties_getLong =
        reinterpret_cast<decltype(orig_SystemProperties_getLong)>(long_methods[0].fnPtr);

    logger::info("SimMock", "SystemProperties phone spoof hooks installed");
}

}  // namespace

void install(JNIEnv* env,
             zygisk::Api* api,
             bool sim1_enabled,
             bool sim2_enabled,
             const std::string& country_iso,
             bool phone_spoof_enabled,
             const std::string& phone_sim1,
             const std::string& phone_sim2) {
    g_api = api;
    g_sim1 = sim1_enabled;
    g_sim2 = sim2_enabled;
    g_phone_spoof = phone_spoof_enabled || sim1_enabled || sim2_enabled;
    g_country_iso = country_iso.empty() ? "in" : country_iso;
    g_phone_sim1 = phone_sim1;
    g_phone_sim2 = phone_sim2;

    if (!g_phone_spoof) return;

    const std::string phone = active_phone();
    if (!phone.empty()) write_spoof_status(phone);

    install_system_properties_hooks(env);

    logger::info("SimMock",
                 "Phone spoof active ISO=%s SIM1=%d SIM2=%d Phone=%s",
                 g_country_iso.c_str(), g_sim1, g_sim2,
                 phone.empty() ? "none" : phone.c_str());
}

}  // namespace sim_mock
