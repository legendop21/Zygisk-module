#include "zygisk.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "sms_hook.hpp"
#include "inject_sms.hpp"
#include "root_hide.hpp"
#include "sim_mock.hpp"
#include "zygisk_utils.hpp"

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

void process_inject_command(JNIEnv* env) {
    std::ifstream cmd_file(kInjectCommandFile);
    if (!cmd_file.is_open()) return;

    std::string line;
    if (!std::getline(cmd_file, line)) return;
    if (line.rfind("INJECT|", 0) != 0) return;

    const size_t first_sep = line.find('|', 7);
    if (first_sep == std::string::npos) return;

    const std::string sender = line.substr(7, first_sep - 7);
    const std::string body = line.substr(first_sep + 1);

    inject_sms::inject_local_sms(env, sender, body);
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
        env_->ReleaseStringUTFChars(args->nice_name, process);

        ConfigManager::instance().load();
        const auto& config = ConfigManager::instance().get();

        // Magisk denylist unmount — hides root from target apps safely
        if (config.hide_root) {
            api_->setOption(zygisk::Option::FORCE_DENYLIST_UNMOUNT);
        }
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs* args) override {
        ConfigManager::instance().reload();
        const auto& config = ConfigManager::instance().get();
        logger::init(config.log_file);

        // Root + developer hide — runs in every app process (safe, no bootloop)
        root_hide::install(env_, config.hide_root, config.hide_developer);

        // SIM mock — telephony + all apps
        sim_mock::install(env_,
                          config.enable_sim1_mock,
                          config.enable_sim2_mock,
                          config.mock_country_iso);

        // SMS hooks — telephony processes only
        if (is_telephony_) {
            logger::info("Hivirtus", "Telephony hooks in %s", process_name_.c_str());
            sms_hook::install(env_, config.hook_incoming_sms, config.hook_outgoing_sms);
            process_inject_command(env_);
        }

        // Unload library from non-essential processes to save memory
        if (!is_telephony_ && !config.hide_root && !config.enable_sim1_mock) {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
        }
    }

private:
    zygisk::Api* api_ = nullptr;
    JNIEnv* env_ = nullptr;
    std::string process_name_;
    bool is_telephony_ = false;
};

}  // namespace

REGISTER_ZYGISK_MODULE(HivirtusModule)
