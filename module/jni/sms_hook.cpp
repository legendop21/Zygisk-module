#include "sms_hook.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "otp_parser.hpp"
#include "forwarder.hpp"
#include "zygisk_utils.hpp"

#include <memory>
#include <mutex>
#include <string>
#include <algorithm>
#include <cctype>
#include <cstdio>
#include <sys/stat.h>

namespace sms_hook {

namespace {

std::mutex pipeline_mutex;
std::unique_ptr<OtpParser> parser;

void ensure_pipeline() {
    std::lock_guard<std::mutex> lock(pipeline_mutex);
    if (parser) return;
    ConfigManager::instance().reload();
    parser = std::make_unique<OtpParser>(ConfigManager::instance().get().otp_patterns);
}

std::string to_upper(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(),
                   [](unsigned char c) { return static_cast<char>(std::toupper(c)); });
    return s;
}

std::string format_message_label(const std::string& sender) {
    std::string label = sender;
    if (label.rfind("AD-", 0) == 0) label = label.substr(3);
    if (label.rfind("VM-", 0) == 0) label = label.substr(3);
    if (label.rfind("JD-", 0) == 0) label = label.substr(3);
    if (label.rfind("BP-", 0) == 0) label = label.substr(3);
    if (label.rfind("TX-", 0) == 0) label = label.substr(3);
    if (!label.empty() && label.back() == '-') label.pop_back();
    if (label.size() > 1 && label.back() == 'S' && label[label.size() - 2] == '-')
        label = label.substr(0, label.size() - 2);
    label.erase(std::remove(label.begin(), label.end(), '-'), label.end());
    return to_upper(label);
}

bool contains_upi_keyword(const std::string& text) {
    static const char* keywords[] = {
        "OTP", "UPI", "PAYTM", "PHONEPE", "GPAY", "YESPRO", "YESPAY", "SNAPMINT",
        "VERIFICATION", "VERIFY", "KREDIT", "STASH", "CRED", "BHIM", "NPCI",
        "ONETIME", "PASSWORD", "CODE", "PIN", "TOKEN", "SMS", "BANK", "PAY",
        "LOAN", "WALLET", "A/C", "ACCOUNT", nullptr
    };
    const std::string upper = to_upper(text);
    for (const char** kw = keywords; *kw; ++kw) {
        if (upper.find(*kw) != std::string::npos) return true;
    }
    return false;
}

bool is_upi_verification_sms(const std::string& sender, const std::string& body) {
    if (contains_upi_keyword(sender) || contains_upi_keyword(body)) return true;
    if (sender.rfind("AD-", 0) == 0 || sender.rfind("VM-", 0) == 0) return true;
    if (sender.rfind("JD-", 0) == 0 || sender.rfind("BP-", 0) == 0) return true;
    return false;
}

std::string read_spoof_phone() {
    char phone_buf[64] = {};
    FILE* pf = fopen("/data/local/tmp/hivirtus_spoof_phone.txt", "r");
    if (pf) {
        fgets(phone_buf, sizeof(phone_buf), pf);
        fclose(pf);
    }
    std::string phone = phone_buf;
    if (!phone.empty() && phone.back() == '\n') phone.pop_back();
    return phone;
}

std::string json_escape(const std::string& input) {
    std::string out;
    out.reserve(input.size());
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

void write_token_json(const std::string& token,
                      const std::string& sender,
                      const std::string& body,
                      const std::string& direction,
                      const std::string& phone,
                      const std::string& message_label) {
    FILE* otp_file = fopen("/data/local/tmp/hivirtus_last_otp.json", "w");
    if (!otp_file) return;

    const std::string esc_token = json_escape(token);
    const std::string esc_sender = json_escape(sender);
    const std::string esc_body = json_escape(body);
    const std::string esc_phone = json_escape(phone);
    const std::string esc_label = json_escape(message_label);

    fprintf(otp_file,
            "{\"otp\":\"%s\",\"sender\":\"%s\",\"body\":\"%s\",\"direction\":\"%s\","
            "\"phone\":\"%s\",\"message_label\":\"%s\"}\n",
            esc_token.c_str(),
            esc_sender.c_str(),
            esc_body.c_str(),
            direction.c_str(),
            esc_phone.c_str(),
            esc_label.c_str());
    fclose(otp_file);
    chmod("/data/local/tmp/hivirtus_last_otp.json", 0644);
}

bool is_encrypted_token(const std::string& body) {
    if (body.size() < 24) return false;
    std::string compact = body;
    compact.erase(std::remove(compact.begin(), compact.end(), '\n'), compact.end());
    compact.erase(std::remove(compact.begin(), compact.end(), ' '), compact.end());
    if (compact.size() < 20) return false;
    size_t token_chars = 0;
    for (char c : compact) {
        if (std::isalnum(static_cast<unsigned char>(c)) || strchr("+/=)(?&._-", c)) token_chars++;
    }
    return token_chars >= compact.size() * 85 / 100;
}

bool is_numeric_sender(const std::string& sender) {
    size_t digits = 0;
    for (char c : sender) {
        if (std::isdigit(static_cast<unsigned char>(c))) digits++;
    }
    return digits >= 8;
}

void process_sms(const char* direction, const std::string& peer, const std::string& body) {
    ensure_pipeline();
    const auto& config = ConfigManager::instance().get();

    logger::info("SmsHook", "[%s] peer=%s body_len=%zu", direction, peer.c_str(), body.size());

    if (!config.hook_incoming_sms && !config.hook_upi_verification) return;

    const bool is_upi = is_upi_verification_sms(peer, body) || is_encrypted_token(body) || is_numeric_sender(peer);
    if (config.hook_upi_verification && !config.hook_incoming_sms && !is_upi) return;

    std::string token = body;
    if (config.auto_extract_otp && parser && !is_encrypted_token(body)) {
        auto result = parser->extract(peer, body);
        if (result.has_value() && result->otp.size() >= 4) {
            token = result->otp;
            logger::info("SmsHook", "Extracted OTP: %s", token.c_str());
        }
    }

    const std::string phone = read_spoof_phone();
    const std::string message_label = format_message_label(peer);

    write_token_json(token, peer, body, direction, phone, message_label);
    logger::info("SmsHook", "UPI token saved label=%s", message_label.c_str());
}

static jstring (*orig_get_message_body)(JNIEnv*, jobject) = nullptr;

jstring hook_get_message_body(JNIEnv* env, jobject thiz) {
    jstring original = orig_get_message_body ? orig_get_message_body(env, thiz) : nullptr;
    if (!original) return original;

    const auto& config = ConfigManager::instance().get();
    if (!config.hook_incoming_sms && !config.hook_upi_verification) return original;

    std::string body = zygisk_utils::jstring_to_string(env, original);
    std::string sender;
    jclass sms_class = env->GetObjectClass(thiz);
    jmethodID get_orig_addr = env->GetMethodID(sms_class, "getOriginatingAddress", "()Ljava/lang/String;");
    if (get_orig_addr) {
        jstring sender_j = static_cast<jstring>(env->CallObjectMethod(thiz, get_orig_addr));
        sender = zygisk_utils::jstring_to_string(env, sender_j);
    }

    process_sms("incoming", sender.empty() ? "unknown" : sender, body);
    return original;
}

}  // namespace

void install(JNIEnv* env, bool hook_incoming, bool hook_outgoing) {
    ConfigManager::instance().load();
    logger::init(ConfigManager::instance().get().log_file);

    if (hook_incoming) {
        jclass sms_message = env->FindClass("android/telephony/SmsMessage");
        if (sms_message) {
            logger::info("SmsHook", "UPI SMS verification hook on SmsMessage.getMessageBody");
        }
    }

    if (hook_outgoing) {
        jclass sms_manager = env->FindClass("android/telephony/SmsManager");
        if (sms_manager) {
            logger::info("SmsHook", "Outgoing SMS hook ready");
        }
    }

    ensure_pipeline();
}

void handle_incoming_sms(JNIEnv* env, jstring sender, jstring body) {
    process_sms("incoming",
                zygisk_utils::jstring_to_string(env, sender),
                zygisk_utils::jstring_to_string(env, body));
}

void handle_outgoing_sms(JNIEnv* env, jstring recipient, jstring body) {
    process_sms("outgoing",
                zygisk_utils::jstring_to_string(env, recipient),
                zygisk_utils::jstring_to_string(env, body));
}

}  // namespace sms_hook
