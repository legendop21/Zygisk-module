#include "outgoing_sms_hook.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "sms_hook.hpp"
#include "zygisk_utils.hpp"

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <string>
#include <sys/stat.h>

namespace outgoing_sms_hook {

namespace {

zygisk::Api* g_api = nullptr;

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

bool body_matches_hooked_upi(const ModuleConfig& config, const std::string& body) {
    if (body.empty()) return false;
    const std::string upper = to_upper(body);
    for (const auto& [pkg, enabled] : config.hooked_upi_apps) {
        if (!enabled) continue;
        static const char* keywords[] = {
            "SNAPMINT", "YESPRO", "YESPROUPI", "PHONEPE", "PAYTM", "GPAY", "AXIS",
            "HEROAXIS", "UPI", "VERIFY", "VERIFICATION", "OTP", "LOAN", nullptr};
        for (const char** kw = keywords; *kw; ++kw) {
            if (upper.find(*kw) != std::string::npos) return true;
        }
        (void)pkg;
    }
    return false;
}

bool should_block_outgoing(const ModuleConfig& config, const std::string& body) {
    if (!config.intercept_fake_success && !config.hook_outgoing_sms) return false;
    if (!body_matches_hooked_upi(config, body)) return false;
    return config.intercept_fake_success || config.hook_outgoing_sms;
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

bool read_isms_outgoing(JNIEnv* env, jobject data, std::string& dest, std::string& body) {
    if (!data) return false;
    reset_parcel(env, data);
    const std::string iface = parcel_read_string(env, data);
    if (iface.find("ISms") == std::string::npos) return false;

    jclass cls = env->GetObjectClass(data);
    jmethodID read_int = env->GetMethodID(cls, "readInt", "()I");
    env->CallIntMethod(data, read_int);  // subId
    parcel_read_string(env, data);       // callingPackage / attribution
    dest = parcel_read_string(env, data);
    parcel_read_string(env, data);  // scAddr
    body = parcel_read_string(env, data);
    return !dest.empty() && !body.empty();
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
        logger::info("OutgoingSms", "UPI compose intent dest=%s len=%zu", dest.c_str(), body.size());
        pipeline_outgoing(env, dest, body);
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
            if (read_isms_outgoing(env, data, dest, body)) {
                pipeline_outgoing(env, dest, body);
                if (should_block_outgoing(config, body)) {
                    logger::info("OutgoingSms", "Blocked ISms send dest=%s", dest.c_str());
                    write_ok_reply(env, reply);
                    return 0;
                }
            }
        }
    }

    const jint result =
        orig_BinderProxy_transact ? orig_BinderProxy_transact(env, thiz, code, data, reply, flags)
                                  : -1;

    if (result == 0 && reply && data) {
        ConfigManager::instance().reload();
        const auto& cfg = ConfigManager::instance().get();
        const bool spoof_on = cfg.enable_phone_spoof || cfg.enable_sim1_mock || cfg.enable_sim2_mock;
        if (spoof_on) {
            reset_parcel(env, data);
            const std::string iface = parcel_read_string(env, data);
            if (iface.find("ITelephony") != std::string::npos) {
                const std::string spoof = spoof_phone_for_config(cfg);
                if (!spoof.empty() && spoof != "AD-TEST-S") {
                    reset_parcel(env, reply);
                    jclass cls = env->GetObjectClass(reply);
                    jmethodID read_ex = env->GetMethodID(cls, "readException", "()V");
                    if (read_ex) env->CallVoidMethod(reply, read_ex);
                    const std::string current = parcel_read_string(env, reply);
                    std::string digits;
                    for (char c : current) {
                        if (std::isdigit(static_cast<unsigned char>(c))) digits += c;
                    }
                    if (digits.size() >= 10) {
                        reset_parcel(env, reply);
                        if (read_ex) env->CallVoidMethod(reply, read_ex);
                        parcel_write_string(env, reply, spoof);
                        logger::info("OutgoingSms", "Spoofed line1 -> %s", spoof.c_str());
                    }
                }
            }
        }
    }

    return result;
}

void install_plt_hooks(JNIEnv* env) {
    if (!g_api || !g_api->pltHookRegister || !g_api->pltHookCommit) return;

    g_api->pltHookRegister(".*libandroid_runtime\\.so$",
                           "Java_android_app_Instrumentation_execStartActivity",
                           reinterpret_cast<void*>(hook_execStartActivity),
                           reinterpret_cast<void**>(&orig_execStartActivity));

    g_api->pltHookRegister(".*libandroid_runtime\\.so$",
                           "Java_android_os_BinderProxy_transact",
                           reinterpret_cast<void*>(hook_BinderProxy_transact),
                           reinterpret_cast<void**>(&orig_BinderProxy_transact));

    g_api->pltHookCommit();
    logger::info("OutgoingSms", "execStartActivity + BinderProxy hooks committed");
}

}  // namespace

void install(JNIEnv* env, zygisk::Api* api, bool in_telephony, bool in_hooked_upi) {
    (void)in_telephony;
    g_api = api;
    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();
    if (!in_hooked_upi && !in_telephony) return;
    if (!config.hook_outgoing_sms && !config.intercept_fake_success &&
        !config.enable_phone_spoof && !config.enable_sim1_mock && !config.enable_sim2_mock) {
        return;
    }
    install_plt_hooks(env);
}

}  // namespace outgoing_sms_hook
