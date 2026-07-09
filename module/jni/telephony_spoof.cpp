#include "telephony_spoof.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "zygisk_utils.hpp"

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstring>
#include <functional>
#include <string>

namespace telephony_spoof {

namespace {

std::string digits_only(const std::string& value) {
    std::string out;
    for (char c : value) {
        if (std::isdigit(static_cast<unsigned char>(c))) out += c;
    }
    return out;
}

std::string read_runtime_phone_file() {
    char buf[96] = {};
    const char* paths[] = {
        "/data/local/tmp/hivirtus_spoof_phone.txt",
        "/data/adb/modules/hivirtus_zygisk_mode/spoof_phone.txt",
        nullptr,
    };
    for (const char** path = paths; *path; ++path) {
        FILE* f = fopen(*path, "r");
        if (!f) continue;
        if (fgets(buf, sizeof(buf), f)) {
            fclose(f);
            std::string phone = buf;
            if (!phone.empty() && phone.back() == '\n') phone.pop_back();
            if (!phone.empty()) return phone;
        } else {
            fclose(f);
        }
    }
    return {};
}

std::string read_real_phone_file() {
    char buf[96] = {};
    const char* paths[] = {
        "/data/local/tmp/hivirtus_real_phone.txt",
        "/data/adb/modules/hivirtus_zygisk_mode/real_phone.txt",
        nullptr,
    };
    for (const char** path = paths; *path; ++path) {
        FILE* f = fopen(*path, "r");
        if (!f) continue;
        if (fgets(buf, sizeof(buf), f)) {
            fclose(f);
            std::string phone = buf;
            if (!phone.empty() && phone.back() == '\n') phone.pop_back();
            const std::string digits = digits_only(phone);
            if (digits.size() >= 10) return digits.substr(digits.size() - 10);
        } else {
            fclose(f);
        }
    }
    return {};
}

bool is_indian_mobile_10(const std::string& ten) {
    return ten.size() == 10 && ten[0] >= '6' && ten[0] <= '9';
}

bool looks_like_imsi(const std::string& digits) {
    return digits.size() >= 14 && digits.size() <= 15;
}

bool looks_like_iccid(const std::string& digits) {
    return digits.size() >= 18 && digits.size() <= 22 && digits[0] == '8';
}

bool looks_like_phone12(const std::string& digits) {
    return digits.size() == 12 && digits.rfind("91", 0) == 0 &&
           is_indian_mobile_10(digits.substr(2));
}

void reset_parcel(JNIEnv* env, jobject parcel) {
    if (!parcel) return;
    jclass cls = env->GetObjectClass(parcel);
    jmethodID set_pos = env->GetMethodID(cls, "setDataPosition", "(I)V");
    if (set_pos) env->CallVoidMethod(parcel, set_pos, 0);
}

SpoofPhoneFormats formats_from_phone(const std::string& raw) {
    SpoofPhoneFormats formats;
    formats.raw = raw;
    std::string digits = digits_only(raw);
    if (digits.size() >= 12 && digits.rfind("91", 0) == 0) {
        formats.digits12 = digits;
        formats.digits10 = digits.substr(2);
    } else if (digits.size() == 10) {
        formats.digits10 = digits;
        formats.digits12 = "91" + digits;
    } else if (digits.size() > 10) {
        formats.digits10 = digits.substr(digits.size() - 10);
        formats.digits12 = "91" + formats.digits10;
    }
    if (!formats.digits10.empty()) formats.e164 = "+91" + formats.digits10;
    return formats;
}

SubscriberSlotProfile build_slot(const ModuleConfig& config, int slot) {
    SubscriberSlotProfile p;
    p.slot_index = slot;
    if (slot == 0) {
        p.enabled = config.virtual_sim_active() || config.enable_sim1_mock || config.enable_phone_spoof;
        p.phone10 = digits_only(config.mock_phone_sim1);
        p.imsi = digits_only(config.mock_imsi_sim1);
        p.iccid = digits_only(config.mock_iccid_sim1);
        p.operator_name = config.mock_operator_name_sim1;
        p.operator_numeric = digits_only(config.mock_operator_numeric_sim1);
    } else {
        p.enabled = config.enable_sim2_mock;
        p.phone10 = digits_only(config.mock_phone_sim2);
        p.imsi = digits_only(config.mock_imsi_sim2);
        p.iccid = digits_only(config.mock_iccid_sim2);
        p.operator_name = config.mock_operator_name_sim2;
        p.operator_numeric = digits_only(config.mock_operator_numeric_sim2);
    }
    p.country_iso = config.mock_country_iso.empty() ? "in" : config.mock_country_iso;
    if (p.operator_numeric.size() >= 5) {
        p.mcc = p.operator_numeric.substr(0, 3);
        p.mnc = p.operator_numeric.substr(3);
    }
    if (!p.phone10.empty()) {
        p.phone_e164 = p.phone10.size() == 10 ? "+91" + p.phone10 : "+" + p.phone10;
    }
    return p;
}

const SubscriberSlotProfile& primary_slot(const VirtualSubscriberProfiles& profiles) {
    if (profiles.sim1.enabled && !profiles.sim1.phone10.empty()) return profiles.sim1;
    if (profiles.sim2.enabled) return profiles.sim2;
    return profiles.sim1;
}

bool replace_ascii_run(std::string& blob, const std::string& from, const std::string& to) {
    if (from.empty() || from.size() != to.size()) return false;
    bool changed = false;
    for (size_t i = 0; i + from.size() <= blob.size(); ++i) {
        if (blob.compare(i, from.size(), from) == 0) {
            blob.replace(i, from.size(), to);
            changed = true;
            i += to.size() - 1;
        }
    }
    return changed;
}

bool replace_utf16_ascii(std::string& blob, const std::string& from, const std::string& to) {
    if (from.empty() || from.size() != to.size()) return false;
    bool changed = false;
    const size_t need = from.size() * 2;
    for (size_t i = 0; i + need <= blob.size(); i += 2) {
        bool match = true;
        for (size_t d = 0; d < from.size(); ++d) {
            const unsigned char hi = static_cast<unsigned char>(blob[i + d * 2]);
            const unsigned char lo = static_cast<unsigned char>(blob[i + d * 2 + 1]);
            if (lo != 0 || hi != static_cast<unsigned char>(from[d])) {
                match = false;
                break;
            }
        }
        if (!match) continue;
        for (size_t d = 0; d < to.size(); ++d) blob[i + d * 2] = to[d];
        changed = true;
    }
    return changed;
}

void scrub_real_phone_formats(std::string& blob, const std::string& real10,
                              const std::string& virtual10) {
    if (real10.empty() || virtual10.empty() || real10 == virtual10) return;
    replace_ascii_run(blob, real10, virtual10);
    replace_utf16_ascii(blob, real10, virtual10);
    const auto real_f = formats_from_phone(real10);
    const auto virt_f = formats_from_phone(virtual10);
    if (!real_f.digits12.empty() && !virt_f.digits12.empty()) {
        replace_ascii_run(blob, real_f.digits12, virt_f.digits12);
        replace_utf16_ascii(blob, real_f.digits12, virt_f.digits12);
    }
    if (!real_f.e164.empty() && !virt_f.e164.empty()) {
        replace_ascii_run(blob, real_f.e164, virt_f.e164);
        replace_utf16_ascii(blob, real_f.e164, virt_f.e164);
    }
}

void scrub_digit_run(std::string& blob, size_t len,
                     const std::function<bool(const std::string&)>& validator,
                     const std::string& replacement) {
    if (replacement.size() != len) return;
    for (size_t i = 0; i + len <= blob.size(); ++i) {
        bool all_digit = true;
        for (size_t j = 0; j < len; ++j) {
            const char c = blob[i + j];
            if (c < '0' || c > '9') {
                all_digit = false;
                break;
            }
        }
        if (!all_digit) continue;
        const std::string found = blob.substr(i, len);
        if (!validator(found)) continue;
        if (found == replacement) continue;
        blob.replace(i, len, replacement);
        i += len - 1;
    }
}

void scrub_ascii_operator_names(std::string& blob, const VirtualSubscriberProfiles& profiles) {
    static const char* kCarriers[] = {"Airtel", "airtel", "AIRTEL", "Jio", "JIO", "Vi India",
                                      "Vodafone", "IDEA", "BSNL", "Reliance", nullptr};
    const std::string& target = primary_slot(profiles).operator_name;
    if (target.empty()) return;
    for (const char** c = kCarriers; *c; ++c) {
        const std::string from = *c;
        if (from == target) continue;
        if (from.size() == target.size()) replace_ascii_run(blob, from, target);
        replace_utf16_ascii(blob, from, target);
    }
}

void scrub_subscriber_blob(std::string& blob, const VirtualSubscriberProfiles& profiles) {
    const auto& s1 = profiles.sim1;
    const auto& s2 = profiles.sim2;
    const std::string real10 = read_real_phone_file();

    if (s1.enabled && !s1.phone10.empty()) {
        if (!real10.empty()) scrub_real_phone_formats(blob, real10, s1.phone10);
        scrub_digit_run(blob, 10, is_indian_mobile_10, s1.phone10);
        const auto f = formats_from_phone(s1.phone10);
        if (!f.digits12.empty()) {
            scrub_digit_run(blob, 12, looks_like_phone12, f.digits12);
            replace_utf16_ascii(blob, f.digits12, f.digits12);
        }
        if (!f.e164.empty()) {
            replace_ascii_run(blob, f.e164, f.e164);
            replace_utf16_ascii(blob, f.e164, f.e164);
        }
        replace_utf16_ascii(blob, s1.phone10, s1.phone10);
    }
    if (profiles.dual_active && s2.enabled && !s2.phone10.empty()) {
        if (!real10.empty()) scrub_real_phone_formats(blob, real10, s2.phone10);
        scrub_digit_run(blob, 10, is_indian_mobile_10, s2.phone10);
        replace_utf16_ascii(blob, s2.phone10, s2.phone10);
    }
    if (s1.enabled && !s1.imsi.empty()) {
        scrub_digit_run(blob, s1.imsi.size(), looks_like_imsi, s1.imsi);
        replace_utf16_ascii(blob, s1.imsi, s1.imsi);
    }
    if (profiles.dual_active && s2.enabled && !s2.imsi.empty()) {
        scrub_digit_run(blob, s2.imsi.size(), looks_like_imsi, s2.imsi);
    }
    if (s1.enabled && !s1.iccid.empty()) {
        scrub_digit_run(blob, s1.iccid.size(), looks_like_iccid, s1.iccid);
        replace_utf16_ascii(blob, s1.iccid, s1.iccid);
    }
    if (profiles.dual_active && s2.enabled && !s2.iccid.empty()) {
        scrub_digit_run(blob, s2.iccid.size(), looks_like_iccid, s2.iccid);
    }
    if (s1.enabled && !s1.operator_numeric.empty()) {
        scrub_digit_run(blob, s1.operator_numeric.size(),
                        [&](const std::string& v) { return v.size() >= 5 && v.size() <= 6; },
                        s1.operator_numeric);
    }
    scrub_ascii_operator_names(blob, profiles);
}

bool rewrite_line1_string_reply(JNIEnv* env, jobject reply,
                                const VirtualSubscriberProfiles& profiles) {
    if (!reply) return false;
    const auto& primary = primary_slot(profiles);
    if (!primary.enabled || primary.phone10.empty()) return false;

    reset_parcel(env, reply);
    jclass cls = env->GetObjectClass(reply);
    jmethodID read_ex = env->GetMethodID(cls, "readException", "()V");
    jmethodID read_string = env->GetMethodID(cls, "readString", "()Ljava/lang/String;");
    jmethodID write_no_ex = env->GetMethodID(cls, "writeNoException", "()V");
    jmethodID write_string = env->GetMethodID(cls, "writeString", "(Ljava/lang/String;)V");
    if (!read_string || !write_string) return false;

    if (read_ex) env->CallVoidMethod(reply, read_ex);
    jstring current_j = (jstring)env->CallObjectMethod(reply, read_string);

    std::string current;
    if (current_j) current = zygisk_utils::jstring_to_string(env, current_j);

    const std::string current_digits = digits_only(current);
    std::string replacement;

    if (current.empty() || current_digits.empty()) {
        replacement = primary.phone10;
    } else if (is_indian_mobile_10(current_digits)) {
        if (current.find("+91") != std::string::npos) replacement = "+91" + primary.phone10;
        else if (current_digits.size() == 12 && current_digits.rfind("91", 0) == 0)
            replacement = "91" + primary.phone10;
        else
            replacement = primary.phone10;
    } else if (looks_like_phone12(current_digits)) {
        replacement = "91" + primary.phone10;
    } else if (!primary.phone_e164.empty() &&
               (current.find("+") != std::string::npos || current.find("91") == 0)) {
        replacement = primary.phone_e164;
    }

    const std::string real10 = read_real_phone_file();
    if (replacement.empty() && !real10.empty() && current_digits == real10) {
        replacement = primary.phone10;
    }

    if (replacement.empty() || replacement == current) return false;

    reset_parcel(env, reply);
    if (write_no_ex) env->CallVoidMethod(reply, write_no_ex);
    env->CallVoidMethod(reply, write_string, zygisk_utils::string_to_jstring(env, replacement));
    logger::info("TelephonySpoof", "getLine1Number %s -> %s", current.c_str(), replacement.c_str());
    return true;
}

bool rewrite_subscriber_string_reply(JNIEnv* env, jobject reply,
                                     const VirtualSubscriberProfiles& profiles) {
    if (!reply) return false;
    const auto& primary = primary_slot(profiles);

    reset_parcel(env, reply);
    jclass cls = env->GetObjectClass(reply);
    jmethodID read_ex = env->GetMethodID(cls, "readException", "()V");
    jmethodID read_string = env->GetMethodID(cls, "readString", "()Ljava/lang/String;");
    jmethodID write_no_ex = env->GetMethodID(cls, "writeNoException", "()V");
    jmethodID write_string = env->GetMethodID(cls, "writeString", "(Ljava/lang/String;)V");
    if (!read_string || !write_string) return false;

    if (read_ex) env->CallVoidMethod(reply, read_ex);
    jstring current_j = (jstring)env->CallObjectMethod(reply, read_string);
    if (!current_j) return false;

    const std::string current = zygisk_utils::jstring_to_string(env, current_j);
    const std::string current_digits = digits_only(current);
    std::string replacement;

    if (looks_like_imsi(current_digits) && !primary.imsi.empty()) {
        replacement = primary.imsi;
    } else if (looks_like_iccid(current_digits) && !primary.iccid.empty()) {
        replacement = primary.iccid;
    } else if (is_indian_mobile_10(current_digits) && !primary.phone10.empty()) {
        if (current.find("+91") != std::string::npos) replacement = "+91" + primary.phone10;
        else if (current_digits.size() == 12 && current_digits.rfind("91", 0) == 0)
            replacement = "91" + primary.phone10;
        else
            replacement = primary.phone10;
    } else if (!primary.operator_name.empty() &&
               (current.find("airtel") != std::string::npos ||
                current.find("jio") != std::string::npos ||
                current.find("vi") != std::string::npos)) {
        replacement = primary.operator_name;
    }

    if (replacement.empty() || replacement == current) return false;

    reset_parcel(env, reply);
    if (write_no_ex) env->CallVoidMethod(reply, write_no_ex);
    env->CallVoidMethod(reply, write_string, zygisk_utils::string_to_jstring(env, replacement));
    logger::info("TelephonySpoof", "SubscriberInfo string %s -> %s", current.c_str(),
                 replacement.c_str());
    return true;
}

bool marshall_scrub_subscriber(JNIEnv* env, jobject reply, const VirtualSubscriberProfiles& profiles) {
    jclass cls = env->GetObjectClass(reply);
    jmethodID marshall = env->GetMethodID(cls, "marshall", "()[B");
    jmethodID unmarshall = env->GetMethodID(cls, "unmarshall", "([BII)V");
    if (!marshall || !unmarshall) return false;

    reset_parcel(env, reply);
    jbyteArray bytes = (jbyteArray)env->CallObjectMethod(reply, marshall);
    if (!bytes) return false;

    jsize len = env->GetArrayLength(bytes);
    if (len <= 0) return false;

    jbyte* data = env->GetByteArrayElements(bytes, nullptr);
    if (!data) return false;

    std::string blob(reinterpret_cast<char*>(data), static_cast<size_t>(len));
    const std::string before = blob;
    scrub_subscriber_blob(blob, profiles);

    if (blob == before) {
        env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
        return false;
    }

    const size_t copy_len =
        blob.size() < static_cast<size_t>(len) ? blob.size() : static_cast<size_t>(len);
    std::memcpy(data, blob.data(), copy_len);
    env->ReleaseByteArrayElements(bytes, data, 0);
    reset_parcel(env, reply);
    env->CallVoidMethod(reply, unmarshall, bytes, 0, len);
    logger::info("TelephonySpoof", "SubscriptionInfo parcel scrubbed slot1=%s",
                 profiles.sim1.phone10.c_str());
    return true;
}

}  // namespace

VirtualSubscriberProfiles load_subscriber_profiles() {
    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();

    VirtualSubscriberProfiles profiles;
    profiles.sim1 = build_slot(config, 0);
    profiles.sim2 = build_slot(config, 1);
    profiles.dual_active = profiles.sim1.enabled && profiles.sim2.enabled;

    const std::string runtime = read_runtime_phone_file();
    if (!runtime.empty() && profiles.sim1.phone10.empty()) {
        profiles.sim1.phone10 = digits_only(runtime);
        profiles.sim1.phone_e164 = "+91" + profiles.sim1.phone10;
        profiles.sim1.enabled = true;
    }
    return profiles;
}

SpoofPhoneFormats load_formats() {
    const auto profiles = load_subscriber_profiles();
    const auto& primary = primary_slot(profiles);
    return formats_from_phone(primary.phone10.empty() ? primary.phone_e164 : primary.phone10);
}

bool phone_spoof_enabled() {
    ConfigManager::instance().reload();
    return ConfigManager::instance().get().virtual_sim_active();
}

bool is_subscription_binder_interface(const std::string& iface) {
    if (iface.empty()) return false;
    std::string lower = iface;
    std::transform(lower.begin(), lower.end(), lower.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return lower.find("isub") != std::string::npos ||
           lower.find("isubscription") != std::string::npos ||
           lower.find("subscriptionmanager") != std::string::npos ||
           lower.find("subscriptioninf") != std::string::npos;
}

bool is_subscriber_id_binder_interface(const std::string& iface) {
    if (iface.empty()) return false;
    std::string lower = iface;
    std::transform(lower.begin(), lower.end(), lower.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return lower.find("iphonesubinfo") != std::string::npos ||
           lower.find("iphonesubinfocontroller") != std::string::npos;
}

bool is_itelephony_binder_interface(const std::string& iface) {
    if (iface.empty()) return false;
    std::string lower = iface;
    std::transform(lower.begin(), lower.end(), lower.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    if (is_subscriber_id_binder_interface(iface)) return false;
    return lower.find("itelephony") != std::string::npos ||
           lower.find("phoneinterfacemanager") != std::string::npos;
}

bool is_telephony_binder_interface(const std::string& iface) {
    if (iface.empty()) return false;
    std::string lower = iface;
    std::transform(lower.begin(), lower.end(), lower.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return is_subscription_binder_interface(iface) || is_subscriber_id_binder_interface(iface) ||
           lower.find("itelephony") != std::string::npos ||
           lower.find("telephony") != std::string::npos;
}

std::string read_binder_interface(JNIEnv* env, jobject data) {
    if (!data) return {};
    reset_parcel(env, data);
    jclass cls = env->GetObjectClass(data);
    jmethodID read_string = env->GetMethodID(cls, "readString", "()Ljava/lang/String;");
    if (!read_string) return {};
    jstring iface_j = (jstring)env->CallObjectMethod(data, read_string);
    if (!iface_j) return {};
    const char* chars = env->GetStringUTFChars(iface_j, nullptr);
    std::string iface = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(iface_j, chars);
    reset_parcel(env, data);
    return iface;
}

void scrub_reply_parcel(JNIEnv* env, jobject reply, const VirtualSubscriberProfiles& profiles,
                        const std::string& binder_iface) {
    if (!reply) return;
    const bool can_scrub = profiles.sim1.enabled || !profiles.sim1.phone10.empty();
    if (!can_scrub) return;

    if (is_itelephony_binder_interface(binder_iface)) {
        if (rewrite_line1_string_reply(env, reply, profiles)) return;
        if (marshall_scrub_subscriber(env, reply, profiles)) return;
        rewrite_subscriber_string_reply(env, reply, profiles);
        return;
    }

    if (is_subscription_binder_interface(binder_iface)) {
        if (marshall_scrub_subscriber(env, reply, profiles)) return;
        if (rewrite_subscriber_string_reply(env, reply, profiles)) return;
        if (rewrite_line1_string_reply(env, reply, profiles)) return;
    }

    if (is_subscriber_id_binder_interface(binder_iface)) {
        if (rewrite_subscriber_string_reply(env, reply, profiles)) return;
        if (rewrite_line1_string_reply(env, reply, profiles)) return;
        if (marshall_scrub_subscriber(env, reply, profiles)) return;
    }

    if (marshall_scrub_subscriber(env, reply, profiles)) return;
    if (rewrite_line1_string_reply(env, reply, profiles)) return;
    rewrite_subscriber_string_reply(env, reply, profiles);
}

}  // namespace telephony_spoof
