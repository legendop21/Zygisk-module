#include "fake_success.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "zygisk_utils.hpp"

#include <cstdio>
#include <ctime>
#include <sys/stat.h>

namespace fake_success {

namespace {

bool write_last_intercept(const std::string& dest, const std::string& body) {
    FILE* f = fopen("/data/local/tmp/hivirtus_last_intercept.json", "w");
    if (!f) return false;
    fprintf(f,
            "{\"dest\":\"%s\",\"body\":\"%s\",\"time\":%ld}\n",
            dest.c_str(), body.c_str(), static_cast<long>(time(nullptr)));
    fclose(f);
    chmod("/data/local/tmp/hivirtus_last_intercept.json", 0644);
    return true;
}

jobject get_app_context(JNIEnv* env) {
    jclass activity_thread = env->FindClass("android/app/ActivityThread");
    if (!activity_thread) return nullptr;
    jmethodID current_app = env->GetStaticMethodID(
        activity_thread, "currentApplication", "()Landroid/app/Application;");
    if (!current_app) return nullptr;
    return env->CallStaticObjectMethod(activity_thread, current_app);
}

}  // namespace

std::string apply_prefix(const std::string& body) {
    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();
    if (!config.prefix_enabled || config.prefix_text.empty() || body.empty()) {
        return body;
    }
    if (body.rfind(config.prefix_text, 0) == 0) return body;
    return config.prefix_text + body;
}

bool insert_fake_sent_sms(JNIEnv* env, const std::string& dest, const std::string& body) {
    if (!env || dest.empty()) return false;

    jobject app = get_app_context(env);
    if (!app) {
        logger::info("FakeSuccess", "No app context — skip insertFakeSentSms");
        return false;
    }

    jclass context_class = env->FindClass("android/content/Context");
    jmethodID get_resolver = env->GetMethodID(
        context_class, "getContentResolver", "()Landroid/content/ContentResolver;");
    jobject resolver = env->CallObjectMethod(app, get_resolver);
    if (!resolver) return false;

    jclass values_class = env->FindClass("android/content/ContentValues");
    jmethodID values_ctor = env->GetMethodID(values_class, "<init>", "()V");
    jobject values = env->NewObject(values_class, values_ctor);
    if (!values) return false;

    jmethodID put_string = env->GetMethodID(
        values_class, "put", "(Ljava/lang/String;Ljava/lang/String;)V");
    jmethodID put_int = env->GetMethodID(
        values_class, "put", "(Ljava/lang/String;Ljava/lang/Integer;)V");
    jmethodID put_long = env->GetMethodID(
        values_class, "put", "(Ljava/lang/String;Ljava/lang/Long;)V");

    jclass integer_class = env->FindClass("java/lang/Integer");
    jmethodID integer_value_of = env->GetStaticMethodID(
        integer_class, "valueOf", "(I)Ljava/lang/Integer;");
    jclass long_class = env->FindClass("java/lang/Long");
    jmethodID long_value_of = env->GetStaticMethodID(
        long_class, "valueOf", "(J)Ljava/lang/Long;");

    const jlong now_ms = static_cast<jlong>(time(nullptr) * 1000LL);

    env->CallVoidMethod(values, put_string, env->NewStringUTF("address"),
                        zygisk_utils::string_to_jstring(env, dest));
    env->CallVoidMethod(values, put_string, env->NewStringUTF("body"),
                        zygisk_utils::string_to_jstring(env, body));
    env->CallVoidMethod(values, put_long, env->NewStringUTF("date"),
                        env->CallStaticObjectMethod(long_class, long_value_of, now_ms));
    env->CallVoidMethod(values, put_long, env->NewStringUTF("date_sent"),
                        env->CallStaticObjectMethod(long_class, long_value_of, now_ms));
    env->CallVoidMethod(values, put_int, env->NewStringUTF("type"),
                        env->CallStaticObjectMethod(integer_class, integer_value_of, 2));
    env->CallVoidMethod(values, put_int, env->NewStringUTF("read"),
                        env->CallStaticObjectMethod(integer_class, integer_value_of, 1));
    env->CallVoidMethod(values, put_int, env->NewStringUTF("seen"),
                        env->CallStaticObjectMethod(integer_class, integer_value_of, 1));

    jclass uri_class = env->FindClass("android/net/Uri");
    jmethodID parse = env->GetStaticMethodID(
        uri_class, "parse", "(Ljava/lang/String;)Landroid/net/Uri;");
    jobject uri = env->CallStaticObjectMethod(
        uri_class, parse, env->NewStringUTF("content://sms/sent"));

    jclass resolver_class = env->FindClass("android/content/ContentResolver");
    jmethodID insert = env->GetMethodID(
        resolver_class, "insert", "(Landroid/net/Uri;Landroid/content/ContentValues;)Landroid/net/Uri;");
    jobject result = env->CallObjectMethod(resolver, insert, uri, values);
    const bool ok = result != nullptr;
    logger::info("FakeSuccess", "insertFakeSentSms %s -> %s", dest.c_str(), ok ? "ok" : "fail");
    return ok;
}

void on_outgoing_intercepted(JNIEnv* env, const std::string& dest, const std::string& body) {
    write_last_intercept(dest, body);
    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();
    if (config.intercept_fake_success) {
        insert_fake_sent_sms(env, dest, body);
    }
}

}  // namespace fake_success
