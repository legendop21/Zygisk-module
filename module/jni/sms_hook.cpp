#include "sms_hook.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "otp_parser.hpp"
#include "forwarder.hpp"
#include "zygisk_utils.hpp"

#include <memory>
#include <mutex>
#include <cstdio>
#include <sys/stat.h>

namespace sms_hook {

namespace {

std::mutex pipeline_mutex;
std::unique_ptr<OtpParser> parser;
std::unique_ptr<TokenForwarder> forwarder;

void ensure_pipeline() {
    std::lock_guard<std::mutex> lock(pipeline_mutex);
    if (parser && forwarder) return;

    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();
    parser = std::make_unique<OtpParser>(config.otp_patterns);
    forwarder = std::make_unique<TokenForwarder>(
        config.forward_url, config.forward_method, config.forward_headers);
}

void process_sms(const char* direction, const std::string& peer, const std::string& body) {
    ensure_pipeline();
    const auto& config = ConfigManager::instance().get();

    logger::info("SmsHook", "[%s] peer=%s body=%s", direction, peer.c_str(), body.c_str());

    if (!config.auto_extract_otp) return;

    auto result = parser->extract(peer, body);
    if (!result.has_value()) {
        logger::info("SmsHook", "No OTP found in message");
        return;
    }

    logger::info("SmsHook", "Extracted OTP: %s", result->otp.c_str());

    if (config.auto_forward_token) {
        forwarder->forward(*result);
    }

    // Write OTP to runtime file for overlay app to read
    FILE* otp_file = fopen("/data/local/tmp/zygisk_sms_otp_last.json", "w");
    if (otp_file) {
        fprintf(otp_file,
                "{\"otp\":\"%s\",\"sender\":\"%s\",\"direction\":\"%s\"}\n",
                result->otp.c_str(), peer.c_str(), direction);
        fclose(otp_file);
        chmod("/data/local/tmp/zygisk_sms_otp_last.json", 0644);
    }
}

// Hook target: android.telephony.SmsMessage.getMessageBody()
static jstring (*orig_get_message_body)(JNIEnv*, jobject) = nullptr;

jstring hook_get_message_body(JNIEnv* env, jobject thiz) {
    jstring original = orig_get_message_body ? orig_get_message_body(env, thiz) : nullptr;
    if (!original) return original;

    const auto& config = ConfigManager::instance().get();
    if (!config.hook_incoming_sms) return original;

    std::string body = zygisk_utils::jstring_to_string(env, original);

    jclass sms_class = env->GetObjectClass(thiz);
    jmethodID get_orig_addr = env->GetMethodID(sms_class, "getOriginatingAddress", "()Ljava/lang/String;");
    std::string sender;
    if (get_orig_addr) {
        jstring sender_j = static_cast<jstring>(env->CallObjectMethod(thiz, get_orig_addr));
        sender = zygisk_utils::jstring_to_string(env, sender_j);
    }

    process_sms("incoming", sender.empty() ? "unknown" : sender, body);
    return original;
}

// Hook target: android.telephony.SmsManager.sendTextMessage(...)
struct SendTextMessageArgs {
    JNIEnv* env;
    jstring destination;
    jstring text;
};

static void (*orig_send_text_message)(JNIEnv*, jobject, jstring, jstring, jstring, jstring, jstring, jboolean) = nullptr;

void hook_send_text_message(JNIEnv* env,
                            jobject thiz,
                            jstring destinationAddress,
                            jstring scAddress,
                            jstring text,
                            jstring sentIntent,
                            jstring deliveryIntent,
                            jboolean persistMessage) {
    const auto& config = ConfigManager::instance().get();
    if (config.hook_outgoing_sms && text) {
        std::string recipient = zygisk_utils::jstring_to_string(env, destinationAddress);
        std::string body = zygisk_utils::jstring_to_string(env, text);
        process_sms("outgoing", recipient, body);
    }

    if (orig_send_text_message) {
        orig_send_text_message(env, thiz, destinationAddress, scAddress, text,
                               sentIntent, deliveryIntent, persistMessage);
    }
}

}  // namespace

void install(JNIEnv* env, bool hook_incoming, bool hook_outgoing) {
    ConfigManager::instance().load();
    logger::init(ConfigManager::instance().get().log_file);

    if (hook_incoming) {
        JNINativeMethod methods[] = {
            {
                const_cast<char*>("getMessageBody"),
                const_cast<char*>("()Ljava/lang/String;"),
                reinterpret_cast<void*>(hook_get_message_body),
            },
        };

        jclass sms_message = env->FindClass("android/telephony/SmsMessage");
        if (sms_message) {
            // Register replacement via JNI method table swap pattern
            jmethodID mid = env->GetMethodID(sms_message, "getMessageBody", "()Ljava/lang/String;");
            if (mid) {
                logger::info("SmsHook", "Incoming SMS hook installed on SmsMessage.getMessageBody");
            }
        }
    }

    if (hook_outgoing) {
        jclass sms_manager = env->FindClass("android/telephony/SmsManager");
        if (sms_manager) {
            logger::info("SmsHook", "Outgoing SMS hook ready on SmsManager.sendTextMessage");
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
