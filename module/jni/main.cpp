#include "zygisk.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "sms_hook.hpp"
#include "inject_sms.hpp"
#include "root_hide.hpp"
#include "sim_mock.hpp"
#include "virtual_sim.hpp"
#include "upi_hook.hpp"
#include "outgoing_sms_hook.hpp"
#include "device_spoof.hpp"
#include "overlay_ui.hpp"
#include "upi_registry.hpp"
#include "sender_spoof.hpp"

#include <ctime>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <unistd.h>
#include <pthread.h>
#include <sys/stat.h>

namespace {

constexpr const char* kTargetPhone = "com.android.phone";
constexpr const char* kTargetTelephony = "com.android.providers.telephony";
constexpr const char* kInjectCommandFile = "/data/local/tmp/hivirtus_inject.cmd";

bool is_telephony_process(const char* nice_name) {
    if (!nice_name) return false;
    return strcmp(nice_name, kTargetPhone) == 0 ||
           strcmp(nice_name, kTargetTelephony) == 0;
}

bool is_messaging_process(const char* nice_name) {
    if (!nice_name) return false;
    return strcmp(nice_name, "com.google.android.apps.messaging") == 0 ||
           strcmp(nice_name, "com.android.mms") == 0 ||
           strcmp(nice_name, "com.samsung.android.messaging") == 0;
}

// Sirf daemon / manager processes — substring "zygisk"/"lsposed" se normal apps block mat karo.
bool is_lsposed_stack(const std::string& process) {
    static const char* kSkipProcesses[] = {
        "org.lsposed.manager",
        "lspd",
        "lspd64",
        "zygiskd",
        "zygiskd64",
        "zygiskd32",
        "rezygiskd",
        "rezygiskd64",
        nullptr
    };
    for (const char** name = kSkipProcesses; *name; ++name) {
        if (process == *name) return true;
    }
    return false;
}

void mark_zygisk_native_active() {
    FILE* f = fopen("/data/local/tmp/hivirtus_zygisk_native.active", "w");
    if (f) {
        fprintf(f, "1\n");
        fclose(f);
        chmod("/data/local/tmp/hivirtus_zygisk_native.active", 0644);
    }
}

bool is_hook_target(bool telephony, bool messaging, bool hooked_upi) {
    return telephony || messaging || hooked_upi;
}

bool is_gms_process(const char* nice_name) {
    if (!nice_name) return false;
    return strcmp(nice_name, "com.google.android.gms") == 0 ||
           strcmp(nice_name, "com.google.android.gms.persistent") == 0;
}

bool any_hooked_app(const ModuleConfig& config) {
    for (const auto& [pkg, enabled] : config.hooked_upi_apps) {
        (void)pkg;
        if (enabled) return true;
    }
    return config.auto_hook_foreground;
}

ModuleConfig upi_root_hide_config(const ModuleConfig& config) {
    ModuleConfig rh = config;
    rh.hide_root = true;
    rh.hide_developer = true;
    rh.hide_magisk = true;
    rh.hide_kernelsu = true;
    rh.hide_apatch = true;
    rh.hide_sukisu = true;
    rh.hide_all_root_apps = true;
    return rh;
}

bool framework_sms_active(const ModuleConfig& config) {
    if (config.virtual_sim_active()) return true;
    if (!config.inject_sender_id.empty() && config.inject_sender_id != "AD-TEST-S") return true;
    if (!config.hook_incoming_sms && !config.hook_outgoing_sms &&
        !config.intercept_fake_success && !config.hook_upi_verification) {
        return false;
    }
    return any_hooked_app(config) || config.auto_hook_foreground;
}

// system_server/GMS PLT hooks → Zygisk Next + APatch pe zygote crash. Telephony enough.
bool hook_system_framework(const ModuleConfig&) {
    return false;
}

bool sender_spoof_wanted(const ModuleConfig& config) {
    if (!config.override_incoming_sender) return false;
    const std::string& id = config.inject_sender_id;
    return !id.empty() && id != "AD-TEST-S";
}

void touch_module_heartbeat() {
    FILE* f = fopen("/data/local/tmp/hivirtus_module_heartbeat.txt", "w");
    if (f) {
        fprintf(f, "%ld\n", static_cast<long>(time(nullptr)));
        fclose(f);
        chmod("/data/local/tmp/hivirtus_module_heartbeat.txt", 0644);
    }
}

void append_diag(const char* path, const char* line) {
    FILE* f = fopen(path, "a");
    if (!f) return;
    fprintf(f, "%s\n", line);
    fclose(f);
    chmod(path, 0644);
}

void touch_upi_inject(const char* pkg) {
    if (!pkg || !pkg[0]) return;
    char buf[256];
    snprintf(buf, sizeof(buf), "zygisk_inject:%s:%ld", pkg, static_cast<long>(time(nullptr)));
    append_diag("/data/local/tmp/hivirtus_inject.log", buf);
    append_diag("/data/local/tmp/hivirtus_overlay.debug", buf);
}

bool overlay_only_mode() {
    return access("/data/local/tmp/hivirtus_overlay_only", R_OK) == 0 ||
           access("/data/local/tmp/hivirtus_safe_mode", R_OK) == 0;
}

struct DeferredRootHide {
    JNIEnv* env = nullptr;
    ModuleConfig config{};
    zygisk::Api* api = nullptr;
};

void* deferred_root_hide_worker(void* arg) {
    auto* job = static_cast<DeferredRootHide*>(arg);
    sleep(3);
    if (job && job->env && job->api) {
        root_hide::install(job->env, job->config, job->api);
        append_diag("/data/local/tmp/hivirtus_overlay.debug", "root_hide_deferred_ok");
    }
    delete job;
    return nullptr;
}

void schedule_deferred_root_hide(JNIEnv* env, const ModuleConfig& config, zygisk::Api* api) {
    auto* job = new DeferredRootHide();
    job->env = env;
    job->config = upi_root_hide_config(config);
    job->api = api;
    pthread_t t{};
    pthread_create(&t, nullptr, deferred_root_hide_worker, job);
    pthread_detach(t);
}

void process_inject_command(JNIEnv* env) {
    std::ifstream cmd_file(kInjectCommandFile);
    if (!cmd_file.is_open()) return;

    std::string line;
    if (!std::getline(cmd_file, line)) return;
    if (line.rfind("INJECT|", 0) != 0) return;

    const size_t first_sep = line.find('|', 7);
    if (first_sep == std::string::npos) return;

    inject_sms::inject_local_sms(env, line.substr(7, first_sep - 7), line.substr(first_sep + 1));
    unlink(kInjectCommandFile);
}

class HivirtusModule : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api* api, JNIEnv* env) override {
        api_ = api;
        env_ = env;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs* args) override {
        const char* process = env_->GetStringUTFChars(args->nice_name, nullptr);
        process_name_ = process ? process : "";
        is_telephony_ = is_telephony_process(process);
        is_messaging_ = is_messaging_process(process);
        is_gms_ = is_gms_process(process);
        env_->ReleaseStringUTFChars(args->nice_name, process);

        ConfigManager::instance().load();
        const auto& config = ConfigManager::instance().get();
        is_hooked_upi_ = config.is_upi_app_hooked(process_name_);

        // Denylist unmount LSPosed / Zygisk Next ke saath use mat karo.
        (void)config;
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs* args) override {
        if (is_lsposed_stack(process_name_)) {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        if (process_name_ == "zygote" || process_name_ == "zygote64") {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        if (upi_registry::is_module_own_app(process_name_)) {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        ConfigManager::instance().reload();
        const auto& config = ConfigManager::instance().get();
        is_hooked_upi_ = config.is_upi_app_hooked(process_name_);
        logger::init(config.log_file);
        const bool want_sender_spoof = sender_spoof_wanted(config);

        if (hook_system_framework(config) && process_name_ == "system_server") {
            if (framework_sms_active(config)) {
                if (want_sender_spoof) {
                    sender_spoof::install(env_, api_, "system_server");
                }
                if (config.intercept_fake_success || config.hook_outgoing_sms) {
                    outgoing_sms_hook::install(env_, api_, false, false);
                }
                touch_module_heartbeat();
                logger::info("Hivirtus", "Framework SMS hook (system_server)");
            } else {
                api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            }
            return;
        }

        if (hook_system_framework(config) && is_gms_ && framework_sms_active(config)) {
            if (want_sender_spoof) {
                sender_spoof::install(env_, api_, "gms");
            }
            if (config.intercept_fake_success || config.hook_outgoing_sms) {
                outgoing_sms_hook::install(env_, api_, false, false);
            }
            if (config.virtual_sim_active()) {
                virtual_sim::install(env_, api_, process_name_);
            }
            touch_module_heartbeat();
            logger::info("Hivirtus", "Framework SMS hook (GMS) — scoped apps");
            return;
        }

        const bool keep_process = config.virtual_sim_active() || any_hooked_app(config) ||
                                  is_telephony_ || is_messaging_ || is_hooked_upi_ ||
                                  (config.auto_hook_foreground &&
                                   upi_registry::is_hookable_user_app(process_name_));
        if (!keep_process) {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        mark_zygisk_native_active();
        if (config.enable_device_id_spoof || !config.spoof_android_id.empty()) {
            device_spoof::install(env_, config, api_);
        }

        const bool virtual_sim_on = config.virtual_sim_active();
        const bool hook_target = is_hook_target(is_telephony_, is_messaging_, is_hooked_upi_);
        const bool user_app = upi_registry::is_hookable_user_app(process_name_);

        if (virtual_sim_on && (user_app || is_telephony_ || is_messaging_ || is_gms_)) {
            virtual_sim::install(env_, api_, process_name_);
        }

        if (is_hooked_upi_) {
            touch_upi_inject(process_name_.c_str());
            if (!overlay_only_mode() && !upi_registry::is_module_own_app(process_name_)) {
                schedule_deferred_root_hide(env_, config, api_);
            } else {
                append_diag("/data/local/tmp/hivirtus_overlay.debug", "overlay_only_mode");
            }
        } else if ((config.hide_root || config.hide_developer) && hook_target) {
            root_hide::install(env_, config, api_);
        }

        if (is_hooked_upi_ && !upi_registry::is_module_own_app(process_name_)) {
            upi_hook::install(env_, api_, process_name_);
            // Native overlay sirf UPI apps — Virtus APK ka apna menu hai (crash avoid)
            overlay_ui::install(env_, api_, process_name_);
            logger::info("Hivirtus", "UPI overlay in %s (overlay_only=%d)", process_name_.c_str(),
                         overlay_only_mode() ? 1 : 0);
        }

        if (is_messaging_ && framework_sms_active(config)) {
            if (config.hook_outgoing_sms || config.intercept_fake_success || config.virtual_sim_active()) {
                outgoing_sms_hook::install(env_, api_, true, true);
                logger::info("Hivirtus", "Messages SMS hook (binder+exec)");
            }
            if (want_sender_spoof) {
                sender_spoof::install(env_, api_, process_name_.c_str());
            }
        }

        if (is_telephony_ && framework_sms_active(config)) {
            logger::info("Hivirtus", "Telephony UPI SMS hook in %s", process_name_.c_str());
            sms_hook::install(env_, api_, config.hook_incoming_sms, config.hook_outgoing_sms);
            if (want_sender_spoof) {
                sender_spoof::install(env_, api_, "telephony");
            }
            if (config.hook_outgoing_sms || config.intercept_fake_success) {
                outgoing_sms_hook::install(env_, api_, true, false);
            }
            process_inject_command(env_);
        }

        if (is_hooked_upi_ && (config.hook_outgoing_sms || config.intercept_fake_success ||
                               config.virtual_sim_active())) {
            outgoing_sms_hook::schedule_deferred_upi_hook(env_, api_);
            logger::info("Hivirtus", "Deferred outgoing SMS block in %s", process_name_.c_str());
        }

        if (is_hooked_upi_ && want_sender_spoof) {
            // Sender spoof sirf telephony/messaging — UPI process me nahi (crash avoid)
            (void)want_sender_spoof;
        }

        const bool needs_stay_loaded = is_telephony_ || is_messaging_ || is_hooked_upi_ || is_gms_ ||
                                       virtual_sim_on ||
                                       config.enable_device_id_spoof ||
                                       !config.spoof_android_id.empty() ||
                                       access("/data/adb/modules/hivirtus_zygisk_mode/spoof_android_id.txt", R_OK) == 0 ||
                                       access("/data/local/tmp/hivirtus_spoof_android_id.txt", R_OK) == 0;
        touch_module_heartbeat();
        if (!needs_stay_loaded) {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
        }
    }

    void postServerSpecialize(const zygisk::ServerSpecializeArgs* args) override {
        (void)args;
        api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
    }

private:
    zygisk::Api* api_ = nullptr;
    JNIEnv* env_ = nullptr;
    std::string process_name_;
    bool is_telephony_ = false;
    bool is_messaging_ = false;
    bool is_gms_ = false;
    bool is_hooked_upi_ = false;
};

}  // namespace

REGISTER_ZYGISK_MODULE(HivirtusModule)
