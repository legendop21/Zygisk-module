#include "telephony_spoof.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "zygisk_utils.hpp"

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstring>
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

bool is_indian_mobile_10(const std::string& ten) {
    return ten.size() == 10 && ten[0] >= '6' && ten[0] <= '9';
}

void reset_parcel(JNIEnv* env, jobject parcel) {
    if (!parcel) return;
    jclass cls = env->GetObjectClass(parcel);
    jmethodID set_pos = env->GetMethodID(cls, "setDataPosition", "(I)V");
    if (set_pos) env->CallVoidMethod(parcel, set_pos, 0);
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

bool replace_utf16_run(std::string& blob, const std::string& from10, const std::string& to10) {
    if (from10.size() != 10 || to10.size() != 10) return false;
    bool changed = false;
    for (size_t i = 0; i + 20 <= blob.size(); i += 2) {
        bool match = true;
        for (size_t d = 0; d < 10; ++d) {
            const unsigned char hi = static_cast<unsigned char>(blob[i + d * 2]);
            const unsigned char lo = static_cast<unsigned char>(blob[i + d * 2 + 1]);
            if (lo != 0 || hi < '0' || hi > '9' || hi != static_cast<unsigned char>(from10[d])) {
                match = false;
                break;
            }
        }
        if (!match) continue;
        for (size_t d = 0; d < 10; ++d) {
            blob[i + d * 2] = to10[d];
        }
        changed = true;
    }
    return changed;
}

void scrub_ascii_phone_patterns(std::string& blob, const SpoofPhoneFormats& formats) {
    if (formats.digits10.empty()) return;

    for (size_t i = 0; i + 10 <= blob.size(); ++i) {
        bool all_digit = true;
        for (size_t j = 0; j < 10; ++j) {
            const char c = blob[i + j];
            if (c < '0' || c > '9') {
                all_digit = false;
                break;
            }
        }
        if (!all_digit) continue;

        const std::string found = blob.substr(i, 10);
        if (!is_indian_mobile_10(found)) continue;
        if (found == formats.digits10) continue;

        blob.replace(i, 10, formats.digits10);
        i += 9;
    }

    if (!formats.digits12.empty()) {
        replace_ascii_run(blob, formats.digits12, formats.digits12);
    }
    if (!formats.e164.empty()) {
        const std::string plus12 = "+" + formats.digits12;
        replace_ascii_run(blob, plus12, plus12);
    }

    const std::string spaced = "+91 - " + formats.digits10;
    for (size_t i = 0; i + 10 <= blob.size(); ++i) {
        bool all_digit = true;
        for (size_t j = 0; j < 10; ++j) {
            const char c = blob[i + j];
            if (c < '0' || c > '9') {
                all_digit = false;
                break;
            }
        }
        if (!all_digit) continue;
        const std::string found = blob.substr(i, 10);
        if (!is_indian_mobile_10(found) || found == formats.digits10) continue;
        blob.replace(i, 10, formats.digits10);
    }

    (void)spaced;
}

void scrub_blob_phones(std::string& blob, const SpoofPhoneFormats& formats) {
    if (formats.digits10.empty()) return;
    scrub_ascii_phone_patterns(blob, formats);

    for (size_t i = 0; i + 20 <= blob.size(); i += 2) {
        bool all_digit = true;
        std::string found10;
        found10.reserve(10);
        for (size_t d = 0; d < 10; ++d) {
            const unsigned char hi = static_cast<unsigned char>(blob[i + d * 2]);
            const unsigned char lo = static_cast<unsigned char>(blob[i + d * 2 + 1]);
            if (lo != 0 || hi < '0' || hi > '9') {
                all_digit = false;
                break;
            }
            found10.push_back(static_cast<char>(hi));
        }
        if (!all_digit || !is_indian_mobile_10(found10) || found10 == formats.digits10) continue;
        for (size_t d = 0; d < 10; ++d) {
            blob[i + d * 2] = formats.digits10[d];
        }
    }

    replace_utf16_run(blob, formats.digits10, formats.digits10);
}

bool rewrite_reply_simple_string(JNIEnv* env, jobject reply, const SpoofPhoneFormats& formats) {
    if (!reply || formats.digits10.empty()) return false;

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
    if (current_digits.size() < 10) return false;
    if (current_digits == formats.digits10 || current_digits == formats.digits12) return false;

    reset_parcel(env, reply);
    if (write_no_ex) env->CallVoidMethod(reply, write_no_ex);

    std::string replacement = formats.e164;
    if (current.find("+91") != std::string::npos || current.find("+91 -") != std::string::npos) {
        replacement = "+91 - " + formats.digits10;
    } else if (current_digits.size() == 12 && current_digits.rfind("91", 0) == 0) {
        replacement = formats.digits12;
    } else if (current_digits.size() == 10) {
        replacement = formats.digits10;
    }

    env->CallVoidMethod(reply, write_string, zygisk_utils::string_to_jstring(env, replacement));
    logger::info("TelephonySpoof", "line1 reply %s -> %s", current.c_str(), replacement.c_str());
    return true;
}

}  // namespace

SpoofPhoneFormats load_formats() {
    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();

    std::string raw = read_runtime_phone_file();
    if (raw.empty()) raw = config.mock_phone_sim1;
    if (raw.empty()) raw = config.mock_phone_sim2;

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
    if (!formats.digits10.empty()) {
        formats.e164 = "+91" + formats.digits10;
    }
    return formats;
}

bool phone_spoof_enabled() {
    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();
    return config.enable_phone_spoof || config.enable_sim1_mock || config.enable_sim2_mock;
}

bool is_telephony_binder_interface(const std::string& iface) {
    if (iface.empty()) return false;
    const std::string lower = [&]() {
        std::string s = iface;
        std::transform(s.begin(), s.end(), s.begin(),
                       [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
        return s;
    }();
    return lower.find("iphonesubinfo") != std::string::npos ||
           lower.find("itelephony") != std::string::npos ||
           lower.find("isub") != std::string::npos ||
           lower.find("isubscription") != std::string::npos ||
           lower.find("subscription") != std::string::npos ||
           lower.find("iphonesubinfocontroller") != std::string::npos ||
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

void scrub_reply_parcel(JNIEnv* env, jobject reply, const SpoofPhoneFormats& formats) {
    if (!reply || formats.digits10.empty()) return;

    if (rewrite_reply_simple_string(env, reply, formats)) {
        return;
    }

    jclass cls = env->GetObjectClass(reply);
    jmethodID marshall = env->GetMethodID(cls, "marshall", "()[B");
    jmethodID unmarshall = env->GetMethodID(cls, "unmarshall", "([BII)V");
    if (!marshall || !unmarshall) return;

    reset_parcel(env, reply);
    jbyteArray bytes = (jbyteArray)env->CallObjectMethod(reply, marshall);
    if (!bytes) return;

    jsize len = env->GetArrayLength(bytes);
    if (len <= 0) return;

    jbyte* data = env->GetByteArrayElements(bytes, nullptr);
    if (!data) return;

    std::string blob(reinterpret_cast<char*>(data), static_cast<size_t>(len));
    const std::string before = blob;
    scrub_blob_phones(blob, formats);

    if (blob != before) {
        std::memcpy(data, blob.data(), blob.size() < static_cast<size_t>(len) ? blob.size() : static_cast<size_t>(len));
        env->ReleaseByteArrayElements(bytes, data, 0);
        reset_parcel(env, reply);
        env->CallVoidMethod(reply, unmarshall, bytes, 0, len);
        logger::info("TelephonySpoof", "Scrubbed subscription/telephony parcel -> %s",
                     formats.digits10.c_str());
    } else {
        env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
    }
}

}  // namespace telephony_spoof
