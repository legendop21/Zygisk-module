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
        env_->ReleaseStringUTFChars(args->nice_name, process);

        ConfigManager::instance().load();
        const auto& config = ConfigManager::instance().get();
        is_hooked_upi_ = config.is_upi_app_hooked(process_name_);

        if (config.hide_root) {
            api_->setOption(zygisk::Option::FORCE_DENYLIST_UNMOUNT);
        }
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs* args) override {
        ConfigManager::instance().reload();
        const auto& config = ConfigManager::instance().get();
        logger::init(config.log_file);

        device_spoof::install(env_, config, api_);

        if (config.hide_root || config.hide_developer) {
            root_hide::install(env_, config, api_);
        }

        const bool phone_active = config.enable_phone_spoof || config.enable_sim1_mock ||
                                  config.enable_sim2_mock;

        sim_mock::install(env_,
                          api_,
                          config.enable_sim1_mock || config.enable_phone_spoof,
                          config.enable_sim2_mock,
                          config.mock_country_iso,
                          phone_active,
                          config.mock_phone_sim1,
                          config.mock_phone_sim2);

        if (phone_active) {
            outgoing_sms_hook::install(env_, api_, is_telephony_, is_hooked_upi_);
            logger::info("Hivirtus", "Phone spoof binder hooks in %s (upi=%d)",
                         process_name_.c_str(), is_hooked_upi_);
        }

        if (is_hooked_upi_) {
            upi_hook::install(env_, api_, process_name_);
            if (!phone_active) {
                outgoing_sms_hook::install(env_, api_, false, true);
            }
        }

        if (is_messaging_) {
            if (config.hook_outgoing_sms || config.intercept_fake_success) {
                if (!phone_active) {
                    outgoing_sms_hook::install(env_, api_, false, true);
                }
                logger::info("Hivirtus", "Messages outgoing SMS hook in %s", process_name_.c_str());
            }
            if (config.override_incoming_sender && !config.inject_sender_id.empty()) {
                sender_spoof::install(env_, api_, process_name_.c_str());
                logger::info("Hivirtus", "Messages sender spoof in %s", process_name_.c_str());
            }
        }

        if (is_telephony_) {
            logger::info("Hivirtus", "Telephony UPI SMS hook in %s", process_name_.c_str());
            sms_hook::install(env_, api_, config.hook_incoming_sms, config.hook_outgoing_sms);
            if (!phone_active) {
                outgoing_sms_hook::install(env_, api_, true, false);
            }
            process_inject_command(env_);
        }

        const bool needs_stay_loaded = is_telephony_ || is_messaging_ || is_hooked_upi_ ||
                                       phone_active || config.enable_device_id_spoof ||
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
    bool is_hooked_upi_ = false;
};

}  // namespace

REGISTER_ZYGISK_MODULE(HivirtusModule)
