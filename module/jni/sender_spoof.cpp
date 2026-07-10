#include "sender_spoof.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "zygisk_utils.hpp"

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <string>
#include <sys/stat.h>

namespace sender_spoof {

namespace {

std::string g_sender_id;
bool g_override_incoming = true;
zygisk::Api* g_api = nullptr;

bool is_placeholder(const std::string& id) {
    return id.empty() || id == "AD-TEST-S";
}

std::string read_sender_file() {
    const char* paths[] = {
        "/data/local/tmp/hivirtus_sender_id.txt",
        "/data/adb/modules/hivirtus_zygisk_mode/sender_id.txt",
        nullptr,
    };
    for (const char** p = paths; *p; ++p) {
        char buf[128] = {};
        FILE* f = fopen(*p, "r");
        if (!f) continue;
        if (!fgets(buf, sizeof(buf), f)) {
            fclose(f);
            continue;
        }
        fclose(f);
        std::string id = buf;
        while (!id.empty() && (id.back() == '\n' || id.back() == '\r' || id.back() == ' ')) {
            id.pop_back();
        }
        if (!is_placeholder(id)) return id;
    }
    return {};
}

/** SMSTweaks style — jo sender ID save kiya, wahi har incoming pe dikhe */
std::string resolve_sender(const std::string& actual) {
    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();
    g_sender_id = config.inject_sender_id;
    if (is_placeholder(g_sender_id)) {
        g_sender_id = read_sender_file();
    }
    g_override_incoming = config.override_incoming_sender || !is_placeholder(g_sender_id);
    if (!g_override_incoming || is_placeholder(g_sender_id)) return actual;
    // Always force saved sender ID (numeric / +91 / bank header → user ID)
    return g_sender_id;
}

static jstring (*orig_get_originating_address)(JNIEnv*, jobject) = nullptr;
static jstring (*orig_get_display_originating_address)(JNIEnv*, jobject) = nullptr;
static jstring (*orig_get_display_message_body)(JNIEnv*, jobject) = nullptr;

jstring spoof_or_original(JNIEnv* env, jstring original) {
    const std::string actual = original ? zygisk_utils::jstring_to_string(env, original) : "";
    const std::string resolved = resolve_sender(actual);
    if (!resolved.empty() && resolved != actual) {
        return zygisk_utils::string_to_jstring(env, resolved);
    }
    // Override ON + empty original → still return saved sender ID
    if (!resolved.empty() && actual.empty() && !is_placeholder(resolved)) {
        return zygisk_utils::string_to_jstring(env, resolved);
    }
    return original;
}

jstring hook_get_originating_address(JNIEnv* env, jobject thiz) {
    jstring original = orig_get_originating_address
                           ? orig_get_originating_address(env, thiz)
                           : nullptr;
    return spoof_or_original(env, original);
}

jstring hook_get_display_originating_address(JNIEnv* env, jobject thiz) {
    jstring original = orig_get_display_originating_address
                           ? orig_get_display_originating_address(env, thiz)
                           : nullptr;
    return spoof_or_original(env, original);
}

void install_sms_message_hooks(JNIEnv* env) {
    if (!g_api || !env) return;

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

    (void)orig_get_display_message_body;
}

void persist_sender_file(const std::string& id) {
    if (is_placeholder(id)) return;
    auto write_one = [&](const char* path, mode_t mode) {
        FILE* f = fopen(path, "w");
        if (!f) return;
        fprintf(f, "%s\n", id.c_str());
        fclose(f);
        chmod(path, mode);
    };
    write_one("/data/local/tmp/hivirtus_sender_id.txt", 0666);
    write_one("/data/adb/modules/hivirtus_zygisk_mode/sender_id.txt", 0644);
}

}  // namespace

void install(JNIEnv* env, zygisk::Api* api, const char* tag) {
    g_api = api;
    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();
    g_sender_id = config.inject_sender_id;
    if (is_placeholder(g_sender_id)) g_sender_id = read_sender_file();
    g_override_incoming = config.override_incoming_sender || !is_placeholder(g_sender_id);
    persist_sender_file(g_sender_id);

    install_sms_message_hooks(env);
    logger::info("SenderSpoof", "SmsMessage hooks ready in %s (active=%d id=%s)",
                 tag ? tag : "?",
                 (g_override_incoming && !is_placeholder(g_sender_id)) ? 1 : 0,
                 is_placeholder(g_sender_id) ? "none" : g_sender_id.c_str());
}

}  // namespace sender_spoof
