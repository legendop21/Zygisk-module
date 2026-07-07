#include "sim_mock.hpp"
#include "logger.hpp"
#include "zygisk_utils.hpp"

#include <cstdio>
#include <sys/stat.h>

namespace sim_mock {

namespace {

bool g_sim1 = false;
bool g_sim2 = false;
bool g_phone_spoof = false;
std::string g_country_iso = "in";
std::string g_phone_sim1;
std::string g_phone_sim2;

std::string active_phone() {
    if (g_phone_spoof) {
        if (g_sim1 && !g_phone_sim1.empty()) return g_phone_sim1;
        if (g_sim2 && !g_phone_sim2.empty()) return g_phone_sim2;
        if (!g_phone_sim1.empty()) return g_phone_sim1;
        if (!g_phone_sim2.empty()) return g_phone_sim2;
    }
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

}  // namespace

void install(JNIEnv* env,
             bool sim1_enabled,
             bool sim2_enabled,
             const std::string& country_iso,
             bool phone_spoof_enabled,
             const std::string& phone_sim1,
             const std::string& phone_sim2) {
    g_sim1 = sim1_enabled;
    g_sim2 = sim2_enabled;
    g_phone_spoof = phone_spoof_enabled;
    g_country_iso = country_iso.empty() ? "in" : country_iso;
    g_phone_sim1 = phone_sim1;
    g_phone_sim2 = phone_sim2;

    if (!g_sim1 && !g_sim2 && !g_phone_spoof) return;

    jclass tm_class = env->FindClass("android/telephony/TelephonyManager");
    if (tm_class) {
        const std::string phone = active_phone();
        if (!phone.empty()) write_spoof_status(phone);

        logger::info("SimMock",
                     "SIM mock ISO=%s SIM1=%d SIM2=%d PhoneSpoof=%d Phone=%s",
                     g_country_iso.c_str(), g_sim1, g_sim2, g_phone_spoof,
                     phone.empty() ? "none" : phone.c_str());
    }
}

}  // namespace sim_mock
