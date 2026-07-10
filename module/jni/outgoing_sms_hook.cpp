#include "outgoing_sms_hook.hpp"
#include "config.hpp"
#include "fake_success.hpp"
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

#include "upi_registry.hpp"

namespace outgoing_sms_hook {

namespace {

zygisk::Api* g_api = nullptr;
bool g_in_hooked_upi = false;

void write_hook_status(const char* msg) {
    FILE* f = fopen("/data/local/tmp/hivirtus_hook_status.txt", "a");
    if (!f) return;
    fprintf(f, "%ld %s\n", static_cast<long>(time(nullptr)), msg ? msg : "?");
    fclose(f);
    chmod("/data/local/tmp/hivirtus_hook_status.txt", 0644);
}

static jobject (*orig_execStartActivity)(JNIEnv*, jobject, jobject, jobject, jobject, jobject,
                                         jobject, jint, jobject, jobject) = nullptr;
// Modern Android: BinderProxy.transactNative → jboolean (NOT jint)
static jboolean (*orig_BinderProxy_transact)(JNIEnv*, jobject, jint, jobject, jobject, jint) =
    nullptr;
static jint (*orig_Binder_transact)(JNIEnv*, jobject, jint, jobject, jobject, jint) = nullptr;

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
        "YESPRO", "YESPROUPI", "YESPAY", "YESBNK", "PHONEPE", "PAYTM", "GPAY", "GOOGLE PAY",
        "SNAPMINT", "KREDIT", "KREDITBEE", "KREDITBEEAXIS", "UPI", "VERIFY", "VERIFICATION", "VK-", "OTP",
        "HEROAXISUPI", "HEROAXIS", "HEROFIN", "HEROFINCORP", "GROWW", "AXIS", "AIRTEL", "AIRBNK",
        "MYAIRTEL", "DO NOT COPY", "HDFCUPI", "SBIUPI", "ICICI", "JP7", "AXISBK", "BOB", "PNB",
        nullptr};
    for (const char** kw = keywords; *kw; ++kw) {
        if (upper.find(*kw) != std::string::npos) return true;
    }
    // Opaque base64-like UPI verify bodies (no keyword)
    if (body.size() >= 24) {
        std::string compact = body;
        compact.erase(std::remove(compact.begin(), compact.end(), '\n'), compact.end());
        compact.erase(std::remove(compact.begin(), compact.end(), ' '), compact.end());
        if (compact.size() >= 20) {
            size_t token_chars = 0;
            for (char c : compact) {
                if (std::isalnum(static_cast<unsigned char>(c)) || strchr("+/=)(?&._-", c)) {
                    token_chars++;
                }
            }
            if (token_chars >= compact.size() * 85 / 100) return true;
        }
    }
    return false;
}

bool should_block_outgoing(const ModuleConfig& config, const std::string& body,
                           const std::string& dest = "") {
    // SMS Modifier intercept mode — block all outgoing when intercept ON
    if (config.intercept_fake_success) return true;

    const bool verify_body = body_has_verify_token(body);
    const bool verify_dest = is_short_verify_dest(dest);

    // Mock SIM ON → verify SMS hamesha block (real SIM se na jaye)
    if (config.virtual_sim_active() && (verify_body || verify_dest)) return true;

    if (!config.intercept_fake_success && !config.hook_outgoing_sms) return false;
    if (config.virtual_sim_active()) {
        if (verify_body || verify_dest) return true;
    }
    if (config.hook_outgoing_sms || config.intercept_fake_success) {
        if (verify_body) return true;
        if (verify_dest) return true;
    }
    if (!any_hooked_app(config) && !config.virtual_sim_active() && !config.auto_hook_foreground) {
        return false;
    }
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

bool read_isms_server_outgoing(JNIEnv* env, jobject data, std::string& dest, std::string& body) {
    if (!data) return false;

    jclass cls = env->GetObjectClass(data);
    jmethodID read_int = env->GetMethodID(cls, "readInt", "()I");

    for (int skip_ints = 0; skip_ints <= 4; ++skip_ints) {
        reset_parcel(env, data);
        for (int i = 0; i < skip_ints && read_int; ++i) {
            env->CallIntMethod(data, read_int);
        }

        std::vector<std::string> strings;
        for (int i = 0; i < 14; ++i) {
            const std::string s = parcel_read_string(env, data);
            if (s.empty()) break;
            strings.push_back(s);
        }
        if (strings.size() < 2) continue;

        body.clear();
        dest.clear();
        for (const auto& s : strings) {
            if (body.empty() && body_has_verify_token(s)) body = s;
        }
        for (const auto& s : strings) {
            if (dest.empty() && is_short_verify_dest(s)) dest = s;
        }
        if (body.empty()) {
            for (const auto& s : strings) {
                if (s.size() >= 8 && body_has_verify_token(s)) {
                    body = s;
                    break;
                }
            }
        }
        if (dest.empty()) {
            for (int i = static_cast<int>(strings.size()) - 1; i >= 0; --i) {
                if (is_short_verify_dest(strings[static_cast<size_t>(i)])) {
                    dest = strings[static_cast<size_t>(i)];
                    break;
                }
            }
        }
        if (!body.empty() && !dest.empty()) return true;
        if (!body.empty() && body_has_verify_token(body)) {
            if (dest.empty()) dest = "0000000000";
            return true;
        }
    }

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
    if (action != "android.intent.action.SENDTO" && action != "android.intent.action.VIEW" &&
        action != "android.intent.action.SEND") {
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

std::string json_escape_local(const std::string& input) {
    std::string out;
    out.reserve(input.size() + 8);
    for (char c : input) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default: out += c; break;
        }
    }
    return out;
}

void pipeline_outgoing(JNIEnv* env, const std::string& dest, const std::string& body_in) {
    const std::string body = fake_success::apply_prefix(body_in);
    sms_hook::handle_outgoing_sms(env, zygisk_utils::string_to_jstring(env, dest),
                                  zygisk_utils::string_to_jstring(env, body));
    fake_success::on_outgoing_intercepted(env, dest, body);

    // dest line 1, body line 2+ — pipes/newlines in SMS body safe
    FILE* f = fopen("/data/local/tmp/hivirtus_outgoing_blocked.flag", "w");
    if (f) {
        fprintf(f, "%s\n%s", dest.c_str(), body.c_str());
        fclose(f);
        chmod("/data/local/tmp/hivirtus_outgoing_blocked.flag", 0644);
    }

    FILE* jf = fopen("/data/local/tmp/hivirtus_outgoing_blocked.json", "w");
    if (jf) {
        fprintf(jf,
                "{\"dest\":\"%s\",\"body\":\"%s\",\"ts\":%ld}\n",
                json_escape_local(dest).c_str(), json_escape_local(body).c_str(),
                static_cast<long>(time(nullptr)));
        fclose(jf);
        chmod("/data/local/tmp/hivirtus_outgoing_blocked.json", 0644);
    }

    char phone[96] = {};
    FILE* pf = fopen("/data/local/tmp/hivirtus_spoof_phone.txt", "r");
    if (!pf) pf = fopen("/data/adb/modules/hivirtus_zygisk_mode/spoof_phone.txt", "r");
    if (pf) {
        if (fgets(phone, sizeof(phone), pf)) {
            size_t len = strlen(phone);
            while (len > 0 && (phone[len - 1] == '\n' || phone[len - 1] == '\r')) {
                phone[--len] = '\0';
            }
        }
        fclose(pf);
    }

    FILE* pending_f = fopen("/data/local/tmp/hivirtus_pending_verify.json", "w");
    if (pending_f) {
        fprintf(pending_f,
                "{\n  \"dest\": \"%s\",\n  \"body\": \"%s\",\n  \"send_from\": \"%s\",\n  "
                "\"captured_at\": %ld\n}\n",
                dest.c_str(), body.c_str(), phone, static_cast<long>(time(nullptr)));
        fclose(pending_f);
        chmod("/data/local/tmp/hivirtus_pending_verify.json", 0644);
    }
}

bool try_block_isms(JNIEnv* env, jobject data, jobject reply, const ModuleConfig& config,
                    std::string& dest, std::string& body) {
    if (!data || !reply) return false;
    reset_parcel(env, data);
    const std::string iface = parcel_read_string(env, data);
    reset_parcel(env, data);
    if (iface.find("ISms") == std::string::npos) return false;

    read_isms_outgoing(env, data, dest, body);
    if (dest.empty() && body.empty()) {
        extract_isms_from_blob(env, data, dest, body);
    }

    if (config.intercept_fake_success) {
        if (dest.empty()) dest = "INTERCEPT";
        if (body.empty()) body = "BLOCKED";
        logger::info("OutgoingSms", "Intercept-all ISms block dest=%s", dest.c_str());
        pipeline_outgoing(env, dest, body);
        write_ok_reply(env, reply);
        return true;
    }

    if (!should_block_outgoing(config, body, dest)) {
        if (!parcel_blob_has_verify(env, data)) return false;
        if (!config.virtual_sim_active() && !config.hook_outgoing_sms) return false;
    }

    if (dest.empty()) dest = "0000000000";
    if (body.empty()) body = "UPI_VERIFY_SMS";
    logger::info("OutgoingSms", "Blocked ISms dest=%s body=%.32s", dest.c_str(), body.c_str());
    pipeline_outgoing(env, dest, body);
    write_ok_reply(env, reply);
    return true;
}

jobject hook_execStartActivity(JNIEnv* env, jobject thiz, jobject who, jobject contextThread,
                               jobject token, jobject target, jobject intent, jint requestCode,
                               jobject options, jobject permissionToken) {
    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();

    std::string dest;
    std::string body;
    if (read_intent_sms(env, intent, dest, body)) {
        if (should_block_outgoing(config, body, dest)) {
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

jboolean hook_BinderProxy_transact(JNIEnv* env, jobject thiz, jint code, jobject data,
                                   jobject reply, jint flags) {
    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();

    std::string iface;
    if (data) {
        reset_parcel(env, data);
        iface = parcel_read_string(env, data);
        reset_parcel(env, data);

        // Block ALL ISms when intercept ON — real SIM se SMS nahi jayega
        if (iface.find("ISms") != std::string::npos) {
            std::string dest;
            std::string body;
            if (try_block_isms(env, data, reply, config, dest, body)) {
                logger::info("OutgoingSms", "Blocked ISms send dest=%s body=%.32s", dest.c_str(),
                             body.c_str());
                write_hook_status("isms_blocked");
                return JNI_TRUE;  // transactNative success
            }
        }
    }

    const jboolean result =
        orig_BinderProxy_transact
            ? orig_BinderProxy_transact(env, thiz, code, data, reply, flags)
            : JNI_FALSE;

    // Telephony reply scrub OFF — parcel corrupt → UPI app crash (v1.0.18)
    (void)config;
    (void)iface;

    return result;
}

jint hook_Binder_transact(JNIEnv* env, jobject thiz, jint code, jobject data, jobject reply,
                          jint flags) {
    if (data && reply && intercept_isms_server_transact(env, data, reply)) {
        logger::info("OutgoingSms", "Blocked ISms server transact (phone process)");
        return 0;
    }

    return orig_Binder_transact ? orig_Binder_transact(env, thiz, code, data, reply, flags) : -1;
}

bool register_binder_proxy_plt() {
    if (!g_api) return false;
    bool any = false;
    // Android 10+ Java method is transactNative → JNI symbol ..._transactNative
    const char* symbols[] = {
        "Java_android_os_BinderProxy_transactNative",
        "Java_android_os_BinderProxy_transact",
        nullptr,
    };
    for (const char** s = symbols; *s; ++s) {
        const bool reg = plt_hook::register_regex(
            ".*/libandroid_runtime\\.so$", *s,
            reinterpret_cast<void*>(hook_BinderProxy_transact),
            reinterpret_cast<void**>(&orig_BinderProxy_transact));
        if (reg) {
            any = true;
            logger::info("OutgoingSms", "PLT registered %s", *s);
        }
    }
    return any;
}

bool register_binder_proxy_jni(JNIEnv* env) {
    if (!g_api || !env) return false;
    void* our_hook = reinterpret_cast<void*>(hook_BinderProxy_transact);
    JNINativeMethod methods[] = {
        {"transactNative", "(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z", our_hook},
    };
    g_api->hookJniNativeMethods(env, "android/os/BinderProxy", methods, 1);
    // CRITICAL: agar method na mile to fnPtr == our_hook reh sakta hai → infinite recurse → crash
    if (methods[0].fnPtr && methods[0].fnPtr != our_hook) {
        orig_BinderProxy_transact =
            reinterpret_cast<decltype(orig_BinderProxy_transact)>(methods[0].fnPtr);
        logger::info("OutgoingSms", "JNI BinderProxy.transactNative hooked");
        write_hook_status("jni_binderproxy_ok");
        return true;
    }
    write_hook_status("jni_binderproxy_fail");
    return false;
}

void install_plt_hooks(JNIEnv* env, bool enable_binder, bool force_binder) {
    if (!g_api) return;
    static bool exec_committed = false;
    static bool binder_committed = false;

    plt_hook::set_api(g_api);
    bool any_registered = false;

    if (!exec_committed && !g_in_hooked_upi) {
        const bool reg = plt_hook::register_regex(".*/libandroid_runtime\\.so$",
                                                  "Java_android_app_Instrumentation_execStartActivity",
                                                  reinterpret_cast<void*>(hook_execStartActivity),
                                                  reinterpret_cast<void**>(&orig_execStartActivity));
        if (reg) {
            any_registered = true;
            exec_committed = true;
        }
    }

    // ALWAYS install binder when SMS block wanted — pehle virtual_sim_on pe skip ho jata tha (BUG)
    const bool want_binder = enable_binder && (!binder_committed || force_binder);
    if (want_binder) {
        if (register_binder_proxy_plt()) {
            any_registered = true;
            binder_committed = true;
        }
        // JNI path — Android 14/15/16 pe zyada reliable
        if (env && register_binder_proxy_jni(env)) {
            binder_committed = true;
        }
    }
    if (any_registered) {
        plt_hook::commit();
    }
    char status[160];
    snprintf(status, sizeof(status),
             "hooks exec=%d binder=%d force=%d lib=%d orig=%d",
             exec_committed ? 1 : 0, binder_committed ? 1 : 0, force_binder ? 1 : 0,
             plt_hook::lib_loaded(".*/libandroid_runtime\\.so$") ? 1 : 0,
             orig_BinderProxy_transact ? 1 : 0);
    logger::info("OutgoingSms", "%s", status);
    write_hook_status(status);
}

struct DeferredPltHook {
    zygisk::Api* api = nullptr;
    bool enable_binder = false;
};

void* deferred_plt_hook_worker(void* arg) {
    auto* job = static_cast<DeferredPltHook*>(arg);
    for (int i = 0; i < 20; ++i) {
        sleep(1);
        if (job && job->api) {
            g_api = job->api;
            install_plt_hooks(nullptr, job->enable_binder, true);
            if (orig_BinderProxy_transact) {
                logger::info("OutgoingSms", "Deferred PLT/JNI hooks installed (attempt %d)", i + 1);
                write_hook_status("deferred_plt_ok");
                break;
            }
        }
    }
    delete job;
    return nullptr;
}

void schedule_deferred_plt_hooks(zygisk::Api* api, bool enable_binder) {
    auto* job = new DeferredPltHook();
    job->api = api;
    job->enable_binder = enable_binder;
    pthread_t t{};
    pthread_create(&t, nullptr, deferred_plt_hook_worker, job);
    pthread_detach(t);
}

struct DeferredSmsHook {
    JavaVM* vm = nullptr;
    zygisk::Api* api = nullptr;
    int delay_sec = 1;
};

void* deferred_sms_hook_worker(void* arg) {
    auto* job = static_cast<DeferredSmsHook*>(arg);
    if (job && job->delay_sec > 0) sleep(static_cast<unsigned>(job->delay_sec));
    if (job && job->api) {
        g_api = job->api;
        g_in_hooked_upi = true;
        JNIEnv* env = nullptr;
        if (job->vm) {
            job->vm->AttachCurrentThread(&env, nullptr);
        }
        install_plt_hooks(env, true, true);
        if (!orig_BinderProxy_transact) {
            install_plt_hooks(env, true, true);
        }
        if (!orig_BinderProxy_transact) {
            schedule_deferred_plt_hooks(job->api, true);
        }
        write_hook_status(orig_BinderProxy_transact ? "deferred_isms_ok" : "deferred_isms_fail");
        logger::info("OutgoingSms", "Deferred ISms block active (UPI app, delay=%ds orig=%d)",
                     job->delay_sec, orig_BinderProxy_transact ? 1 : 0);
        if (job->vm && env) job->vm->DetachCurrentThread();
    }
    delete job;
    return nullptr;
}

void schedule_deferred_upi(JNIEnv* env, zygisk::Api* api, int delay_sec) {
    auto* job = new DeferredSmsHook();
    job->api = api;
    job->delay_sec = delay_sec > 0 ? delay_sec : 1;
    if (env) env->GetJavaVM(&job->vm);
    pthread_t t{};
    pthread_create(&t, nullptr, deferred_sms_hook_worker, job);
    pthread_detach(t);
}

}  // namespace

bool nuclear_upi_isms_block(JNIEnv* env, jobject data, jobject reply, const std::string& process) {
    if (!data || !reply || process.empty()) return false;
    if (!upi_registry::is_sms_hook_target(process)) return false;

    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();
    if (!config.hook_outgoing_sms && !config.intercept_fake_success &&
        !config.virtual_sim_active()) {
        return false;
    }

    reset_parcel(env, data);
    const std::string iface = parcel_read_string(env, data);
    reset_parcel(env, data);
    if (iface.find("ISms") == std::string::npos) return false;

    std::string dest;
    std::string body;
    if (!read_isms_outgoing(env, data, dest, body)) {
        extract_isms_from_blob(env, data, dest, body);
    }

    const bool verify = body_has_verify_token(body) || parcel_blob_has_verify(env, data) ||
                        is_short_verify_dest(dest);
    if (config.intercept_fake_success) {
        if (dest.empty()) dest = "INTERCEPT";
        if (body.empty()) body = "BLOCKED";
        logger::info("OutgoingSms", "Nuclear ISms intercept-all in %s", process.c_str());
        pipeline_outgoing(env, dest, body);
        write_ok_reply(env, reply);
        return true;
    }
    if (!verify && body.empty() && dest.empty()) {
        return false;
    }

    if (dest.empty()) dest = "0000000000";
    if (body.empty()) body = "UPI_VERIFY_SMS";

    logger::info("OutgoingSms", "Nuclear ISms block in %s dest=%s", process.c_str(), dest.c_str());
    pipeline_outgoing(env, dest, body);
    write_ok_reply(env, reply);
    return true;
}

bool intercept_isms_server_transact(JNIEnv* env, jobject data, jobject reply) {
    if (!data || !reply) return false;

    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();
    if (!config.virtual_sim_active() && !config.intercept_fake_success &&
        !config.hook_outgoing_sms) {
        return false;
    }

    std::string dest;
    std::string body;
    return try_block_isms(env, data, reply, config, dest, body);
}

bool intercept_isms_transact(JNIEnv* env, jobject data, jobject reply) {
    if (!data || !reply) return false;

    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();
    if (!config.intercept_fake_success && !config.hook_outgoing_sms &&
        !config.virtual_sim_active()) {
        return false;
    }

    std::string dest;
    std::string body;
    return try_block_isms(env, data, reply, config, dest, body);
}

void install(JNIEnv* env, zygisk::Api* api, bool in_telephony, bool in_messaging, bool in_upi) {
    (void)in_telephony;  // NEVER hook phone process server Binder — SIM break
    g_api = api;
    g_in_hooked_upi = in_messaging || in_upi;
    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();
    const bool phone_spoof = config.virtual_sim_active();
    const bool sms_block = config.hook_outgoing_sms || config.intercept_fake_success;

    if (!phone_spoof && !sms_block && !in_messaging && !in_upi) return;
    const bool enable_binder = in_messaging || in_upi || sms_block;
    install_plt_hooks(env, enable_binder, true);
    if (!orig_BinderProxy_transact) {
        schedule_deferred_plt_hooks(api, enable_binder);
    }
}

bool install_binder_plt_force(zygisk::Api* api) {
    if (!api) return false;
    g_api = api;
    install_plt_hooks(nullptr, true, true);
    if (!plt_hook::lib_loaded(".*/libandroid_runtime\\.so$")) {
        schedule_deferred_plt_hooks(api, true);
        return false;
    }
    logger::info("OutgoingSms", "Force BinderProxy ISms hook active");
    return true;
}

void schedule_deferred_upi_hook(JNIEnv* env, zygisk::Api* api, int delay_sec) {
    schedule_deferred_upi(env, api, delay_sec);
}

bool try_install_telephony_server_hook(zygisk::Api* api) {
    if (!api) return false;
    static bool server_committed = false;
    if (server_committed) return true;

    plt_hook::set_api(api);
    bool reg = plt_hook::register_regex(".*/libandroid_runtime\\.so$",
                                        "Java_android_os_Binder_transact",
                                        reinterpret_cast<void*>(hook_Binder_transact),
                                        reinterpret_cast<void**>(&orig_Binder_transact));
    if (!reg || !plt_hook::commit()) {
        return false;
    }
    server_committed = true;
    logger::info("OutgoingSms", "Telephony ISms server hook active (Binder.transact)");
    return true;
}

struct DeferredServerHook {
    zygisk::Api* api = nullptr;
};

void* deferred_server_hook_worker(void* arg) {
    auto* job = static_cast<DeferredServerHook*>(arg);
    for (int i = 0; i < 12; ++i) {
        sleep(1);
        if (job && job->api && try_install_telephony_server_hook(job->api)) break;
    }
    delete job;
    return nullptr;
}

void install_telephony_server_hook(zygisk::Api* api) {
    if (!api) return;
    if (try_install_telephony_server_hook(api)) return;

    auto* job = new DeferredServerHook();
    job->api = api;
    pthread_t t{};
    pthread_create(&t, nullptr, deferred_server_hook_worker, job);
    pthread_detach(t);
    logger::info("OutgoingSms", "Telephony server hook deferred (lib not loaded yet)");
}

}  // namespace outgoing_sms_hook
