#include "sender_spoof.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "zygisk_utils.hpp"

#include <algorithm>
#include <cctype>
#include <string>

namespace sender_spoof {

namespace {

std::string g_sender_id;
zygisk::Api* g_api = nullptr;

bool is_placeholder(const std::string& id) {
    return id.empty() || id == "AD-TEST-S";
}

bool is_indian_mobile(const std::string& sender) {
    std::string digits;
    for (char c : sender) {
        if (std::isdigit(static_cast<unsigned char>(c))) digits += c;
    }
    if (digits.size() == 10) return true;
    if (digits.size() == 12 && digits.rfind("91", 0) == 0) return true;
    if (digits.size() == 11 && digits[0] == '0') return true;
    return false;
}

bool is_numeric_sender(const std::string& sender) {
    size_t digits = 0;
    for (char c : sender) {
        if (std::isdigit(static_cast<unsigned char>(c))) digits++;
    }
    return digits >= 8 && !std::any_of(sender.begin(), sender.end(),
                                       [](unsigned char c) { return std::isalpha(c); });
}

std::string resolve_sender(const std::string& actual) {
    if (!is_placeholder(g_sender_id) &&
        (is_indian_mobile(actual) || is_numeric_sender(actual))) {
        return g_sender_id;
    }
    return actual;
}

static jstring (*orig_get_originating_address)(JNIEnv*, jobject) = nullptr;
static jstring (*orig_get_display_originating_address)(JNIEnv*, jobject) = nullptr;

jstring hook_get_originating_address(JNIEnv* env, jobject thiz) {
    jstring original = orig_get_originating_address
                           ? orig_get_originating_address(env, thiz)
                           : nullptr;
    if (!original || is_placeholder(g_sender_id)) return original;

    const std::string actual = zygisk_utils::jstring_to_string(env, original);
    const std::string resolved = resolve_sender(actual);
    if (resolved == actual) return original;
    return zygisk_utils::string_to_jstring(env, resolved);
}

jstring hook_get_display_originating_address(JNIEnv* env, jobject thiz) {
    jstring original = orig_get_display_originating_address
                           ? orig_get_display_originating_address(env, thiz)
                           : nullptr;
    if (!original || is_placeholder(g_sender_id)) return original;

    const std::string actual = zygisk_utils::jstring_to_string(env, original);
    const std::string resolved = resolve_sender(actual);
    if (resolved == actual) return original;
    return zygisk_utils::string_to_jstring(env, resolved);
}

void install_sms_message_hooks(JNIEnv* env) {
    if (!g_api || !g_api->hookJniNativeMethods || is_placeholder(g_sender_id)) return;

    JNINativeMethod addr_methods[] = {
        {"getOriginatingAddress", "()Ljava/lang/String;",
         reinterpret_cast<void*>(hook_get_originating_address)},
    };
    g_api->hookJniNativeMethods(env, "android/telephony/SmsMessage", addr_methods, 1);
    if (addr_methods[0].fnPtr) {
        orig_get_originating_address =
            reinterpret_cast<decltype(orig_get_originating_address)>(addr_methods[0].fnPtr);
        logger::info("SenderSpoof", "getOriginatingAddress hook installed");
    }

    JNINativeMethod display_methods[] = {
        {"getDisplayOriginatingAddress", "()Ljava/lang/String;",
         reinterpret_cast<void*>(hook_get_display_originating_address)},
    };
    g_api->hookJniNativeMethods(env, "android/telephony/SmsMessage", display_methods, 1);
    if (display_methods[0].fnPtr) {
        orig_get_display_originating_address =
            reinterpret_cast<decltype(orig_get_display_originating_address)>(
                display_methods[0].fnPtr);
        logger::info("SenderSpoof", "getDisplayOriginatingAddress hook installed");
    }
}

}  // namespace

void install(JNIEnv* env, zygisk::Api* api, const char* tag) {
    g_api = api;
    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();
    g_sender_id = config.inject_sender_id;
    if (is_placeholder(g_sender_id)) return;

    install_sms_message_hooks(env);
    logger::info("SenderSpoof", "Sender ID spoof active in %s -> %s", tag, g_sender_id.c_str());
}

}  // namespace sender_spoof
