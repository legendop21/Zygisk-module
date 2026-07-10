#include "zygisk.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "outgoing_sms_hook.hpp"
#include "overlay_ui.hpp"
#include "upi_registry.hpp"
#include "sender_spoof.hpp"
#include "phone_number_hook.hpp"

#include <cstdint>
#include <ctime>
#include <cstdio>
#include <cstring>
#include <string>
#include <unistd.h>
#include <pthread.h>
#include <sys/stat.h>

namespace {

// v1.0.20:
// - Root companion logs inject (app UID / SELinux se /data/local/tmp write fail ho sakta)
// - Process name :suffix strip (com.phonepe.app:push → com.phonepe.app)
// - Skip reasons logged so waiting_for_upi_app_open debug ho sake

bool is_dangerous_process(const std::string& process) {
    if (process.empty()) return true;
    static const char* kNever[] = {
        "zygote", "zygote64", "system_server",
        "com.android.phone",
        "com.android.providers.telephony",
        "com.android.systemui",
        "com.android.settings",
        "com.android.shell",
        "com.android.keychain",
        "com.android.networkstack",
        "com.android.networkstack.tethering",
        "com.android.se",
        "com.android.nfc",
        "com.android.mms",
        "com.android.mms.service",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.samsung.android.settings",
        "com.samsung.android.app.telephonyui",
        "com.samsung.android.dialer",
        "com.google.android.permissioncontroller",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "android.ext.services",
        "org.lsposed.manager",
        "lspd", "lspd64",
        "zygiskd", "zygiskd64", "zygiskd32",
        "rezygiskd", "rezygiskd64",
        nullptr,
    };
    for (const char** p = kNever; *p; ++p) {
        if (process == *p) return true;
    }
    if (process.rfind("com.android.phone:", 0) == 0) return true;
    if (process.rfind("com.android.settings:", 0) == 0) return true;
    if (process.rfind("com.android.providers.telephony:", 0) == 0) return true;
    if (process.find("telephony") != std::string::npos) return true;
    if (process.find("simsettings") != std::string::npos) return true;
    if (upi_registry::is_launcher_package(process)) return true;
    if (process.rfind("com.android.", 0) == 0) return true;
    if (process.rfind("android.", 0) == 0) return true;
    return false;
}

std::string base_package(const std::string& process) {
    const auto pos = process.find(':');
    if (pos == std::string::npos) return process;
    return process.substr(0, pos);
}

void append_diag(const char* path, const char* line) {
    FILE* f = fopen(path, "a");
    if (!f) return;
    fprintf(f, "%s\n", line);
    fclose(f);
    chmod(path, 0666);
}

void report_line(zygisk::Api* api, const std::string& line) {
    // Prefer root companion (reliable). Fallback: direct write.
    if (api) {
        const int fd = api->connectCompanion();
        if (fd >= 0) {
            const uint32_t len = static_cast<uint32_t>(line.size());
            if (write(fd, &len, sizeof(len)) == static_cast<ssize_t>(sizeof(len)) &&
                (len == 0 || write(fd, line.data(), len) == static_cast<ssize_t>(len))) {
                close(fd);
                return;
            }
            close(fd);
        }
    }
    append_diag("/data/local/tmp/hivirtus_inject.log", line.c_str());
}

void mark_active() {
    FILE* f = fopen("/data/local/tmp/hivirtus_zygisk_native.active", "w");
    if (f) {
        fprintf(f, "1\n");
        fclose(f);
        chmod("/data/local/tmp/hivirtus_zygisk_native.active", 0644);
    }
}

bool native_overlay_wanted() {
    return access("/data/local/tmp/hivirtus_disable_native_overlay", R_OK) != 0;
}

bool sender_spoof_wanted(const ModuleConfig& config) {
    if (!config.override_incoming_sender) return false;
    const std::string& id = config.inject_sender_id;
    return !id.empty() && id != "AD-TEST-S";
}

bool is_yespay(const std::string& pkg) {
    return pkg.find("yespay") != std::string::npos || pkg.find("yesbank") != std::string::npos;
}

struct DeferredOverlayJob {
    JavaVM* vm = nullptr;
    zygisk::Api* api = nullptr;
    std::string pkg;
    int delay_sec = 3;
};

void* deferred_overlay_worker(void* arg) {
    auto* job = static_cast<DeferredOverlayJob*>(arg);
    if (job->delay_sec > 0) sleep(static_cast<unsigned>(job->delay_sec));
    if (job->vm && job->api && native_overlay_wanted()) {
        JNIEnv* env = nullptr;
        if (job->vm->AttachCurrentThread(&env, nullptr) == JNI_OK && env) {
            overlay_ui::install(env, job->api, job->pkg);
            append_diag("/data/local/tmp/hivirtus_overlay.debug", "overlay_ok");
            job->vm->DetachCurrentThread();
        }
    }
    delete job;
    return nullptr;
}

void schedule_overlay_ui(JNIEnv* env, zygisk::Api* api, const std::string& pkg, int delay_sec) {
    if (!native_overlay_wanted() || pkg.empty() || !env || !api) return;
    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK || !vm) return;
    auto* job = new DeferredOverlayJob();
    job->vm = vm;
    job->api = api;
    job->pkg = pkg;
    job->delay_sec = delay_sec;
    pthread_t t{};
    pthread_create(&t, nullptr, deferred_overlay_worker, job);
    pthread_detach(t);
}

struct DeferredSenderJob {
    JavaVM* vm = nullptr;
    zygisk::Api* api = nullptr;
    std::string pkg;
    int delay_sec = 5;
};

void* deferred_sender_worker(void* arg) {
    auto* job = static_cast<DeferredSenderJob*>(arg);
    if (job->delay_sec > 0) sleep(static_cast<unsigned>(job->delay_sec));
    if (job->vm && job->api) {
        JNIEnv* env = nullptr;
        if (job->vm->AttachCurrentThread(&env, nullptr) == JNI_OK && env) {
            sender_spoof::install(env, job->api, job->pkg.c_str());
            job->vm->DetachCurrentThread();
        }
    }
    delete job;
    return nullptr;
}

void schedule_sender(JNIEnv* env, zygisk::Api* api, const std::string& pkg, int delay_sec) {
    if (!env || !api) return;
    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK || !vm) return;
    auto* job = new DeferredSenderJob();
    job->vm = vm;
    job->api = api;
    job->pkg = pkg;
    job->delay_sec = delay_sec;
    pthread_t t{};
    pthread_create(&t, nullptr, deferred_sender_worker, job);
    pthread_detach(t);
}

class VirtusModule : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api* api, JNIEnv* env) override {
        api_ = api;
        env_ = env;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs* args) override {
        const char* process = env_->GetStringUTFChars(args->nice_name, nullptr);
        process_name_ = process ? process : "";
        env_->ReleaseStringUTFChars(args->nice_name, process);
        pkg_ = base_package(process_name_);

        if (is_dangerous_process(pkg_) ||
            upi_registry::is_module_own_app(pkg_) ||
            !upi_registry::is_sms_hook_target(pkg_)) {
            // Still report UPI-looking names that we skipped for other reasons
            if (!pkg_.empty() && !is_dangerous_process(pkg_) &&
                (pkg_.find("pay") != std::string::npos ||
                 pkg_.find("upi") != std::string::npos ||
                 pkg_.find("bank") != std::string::npos ||
                 pkg_.find("loan") != std::string::npos ||
                 pkg_.find("kredit") != std::string::npos)) {
                report_line(api_, "skip_not_target:" + process_name_);
            }
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        // Root companion: prove Zygisk loaded us into this UPI process
        report_line(api_, "pre_seen:" + process_name_);
        keep_ = true;
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs* args) override {
        (void)args;
        if (!keep_) return;

        if (is_dangerous_process(pkg_) ||
            upi_registry::is_module_own_app(pkg_) ||
            !upi_registry::is_sms_hook_target(pkg_)) {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        ConfigManager::instance().reload();
        const auto& config = ConfigManager::instance().get();
        if (!config.is_upi_app_hooked(pkg_)) {
            report_line(api_, "skip_config_off:" + pkg_);
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        logger::init(config.log_file);
        mark_active();
        report_line(api_, "safe_inject:" + pkg_);
        if (process_name_ != pkg_) {
            report_line(api_, "proc:" + process_name_);
        }

        ConfigManager::instance().apply_ui_save_file();
        ConfigManager::instance().reload();
        const auto& live = ConfigManager::instance().get();

        const bool sms_block = live.hook_outgoing_sms || live.intercept_fake_success;
        const bool phone_spoof = live.virtual_sim_active() || live.enable_phone_spoof ||
                                 live.enable_sim1_mock;
        const bool want_sender = sender_spoof_wanted(live);
        const bool fragile = upi_registry::is_fragile_banking_app(pkg_);
        const bool yespay = is_yespay(pkg_);

        if (sms_block) {
            int sms_delay = 4;
            if (fragile) sms_delay = 8;
            if (yespay) sms_delay = 12;
            outgoing_sms_hook::schedule_deferred_upi_hook(env_, api_, sms_delay);
            report_line(api_, "isms_deferred:" + pkg_ + " d=" + std::to_string(sms_delay));
        }

        if (phone_spoof) {
            const int ph_delay = yespay ? 14 : (fragile ? 10 : 6);
            phone_number_hook::schedule_deferred_install(
                env_, api_, pkg_.c_str(), ph_delay);
        }

        if (want_sender) {
            const int sd = yespay ? 14 : (fragile ? 10 : 5);
            schedule_sender(env_, api_, pkg_, sd);
        }

        if (native_overlay_wanted()) {
            const int ov = yespay ? 5 : (fragile ? 3 : 2);
            schedule_overlay_ui(env_, api_, pkg_, ov);
        }

        touch_heartbeat();
    }

    void postServerSpecialize(const zygisk::ServerSpecializeArgs* args) override {
        (void)args;
        api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
    }

private:
    void touch_heartbeat() {
        FILE* f = fopen("/data/local/tmp/hivirtus_module_heartbeat.txt", "w");
        if (f) {
            fprintf(f, "%ld\n", static_cast<long>(time(nullptr)));
            fclose(f);
            chmod("/data/local/tmp/hivirtus_module_heartbeat.txt", 0644);
        }
    }

    zygisk::Api* api_ = nullptr;
    JNIEnv* env_ = nullptr;
    std::string process_name_;
    std::string pkg_;
    bool keep_ = false;
};

void companion_handler(int client) {
    uint32_t len = 0;
    if (read(client, &len, sizeof(len)) != static_cast<ssize_t>(sizeof(len))) return;
    if (len == 0 || len > 1024) return;
    std::string line(len, '\0');
    if (read(client, line.data(), len) != static_cast<ssize_t>(len)) return;

    FILE* f = fopen("/data/local/tmp/hivirtus_inject.log", "a");
    if (f) {
        fprintf(f, "%s\n", line.c_str());
        fclose(f);
        chmod("/data/local/tmp/hivirtus_inject.log", 0666);
    }
    // Also mirror under module dir (always root-writable)
    f = fopen("/data/adb/modules/hivirtus_zygisk_mode/inject_mirror.log", "a");
    if (f) {
        fprintf(f, "%s\n", line.c_str());
        fclose(f);
    }
}

}  // namespace

REGISTER_ZYGISK_MODULE(VirtusModule)
REGISTER_ZYGISK_COMPANION(companion_handler)
