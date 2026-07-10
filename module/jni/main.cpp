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
#include <sys/types.h>
#include <fcntl.h>
#include <errno.h>
#include <cstdlib>

namespace {

// v1.0.21:
// - safe_inject logged in pre via companion (post companion = SELinux fail)
// - SMS/phone/sender hooks installed NOW in post (deferred api hooks were no-ops)
// - Overlay UI still deferred (no Zygisk Api needed)

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

        // connectCompanion ONLY works in preSpecialize — log + seed A16 assets here.
        report_line(api_, "pre_seen:" + process_name_);
        report_line(api_, "safe_inject:" + pkg_);

        // Android 16: app cannot read /data/local/tmp — copy dex/ui into app code_cache
        std::string data_dir;
        if (args->app_data_dir) {
            const char* dd = env_->GetStringUTFChars(args->app_data_dir, nullptr);
            if (dd) {
                data_dir = dd;
                env_->ReleaseStringUTFChars(args->app_data_dir, dd);
            }
        }
        const int uid = args->uid;
        if (!data_dir.empty()) {
            report_line(api_, "prep_assets|" + pkg_ + "|" + std::to_string(uid) + "|" + data_dir);
        }
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

        // post: companion unavailable — best-effort direct log
        append_diag("/data/local/tmp/hivirtus_inject.log",
                    ("post_enter:" + pkg_).c_str());

        ConfigManager::instance().reload();
        const auto& config = ConfigManager::instance().get();
        if (!config.is_upi_app_hooked(pkg_)) {
            append_diag("/data/local/tmp/hivirtus_inject.log",
                        ("skip_config_off:" + pkg_).c_str());
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        logger::init(config.log_file);
        mark_active();

        ConfigManager::instance().apply_ui_save_file();
        ConfigManager::instance().reload();
        const auto& live = ConfigManager::instance().get();

        const bool sms_block = live.hook_outgoing_sms || live.intercept_fake_success;
        const bool phone_spoof = live.virtual_sim_active() || live.enable_phone_spoof ||
                                 live.enable_sim1_mock;
        const bool want_sender = sender_spoof_wanted(live);
        const bool fragile = upi_registry::is_fragile_banking_app(pkg_);
        const bool yespay = is_yespay(pkg_);

        // Overlay FIRST — SMS PLT crash se pehle bubble schedule
        if (native_overlay_wanted()) {
            const int ov = yespay ? 2 : (fragile ? 2 : 1);
            schedule_overlay_ui(env_, api_, pkg_, ov);
            append_diag("/data/local/tmp/hivirtus_inject.log",
                        ("overlay_sched:" + pkg_ + " d=" + std::to_string(ov)).c_str());
        }

        // Zygisk Api valid ONLY during postSpecialize. Deferred api hooks = no-op.
        if (sms_block) {
            outgoing_sms_hook::install_for_upi(env_, api_, pkg_.c_str());
            append_diag("/data/local/tmp/hivirtus_inject.log",
                        ("isms_now:" + pkg_).c_str());
        }

        if (phone_spoof) {
            phone_number_hook::install(env_, api_, pkg_.c_str());
            append_diag("/data/local/tmp/hivirtus_inject.log",
                        ("phone_now:" + pkg_).c_str());
        }

        if (want_sender) {
            sender_spoof::install(env_, api_, pkg_.c_str());
            append_diag("/data/local/tmp/hivirtus_inject.log",
                        ("sender_now:" + pkg_).c_str());
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

void companion_prep_assets(const std::string& pkg, int uid, const std::string& data_dir) {
    if (pkg.empty() || data_dir.empty() || uid <= 0) return;
    const char* mod = "/data/adb/modules/hivirtus_zygisk_mode";
    std::string dest = data_dir + "/code_cache/hivirtus";
    std::string cmd = "mkdir -p '" + dest + "/ui' 2>/dev/null; "
                      "cp -f '" + std::string(mod) + "/bridge.dex' '" + dest + "/bridge.dex' 2>/dev/null; "
                      "cp -f '" + std::string(mod) + "/ui/'* '" + dest + "/ui/' 2>/dev/null; "
                      "cp -f '" + std::string(mod) + "/config.json' '" + dest + "/config.json' 2>/dev/null; "
                      "cp -f /data/local/tmp/hivirtus_ui_save.json '" + dest + "/ui_save.json' 2>/dev/null; "
                      "cp -f /data/local/tmp/hivirtus_zygisk_mode_config.json '" + dest + "/config.json' 2>/dev/null; "
                      "cp -f /data/local/tmp/hivirtus_bridge.dex '" + dest + "/bridge.dex' 2>/dev/null; "
                      "chmod -R 755 '" + dest + "' 2>/dev/null; "
                      "chmod 644 '" + dest + "/bridge.dex' '" + dest + "/ui/'* '" + dest + "/config.json' 2>/dev/null; "
                      "chown -R " + std::to_string(uid) + ":" + std::to_string(uid) + " '" + dest + "' 2>/dev/null; "
                      "restorecon -R '" + dest + "' 2>/dev/null; true";
    system(cmd.c_str());

    FILE* f = fopen("/data/local/tmp/hivirtus_overlay.debug", "a");
    if (f) {
        fprintf(f, "prep_assets_ok:%s uid=%d dir=%s\n", pkg.c_str(), uid, dest.c_str());
        fclose(f);
        chmod("/data/local/tmp/hivirtus_overlay.debug", 0666);
    }
}

void companion_handler(int client) {
    uint32_t len = 0;
    if (read(client, &len, sizeof(len)) != static_cast<ssize_t>(sizeof(len))) return;
    if (len == 0 || len > 2048) return;
    std::string line(len, '\0');
    if (read(client, line.data(), len) != static_cast<ssize_t>(len)) return;

    if (line.rfind("prep_assets|", 0) == 0) {
        // prep_assets|pkg|uid|data_dir
        std::string rest = line.substr(12);
        auto p1 = rest.find('|');
        auto p2 = p1 == std::string::npos ? std::string::npos : rest.find('|', p1 + 1);
        if (p1 != std::string::npos && p2 != std::string::npos) {
            std::string pkg = rest.substr(0, p1);
            int uid = atoi(rest.substr(p1 + 1, p2 - p1 - 1).c_str());
            std::string data_dir = rest.substr(p2 + 1);
            companion_prep_assets(pkg, uid, data_dir);
        }
    }

    FILE* f = fopen("/data/local/tmp/hivirtus_inject.log", "a");
    if (f) {
        fprintf(f, "%s\n", line.c_str());
        fclose(f);
        chmod("/data/local/tmp/hivirtus_inject.log", 0666);
    }
    f = fopen("/data/adb/modules/hivirtus_zygisk_mode/inject_mirror.log", "a");
    if (f) {
        fprintf(f, "%s\n", line.c_str());
        fclose(f);
    }
}

}  // namespace

REGISTER_ZYGISK_MODULE(VirtusModule)
REGISTER_ZYGISK_COMPANION(companion_handler)
