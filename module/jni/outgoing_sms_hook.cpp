#include "outgoing_sms_hook.hpp"
#include "config.hpp"
#include "fake_success.hpp"
#include "inline_hook.hpp"
#include "logger.hpp"
#include "plt_hook.hpp"
#include "sms_hook.hpp"
#include "telephony_spoof.hpp"
#include "zygisk_utils.hpp"

#include <vector>
#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstring>
#include <string>
#include <pthread.h>
#include <unistd.h>
#include <sys/stat.h>
#include <ctime>
#include <dlfcn.h>
#include <cstdint>

#include "upi_registry.hpp"

namespace outgoing_sms_hook {

namespace {

zygisk::Api* g_api = nullptr;
bool g_in_hooked_upi = false;
bool g_is_messaging_app = false;
bool g_is_telephony_server = false;
std::string g_upi_pkg;
// Fragile Hero: PLT Intent registered early but armed=false until UI up
volatile bool g_intercept_armed = true;

void ensure_pkg_diag_dir() {
    if (g_upi_pkg.empty()) return;
    char dir[512];
    snprintf(dir, sizeof(dir), "/data/user/0/%s/code_cache", g_upi_pkg.c_str());
    mkdir(dir, 0700);
    snprintf(dir, sizeof(dir), "/data/user/0/%s/code_cache/hivirtus", g_upi_pkg.c_str());
    mkdir(dir, 0700);
    snprintf(dir, sizeof(dir), "/data/data/%s/code_cache", g_upi_pkg.c_str());
    mkdir(dir, 0700);
    snprintf(dir, sizeof(dir), "/data/data/%s/code_cache/hivirtus", g_upi_pkg.c_str());
    mkdir(dir, 0700);
}

void write_diag_multi(const char* name, const char* content) {
    char path[512];
    snprintf(path, sizeof(path), "/data/local/tmp/%s", name);
    FILE* f = fopen(path, "w");
    if (f) {
        fputs(content, f);
        fclose(f);
        chmod(path, 0666);
    }
    // Permanent copy — TG forward must NEVER delete this (user checks this file)
    if (name && (strstr(name, "outgoing_blocked") || strstr(name, "pending_verify"))) {
        snprintf(path, sizeof(path), "/data/local/tmp/hivirtus_last_outgoing.json");
        if (strstr(name, ".flag")) {
            snprintf(path, sizeof(path), "/data/local/tmp/hivirtus_last_outgoing.flag");
        }
        f = fopen(path, "w");
        if (f) {
            fputs(content, f);
            fclose(f);
            chmod(path, 0666);
        }
    }
    snprintf(path, sizeof(path), "/data/adb/modules/hivirtus_zygisk_mode/%s", name);
    f = fopen(path, "w");
    if (f) {
        fputs(content, f);
        fclose(f);
        chmod(path, 0644);
    }
    if (!g_upi_pkg.empty()) {
        ensure_pkg_diag_dir();
        snprintf(path, sizeof(path), "/data/user/0/%s/code_cache/hivirtus/%s", g_upi_pkg.c_str(),
                 name);
        f = fopen(path, "w");
        if (!f) {
            snprintf(path, sizeof(path), "/data/data/%s/code_cache/hivirtus/%s", g_upi_pkg.c_str(),
                     name);
            f = fopen(path, "w");
        }
        if (f) {
            fputs(content, f);
            fclose(f);
        }
    }
}

void write_hook_status(const char* msg) {
    char line[320];
    snprintf(line, sizeof(line), "%ld %s\n", static_cast<long>(time(nullptr)), msg ? msg : "?");
    auto append_one = [&](const char* path) {
        FILE* f = fopen(path, "a");
        if (!f) {
            // Create if missing
            f = fopen(path, "w");
            if (!f) return;
        }
        fputs(line, f);
        fclose(f);
        chmod(path, 0666);
    };
    auto write_latest = [&](const char* path) {
        FILE* f = fopen(path, "w");
        if (!f) return;
        fputs(line, f);
        fclose(f);
        chmod(path, 0666);
    };

    // Root-seeded tmp (companion) + module + app code_cache
    append_one("/data/local/tmp/hivirtus_hook_status.txt");
    write_latest("/data/local/tmp/hivirtus_hook_status_latest.txt");
    append_one("/data/adb/modules/hivirtus_zygisk_mode/hook_status.txt");
    write_latest("/data/adb/modules/hivirtus_zygisk_mode/hook_status_latest.txt");

    if (!g_upi_pkg.empty()) {
        ensure_pkg_diag_dir();
        std::string p = "/data/user/0/" + g_upi_pkg + "/code_cache/hivirtus/hook_status.txt";
        append_one(p.c_str());
        p = "/data/user/0/" + g_upi_pkg + "/code_cache/hivirtus/hook_status_latest.txt";
        write_latest(p.c_str());
        p = "/data/data/" + g_upi_pkg + "/code_cache/hivirtus/hook_status.txt";
        append_one(p.c_str());
        // filesDir fallback (often writable when code_cache odd)
        p = "/data/user/0/" + g_upi_pkg + "/files/hivirtus_hook_status.txt";
        append_one(p.c_str());
        p = "/data/data/" + g_upi_pkg + "/files/hivirtus_hook_status.txt";
        append_one(p.c_str());
    }
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
    // UPI verify shortcodes + 10-digit / leading-0 11-digit numbers
    return d.length() >= 4 && d.length() <= 12;
}

bool body_has_verify_token(const std::string& body) {
    if (body.empty()) return false;
    const std::string upper = to_upper(body);
    static const char* keywords[] = {
        "YESPRO", "YESPROUPI", "YESPAY", "YESBNK", "PHONEPE", "PAYTM", "GPAY", "GOOGLE PAY",
        "SNAPMINT", "KREDIT", "KREDITBEE", "KREDITBEEAXIS", "UPI", "VERIFY", "VERIFICATION", "VK-", "OTP",
        "HEROAXISUPI", "HEROAXIS", "HEROFIN", "HEROFINCORP", "HERO", "GROWW", "AXIS", "AIRTEL", "AIRBNK",
        "MYAIRTEL", "DO NOT COPY", "DO NOT", "HDFCUPI", "SBIUPI", "ICICI", "JP7", "AXISBK", "BOB", "PNB",
        "FORWARD OR SHARE", "UNDER ANY CIRCUMSTANCE",
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

// Messages: SMSTweaks intercept-ALL — A16 pe transaction codes shift → code-range miss = real SIM.
// UPI apps: content-only (code-range alone crashes banks).
bool looks_like_isms_send(jint code, const std::string& dest, const std::string& body,
                          bool blob_verify, bool /*blob_sms*/) {
    if (blob_verify) return true;
    if (!body.empty() && body_has_verify_token(body)) return true;
    if (!dest.empty() && is_short_verify_dest(dest)) return true;
    if (!dest.empty() && !body.empty()) return true;
    if (!dest.empty() && dest.size() <= 14 && is_short_verify_dest(dest)) return true;
    if (g_is_messaging_app || g_is_telephony_server) {
        // NUCLEAR: Messages/mms/phone pe koi bhi ISms send* — parse miss pe bhi block
        // (HEROAXISUPI real SIM tab jata tha jab code A16 pe 4..32 se bahar tha)
        if (code >= 1) return true;
        if (!body.empty() || !dest.empty()) return true;
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
    jstring js = (jstring)env->CallObjectMethod(parcel, read);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return "";
    }
    return zygisk_utils::jstring_to_string(env, js);
}

/** Gamex needs PendingIntent objects — try readParcelable from ISms data parcel. */
jobject parcel_read_pending_intent(JNIEnv* env, jobject parcel) {
    if (!env || !parcel) return nullptr;
    jclass parcel_cls = env->GetObjectClass(parcel);
    jclass pi_cls = env->FindClass("android/app/PendingIntent");
    if (!parcel_cls || !pi_cls) return nullptr;

    // API 33+: readParcelable(ClassLoader, Class)
    jmethodID read33 = env->GetMethodID(
        parcel_cls, "readParcelable",
        "(Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/Object;");
    if (read33) {
        jobject obj = env->CallObjectMethod(parcel, read33, nullptr, pi_cls);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return nullptr;
        }
        if (obj && env->IsInstanceOf(obj, pi_cls)) return obj;
        return nullptr;
    }

    jmethodID read_old = env->GetMethodID(
        parcel_cls, "readParcelable", "(Ljava/lang/ClassLoader;)Landroid/os/Parcelable;");
    if (!read_old) return nullptr;
    jobject obj = env->CallObjectMethod(parcel, read_old, nullptr);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    if (obj && env->IsInstanceOf(obj, pi_cls)) return obj;
    return nullptr;
}

/**
 * After iface token + strings (callingPkg/dest/sc/text), next two Parcelables are often
 * sentIntent + deliveryIntent (AOSP ISms.sendText*). Best-effort — never crash.
 */
void try_read_isms_pending_intents(JNIEnv* env, jobject data, jobject* out_sent,
                                   jobject* out_delivery) {
    if (out_sent) *out_sent = nullptr;
    if (out_delivery) *out_delivery = nullptr;
    if (!env || !data) return;

    // Try a few common layouts: skip 0..2 ints after iface, then 2..5 strings, then PIs
    for (int skip_ints = 0; skip_ints <= 2; ++skip_ints) {
        for (int n_strings = 2; n_strings <= 5; ++n_strings) {
            reset_parcel(env, data);
            const std::string iface = parcel_read_string(env, data);
            if (iface.find("ISms") == std::string::npos) return;

            jclass cls = env->GetObjectClass(data);
            jmethodID read_int = env->GetMethodID(cls, "readInt", "()I");
            for (int i = 0; i < skip_ints; ++i) {
                if (read_int) env->CallIntMethod(data, read_int);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    break;
                }
            }

            bool strings_ok = true;
            for (int i = 0; i < n_strings; ++i) {
                parcel_read_string(env, data);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    strings_ok = false;
                    break;
                }
            }
            if (!strings_ok) continue;

            jobject sent = parcel_read_pending_intent(env, data);
            jobject del = parcel_read_pending_intent(env, data);
            if (sent || del) {
                if (out_sent) *out_sent = sent;
                if (out_delivery) *out_delivery = del;
                logger::info("OutgoingSms", "ISms PendingIntents extracted ints=%d strs=%d sent=%d del=%d",
                             skip_ints, n_strings, sent ? 1 : 0, del ? 1 : 0);
                reset_parcel(env, data);
                return;
            }
        }
    }
    reset_parcel(env, data);
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

bool parcel_blob_has_sms_compose(JNIEnv* env, jobject data) {
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
    const std::string upper = to_upper(blob);
    if (upper.find("SMSTO:") != std::string::npos) return true;
    if (upper.find("SMS:") != std::string::npos) return true;
    if (upper.find("ANDROID.INTENT.ACTION.SENDTO") != std::string::npos) return true;
    if (upper.find("VND.ANDROID-DIR/MMS-SMS") != std::string::npos) return true;
    if (upper.find("SMS_BODY") != std::string::npos) return true;
    if (body_has_verify_token(blob)) return true;
    return false;
}

void extract_sms_compose_from_blob(JNIEnv* env, jobject data, std::string& dest, std::string& body) {
    dest.clear();
    body.clear();
    if (!data) return;
    jclass cls = env->GetObjectClass(data);
    jmethodID marshall = env->GetMethodID(cls, "marshall", "()[B");
    if (!marshall) return;
    reset_parcel(env, data);
    jbyteArray bytes = (jbyteArray)env->CallObjectMethod(data, marshall);
    if (!bytes) return;
    const jsize len = env->GetArrayLength(bytes);
    if (len <= 0) return;
    jbyte* raw = env->GetByteArrayElements(bytes, nullptr);
    if (!raw) return;
    std::string blob(reinterpret_cast<char*>(raw), static_cast<size_t>(len));
    env->ReleaseByteArrayElements(bytes, raw, JNI_ABORT);
    reset_parcel(env, data);

    // Prefer verify-token chunk as body
    for (size_t i = 0; i < blob.size(); ++i) {
        if (!std::isprint(static_cast<unsigned char>(blob[i]))) continue;
        size_t j = i;
        while (j < blob.size() && std::isprint(static_cast<unsigned char>(blob[j])) &&
               blob[j] != '\0')
            j++;
        const std::string chunk = blob.substr(i, j - i);
        if (chunk.size() >= 8 && body_has_verify_token(chunk) && chunk.size() > body.size()) {
            body = chunk;
        }
        i = j;
    }
    // smsto:DEST
    const std::string upper = to_upper(blob);
    size_t p = upper.find("SMSTO:");
    if (p != std::string::npos) {
        std::string d;
        for (size_t i = p + 6; i < blob.size(); ++i) {
            char c = blob[i];
            if (!std::isdigit(static_cast<unsigned char>(c)) && c != '+' && c != '-') break;
            d += c;
        }
        if (!d.empty()) dest = d;
    }
    if (dest.empty()) dest = "INTERCEPT";
    if (body.empty()) body = "BLOCKED";
}

bool extract_isms_from_blob(JNIEnv* env, jobject data, std::string& dest, std::string& body) {
    dest.clear();
    body.clear();
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

    // A16 parcels = UTF-16LE strings. ASCII-only scan misses HEROAXISUPI / dest.
    std::vector<std::string> strs;
    auto push_unique = [&](const std::string& s) {
        if (s.size() < 2) return;
        // skip binder iface noise
        if (s.find("android.") == 0 || s.find("com.android") == 0) return;
        if (s.find("ISms") != std::string::npos || s == "isms") return;
        for (const auto& e : strs)
            if (e == s) return;
        strs.push_back(s);
    };

    // UTF-16LE runs (allow \n \r \t)
    for (size_t i = 0; i + 1 < blob.size();) {
        const unsigned char c = static_cast<unsigned char>(blob[i]);
        const unsigned char n = static_cast<unsigned char>(blob[i + 1]);
        const bool ok = (n == 0) && ((c >= 0x20 && c < 0x7f) || c == '\n' || c == '\r' || c == '\t');
        if (!ok) {
            ++i;
            continue;
        }
        std::string s;
        size_t j = i;
        while (j + 1 < blob.size()) {
            const unsigned char cj = static_cast<unsigned char>(blob[j]);
            const unsigned char nj = static_cast<unsigned char>(blob[j + 1]);
            if (nj != 0) break;
            if (!((cj >= 0x20 && cj < 0x7f) || cj == '\n' || cj == '\r' || cj == '\t')) break;
            s += static_cast<char>(cj);
            j += 2;
        }
        push_unique(s);
        i = j > i ? j : i + 1;
    }
    // ASCII runs
    for (size_t i = 0; i < blob.size();) {
        if (!std::isprint(static_cast<unsigned char>(blob[i])) || blob[i] == '\0') {
            ++i;
            continue;
        }
        size_t j = i;
        while (j < blob.size() && std::isprint(static_cast<unsigned char>(blob[j])) &&
               blob[j] != '\0')
            ++j;
        push_unique(blob.substr(i, j - i));
        i = j > i ? j : i + 1;
    }

    for (const auto& s : strs) {
        if (body.empty() && body_has_verify_token(s)) body = s;
    }
    if (body.empty()) {
        for (const auto& s : strs) {
            if (s.size() >= 12 && s.find('.') == std::string::npos &&
                s.find('/') == std::string::npos) {
                // long opaque token body
                size_t alnum = 0;
                for (char c : s)
                    if (std::isalnum(static_cast<unsigned char>(c)) || strchr("+/=)(?&._- ", c))
                        ++alnum;
                if (alnum * 10 >= s.size() * 7 && s.size() > body.size()) body = s;
            }
        }
    }
    for (const auto& s : strs) {
        if (dest.empty() && is_short_verify_dest(s)) dest = s;
    }
    // digit runs from UTF-16 decoded strings already covered; also scan flattened UTF-16 ascii
    if (dest.empty()) {
        std::string flat;
        for (size_t i = 0; i + 1 < blob.size(); ++i) {
            if (blob[i + 1] == 0 && std::isdigit(static_cast<unsigned char>(blob[i])))
                flat += blob[i];
            else if (!flat.empty()) {
                if (is_short_verify_dest(flat)) {
                    dest = flat;
                    break;
                }
                flat.clear();
            }
        }
        if (dest.empty() && is_short_verify_dest(flat)) dest = flat;
    }

    if (body.empty() && blob_contains_needle(blob, "HEROAXIS")) {
        // last resort: pull around HEROAXIS marker
        for (const auto& s : strs) {
            if (to_upper(s).find("HERO") != std::string::npos) {
                body = s;
                break;
            }
        }
    }
    if (dest.empty() && !body.empty()) dest = "0000000000";
    return !body.empty();
}

/** True when parcel looks like an OUTGOING send (not ISms status/query). */
bool blob_looks_like_outgoing_send(const std::string& blob) {
    if (blob.size() < 8) return false;
    if (blob_contains_needle(blob, "sendText")) return true;
    if (blob_contains_needle(blob, "sendMultipart")) return true;
    if (blob_contains_needle(blob, "sendDataMessage")) return true;
    if (blob_contains_needle(blob, "sendRawPdu")) return true;
    if (blob_contains_needle(blob, "HEROAXIS")) return true;
    if (blob_contains_needle(blob, "AXISUPI")) return true;
    if (blob_contains_needle(blob, "DO NOT COPY")) return true;
    if (blob_contains_needle(blob, "YESPRO")) return true;
    if (blob_contains_needle(blob, "smsto:")) return true;
    if (blob_contains_needle(blob, "SMSTO:")) return true;
    if (blob_contains_needle(blob, "pp-")) return true;
    return false;
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

// ISms send* are void — reply MUST be writeNoException only.
// Extra writeInt(0) after that can make UPI SDKs treat the call as failed
// ("Verification Failed / No permission" style).
void write_isms_trace(const char* msg) {
    FILE* f = fopen("/data/local/tmp/hivirtus_isms_trace.txt", "a");
    if (!f) return;
    fprintf(f, "%ld %s\n", static_cast<long>(time(nullptr)), msg ? msg : "?");
    fclose(f);
    chmod("/data/local/tmp/hivirtus_isms_trace.txt", 0666);
}

bool blob_contains_needle(const std::string& blob, const char* ascii) {
    if (!ascii || !*ascii || blob.empty()) return false;
    if (blob.find(ascii) != std::string::npos) return true;
    // Parcel strings are UTF-16LE — "ISms" appears as I\0S\0m\0s\0
    std::string u16;
    u16.reserve(std::strlen(ascii) * 2);
    for (const char* p = ascii; *p; ++p) {
        u16.push_back(*p);
        u16.push_back('\0');
    }
    return blob.find(u16) != std::string::npos;
}

std::string parcel_marshall_blob(JNIEnv* env, jobject data) {
    if (!env || !data) return {};
    jclass cls = env->GetObjectClass(data);
    jmethodID marshall = env->GetMethodID(cls, "marshall", "()[B");
    if (!marshall) return {};
    reset_parcel(env, data);
    jbyteArray bytes = (jbyteArray)env->CallObjectMethod(data, marshall);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        reset_parcel(env, data);
        return {};
    }
    if (!bytes) {
        reset_parcel(env, data);
        return {};
    }
    const jsize len = env->GetArrayLength(bytes);
    std::string blob;
    if (len > 0) {
        jbyte* raw = env->GetByteArrayElements(bytes, nullptr);
        if (raw) {
            blob.assign(reinterpret_cast<char*>(raw), static_cast<size_t>(len));
            env->ReleaseByteArrayElements(bytes, raw, JNI_ABORT);
        }
    }
    reset_parcel(env, data);
    return blob;
}

bool messaging_blob_looks_like_sms_send(const std::string& blob) {
    if (blob.size() < 8) return false;
    if (blob_contains_needle(blob, "ISms")) return true;
    if (blob_contains_needle(blob, "isms")) return true;
    if (blob_contains_needle(blob, "ISmsEx")) return true;
    if (blob_contains_needle(blob, "IMms")) return true;
    if (blob_contains_needle(blob, "sendText")) return true;
    if (blob_contains_needle(blob, "sendMultipart")) return true;
    if (blob_contains_needle(blob, "sendDataMessage")) return true;
    if (blob_contains_needle(blob, "sendRawPdu")) return true;
    if (blob_contains_needle(blob, "HEROAXIS")) return true;
    if (blob_contains_needle(blob, "AXISUPI")) return true;
    if (blob_contains_needle(blob, "DO NOT COPY")) return true;
    if (blob_contains_needle(blob, "smsto:")) return true;
    if (blob_contains_needle(blob, "SMSTO:")) return true;
    if (blob_contains_needle(blob, "IMotoSms")) return true;
    if (blob_contains_needle(blob, "MotoSms")) return true;
    // UPI verify-ish tokens commonly in body
    if (blob_contains_needle(blob, "pp-")) return true;
    return false;
}

// ISms send* are void — reply MUST be writeNoException only.
void write_ok_reply(JNIEnv* env, jobject reply) {
    if (!reply) return;
    reset_parcel(env, reply);
    jclass cls = env->GetObjectClass(reply);
    jmethodID write_no_ex = env->GetMethodID(cls, "writeNoException", "()V");
    if (write_no_ex) {
        env->CallVoidMethod(reply, write_no_ex);
        if (env->ExceptionCheck()) env->ExceptionClear();
        return;
    }
    jmethodID write_int = env->GetMethodID(cls, "writeInt", "(I)V");
    if (write_int) env->CallVoidMethod(reply, write_int, 0);
}

bool intent_has_sms_markers(JNIEnv* env, jobject intent) {
    if (!intent) return false;
    jclass intent_cls = env->GetObjectClass(intent);
    jmethodID get_action = env->GetMethodID(intent_cls, "getAction", "()Ljava/lang/String;");
    jmethodID get_data = env->GetMethodID(intent_cls, "getDataString", "()Ljava/lang/String;");
    jmethodID get_type = env->GetMethodID(intent_cls, "getType", "()Ljava/lang/String;");
    jmethodID get_extra = env->GetMethodID(intent_cls, "getStringExtra",
                                            "(Ljava/lang/String;)Ljava/lang/String;");
    const std::string action =
        get_action ? zygisk_utils::jstring_to_string(
                         env, (jstring)env->CallObjectMethod(intent, get_action))
                   : "";
    const std::string data =
        get_data ? zygisk_utils::jstring_to_string(env,
                                                   (jstring)env->CallObjectMethod(intent, get_data))
                 : "";
    const std::string type =
        get_type ? zygisk_utils::jstring_to_string(env,
                                                   (jstring)env->CallObjectMethod(intent, get_type))
                 : "";
    std::string lower = to_upper(data + " " + action + " " + type);
    if (lower.find("SMSTO:") != std::string::npos || lower.find("SMS:") != std::string::npos)
        return true;
    if (lower.find("SENDTO") != std::string::npos &&
        (lower.find("SMS") != std::string::npos || lower.find("SMSTO") != std::string::npos))
        return true;
    if (type.find("mms-sms") != std::string::npos) return true;
    if (get_extra) {
        std::string body = zygisk_utils::jstring_to_string(
            env, (jstring)env->CallObjectMethod(intent, get_extra, env->NewStringUTF("sms_body")));
        if (!body.empty()) return true;
    }
    return false;
}

bool read_intent_sms(JNIEnv* env, jobject intent, std::string& dest, std::string& body) {
    if (!intent) return false;
    jclass intent_cls = env->GetObjectClass(intent);

    jmethodID get_action = env->GetMethodID(intent_cls, "getAction", "()Ljava/lang/String;");
    jmethodID get_data = env->GetMethodID(intent_cls, "getData", "()Landroid/net/Uri;");
    jmethodID get_extra = env->GetMethodID(intent_cls, "getStringExtra",
                                            "(Ljava/lang/String;)Ljava/lang/String;");

    const std::string action =
        get_action ? zygisk_utils::jstring_to_string(
                         env, (jstring)env->CallObjectMethod(intent, get_action))
                   : "";

    body = get_extra ? zygisk_utils::jstring_to_string(
                           env, (jstring)env->CallObjectMethod(intent, get_extra,
                                                              env->NewStringUTF("sms_body")))
                     : "";
    if (body.empty() && get_extra) {
        body = zygisk_utils::jstring_to_string(
            env, (jstring)env->CallObjectMethod(intent, get_extra,
                                                env->NewStringUTF("android.intent.extra.TEXT")));
    }

    jobject uri = get_data ? env->CallObjectMethod(intent, get_data) : nullptr;
    if (uri) {
        jclass uri_cls = env->GetObjectClass(uri);
        jmethodID get_scheme = env->GetMethodID(uri_cls, "getScheme", "()Ljava/lang/String;");
        const std::string scheme =
            get_scheme ? zygisk_utils::jstring_to_string(
                             env, (jstring)env->CallObjectMethod(uri, get_scheme))
                       : "";
        if (scheme == "smsto" || scheme == "sms") {
            jmethodID get_ssp =
                env->GetMethodID(uri_cls, "getSchemeSpecificPart", "()Ljava/lang/String;");
            if (get_ssp) {
                dest = zygisk_utils::jstring_to_string(
                    env, (jstring)env->CallObjectMethod(uri, get_ssp));
            }
            // CRITICAL: body empty ho to bhi block (Hero → Messages fill later)
            return true;
        }
    }

    if (action == "android.intent.action.SENDTO" || action == "android.intent.action.SEND" ||
        action == "android.intent.action.VIEW") {
        if (intent_has_sms_markers(env, intent)) return true;
    }
    return intent_has_sms_markers(env, intent);
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

void pipeline_outgoing(JNIEnv* env, const std::string& dest, const std::string& body_in,
                       jobject sent_intent = nullptr, jobject delivery_intent = nullptr) {
    const std::string body = fake_success::apply_prefix(body_in);
    sms_hook::handle_outgoing_sms(env, zygisk_utils::string_to_jstring(env, dest),
                                  zygisk_utils::string_to_jstring(env, body));
    // Gamex handleSmsResult: insert + PendingIntent RESULT_OK + SMS_SENT broadcasts
    fake_success::on_outgoing_intercepted(env, dest, body, sent_intent, delivery_intent);

    // A16: app UID often cannot create /data/local/tmp files — write module + code_cache too
    char flag_buf[2048];
    snprintf(flag_buf, sizeof(flag_buf), "%s\n%s", dest.c_str(), body.c_str());
    write_diag_multi("hivirtus_outgoing_blocked.flag", flag_buf);

    char json_buf[4096];
    snprintf(json_buf, sizeof(json_buf),
             "{\"dest\":\"%s\",\"body\":\"%s\",\"pkg\":\"%s\",\"ts\":%ld}\n",
             json_escape_local(dest).c_str(), json_escape_local(body).c_str(),
             g_upi_pkg.c_str(), static_cast<long>(time(nullptr)));
    write_diag_multi("hivirtus_outgoing_blocked.json", json_buf);
    write_hook_status("outgoing_json_written");

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

    char pending_buf[4096];
    snprintf(pending_buf, sizeof(pending_buf),
             "{\n  \"dest\": \"%s\",\n  \"body\": \"%s\",\n  \"send_from\": \"%s\",\n  "
             "\"pkg\": \"%s\",\n  \"captured_at\": %ld\n}\n",
             json_escape_local(dest).c_str(), json_escape_local(body).c_str(), phone,
             g_upi_pkg.c_str(), static_cast<long>(time(nullptr)));
    write_diag_multi("hivirtus_pending_verify.json", pending_buf);
}

bool try_block_isms(JNIEnv* env, jobject data, jobject reply, const ModuleConfig& config,
                    std::string& dest, std::string& body, jint code) {
    // reply may be null on FLAG_ONEWAY — still must block real SIM (Messages A16)
    if (!data) return false;
    reset_parcel(env, data);
    const std::string iface = parcel_read_string(env, data);
    reset_parcel(env, data);
    // Match ISms / isms / Sms (AOSP + OEM descriptors)
    const bool is_sms_iface =
        iface.find("ISms") != std::string::npos || iface.find("isms") != std::string::npos ||
        iface.find("ISmsEx") != std::string::npos || iface.find("IMms") != std::string::npos;
    if (!is_sms_iface) return false;

    read_isms_outgoing(env, data, dest, body);
    if (dest.empty() && body.empty()) {
        extract_isms_from_blob(env, data, dest, body);
    }
    const bool blob_verify = parcel_blob_has_verify(env, data);
    const bool blob_sms = parcel_blob_has_sms_compose(env, data);

    if (!looks_like_isms_send(code, dest, body, blob_verify, blob_sms)) {
        // Messages: still log miss for debug
        if (g_is_messaging_app) {
            char miss[160];
            snprintf(miss, sizeof(miss), "isms_passthrough code=%d destlen=%zu bodylen=%zu",
                     (int)code, dest.size(), body.size());
            write_hook_status(miss);
        }
        return false;
    }

    const bool want = config.intercept_fake_success || config.hook_outgoing_sms ||
                      blob_verify || g_in_hooked_upi || g_is_messaging_app;
    if (!want) return false;

    if (dest.empty()) dest = "0000000000";
    if (body.empty()) {
        if (blob_verify) body = "UPI_VERIFY_SMS";
        else if (g_is_messaging_app) body = "MSG_SMS_INTERCEPT";
        else body = "SMS_INTERCEPT";
    }
    logger::info("OutgoingSms", "ISms SAFE block code=%d dest=%s body=%.60s msg=%d reply=%d",
                 (int)code, dest.c_str(), body.c_str(), g_is_messaging_app ? 1 : 0,
                 reply ? 1 : 0);

    jobject sent_pi = nullptr;
    jobject del_pi = nullptr;
    try_read_isms_pending_intents(env, data, &sent_pi, &del_pi);

    pipeline_outgoing(env, dest, body, sent_pi, del_pi);
    if (reply) write_ok_reply(env, reply);
    write_hook_status("isms_blocked_ok");
    return true;
}

jobject hook_execStartActivity(JNIEnv* env, jobject thiz, jobject who, jobject contextThread,
                               jobject token, jobject target, jobject intent, jint requestCode,
                               jobject options, jobject permissionToken) {
    // Fragile: registered in pre but not armed yet — pass-through (open crash fix)
    if (!g_intercept_armed) {
        if (!orig_execStartActivity) return nullptr;
        return orig_execStartActivity(env, thiz, who, contextThread, token, target, intent,
                                      requestCode, options, permissionToken);
    }
    ConfigManager::instance().reload();
    auto config = ConfigManager::instance().get();
    // Hero/A16 often SENDTO → Messages → real SIM; force block in UPI process
    if (g_in_hooked_upi) {
        config.intercept_fake_success = true;
        config.hook_outgoing_sms = true;
    }

    std::string dest;
    std::string body;
    if (read_intent_sms(env, intent, dest, body) ||
        (g_in_hooked_upi && intent_has_sms_markers(env, intent))) {
        if (should_block_outgoing(config, body, dest) || g_in_hooked_upi) {
            logger::info("OutgoingSms", "Blocked compose intent dest=%s len=%zu", dest.c_str(),
                         body.size());
            if (dest.empty()) dest = "INTERCEPT";
            if (body.empty()) body = "BLOCKED";
            pipeline_outgoing(env, dest, body);
            write_hook_status("intent_sms_blocked");
            return nullptr;
        }
    }

    if (!orig_execStartActivity) return nullptr;
    return orig_execStartActivity(env, thiz, who, contextThread, token, target, intent,
                                  requestCode, options, permissionToken);
}

// Android 10+ overload without permissionToken
jobject hook_execStartActivity7(JNIEnv* env, jobject thiz, jobject who, jobject contextThread,
                                jobject token, jobject target, jobject intent, jint requestCode,
                                jobject options) {
    return hook_execStartActivity(env, thiz, who, contextThread, token, target, intent,
                                  requestCode, options, nullptr);
}

static jobject (*orig_execStartActivity7)(JNIEnv*, jobject, jobject, jobject, jobject, jobject,
                                          jobject, jint, jobject) = nullptr;

jobject hook_execStartActivity7_wrap(JNIEnv* env, jobject thiz, jobject who, jobject contextThread,
                                     jobject token, jobject target, jobject intent,
                                     jint requestCode, jobject options) {
    ConfigManager::instance().reload();
    auto config = ConfigManager::instance().get();
    if (g_in_hooked_upi) {
        config.intercept_fake_success = true;
        config.hook_outgoing_sms = true;
    }
    std::string dest;
    std::string body;
    if (read_intent_sms(env, intent, dest, body) ||
        (g_in_hooked_upi && intent_has_sms_markers(env, intent))) {
        if (should_block_outgoing(config, body, dest) || g_in_hooked_upi) {
            if (dest.empty()) dest = "INTERCEPT";
            if (body.empty()) body = "BLOCKED";
            pipeline_outgoing(env, dest, body);
            write_hook_status("intent_sms_blocked7");
            return nullptr;
        }
    }
    if (orig_execStartActivity7)
        return orig_execStartActivity7(env, thiz, who, contextThread, token, target, intent,
                                       requestCode, options);
    return nullptr;
}

bool register_instrumentation_jni(JNIEnv* env) {
    if (!g_api || !env) return false;
    bool ok = false;
    {
        void* h = reinterpret_cast<void*>(hook_execStartActivity7_wrap);
        JNINativeMethod m[] = {
            {"execStartActivity",
             "(Landroid/content/Context;Landroid/os/IBinder;Landroid/os/IBinder;Landroid/app/"
             "Activity;Landroid/content/Intent;ILandroid/os/Bundle;)Landroid/app/Instrumentation$"
             "ActivityResult;",
             h},
        };
        g_api->hookJniNativeMethods(env, "android/app/Instrumentation", m, 1);
        if (m[0].fnPtr && m[0].fnPtr != h) {
            orig_execStartActivity7 =
                reinterpret_cast<decltype(orig_execStartActivity7)>(m[0].fnPtr);
            ok = true;
            write_hook_status("instrumentation_jni7_ok");
        }
    }
    {
        void* h = reinterpret_cast<void*>(hook_execStartActivity);
        JNINativeMethod m[] = {
            {"execStartActivity",
             "(Landroid/content/Context;Landroid/os/IBinder;Landroid/os/IBinder;Landroid/app/"
             "Activity;Landroid/content/Intent;ILandroid/os/Bundle;Ljava/lang/Object;)Landroid/app/"
             "Instrumentation$ActivityResult;",
             h},
        };
        g_api->hookJniNativeMethods(env, "android/app/Instrumentation", m, 1);
        if (m[0].fnPtr && m[0].fnPtr != h) {
            orig_execStartActivity =
                reinterpret_cast<decltype(orig_execStartActivity)>(m[0].fnPtr);
            ok = true;
            write_hook_status("instrumentation_jni8_ok");
        }
    }
    return ok;
}

static volatile int g_binder_live_logged = 0;

jboolean hook_BinderProxy_transact(JNIEnv* env, jobject thiz, jint code, jobject data,
                                   jobject reply, jint flags) {
    if (!orig_BinderProxy_transact) {
        write_hook_status("FATAL_binder_orig_null");
        return JNI_TRUE;
    }

    // Proof hook is actually in the call path (PLT-only was false OK before)
    if (!g_binder_live_logged) {
        g_binder_live_logged = 1;
        write_hook_status("binder_hook_live");
        write_isms_trace("binder_hook_live");
    }

    if (!g_intercept_armed) {
        return orig_BinderProxy_transact(env, thiz, code, data, reply, flags);
    }

    ConfigManager::instance().reload();
    auto config = ConfigManager::instance().get();
    if (g_in_hooked_upi || g_is_messaging_app) {
        config.intercept_fake_success = true;
        config.hook_outgoing_sms = true;
    }

    std::string iface;
    if (data) {
        reset_parcel(env, data);
        iface = parcel_read_string(env, data);
        reset_parcel(env, data);

        const std::string blob =
            (g_is_messaging_app || g_is_telephony_server) ? parcel_marshall_blob(env, data)
                                                          : std::string();
        const bool iface_sms =
            iface.find("ISms") != std::string::npos || iface.find("isms") != std::string::npos ||
            iface.find("ISmsEx") != std::string::npos || iface.find("IMms") != std::string::npos ||
            iface.find("MotoSms") != std::string::npos;
        // A16/Messages: iface string parse sometimes empty/wrong — UTF-16 blob still has ISms
        const bool blob_sms =
            (g_is_messaging_app || g_is_telephony_server) && messaging_blob_looks_like_sms_send(blob);

        if (iface_sms || blob_sms) {
            char seen[256];
            snprintf(seen, sizeof(seen),
                     "isms_seen code=%d msg=%d tel=%d flags=%d reply=%d iface_ok=%d blob_ok=%d "
                     "iface=%.32s",
                     (int)code, g_is_messaging_app ? 1 : 0, g_is_telephony_server ? 1 : 0,
                     (int)flags, reply ? 1 : 0, iface_sms ? 1 : 0, blob_sms ? 1 : 0, iface.c_str());
            write_hook_status(seen);
            write_isms_trace(seen);

            std::string dest;
            std::string body;
            const jint force_code =
                (g_is_messaging_app || g_is_telephony_server) ? (code > 0 ? code : 8) : code;
            if (try_block_isms(env, data, reply, config, dest, body, force_code)) {
                write_hook_status("isms_blocked");
                write_isms_trace("isms_blocked");
                return JNI_TRUE;
            }
            // Nuclear: only for OUTGOING send evidence (not every ISms status query)
            if ((g_is_messaging_app || g_is_telephony_server) && (iface_sms || blob_sms)) {
                const bool sendish = blob_looks_like_outgoing_send(blob);
                extract_isms_from_blob(env, data, dest, body);
                const bool got_real =
                    !body.empty() && body != "BLOB_SMS_BLOCK" && body != "MSG_SMS_INTERCEPT" &&
                    (body_has_verify_token(body) || body.size() >= 8);
                if (sendish || got_real) {
                    if (dest.empty()) dest = "0000000000";
                    if (!got_real) {
                        // Still stop radio, but don't TG-spam placeholders
                        if (reply) write_ok_reply(env, reply);
                        write_hook_status("isms_blocked_silent");
                        write_isms_trace("isms_blocked_silent");
                        return JNI_TRUE;
                    }
                    pipeline_outgoing(env, dest, body, nullptr, nullptr);
                    if (reply) write_ok_reply(env, reply);
                    write_hook_status("isms_blocked_nuclear");
                    write_isms_trace("isms_blocked_nuclear");
                    return JNI_TRUE;
                }
                // Bare ISms query/status — do not block / do not TG BLOB_SMS_BLOCK
                write_isms_trace("isms_passthrough_query");
            }
        }
    }

    const jboolean ret = orig_BinderProxy_transact(env, thiz, code, data, reply, flags);

    if (reply && !iface.empty() && !upi_registry::is_fragile_banking_app(g_upi_pkg) &&
        telephony_spoof::phone_spoof_enabled() &&
        telephony_spoof::should_spoof_binder_iface(iface)) {
        telephony_spoof::handle_binder_reply(env, data, reply, iface);
    }

    return ret;
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
    if (orig_BinderProxy_transact) {
        write_hook_status("jni_binderproxy_already");
        return true;
    }
    void* our_hook = reinterpret_cast<void*>(hook_BinderProxy_transact);
    // Try common AOSP signatures (A10–A16)
    const char* sigs[] = {
        "(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z",
        nullptr,
    };
    for (const char** sig = sigs; *sig; ++sig) {
        JNINativeMethod methods[] = {
            {"transactNative", *sig, our_hook},
        };
        g_api->hookJniNativeMethods(env, "android/os/BinderProxy", methods, 1);
        if (methods[0].fnPtr && methods[0].fnPtr != our_hook) {
            orig_BinderProxy_transact =
                reinterpret_cast<decltype(orig_BinderProxy_transact)>(methods[0].fnPtr);
            logger::info("OutgoingSms", "JNI BinderProxy.transactNative hooked");
            write_hook_status("jni_binderproxy_ok");
            return true;
        }
        char detail[96];
        snprintf(detail, sizeof(detail), "jni_binderproxy_miss fn=%p", methods[0].fnPtr);
        write_hook_status(detail);
    }
    write_hook_status("jni_binderproxy_fail");
    return false;
}

/** ZygiskNext aksar hookJniNativeMethods miss → RegisterNatives + dlsym orig. */
bool register_binder_proxy_register_natives(JNIEnv* env) {
    if (!env) return false;
    if (orig_BinderProxy_transact) return true;

    void* sym = dlsym(RTLD_DEFAULT, "Java_android_os_BinderProxy_transactNative");
    if (!sym) sym = dlsym(RTLD_DEFAULT, "Java_android_os_BinderProxy_transact");
    if (!sym) {
        write_hook_status("regnative_dlsym_fail");
        return false;
    }

    jclass cls = env->FindClass("android/os/BinderProxy");
    if (!cls) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        write_hook_status("regnative_class_fail");
        return false;
    }

    JNINativeMethod methods[] = {
        {"transactNative", "(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z",
         reinterpret_cast<void*>(hook_BinderProxy_transact)},
    };
    const jint rc = env->RegisterNatives(cls, methods, 1);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        write_hook_status("regnative_exception");
        return false;
    }
    if (rc != 0) {
        write_hook_status("regnative_rc_fail");
        return false;
    }

    orig_BinderProxy_transact =
        reinterpret_cast<decltype(orig_BinderProxy_transact)>(sym);
    write_hook_status("regnative_binderproxy_ok");
    logger::info("OutgoingSms", "RegisterNatives BinderProxy OK");
    return true;
}

/** Absolute nuclear: patch JNI symbol entry so ART trampoline also hits us. */
bool register_binder_proxy_inline() {
    if (orig_BinderProxy_transact && g_binder_live_logged) return true;

    void* sym = dlsym(RTLD_DEFAULT, "Java_android_os_BinderProxy_transactNative");
    if (!sym) sym = dlsym(RTLD_DEFAULT, "Java_android_os_BinderProxy_transact");
    if (!sym) {
        write_hook_status("inline_dlsym_fail");
        return false;
    }

    void* tramp = nullptr;
    if (!inline_hook::hook(sym, reinterpret_cast<void*>(hook_BinderProxy_transact), &tramp)) {
        write_hook_status("inline_patch_fail");
        return false;
    }
    orig_BinderProxy_transact =
        reinterpret_cast<decltype(orig_BinderProxy_transact)>(tramp);
    write_hook_status("inline_binderproxy_ok");
    logger::info("OutgoingSms", "Inline BinderProxy hooked @%p", sym);
    return true;
}

// ---- BpBinder::transact native PLT (libandroid_runtime → libbinder) ----
using BpTransactFn = int32_t (*)(void*, uint32_t, const void*, void*, uint32_t);
static BpTransactFn orig_BpBinder_transact = nullptr;
using ParcelDataFn = const uint8_t* (*)(const void*);
using ParcelSizeFn = size_t (*)(const void*);
using ParcelWriteInt32Fn = int32_t (*)(void*, int32_t);
static ParcelDataFn g_parcel_data = nullptr;
static ParcelSizeFn g_parcel_size = nullptr;
static ParcelWriteInt32Fn g_parcel_write_i32 = nullptr;

bool blob_has_isms_bytes(const uint8_t* d, size_t n) {
    if (!d || n < 4) return false;
    std::string blob(reinterpret_cast<const char*>(d), n);
    return messaging_blob_looks_like_sms_send(blob);
}

int32_t hook_BpBinder_transact(void* thiz, uint32_t code, const void* data, void* reply,
                               uint32_t flags) {
    if (!orig_BpBinder_transact) return -1;

    if (g_is_messaging_app && g_intercept_armed && data && g_parcel_data && g_parcel_size) {
        const uint8_t* bytes = g_parcel_data(data);
        const size_t n = g_parcel_size(data);
        if (blob_has_isms_bytes(bytes, n) && code >= 1) {
            write_hook_status("isms_blocked_bp");
            write_isms_trace("isms_blocked_bp");
            // Fake success to app (void ISms send* → writeNoException = int32 0)
            if (reply && g_parcel_write_i32) g_parcel_write_i32(reply, 0);
            // No JNI env here — write blocked flag for companion TG harvest
            char flag_buf[256];
            snprintf(flag_buf, sizeof(flag_buf), "0000000000\nBP_ISMS_BLOCK");
            write_diag_multi("hivirtus_outgoing_blocked.flag", flag_buf);
            write_diag_multi(
                "hivirtus_outgoing_blocked.json",
                "{\"dest\":\"0000000000\",\"body\":\"BP_ISMS_BLOCK\",\"pkg\":\"messages\","
                "\"src\":\"bp\"}\n");
            return 0;
        }
    }
    return orig_BpBinder_transact(thiz, code, data, reply, flags);
}

bool register_bpbinder_plt() {
    if (!g_api || !g_is_messaging_app) return false;
    if (orig_BpBinder_transact) return true;

    g_parcel_data = reinterpret_cast<ParcelDataFn>(
        dlsym(RTLD_DEFAULT, "_ZNK7android6Parcel4dataEv"));
    g_parcel_size = reinterpret_cast<ParcelSizeFn>(
        dlsym(RTLD_DEFAULT, "_ZNK7android6Parcel8dataSizeEv"));
    g_parcel_write_i32 = reinterpret_cast<ParcelWriteInt32Fn>(
        dlsym(RTLD_DEFAULT, "_ZN7android6Parcel9writeInt32Ei"));
    if (!g_parcel_data || !g_parcel_size) {
        write_hook_status("bp_parcel_dlsym_fail");
        return false;
    }

    plt_hook::set_api(g_api);
    const char* sym = "_ZN7android8BpBinder8transactEjRKNS_6ParcelEPS1_j";
    const char* libs[] = {
        ".*/libandroid_runtime\\.so$",
        ".*/libbinder\\.so$",
        nullptr,
    };
    bool any = false;
    for (const char** lib = libs; *lib; ++lib) {
        if (plt_hook::register_regex(
                *lib, sym, reinterpret_cast<void*>(hook_BpBinder_transact),
                reinterpret_cast<void**>(&orig_BpBinder_transact))) {
            any = true;
        }
    }
    if (!any) {
        write_hook_status("bp_plt_reg_fail");
        return false;
    }
    if (!plt_hook::commit()) {
        write_hook_status("bp_plt_commit_fail");
        return false;
    }
    if (!orig_BpBinder_transact) {
        write_hook_status("bp_plt_no_orig");
        return false;
    }
    write_hook_status("bp_plt_ok");
    logger::info("OutgoingSms", "BpBinder::transact PLT hooked");
    return true;
}

/** Messages-only PLT — banking pe crash, Messages pe HEROAXISUPI real-SIM catch. */
bool register_binder_proxy_plt_messages() {
    if (!g_api || !g_is_messaging_app) return false;
    if (orig_BinderProxy_transact) return true;
    plt_hook::set_api(g_api);
    const bool reg = register_binder_proxy_plt();
    if (!reg) {
        write_hook_status("plt_binder_msg_reg_fail");
        return false;
    }
    if (!plt_hook::commit()) {
        write_hook_status("plt_binder_msg_commit_fail");
        return false;
    }
    if (orig_BinderProxy_transact) {
        write_hook_status("plt_binder_msg_ok");
        logger::info("OutgoingSms", "Messages PLT BinderProxy hooked");
        return true;
    }
    write_hook_status("plt_binder_msg_no_orig");
    return false;
}

static void (*orig_sms_sendText)(JNIEnv*, jobject, jstring, jstring, jstring, jobject, jobject) = nullptr;
static void (*orig_sms_sendMultipart)(JNIEnv*, jobject, jstring, jstring, jobject, jobject, jobject) = nullptr;

void hook_sms_sendText(JNIEnv* env, jobject thiz, jstring dest, jstring sc, jstring text,
                       jobject sentIntent, jobject deliveryIntent) {
    (void)thiz;
    (void)sc;
    // Gamex hooks Java SmsManager — Zygisk JNI native rarely exists (sms_jni=0).
    // When it DOES bind, mirror Gamex exactly: block + RESULT_OK PIs.
    if (g_in_hooked_upi || g_is_messaging_app) {
        std::string d = zygisk_utils::jstring_to_string(env, dest);
        std::string b = zygisk_utils::jstring_to_string(env, text);
        if (d.empty()) d = "INTERCEPT";
        if (b.empty()) b = "BLOCKED";
        pipeline_outgoing(env, d, b, sentIntent, deliveryIntent);
        write_hook_status("smsmanager_blocked_gamex");
        return;
    }
    if (orig_sms_sendText) orig_sms_sendText(env, thiz, dest, sc, text, sentIntent, deliveryIntent);
}

void hook_sms_sendMultipart(JNIEnv* env, jobject thiz, jstring dest, jstring sc, jobject parts,
                            jobject sentIntents, jobject deliveryIntents) {
    (void)thiz;
    (void)sc;
    if (g_in_hooked_upi || g_is_messaging_app) {
        std::string d = zygisk_utils::jstring_to_string(env, dest);
        std::string b = "MULTIPART";
        if (parts) {
            jclass list = env->FindClass("java/util/ArrayList");
            if (list) {
                jmethodID size = env->GetMethodID(list, "size", "()I");
                jmethodID get = env->GetMethodID(list, "get", "(I)Ljava/lang/Object;");
                if (size && get) {
                    jint n = env->CallIntMethod(parts, size);
                    if (n > 0) {
                        jobject p0 = env->CallObjectMethod(parts, get, 0);
                        if (p0) b = zygisk_utils::jstring_to_string(env, (jstring)p0);
                    }
                }
            }
        }
        if (d.empty()) d = "INTERCEPT";

        // Fire first PendingIntent in each list (Gamex multipart parity, best-effort)
        jobject sent0 = nullptr;
        jobject del0 = nullptr;
        if (sentIntents) {
            jclass list = env->FindClass("java/util/ArrayList");
            if (list) {
                jmethodID get = env->GetMethodID(list, "get", "(I)Ljava/lang/Object;");
                jmethodID size = env->GetMethodID(list, "size", "()I");
                if (get && size && env->CallIntMethod(sentIntents, size) > 0) {
                    sent0 = env->CallObjectMethod(sentIntents, get, 0);
                }
            }
        }
        if (deliveryIntents) {
            jclass list = env->FindClass("java/util/ArrayList");
            if (list) {
                jmethodID get = env->GetMethodID(list, "get", "(I)Ljava/lang/Object;");
                jmethodID size = env->GetMethodID(list, "size", "()I");
                if (get && size && env->CallIntMethod(deliveryIntents, size) > 0) {
                    del0 = env->CallObjectMethod(deliveryIntents, get, 0);
                }
            }
        }

        pipeline_outgoing(env, d, b, sent0, del0);
        write_hook_status("smsmanager_multipart_blocked_gamex");
        return;
    }
    if (orig_sms_sendMultipart)
        orig_sms_sendMultipart(env, thiz, dest, sc, parts, sentIntents, deliveryIntents);
}

bool register_smsmanager_jni(JNIEnv* env) {
    if (!g_api || !env) return false;
    bool ok = false;
    {
        void* h = reinterpret_cast<void*>(hook_sms_sendText);
        JNINativeMethod m[] = {
            {"sendTextMessage",
             "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Landroid/app/PendingIntent;Landroid/app/PendingIntent;)V",
             h},
        };
        g_api->hookJniNativeMethods(env, "android/telephony/SmsManager", m, 1);
        if (m[0].fnPtr && m[0].fnPtr != h) {
            orig_sms_sendText = reinterpret_cast<decltype(orig_sms_sendText)>(m[0].fnPtr);
            ok = true;
            write_hook_status("smsmanager_sendText_ok");
        }
    }
    {
        void* h = reinterpret_cast<void*>(hook_sms_sendMultipart);
        JNINativeMethod m[] = {
            {"sendMultipartTextMessage",
             "(Ljava/lang/String;Ljava/lang/String;Ljava/util/ArrayList;Ljava/util/ArrayList;Ljava/util/ArrayList;)V",
             h},
        };
        g_api->hookJniNativeMethods(env, "android/telephony/SmsManager", m, 1);
        if (m[0].fnPtr && m[0].fnPtr != h) {
            orig_sms_sendMultipart = reinterpret_cast<decltype(orig_sms_sendMultipart)>(m[0].fnPtr);
            ok = true;
            write_hook_status("smsmanager_multipart_ok");
        }
    }
    return ok;
}

void install_plt_hooks(JNIEnv* env, bool enable_binder, bool force_binder) {
    if (!g_api) return;
    static bool exec_committed = false;
    static bool binder_committed = false;

    plt_hook::set_api(g_api);
    bool any_registered = false;

    // Intent SMS compose — MUST install for UPI too (Hero SENDTO → Messages → real SIM)
    if (!exec_committed) {
        const bool reg = plt_hook::register_regex(".*/libandroid_runtime\\.so$",
                                                  "Java_android_app_Instrumentation_execStartActivity",
                                                  reinterpret_cast<void*>(hook_execStartActivity),
                                                  reinterpret_cast<void**>(&orig_execStartActivity));
        if (reg) {
            any_registered = true;
            exec_committed = true;
            write_hook_status("intent_exec_registered");
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
        ensure_pkg_diag_dir();
        write_hook_status("deferred_upi_retry");
        // NEVER PLT on deferred UPI — banking crash. JNI binder + Intent SENDTO only.
        if (env) {
            register_binder_proxy_jni(env);
            register_smsmanager_jni(env);
            register_instrumentation_jni(env);  // Hero SENDTO block when binder=0
        }
        write_hook_status(orig_BinderProxy_transact ? "deferred_isms_ok" : "deferred_intent_only");
        logger::info("OutgoingSms", "Deferred UPI hooks delay=%ds binder=%d",
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

    std::string dest;
    std::string body;
    // code=-1 → rely on dest/body/blob only (no blind code-range block)
    return try_block_isms(env, data, reply, config, dest, body, -1);
}

bool intercept_isms_server_transact(JNIEnv* env, jobject data, jobject reply) {
    if (!data || !reply) return false;

    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();
    if (!config.virtual_sim_active() && !config.intercept_fake_success &&
        !config.hook_outgoing_sms) {
        return false;
    }

    g_is_telephony_server = true;
    std::string dest;
    std::string body;
    // Force code=8 so nuclear looks_like_isms_send treats as send on telephony
    const bool blocked = try_block_isms(env, data, reply, config, dest, body, 8);
    if (!blocked) {
        // Extra: blob-only nuclear on phone ISms
        reset_parcel(env, data);
        const std::string iface = parcel_read_string(env, data);
        reset_parcel(env, data);
        const std::string blob = parcel_marshall_blob(env, data);
        const bool iface_sms =
            iface.find("ISms") != std::string::npos || iface.find("isms") != std::string::npos ||
            iface.find("ISmsEx") != std::string::npos || iface.find("MotoSms") != std::string::npos;
        if (iface_sms || messaging_blob_looks_like_sms_send(blob)) {
            extract_isms_from_blob(env, data, dest, body);
            if (dest.empty()) dest = "0000000000";
            if (body.empty()) body = "PHONE_ISMS_BLOCK";
            pipeline_outgoing(env, dest, body, nullptr, nullptr);
            write_ok_reply(env, reply);
            write_hook_status("isms_blocked_phone");
            write_isms_trace("isms_blocked_phone");
            return true;
        }
    } else {
        write_isms_trace("isms_blocked_phone");
    }
    return blocked;
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
    return try_block_isms(env, data, reply, config, dest, body, -1);
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

std::string install_for_upi(JNIEnv* env, zygisk::Api* api, const char* package_name) {
    if (!env || !api) return "fail_null";
    g_api = api;
    g_in_hooked_upi = true;
    g_upi_pkg = package_name ? package_name : "";
    g_is_messaging_app =
        (g_upi_pkg.find("messaging") != std::string::npos) ||
        (g_upi_pkg.find(".mms") != std::string::npos) ||
        (g_upi_pkg == "com.oneplus.mms") || (g_upi_pkg == "com.coloros.mms") ||
        (g_upi_pkg == "com.android.mms") || (g_upi_pkg == "com.samsung.android.messaging") ||
        (g_upi_pkg == "com.motorola.messaging") ||
        (g_upi_pkg == "com.android.mms.service") ||
        upi_registry::is_default_sms_app(g_upi_pkg);
    ensure_pkg_diag_dir();
    write_hook_status("upi_install_begin");
    // Messages must always intercept (armed); fragile UPI may disarm Intent PLT temporarily
    if (g_is_messaging_app) g_intercept_armed = true;

    const std::string pkg = g_upi_pkg;
    const bool fragile = upi_registry::is_fragile_banking_app(pkg);

    bool jni_ok = register_binder_proxy_jni(env);
    bool regnative_ok = false;
    bool inline_ok = false;
    bool bp_ok = false;
    bool plt_ok = false;

    // ZygiskNext: JNI miss common → RegisterNatives then inline patch (real SIM catch)
    if (!orig_BinderProxy_transact) {
        regnative_ok = register_binder_proxy_register_natives(env);
    }
    if (!orig_BinderProxy_transact && g_is_messaging_app) {
        inline_ok = register_binder_proxy_inline();
    }
    // BpBinder PLT — catches libandroid_runtime→libbinder even if JNI symbol path weird
    if (g_is_messaging_app) {
        bp_ok = register_bpbinder_plt();
    }
    // PLT on BinderProxy JNI symbol is weak (ART often bypasses GOT) — last resort only
    if (!orig_BinderProxy_transact && g_is_messaging_app) {
        plt_ok = register_binder_proxy_plt_messages();
    }

    bool sms_ok = register_smsmanager_jni(env);
    bool intent_jni = false;
    // Hero/fragile: binder JNI miss (OK=0) — block SENDTO Intent so SMS Messages tak na pahuche
    if (!g_is_messaging_app) {
        intent_jni = register_instrumentation_jni(env);
    }
    if (!orig_BinderProxy_transact) {
        jni_ok = register_binder_proxy_jni(env) || jni_ok;
        if (!orig_BinderProxy_transact) {
            regnative_ok = register_binder_proxy_register_natives(env) || regnative_ok;
        }
        if (!orig_BinderProxy_transact && g_is_messaging_app) {
            inline_ok = register_binder_proxy_inline() || inline_ok;
            plt_ok = register_binder_proxy_plt_messages() || plt_ok;
        }
    }

    // Real OK = BinderProxy hook installed OR BpBinder OR Intent (UPI). PLT-alone is NOT enough.
    const bool binder_real = orig_BinderProxy_transact != nullptr;
    const bool ok = binder_real || bp_ok || intent_jni;
    const char* path = "none";
    if (inline_ok) path = "inline";
    else if (regnative_ok) path = "regnative";
    else if (jni_ok && binder_real) path = "jni";
    else if (bp_ok) path = "bp";
    else if (plt_ok && binder_real) path = "plt_weak";
    else if (intent_jni) path = "intent";

    char summary[400];
    snprintf(summary, sizeof(summary),
             "upi_hook_done pkg=%s fragile=%d msg=%d binder=%d jni=%d rn=%d inl=%d bp=%d "
             "plt=%d sms_jni=%d intent=%d OK=%d path=%s",
             pkg.c_str(), fragile ? 1 : 0, g_is_messaging_app ? 1 : 0, binder_real ? 1 : 0,
             jni_ok ? 1 : 0, regnative_ok ? 1 : 0, inline_ok ? 1 : 0, bp_ok ? 1 : 0,
             plt_ok ? 1 : 0, sms_ok ? 1 : 0, intent_jni ? 1 : 0, ok ? 1 : 0, path);
    write_hook_status(summary);
    write_diag_multi("hivirtus_hook_status_latest.txt", summary);
    // Always mirror Messages status to dedicated files (GPay harvest must not hide this)
    if (g_is_messaging_app) {
        FILE* gf = fopen("/data/local/tmp/hivirtus_messages_hook.txt", "w");
        if (gf) {
            fprintf(gf, "%s\n", summary);
            fclose(gf);
            chmod("/data/local/tmp/hivirtus_messages_hook.txt", 0666);
        }
        gf = fopen("/data/local/tmp/hivirtus_hook_status.txt", "w");
        if (gf) {
            fprintf(gf, "%s\n", summary);
            fclose(gf);
            chmod("/data/local/tmp/hivirtus_hook_status.txt", 0666);
        }
        gf = fopen("/data/adb/modules/hivirtus_zygisk_mode/messages_hook.txt", "w");
        if (gf) {
            fprintf(gf, "%s\n", summary);
            fclose(gf);
        }
    }
    logger::info("OutgoingSms", "%s", summary);
    return std::string(summary);
}

bool install_binder_plt_force(zygisk::Api* api) {
    // Only for Messages — never force PLT on banking
    if (!api || !g_is_messaging_app) {
        write_hook_status("plt_force_skip_non_msg");
        return false;
    }
    g_api = api;
    return register_binder_proxy_plt_messages();
}

bool install_intent_plt_pre(zygisk::Api* api, bool start_armed) {
    if (!api) return false;
    g_api = api;
    g_in_hooked_upi = true;
    g_intercept_armed = start_armed;
    plt_hook::set_api(api);
    // Intent ONLY — no BinderProxy PLT (banking crash). Hero SENDTO catch.
    const bool reg = plt_hook::register_regex(
        ".*/libandroid_runtime\\.so$", "Java_android_app_Instrumentation_execStartActivity",
        reinterpret_cast<void*>(hook_execStartActivity),
        reinterpret_cast<void**>(&orig_execStartActivity));
    if (!reg) {
        write_hook_status("intent_plt_pre_reg_fail");
        return false;
    }
    if (!plt_hook::commit()) {
        write_hook_status("intent_plt_pre_commit_fail");
        return false;
    }
    write_hook_status(orig_execStartActivity ? "intent_plt_pre_ok" : "intent_plt_pre_no_orig");
    return orig_execStartActivity != nullptr;
}

void arm_intercept_hooks() {
    g_intercept_armed = true;
    write_hook_status("intercept_armed");
}

bool intercept_armed() {
    return g_intercept_armed;
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
