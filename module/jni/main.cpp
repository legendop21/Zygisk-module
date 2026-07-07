#include "zygisk.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "sms_hook.hpp"
#include "inject_sms.hpp"
#include "zygisk_utils.hpp"

#include <cstring>
#include <fstream>
#include <string>
#include <unistd.h>
#include <sys/stat.h>

namespace {

constexpr const char* kTargetPhone = "com.android.phone";
constexpr const char* kTargetTelephony = "com.android.providers.telephony";
constexpr const char* kInjectCommandFile = "/data/local/tmp/zygisk_sms_otp_inject.cmd";

bool is_target_process(const char* nice_name) {
    if (!nice_name) return false;
    return strcmp(nice_name, kTargetPhone) == 0 ||
           strcmp(nice_name, kTargetTelephony) == 0;
}

void process_inject_command(JNIEnv* env) {
    std::ifstream cmd_file(kInjectCommandFile);
    if (!cmd_file.is_open()) return;

    std::string line;
    if (!std::getline(cmd_file, line)) return;

    // Format: INJECT|<sender>|<body>
    if (line.rfind("INJECT|", 0) != 0) return;

    const size_t first_sep = line.find('|', 7);
    if (first_sep == std::string::npos) return;

    const std::string sender = line.substr(7, first_sep - 7);
    const std::string body = line.substr(first_sep + 1);

    inject_sms::inject_local_sms(env, sender, body);
    unlink(kInjectCommandFile);
}

class SmsOtpModule : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api* api, JNIEnv* env) override {
        api_ = api;
        env_ = env;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs* args) override {
        const char* process = env_->GetStringUTFChars(args->nice_name, nullptr);
        should_hook_ = is_target_process(process);
        env_->ReleaseStringUTFChars(args->nice_name, process);
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs* args) override {
        if (!should_hook_) {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        ConfigManager::instance().load();
        const auto& config = ConfigManager::instance().get();
        logger::init(config.log_file);

        logger::info("ZygiskSmsOtp", "Module loaded in telephony process");

        sms_hook::install(env_, config.hook_incoming_sms, config.hook_outgoing_sms);

        // Poll inject commands written by overlay app
        process_inject_command(env_);
    }

private:
    zygisk::Api* api_ = nullptr;
    JNIEnv* env_ = nullptr;
    bool should_hook_ = false;
};

}  // namespace

REGISTER_ZYGISK_MODULE(SmsOtpModule)
