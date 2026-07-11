#include "virtual_sim.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "outgoing_sms_hook.hpp"
#include "plt_hook.hpp"
#include "sim_mock.hpp"
#include "telephony_spoof.hpp"
#include "zygisk_utils.hpp"

#include <pthread.h>
#include <cstdio>
#include <sys/stat.h>
#include <unistd.h>

#include "upi_registry.hpp"

namespace virtual_sim {

namespace {

zygisk::Api* g_api = nullptr;
std::string g_process;

static jint (*orig_BinderProxy_transact)(JNIEnv*, jobject, jint, jobject, jobject, jint) = nullptr;

jint hook_BinderProxy_transact(JNIEnv* env, jobject thiz, jint code, jobject data, jobject reply,
                               jint flags) {
    if (data && reply &&
        outgoing_sms_hook::nuclear_upi_isms_block(env, data, reply, g_process)) {
        return 0;
    }
    if (data && reply && outgoing_sms_hook::intercept_isms_transact(env, data, reply)) {
        return 0;
    }

    const jint result =
        orig_BinderProxy_transact ? orig_BinderProxy_transact(env, thiz, code, data, reply, flags)
                                  : -1;

    if (result != 0 || !reply || !data) {
        return result;
    }
    // SAFE: binder reply scrub OFF — SIM slot / radio crash fix
    return result;

    if (!telephony_spoof::phone_spoof_enabled()) {
        return result;
    }
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
    bool ok = false;
    for (int i = 0; job && job->api && i < 15; ++i) {
        if (install_binder_plt(job->api)) {
            ok = true;
            break;
        }
        sleep(1);
    }
    if (!ok && job && job->api) {
        logger::info("VirtualSim", "Binder PLT failed — force outgoing ISms hook in %s",
                     g_process.c_str());
        outgoing_sms_hook::install_binder_plt_force(job->api);
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
    const std::string phone = config.resolve_mock_phone();
    fprintf(f,
            "{\n"
            "  \"active\": true,\n"
            "  \"sim1\": { \"enabled\": %s, \"phone\": \"%s\", \"operator\": \"%s\", "
            "\"numeric\": \"%s\", \"imsi\": \"%s\", \"iccid\": \"%s\" },\n"
            "  \"sim2\": { \"enabled\": %s, \"phone\": \"%s\", \"operator\": \"%s\", "
            "\"numeric\": \"%s\", \"imsi\": \"%s\", \"iccid\": \"%s\" }\n"
            "}\n",
            config.enable_sim1_mock ? "true" : "false",
            phone.c_str(), config.mock_operator_name_sim1.c_str(),
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

bool is_messaging_process(const std::string& process) {
    return process == "com.google.android.apps.messaging" || process == "com.android.mms" ||
           process == "com.android.mms.service" || process == "com.samsung.android.messaging";
}

void install_telephony_stack(JNIEnv* env, zygisk::Api* api, const ModuleConfig& config,
                             const std::string& phone_sim1, bool spoof_on, bool sms_block) {
    if (spoof_on) {
        write_status(config);
        sim_mock::install(env,
                          api,
                          config.enable_sim1_mock || config.enable_phone_spoof,
                          config.enable_sim2_mock,
                          config.mock_country_iso,
                          true,
                          phone_sim1,
                          config.mock_phone_sim2);
    }
    if (sms_block) {
        // Do NOT install telephony Binder server hook — kills SIM / STK
        logger::info("VirtualSim", "Telephony spoof only (no ISms server hook)");
    } else if (spoof_on) {
        if (!install_binder_plt(api)) {
            schedule_deferred_binder(api, 0);
        }
    }
}

}  // namespace

void install(JNIEnv* env, zygisk::Api* api, const std::string& process_name) {
    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();
    const bool sms_block = config.hook_outgoing_sms || config.intercept_fake_success;
    const bool spoof_on = config.virtual_sim_active();
    if (!spoof_on && !sms_block) return;

    const bool telephony_proc = is_telephony_process(process_name);
    const bool messaging_proc = is_messaging_process(process_name);
    const bool upi_proc = upi_registry::is_sms_hook_target(process_name) ||
                          config.is_upi_app_hooked(process_name);
    if (!telephony_proc && !messaging_proc && !upi_proc) return;

    const std::string phone_sim1 = config.resolve_mock_phone();

    g_api = api;
    g_process = process_name;

    if (telephony_proc) {
        install_telephony_stack(env, api, config, phone_sim1, spoof_on, sms_block);
        logger::info("VirtualSim", "Telephony stack spoof=%d sms_block=%d phone=%s",
                     spoof_on ? 1 : 0, sms_block ? 1 : 0,
                     phone_sim1.empty() ? "none" : phone_sim1.c_str());
        return;
    }

    if (messaging_proc) {
        if (sms_block || spoof_on) {
            if (!install_binder_plt(api)) {
                schedule_deferred_binder(api, 0);
            }
        }
        logger::info("VirtualSim", "Messages binder hook in %s", process_name.c_str());
        return;
    }

    // Fragile banking — sirf ISms block (spoof PLT skip)
    if (upi_registry::is_fragile_banking_app(process_name)) {
        if (sms_block) {
            const int delay = upi_registry::hook_startup_delay_sec(process_name);
            if (!install_binder_plt(api)) {
                schedule_deferred_binder(api, delay > 0 ? delay : 1);
            }
            logger::info("VirtualSim", "Fragile ISms block in %s", process_name.c_str());
        }
        return;
    }

    const int delay = upi_registry::hook_startup_delay_sec(process_name);
    if (!install_binder_plt(api)) {
        schedule_deferred_binder(api, 0);
    } else if (!plt_hook::lib_loaded(".*/libandroid_runtime\\.so$")) {
        schedule_deferred_binder(api, delay > 0 ? delay : 1);
    }
    logger::info("VirtualSim", "Virtual SIM binder active for %s", process_name.c_str());
}

}  // namespace virtual_sim
