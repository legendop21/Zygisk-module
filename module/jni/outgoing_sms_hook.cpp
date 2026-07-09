#include "outgoing_sms_hook.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "plt_hook.hpp"
#include "sms_hook.hpp"
#include "telephony_spoof.hpp"
#include "zygisk_utils.hpp"

#include <vector>
#include <algorithm>
#include <cctype>
#include <cstdio>
#include <string>
#include <pthread.h>
#include <unistd.h>
#include <sys/stat.h>
#include <ctime>

namespace outgoing_sms_hook {

namespace {

zygisk::Api* g_api = nullptr;
bool g_in_hooked_upi = false;

static jobject (*orig_execStartActivity)(JNIEnv*, jobject, jobject, jobject, jobject, jobject,
                                         jobject, jint, jobject, jobject) = nullptr;
static jint (*orig_BinderProxy_transact)(JNIEnv*, jobject, jint, jobject, jobject, jint) =
    nullptr;

std::string to_upper(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(),
                   [](unsigned char c) { return static_cast<char>(std::toupper(c)); });
    return s;
}

bool is_placeholder_sender(const std::string& id) {
    return id.empty() || id == "AD-TEST-S";
}

bool any_hooked_app(const ModuleConfig& config) {
    for (const auto& [pkg, enabled] : config.hooked_upi_apps) {
        (void)pkg;
        if (enabled) return true;
    }
    return config.auto_hook_foreground;
}

bool is_short_verify_dest(const std::string& dest) {
    std::string d;
    for (char c : dest) {
        if (std::isdigit(static_cast<unsigned char>(c))) d += c;
    }
    return d.length() >= 4 && d.length() <= 10;
}

bool body_has_verify_token(const std::string& body) {
    if (body.empty()) return false;
    const std::string upper = to_upper(body);
    static const char* keywords[] = {
        "YESPRO", "YESPROUPI", "YESPAY", "YESBNK", "PHONEPE", "PAYTM", "GPAY",
        "SNAPMINT", "KREDIT", "KREDITBEE", "KREDITBEEAXIS", "UPI", "VERIFY", "VERIFICATION", "VK-", "OTP",
        "HEROAXISUPI", "HEROAXIS", "HEROFIN", "HEROFINCORP", "GROWW", "AXIS", "AIRTEL", "AIRBNK",
        "MYAIRTEL", "DO NOT COPY", nullptr};
    for (const char** kw = keywords; *kw; ++kw) {
        if (upper.find(*kw) != std::string::npos) return true;
    }
    return false;
}

bool should_block_outgoing(const ModuleConfig& config, const std::string& body,
                           const std::string& dest = "") {
    const bool verify_body = body_has_verify_token(body);
    const bool verify_dest = is_short_verify_dest(dest);

    // Mock SIM ON → verify SMS hamesha block (real SIM se na jaye)
    if (config.virtual_sim_active() && (verify_body || verify_dest)) return true;

    if (!config.intercept_fake_success && !config.hook_outgoing_sms) return false;
    if (!any_hooked_app(config) && !config.virtual_sim_active() && !config.auto_hook_foreground) {
        return false;
    }
    if (verify_body) return true;
    if (config.hook_outgoing_sms && config.virtual_sim_active() && verify_dest) return true;
    return false;
}

std::string read_spoof_phone() {
    char buf[64] = {};
    FILE* f = fopen("/data/local/tmp/hivirtus_spoof_phone.txt", "r");
    if (f) {
        fgets(buf, sizeof(buf), f);
        fclose(f);
    }
    std::string phone = buf;
    if (!phone.empty() && phone.back() == '\n') phone.pop_back();
    return phone;
}

std::string spoof_phone_for_config(const ModuleConfig& config) {
    std::string phone = read_spoof_phone();
    if (!phone.empty()) return phone;
    if (!config.mock_phone_sim1.empty()) return config.mock_phone_sim1;
    if (!config.inject_sender_id.empty() && !is_placeholder_sender(config.inject_sender_id)) {
        return config.inject_sender_id;
    }
    return "";
}

void reset_parcel(JNIEnv* env, jobject parcel) {
    if (!parcel) return;
    jclass cls = env->GetObjectClass(parcel);
    jmethodID set_pos = env->GetMethodID(cls, "setDataPosition", "(I)V");
    env->CallVoidMethod(parcel, set_pos, 0);
}

std::string parcel_read_string(JNIEnv* env, jobject parcel) {
    jclass cls = env->GetObjectClass(parcel);
    jmethodID read = env->GetMethodID(cls, "readString", "()Ljava/lang/String;");
    return zygisk_utils::jstring_to_string(env, (jstring)env->CallObjectMethod(parcel, read));
}

void parcel_write_string(JNIEnv* env, jobject parcel, const std::string& value) {
    jclass cls = env->GetObjectClass(parcel);
    jmethodID write = env->GetMethodID(cls, "writeString", "(Ljava/lang/String;)V");
    env->CallVoidMethod(parcel, write, zygisk_utils::string_to_jstring(env, value));
}

bool try_parse_isms_strings(JNIEnv* env, jobject data, int skip_ints_after_iface,
                            std::string& dest, std::string& body) {
    reset_parcel(env, data);
    const std::string iface = parcel_read_string(env, data);
    if (iface.find("ISms") == std::string::npos) return false;

    jclass cls = env->GetObjectClass(data);
    jmethodID read_int = env->GetMethodID(cls, "readInt", "()I");
    for (int i = 0; i < skip_ints_after_iface; ++i) {
        if (read_int) env->CallIntMethod(data, read_int);
    }

    std::vector<std::string> strings;
    for (int i = 0; i < 10; ++i) {
        const std::string s = parcel_read_string(env, data);
        if (s.empty()) break;
        strings.push_back(s);
    }
    if (strings.empty()) return false;

    for (const auto& s : strings) {
        if (body.empty() && body_has_verify_token(s)) body = s;
    }
    for (const auto& s : strings) {
        if (dest.empty() && is_short_verify_dest(s)) dest = s;
    }
    if (body.empty() && strings.size() >= 4) body = strings[3];
    if (body.empty()) body = strings.back();
    if (dest.empty() && strings.size() >= 2) {
        for (size_t i = 0; i < strings.size(); ++i) {
            if (strings[i].find('.') != std::string::npos) continue;  // skip package name
            if (is_short_verify_dest(strings[i])) {
                dest = strings[i];
                break;
            }
        }
    }
    if (dest.empty() && strings.size() >= 2) dest = strings[1];
    return !dest.empty() && !body.empty();
}

bool parcel_blob_has_verify(JNIEnv* env, jobject data) {
    if (!data) return false;
    jclass cls = env->GetObjectClass(data);
    jmethodID marshall = env->GetMethodID(cls, "marshall", "()[B");
    if (!marshall) return false;
    reset_parcel(env, data);
    jbyteArray bytes = (jbyteArray)env->CallObjectMethod(data, marshall);
    if (!bytes) return false;
    const jsize len = env->GetArrayLength(bytes);
    if (len <= 0) return false;
    jbyte* raw = env->GetByteArrayElements(bytes, nullptr);
    if (!raw) return false;
    std::string blob(reinterpret_cast<char*>(raw), static_cast<size_t>(len));
    env->ReleaseByteArrayElements(bytes, raw, JNI_ABORT);
    reset_parcel(env, data);
    return body_has_verify_token(blob);
}

bool extract_isms_from_blob(JNIEnv* env, jobject data, std::string& dest, std::string& body) {
    if (!data) return false;
    jclass cls = env->GetObjectClass(data);
    jmethodID marshall = env->GetMethodID(cls, "marshall", "()[B");
    if (!marshall) return false;
    reset_parcel(env, data);
    jbyteArray bytes = (jbyteArray)env->CallObjectMethod(data, marshall);
    if (!bytes) return false;
    const jsize len = env->GetArrayLength(bytes);
    if (len <= 0) return false;
    jbyte* raw = env->GetByteArrayElements(bytes, nullptr);
    if (!raw) return false;
    std::string blob(reinterpret_cast<char*>(raw), static_cast<size_t>(len));
    env->ReleaseByteArrayElements(bytes, raw, JNI_ABORT);
    reset_parcel(env, data);
    if (!body_has_verify_token(blob)) return false;

    std::string best_body;
    for (size_t i = 0; i < blob.size(); ++i) {
        if (!std::isprint(static_cast<unsigned char>(blob[i]))) continue;
        size_t j = i;
        while (j < blob.size() && std::isprint(static_cast<unsigned char>(blob[j])) &&
               blob[j] != '\0') {
            j++;
        }
        const std::string chunk = blob.substr(i, j - i);
        if (chunk.size() >= 8 && body_has_verify_token(chunk) && chunk.size() > best_body.size()) {
            best_body = chunk;
        }
        i = j;
    }
    body = best_body.empty() ? blob : best_body;

    std::string digits;
    for (char c : blob) {
        if (std::isdigit(static_cast<unsigned char>(c))) {
            digits += c;
            if (digits.size() > 14) {
                digits.erase(0, digits.size() - 14);
            }
        } else if (!digits.empty()) {
            if (is_short_verify_dest(digits)) {
                dest = digits;
                break;
            }
            digits.clear();
        }
    }
    if (dest.empty() && !digits.empty() && is_short_verify_dest(digits)) dest = digits;
    if (dest.empty()) dest = "0000000000";
    return !body.empty();
}

bool read_isms_outgoing(JNIEnv* env, jobject data, std::string& dest, std::string& body) {
    if (!data) return false;
    for (int skip = 0; skip <= 4; ++skip) {
        if (try_parse_isms_strings(env, data, skip, dest, body)) return true;
    }

    // Fallback: collect strings only (older parcel layouts)
    reset_parcel(env, data);
    const std::string iface = parcel_read_string(env, data);
    if (iface.find("ISms") == std::string::npos) return false;

    jclass cls = env->GetObjectClass(data);
    jmethodID read_int = env->GetMethodID(cls, "readInt", "()I");
    if (read_int) env->CallIntMethod(data, read_int);

    std::vector<std::string> strings;
    for (int i = 0; i < 12; ++i) {
        const std::string s = parcel_read_string(env, data);
        if (s.empty()) break;
        strings.push_back(s);
    }
    if (strings.size() < 2) return false;

    for (const auto& s : strings) {
        if (body.empty() && body_has_verify_token(s)) body = s;
    }
    body = body.empty() ? strings.back() : body;
    for (int i = static_cast<int>(strings.size()) - 2; i >= 0; --i) {
        if (is_short_verify_dest(strings[static_cast<size_t>(i)])) {
            dest = strings[static_cast<size_t>(i)];
            break;
        }
    }
    if (dest.empty()) {
        for (int i = static_cast<int>(strings.size()) - 2; i >= 0; --i) {
            if (!strings[static_cast<size_t>(i)].empty()) {
                dest = strings[static_cast<size_t>(i)];
                break;
            }
        }
    }
    if (!dest.empty() && !body.empty()) return true;
    return extract_isms_from_blob(env, data, dest, body);
}

void write_ok_reply(JNIEnv* env, jobject reply) {
    if (!reply) return;
    reset_parcel(env, reply);
    jclass cls = env->GetObjectClass(reply);
    jmethodID write_int = env->GetMethodID(cls, "writeInt", "(I)V");
    jmethodID write_no_ex = env->GetMethodID(cls, "writeNoException", "()V");
    if (write_no_ex) env->CallVoidMethod(reply, write_no_ex);
    if (write_int) env->CallVoidMethod(reply, write_int, 0);
}

bool read_intent_sms(JNIEnv* env, jobject intent, std::string& dest, std::string& body) {
    if (!intent) return false;
    jclass intent_cls = env->GetObjectClass(intent);

    jmethodID get_action = env->GetMethodID(intent_cls, "getAction", "()Ljava/lang/String;");
    jmethodID get_data = env->GetMethodID(intent_cls, "getData", "()Landroid/net/Uri;");
    jmethodID get_extra = env->GetMethodID(intent_cls, "getStringExtra", "(Ljava/lang/String;)Ljava/lang/String;");

    const std::string action =
        zygisk_utils::jstring_to_string(env, (jstring)env->CallObjectMethod(intent, get_action));
    if (action != "android.intent.action.SENDTO" && action != "android.intent.action.VIEW") {
        return false;
    }

    jobject uri = env->CallObjectMethod(intent, get_data);
    if (!uri) return false;

    jclass uri_cls = env->GetObjectClass(uri);
    jmethodID get_scheme = env->GetMethodID(uri_cls, "getScheme", "()Ljava/lang/String;");
    const std::string scheme =
        zygisk_utils::jstring_to_string(env, (jstring)env->CallObjectMethod(uri, get_scheme));
    if (scheme != "smsto" && scheme != "sms") return false;

    jmethodID get_ssp = env->GetMethodID(uri_cls, "getSchemeSpecificPart", "()Ljava/lang/String;");
    dest = zygisk_utils::jstring_to_string(
        env, (jstring)env->CallObjectMethod(uri, get_ssp));

    body = zygisk_utils::jstring_to_string(
        env, (jstring)env->CallObjectMethod(intent, get_extra, env->NewStringUTF("sms_body")));
    if (body.empty()) {
        body = zygisk_utils::jstring_to_string(
            env, (jstring)env->CallObjectMethod(intent, get_extra, env->NewStringUTF("android.intent.extra.TEXT")));
    }
    return !dest.empty() && !body.empty();
}

void pipeline_outgoing(JNIEnv* env, const std::string& dest, const std::string& body) {
    sms_hook::handle_outgoing_sms(env, zygisk_utils::string_to_jstring(env, dest),
                                  zygisk_utils::string_to_jstring(env, body));
    FILE* f = fopen("/data/local/tmp/hivirtus_outgoing_blocked.flag", "w");
    if (f) {
        fprintf(f, "%s|%s\n", dest.c_str(), body.c_str());
        fclose(f);
        chmod("/data/local/tmp/hivirtus_outgoing_blocked.flag", 0644);
    }
}

jobject hook_execStartActivity(JNIEnv* env, jobject thiz, jobject who, jobject contextThread,
                               jobject token, jobject target, jobject intent, jint requestCode,
                               jobject options, jobject permissionToken) {
    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();

    std::string dest;
    std::string body;
    if (read_intent_sms(env, intent, dest, body)) {
        if (should_block_outgoing(config, body)) {
            logger::info("OutgoingSms", "Blocked compose intent dest=%s len=%zu", dest.c_str(),
                         body.size());
            pipeline_outgoing(env, dest, body);
            return nullptr;
        }
    }

    if (!orig_execStartActivity) return nullptr;
    return orig_execStartActivity(env, thiz, who, contextThread, token, target, intent,
                                  requestCode, options, permissionToken);
}

jint hook_BinderProxy_transact(JNIEnv* env, jobject thiz, jint code, jobject data, jobject reply,
                               jint flags) {
    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();

    if (data) {
        reset_parcel(env, data);
        const std::string iface = parcel_read_string(env, data);
        reset_parcel(env, data);

        if (iface.find("ISms") != std::string::npos) {
            std::string dest;
            std::string body;
            if (read_isms_outgoing(env, data, dest, body) &&
                should_block_outgoing(config, body, dest)) {
                logger::info("OutgoingSms", "Blocked ISms send dest=%s body=%.32s", dest.c_str(),
                     body.c_str());
                pipeline_outgoing(env, dest, body);
                write_ok_reply(env, reply);
                return 0;
            }
        }
    }

    const jint result =
        orig_BinderProxy_transact ? orig_BinderProxy_transact(env, thiz, code, data, reply, flags)
                                  : -1;

    // Telephony spoof virtual_sim.cpp me — yahan dubara scrub se inject corrupt hota tha
    if (result == 0 && reply && telephony_spoof::phone_spoof_enabled() &&
        !ConfigManager::instance().get().virtual_sim_active()) {
        const std::string iface =
            data ? telephony_spoof::read_binder_interface(env, data) : std::string();
        if (g_in_hooked_upi || iface.empty() ||
            telephony_spoof::is_telephony_binder_interface(iface)) {
            const auto profiles = telephony_spoof::load_subscriber_profiles();
            telephony_spoof::scrub_reply_parcel(env, reply, profiles, iface);
        }
    }

    return result;
}

void install_plt_hooks(JNIEnv* env, bool enable_binder) {
    (void)env;
    if (!g_api) return;
    static bool exec_committed = false;
    static bool binder_committed = false;

    ConfigManager::instance().reload();
    const bool virtual_sim_on = ConfigManager::instance().get().virtual_sim_active();

    plt_hook::set_api(g_api);
    if (!exec_committed) {
        exec_committed = true;
        plt_hook::register_regex(".*/libandroid_runtime\\.so$",
                                 "Java_android_app_Instrumentation_execStartActivity",
                                 reinterpret_cast<void*>(hook_execStartActivity),
                                 reinterpret_cast<void**>(&orig_execStartActivity));
    }
    // virtual_sim handles binder when mock ON; else outgoing installs ISms block
    if (enable_binder && !binder_committed && !virtual_sim_on) {
        binder_committed = true;
        plt_hook::register_regex(".*/libandroid_runtime\\.so$",
                                 "Java_android_os_BinderProxy_transact",
                                 reinterpret_cast<void*>(hook_BinderProxy_transact),
                                 reinterpret_cast<void**>(&orig_BinderProxy_transact));
    }
    plt_hook::commit();
    logger::info("OutgoingSms", "hooks: exec=%d binder=%d virtual_sim=%d", exec_committed ? 1 : 0,
                 binder_committed ? 1 : 0, virtual_sim_on ? 1 : 0);
}

struct DeferredSmsHook {
    zygisk::Api* api = nullptr;
};

void* deferred_sms_hook_worker(void* arg) {
    auto* job = static_cast<DeferredSmsHook*>(arg);
    sleep(1);
    if (job && job->api) {
        g_api = job->api;
        g_in_hooked_upi = true;
        install_plt_hooks(nullptr, true);
        logger::info("OutgoingSms", "Deferred ISms block active (UPI app, exec only if virtual SIM)");
    }
    delete job;
    return nullptr;
}

void schedule_deferred_upi(JNIEnv* env, zygisk::Api* api) {
    (void)env;
    auto* job = new DeferredSmsHook();
    job->api = api;
    pthread_t t{};
    pthread_create(&t, nullptr, deferred_sms_hook_worker, job);
    pthread_detach(t);
}

}  // namespace

bool intercept_isms_transact(JNIEnv* env, jobject data, jobject reply) {
    if (!data || !reply) return false;
    static thread_local bool block_on = false;
    static thread_local time_t cached_at = 0;
    const time_t now = time(nullptr);
    if (cached_at == 0 || now - cached_at >= 2) {
        ConfigManager::instance().reload();
        const auto& config = ConfigManager::instance().get();
        block_on = config.intercept_fake_success || config.hook_outgoing_sms ||
                   config.virtual_sim_active();
        cached_at = now;
    }
    if (!block_on) return false;

    const auto& config = ConfigManager::instance().get();
    reset_parcel(env, data);
    const std::string iface = parcel_read_string(env, data);
    reset_parcel(env, data);
    if (iface.find("ISms") == std::string::npos) return false;

    std::string dest;
    std::string body;
    if (!read_isms_outgoing(env, data, dest, body)) {
        if (!extract_isms_from_blob(env, data, dest, body)) return false;
    }
    if (!should_block_outgoing(config, body, dest)) {
        if (!parcel_blob_has_verify(env, data)) return false;
        if (!config.virtual_sim_active() && !config.hook_outgoing_sms &&
            !config.intercept_fake_success) {
            return false;
        }
    }

    logger::info("OutgoingSms", "Blocked ISms (virtual_sim) dest=%s body=%.32s", dest.c_str(),
                 body.c_str());
    pipeline_outgoing(env, dest, body);
    write_ok_reply(env, reply);
    return true;
}

void install(JNIEnv* env, zygisk::Api* api, bool in_telephony, bool in_messaging, bool in_upi) {
    (void)env;
    g_api = api;
    g_in_hooked_upi = in_messaging || in_upi;
    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();
    const bool phone_spoof = config.virtual_sim_active();
    const bool sms_block = config.hook_outgoing_sms || config.intercept_fake_success;

    if (!phone_spoof && !sms_block && !in_telephony && !in_messaging && !in_upi) return;
    install_plt_hooks(env, in_telephony || in_messaging || in_upi);
}

void schedule_deferred_upi_hook(JNIEnv* env, zygisk::Api* api) {
    schedule_deferred_upi(env, api);
}

}  // namespace outgoing_sms_hook
