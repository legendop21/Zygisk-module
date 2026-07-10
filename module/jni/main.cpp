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
#include "float_overlay.hpp"

#include <ctime>
#include <cstring>
#include <fstream>
#include <string>
#include <unistd.h>
#include <sys/stat.h>

namespace {

constexpr const char* kTargetPhone = "com.android.phone";
constexpr const char* kTargetTelephony = "com.android.providers.telephony";
constexpr const char* kTargetSystemUI = "com.android.systemui";
constexpr const char* kInjectCommandFile = "/data/local/tmp/hivirtus_inject.cmd";

bool is_telephony_process(const char* nice_name) {
    if (!nice_name) return false;
    return strcmp(nice_name, kTargetPhone) == 0 ||
           strcmp(nice_name, kTargetTelephony) == 0;
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
        is_systemui_ = process_name_ == kTargetSystemUI;
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

        if (is_hooked_upi_) {
            upi_hook::install(env_, api_, process_name_);
            outgoing_sms_hook::install(env_, api_, false, true);
        }

        if (is_telephony_) {
            logger::info("Hivirtus", "Telephony UPI SMS hook in %s", process_name_.c_str());
            sms_hook::install(env_, api_, config.hook_incoming_sms, config.hook_outgoing_sms);
            outgoing_sms_hook::install(env_, api_, true, false);
            process_inject_command(env_);
        }

        if (is_systemui_) {
            logger::info("Hivirtus", "Floating overlay in SystemUI");
            float_overlay::install(env_, api_);
        }

        const bool needs_stay_loaded = is_telephony_ || is_hooked_upi_ || is_systemui_ ||
                                       config.hide_root ||
                                       phone_active || config.enable_device_id_spoof ||
                                       !config.spoof_android_id.empty() ||
                                       access("/data/adb/modules/hivirtus_zygisk_mode/spoof_android_id.txt", R_OK) == 0 ||
                                       access("/data/local/tmp/hivirtus_spoof_android_id.txt", R_OK) == 0;
        touch_module_heartbeat();
        if (is_systemui_ || is_telephony_ || is_hooked_upi_) {
            FILE* zf = fopen("/data/local/tmp/hivirtus_zygisk_active.flag", "w");
            if (zf) {
                fprintf(zf, "%ld\n", static_cast<long>(time(nullptr)));
                fclose(zf);
                chmod("/data/local/tmp/hivirtus_zygisk_active.flag", 0644);
            }
        }
        if (!needs_stay_loaded) {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
        }
    }

private:
    zygisk::Api* api_ = nullptr;
    JNIEnv* env_ = nullptr;
    std::string process_name_;
    bool is_telephony_ = false;
    bool is_hooked_upi_ = false;
    bool is_systemui_ = false;
};

}  // namespace

REGISTER_ZYGISK_MODULE(HivirtusModule)
