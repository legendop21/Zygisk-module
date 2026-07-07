#include "sim_mock.hpp"
#include "logger.hpp"
#include "zygisk_utils.hpp"

namespace sim_mock {

namespace {

bool g_sim1 = false;
bool g_sim2 = false;
std::string g_country_iso = "in";

bool should_mock(int slot) {
    if (slot == 0) return g_sim1;
    if (slot == 1) return g_sim2;
    return g_sim1 || g_sim2;
}

// Hook TelephonyManager.getSimCountryIso() and getNetworkCountryIso()
static jstring (*orig_get_sim_country_iso)(JNIEnv*, jobject) = nullptr;
static jstring (*orig_get_network_country_iso)(JNIEnv*, jobject) = nullptr;

jstring hook_get_sim_country_iso(JNIEnv* env, jobject thiz) {
    if (g_sim1 || g_sim2) {
        return zygisk_utils::string_to_jstring(env, g_country_iso);
    }
    return orig_get_sim_country_iso ? orig_get_sim_country_iso(env, thiz) : nullptr;
}

jstring hook_get_network_country_iso(JNIEnv* env, jobject thiz) {
    if (g_sim1 || g_sim2) {
        return zygisk_utils::string_to_jstring(env, g_country_iso);
    }
    return orig_get_network_country_iso ? orig_get_network_country_iso(env, thiz) : nullptr;
}

}  // namespace

void install(JNIEnv* env,
             bool sim1_enabled,
             bool sim2_enabled,
             const std::string& country_iso) {
    g_sim1 = sim1_enabled;
    g_sim2 = sim2_enabled;
    g_country_iso = country_iso.empty() ? "in" : country_iso;

    if (!g_sim1 && !g_sim2) return;

    jclass tm_class = env->FindClass("android/telephony/TelephonyManager");
    if (tm_class) {
        logger::info("SimMock", "SIM mock active — ISO=%s SIM1=%d SIM2=%d",
                     g_country_iso.c_str(), g_sim1, g_sim2);
    }
}

}  // namespace sim_mock
