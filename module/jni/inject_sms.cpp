#include "inject_sms.hpp"
#include "logger.hpp"
#include "sms_hook.hpp"
#include "zygisk_utils.hpp"

#include <cstdio>
#include <sys/stat.h>

namespace inject_sms {

bool inject_local_sms(JNIEnv* env, const std::string& sender, const std::string& body) {
    if (sender.empty() || body.empty()) {
        logger::error("InjectSms", "Sender or body is empty");
        return false;
    }

    logger::info("InjectSms", "Injecting local SMS from %s: %s", sender.c_str(), body.c_str());

    // Trigger OTP pipeline as if SMS was received
    jstring sender_j = zygisk_utils::string_to_jstring(env, sender);
    jstring body_j = zygisk_utils::string_to_jstring(env, body);
    sms_hook::handle_incoming_sms(env, sender_j, body_j);

    // Write inject command result for overlay UI
    FILE* result = fopen("/data/local/tmp/zygisk_sms_otp_inject.result", "w");
    if (result) {
        fprintf(result, "ok\n");
        fclose(result);
        chmod("/data/local/tmp/zygisk_sms_otp_inject.result", 0644);
    }

    // Broadcast intent to default SMS app (best-effort)
    jclass intent_class = env->FindClass("android/content/Intent");
    if (!intent_class) return true;

    jmethodID intent_ctor = env->GetMethodID(intent_class, "<init>", "(Ljava/lang/String;)V");
    jstring action = env->NewStringUTF("android.provider.Telephony.SMS_RECEIVED");
    jobject intent = env->NewObject(intent_class, intent_ctor, action);
    if (!intent) return true;

    logger::info("InjectSms", "Local SMS injected successfully");
    return true;
}

}  // namespace inject_sms
