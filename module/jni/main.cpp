#include "zygisk.hpp"
#include "config.hpp"
#include "logger.hpp"

#include <ctime>
#include <cstdio>
#include <sys/stat.h>

namespace {

constexpr const char* kCompanionPackage = "com.hivirtus.zygiskmode";

void touch_module_heartbeat() {
    FILE* file = fopen("/data/local/tmp/hivirtus_module_heartbeat.txt", "w");
    if (!file) return;
    fprintf(file, "%ld\n", static_cast<long>(time(nullptr)));
    fclose(file);
    chmod("/data/local/tmp/hivirtus_module_heartbeat.txt", 0644);
}

class EducationalZygiskModule : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api* api, JNIEnv* env) override {
        api_ = api;
        env_ = env;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs* args) override {
        const char* process = env_->GetStringUTFChars(args->nice_name, nullptr);
        process_name_ = process ? process : "";
        env_->ReleaseStringUTFChars(args->nice_name, process);

        is_companion_app_ = process_name_.find(kCompanionPackage) != std::string::npos;
        ConfigManager::instance().load();
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs* /*args*/) override {
        ConfigManager::instance().reload();
        const auto& config = ConfigManager::instance().get();
        logger::init(config.log_file);

        touch_module_heartbeat();

        if (is_companion_app_) {
            logger::info(
                "ZygiskEduLab",
                "Educational module active in companion app process: %s",
                process_name_.c_str());
        }

        // Educational build: no hooks in third-party apps. Unload when idle.
        if (!is_companion_app_) {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
        }
    }

private:
    zygisk::Api* api_ = nullptr;
    JNIEnv* env_ = nullptr;
    std::string process_name_;
    bool is_companion_app_ = false;
};

}  // namespace

REGISTER_ZYGISK_MODULE(EducationalZygiskModule)
