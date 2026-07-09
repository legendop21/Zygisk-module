#include "virtual_sim.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "plt_hook.hpp"
#include "sim_mock.hpp"
#include "telephony_spoof.hpp"
#include "zygisk_utils.hpp"

#include <pthread.h>
#include <cstdio>
#include <sys/stat.h>
#include <unistd.h>

namespace virtual_sim {

namespace {

zygisk::Api* g_api = nullptr;
std::string g_process;

static jint (*orig_BinderProxy_transact)(JNIEnv*, jobject, jint, jobject, jobject, jint) = nullptr;

jint hook_BinderProxy_transact(JNIEnv* env, jobject thiz, jint code, jobject data, jobject reply,
                               jint flags) {
    const jint result =
        orig_BinderProxy_transact ? orig_BinderProxy_transact(env, thiz, code, data, reply, flags)
                                  : -1;

    if (result == 0 && reply && telephony_spoof::phone_spoof_enabled()) {
        const std::string iface = data ? telephony_spoof::read_binder_interface(env, data) : "";
        const auto profiles = telephony_spoof::load_subscriber_profiles();
        if (telephony_spoof::is_subscription_binder_interface(iface)) {
            if (telephony_spoof::inject_subscription_if_empty(env, reply, profiles)) {
                return result;
            }
        }
        if (iface.empty() || telephony_spoof::is_telephony_binder_interface(iface)) {
            telephony_spoof::scrub_reply_parcel(env, reply, profiles, iface);
        }
    }
    return result;
}

bool install_binder_plt(zygisk::Api* api) {
    if (!api) return false;
    static bool committed = false;
    if (committed) return true;

    plt_hook::set_api(api);
    const bool reg = plt_hook::register_regex(".*/libandroid_runtime\\.so$",
                                              "Java_android_os_BinderProxy_transact",
                                              reinterpret_cast<void*>(hook_BinderProxy_transact),
                                              reinterpret_cast<void**>(&orig_BinderProxy_transact));
    if (!reg || !plt_hook::commit()) return false;
    committed = true;
    logger::info("VirtualSim", "Binder telephony spoof active in %s", g_process.c_str());
    return true;
}

struct DeferredBinder {
    zygisk::Api* api = nullptr;
    int delay_sec = 1;
};

void* deferred_binder_worker(void* arg) {
    auto* job = static_cast<DeferredBinder*>(arg);
    if (job && job->delay_sec > 0) sleep(static_cast<unsigned>(job->delay_sec));
    if (job && job->api) {
        if (!install_binder_plt(job->api)) {
            sleep(2);
            install_binder_plt(job->api);
        }
    }
    delete job;
    return nullptr;
}

void schedule_deferred_binder(zygisk::Api* api, int delay_sec) {
    auto* job = new DeferredBinder();
    job->api = api;
    job->delay_sec = delay_sec;
    pthread_t t{};
    pthread_create(&t, nullptr, deferred_binder_worker, job);
    pthread_detach(t);
}

void write_status(const ModuleConfig& config) {
    FILE* f = fopen("/data/local/tmp/hivirtus_virtual_sim.json", "w");
    if (!f) return;
    fprintf(f,
            "{\n"
            "  \"active\": true,\n"
            "  \"sim1\": { \"enabled\": %s, \"phone\": \"%s\", \"operator\": \"%s\", "
            "\"numeric\": \"%s\", \"imsi\": \"%s\", \"iccid\": \"%s\" },\n"
            "  \"sim2\": { \"enabled\": %s, \"phone\": \"%s\", \"operator\": \"%s\", "
            "\"numeric\": \"%s\", \"imsi\": \"%s\", \"iccid\": \"%s\" }\n"
            "}\n",
            (config.enable_sim1_mock || config.enable_phone_spoof) ? "true" : "false",
            config.mock_phone_sim1.c_str(), config.mock_operator_name_sim1.c_str(),
            config.mock_operator_numeric_sim1.c_str(), config.mock_imsi_sim1.c_str(),
            config.mock_iccid_sim1.c_str(),
            config.enable_sim2_mock ? "true" : "false", config.mock_phone_sim2.c_str(),
            config.mock_operator_name_sim2.c_str(), config.mock_operator_numeric_sim2.c_str(),
            config.mock_imsi_sim2.c_str(), config.mock_iccid_sim2.c_str());
    fclose(f);
    chmod("/data/local/tmp/hivirtus_virtual_sim.json", 0644);
}

bool is_telephony_process(const std::string& process) {
    return process == "com.android.phone" || process == "com.android.providers.telephony";
}

bool is_gms_process(const std::string& process) {
    return process == "com.google.android.gms" || process == "com.google.android.gms.persistent";
}

}  // namespace

void install(JNIEnv* env, zygisk::Api* api, const std::string& process_name) {
    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();
    if (!config.virtual_sim_active()) return;

    g_api = api;
    g_process = process_name;
    write_status(config);

    sim_mock::install(env,
                      api,
                      config.enable_sim1_mock || config.enable_phone_spoof,
                      config.enable_sim2_mock,
                      config.mock_country_iso,
                      true,
                      config.mock_phone_sim1,
                      config.mock_phone_sim2);

    if (is_telephony_process(process_name)) {
        install_binder_plt(api);
        logger::info("VirtualSim", "Dual virtual SIM ready in telephony (%s)", process_name.c_str());
        return;
    }

    if (is_gms_process(process_name)) {
        schedule_deferred_binder(api, 2);
        logger::info("VirtualSim", "Deferred binder spoof scheduled for GMS (2s)");
        return;
    }

    // Groww/UPI — turant binder hook (1s delay se pehle SIM check ho jata tha)
    if (!install_binder_plt(api)) {
        schedule_deferred_binder(api, 1);
    }
    logger::info("VirtualSim", "Virtual SIM binder active for %s", process_name.c_str());
}

}  // namespace virtual_sim
