#include "zygisk.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "sms_hook.hpp"
#include "inject_sms.hpp"
#include "root_hide.hpp"
#include "sim_mock.hpp"
#include "upi_hook.hpp"
#include "outgoing_sms_hook.hpp"
#include "device_spoof.hpp"
#include "overlay_ui.hpp"
#include "upi_registry.hpp"
#include "sender_spoof.hpp"

#include <ctime>
#include <cstring>
#include <fstream>
#include <string>
#include <unistd.h>
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

bool framework_sms_active(const ModuleConfig& config) {
    if (!config.hook_incoming_sms && !config.hook_outgoing_sms &&
        !config.intercept_fake_success && !config.hook_upi_verification) {
        return false;
    }
    return any_hooked_app(config);
}

bool sender_spoof_wanted(const ModuleConfig& config) {
    if (!any_hooked_app(config)) return false;
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

        ConfigManager::instance().reload();
        const auto& config = ConfigManager::instance().get();
        logger::init(config.log_file);
        const bool want_sender_spoof = sender_spoof_wanted(config);

        // LSPosed "System Framework" — sirf jab UPI tab me app select ho
        if (process_name_ == "system_server") {
            if (framework_sms_active(config)) {
                if (want_sender_spoof) {
                    sender_spoof::install(env_, api_, "system_server");
                }
                if (config.intercept_fake_success || config.hook_outgoing_sms) {
                    outgoing_sms_hook::install(env_, api_, false, false);
                }
                touch_module_heartbeat();
                logger::info("Hivirtus", "Framework SMS hook (system_server) — scoped apps");
            } else {
                api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            }
            return;
        }

        if (is_gms_ && framework_sms_active(config)) {
            if (want_sender_spoof) {
                sender_spoof::install(env_, api_, "gms");
            }
            if (config.intercept_fake_success || config.hook_outgoing_sms) {
                outgoing_sms_hook::install(env_, api_, false, false);
            }
            touch_module_heartbeat();
            logger::info("Hivirtus", "Framework SMS hook (GMS) — scoped apps");
            return;
        }

        if (!any_hooked_app(config) && !is_telephony_ && !is_messaging_ && !is_hooked_upi_) {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        mark_zygisk_native_active();
        device_spoof::install(env_, config, api_);

        const bool hook_target = is_hook_target(is_telephony_, is_messaging_, is_hooked_upi_);

        if ((config.hide_root || config.hide_developer) && hook_target) {
            root_hide::install(env_, config, api_);
        }

        const bool phone_active = config.enable_phone_spoof || config.enable_sim1_mock ||
                                  config.enable_sim2_mock;

        if (phone_active && hook_target) {
            sim_mock::install(env_,
                              api_,
                              config.enable_sim1_mock || config.enable_phone_spoof,
                              config.enable_sim2_mock,
                              config.mock_country_iso,
                              phone_active,
                              config.mock_phone_sim1,
                              config.mock_phone_sim2);
        }

        if (phone_active && hook_target) {
            outgoing_sms_hook::install(env_, api_, is_telephony_, is_hooked_upi_);
            if (hook_target) {
                logger::info("Hivirtus", "Phone spoof binder hooks in %s (upi=%d)",
                             process_name_.c_str(), is_hooked_upi_);
            }
        }

        if (is_hooked_upi_) {
            upi_hook::install(env_, api_, process_name_);
            outgoing_sms_hook::install(env_, api_, false, true);
            overlay_ui::install(env_, api_, process_name_);
            logger::info("Hivirtus", "UPI auto-hook + overlay in %s", process_name_.c_str());
        }

        if (is_messaging_ && framework_sms_active(config)) {
            if (config.hook_outgoing_sms || config.intercept_fake_success) {
                if (!phone_active) {
                    outgoing_sms_hook::install(env_, api_, false, true);
                }
                logger::info("Hivirtus", "Messages SMS hook (scoped)");
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
            if (!phone_active) {
                outgoing_sms_hook::install(env_, api_, true, false);
            }
            process_inject_command(env_);
        }

        if (is_hooked_upi_ && want_sender_spoof) {
            sender_spoof::install(env_, api_, process_name_.c_str());
        }

        const bool needs_stay_loaded = is_telephony_ || is_messaging_ || is_hooked_upi_ || is_gms_ ||
                                       config.enable_device_id_spoof ||
                                       !config.spoof_android_id.empty() ||
                                       access("/data/adb/modules/hivirtus_zygisk_mode/spoof_android_id.txt", R_OK) == 0 ||
                                       access("/data/local/tmp/hivirtus_spoof_android_id.txt", R_OK) == 0;
        touch_module_heartbeat();
        if (!needs_stay_loaded) {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
        }
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
