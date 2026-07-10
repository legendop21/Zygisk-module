#include "zygisk.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "outgoing_sms_hook.hpp"
#include "overlay_ui.hpp"
#include "upi_registry.hpp"
#include "sender_spoof.hpp"
#include "phone_number_hook.hpp"

#include <ctime>
#include <cstdio>
#include <cstring>
#include <string>
#include <unistd.h>
#include <pthread.h>
#include <sys/stat.h>

namespace {

// v1.0.1 SAFE MODE:
// - NEVER inject into com.android.phone / telephony / launcher / systemui
//   (ye SIM gayab + screen flicker + phone apps crash karte the)
// - Sirf known UPI/banking apps: ISms client block + number spoof + floating bubble

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
    // Process name with suffix: com.android.phone:ui
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

void mark_active() {
    FILE* f = fopen("/data/local/tmp/hivirtus_zygisk_native.active", "w");
    if (f) {
        fprintf(f, "1\n");
        fclose(f);
        chmod("/data/local/tmp/hivirtus_zygisk_native.active", 0644);
    }
}

void append_diag(const char* path, const char* line) {
    FILE* f = fopen(path, "a");
    if (!f) return;
    fprintf(f, "%s\n", line);
    fclose(f);
    chmod(path, 0644);
}

bool native_overlay_wanted() {
    return access("/data/local/tmp/hivirtus_disable_native_overlay", R_OK) != 0;
}

bool sender_spoof_wanted(const ModuleConfig& config) {
    if (!config.override_incoming_sender) return false;
    const std::string& id = config.inject_sender_id;
    return !id.empty() && id != "AD-TEST-S";
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

        // Fast unload — phone/SIM/launcher/system kabhi load mat karo
        if (is_dangerous_process(process_name_) ||
            upi_registry::is_module_own_app(process_name_) ||
            !upi_registry::is_sms_hook_target(process_name_)) {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs* args) override {
        (void)args;

        if (is_dangerous_process(process_name_) ||
            upi_registry::is_module_own_app(process_name_) ||
            !upi_registry::is_sms_hook_target(process_name_)) {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        ConfigManager::instance().reload();
        const auto& config = ConfigManager::instance().get();
        if (!config.is_upi_app_hooked(process_name_)) {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        logger::init(config.log_file);
        mark_active();
        append_diag("/data/local/tmp/hivirtus_inject.log",
                    ("safe_inject:" + process_name_).c_str());

        const bool sms_block = config.hook_outgoing_sms || config.intercept_fake_success;
        const bool phone_spoof = config.virtual_sim_active();
        const bool want_sender = sender_spoof_wanted(config);
        const int delay = upi_registry::hook_startup_delay_sec(process_name_);
        const bool fragile = upi_registry::is_fragile_banking_app(process_name_);

        // SMS intercept — delayed more on fragile apps (PhonePe crash avoid)
        if (sms_block) {
            const int sms_delay = fragile ? (delay > 4 ? delay : 5) : (delay > 0 ? delay : 2);
            outgoing_sms_hook::install(env_, api_, false, false, true);
            outgoing_sms_hook::schedule_deferred_upi_hook(env_, api_, sms_delay);
            logger::info("Virtus", "Safe ISms intercept in %s delay=%d", process_name_.c_str(),
                         sms_delay);
        }

        (void)phone_spoof;
        (void)want_sender;

        // Floating bubble — fragile apps: wait 2s so app UI settle
        if (native_overlay_wanted()) {
            schedule_overlay_ui(env_, api_, process_name_, fragile ? 2 : 0);
            logger::info("Virtus", "Overlay scheduled in %s", process_name_.c_str());
        }

        touch_heartbeat();
    }

    void postServerSpecialize(const zygisk::ServerSpecializeArgs* args) override {
        (void)args;
        // system_server — kabhi hook mat karo
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
};

}  // namespace

REGISTER_ZYGISK_MODULE(VirtusModule)
