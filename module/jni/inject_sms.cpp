#include "inject_sms.hpp"
#include "logger.hpp"
#include "sms_hook.hpp"
#include "zygisk_utils.hpp"

#include <cstdio>
#include <ctime>
#include <sys/stat.h>

namespace inject_sms {

namespace {

bool insert_inbox_via_provider(JNIEnv* env, const std::string& sender, const std::string& body) {
    jclass activity_thread = env->FindClass("android/app/ActivityThread");
    if (!activity_thread) return false;

    jmethodID current_app = env->GetStaticMethodID(
        activity_thread, "currentApplication", "()Landroid/app/Application;");
    if (!current_app) return false;

    jobject app = env->CallStaticObjectMethod(activity_thread, current_app);
    if (!app) return false;

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

    env->CallVoidMethod(values, put_string,
                        env->NewStringUTF("address"),
                        zygisk_utils::string_to_jstring(env, sender));
    env->CallVoidMethod(values, put_string,
                        env->NewStringUTF("body"),
                        zygisk_utils::string_to_jstring(env, body));
    env->CallVoidMethod(values, put_int,
                        env->NewStringUTF("type"),
                        env->CallStaticObjectMethod(integer_class, integer_value_of, 1));
    env->CallVoidMethod(values, put_int,
                        env->NewStringUTF("read"),
                        env->CallStaticObjectMethod(integer_class, integer_value_of, 0));
    env->CallVoidMethod(values, put_long,
                        env->NewStringUTF("date"),
                        env->CallStaticObjectMethod(long_class, long_value_of,
                                                    static_cast<jlong>(time(nullptr) * 1000LL)));

    jclass uri_class = env->FindClass("android/net/Uri");
    jmethodID parse = env->GetStaticMethodID(
        uri_class, "parse", "(Ljava/lang/String;)Landroid/net/Uri;");
    jobject uri = env->CallStaticObjectMethod(
        uri_class, parse, env->NewStringUTF("content://sms/inbox"));

    jclass resolver_class = env->FindClass("android/content/ContentResolver");
    jmethodID insert = env->GetMethodID(
        resolver_class, "insert", "(Landroid/net/Uri;Landroid/content/ContentValues;)Landroid/net/Uri;");
    jobject result = env->CallObjectMethod(resolver, insert, uri, values);
    return result != nullptr;
}

}  // namespace

bool inject_local_sms(JNIEnv* env, const std::string& sender, const std::string& body) {
    if (sender.empty() || body.empty()) {
        logger::error("InjectSms", "Sender or body is empty");
        return false;
    }

    logger::info("InjectSms", "Injecting local SMS from %s (len=%zu)", sender.c_str(), body.size());

    const bool inserted = insert_inbox_via_provider(env, sender, body);

    jstring sender_j = zygisk_utils::string_to_jstring(env, sender);
    jstring body_j = zygisk_utils::string_to_jstring(env, body);
    sms_hook::handle_incoming_sms(env, sender_j, body_j);

    FILE* result = fopen("/data/local/tmp/hivirtus_inject.result", "w");
    if (result) {
        fprintf(result, inserted ? "ok\n" : "pipeline_only\n");
        fclose(result);
        chmod("/data/local/tmp/hivirtus_inject.result", 0644);
    }

    logger::info("InjectSms", "Local SMS inject done inserted=%d", inserted ? 1 : 0);
    return true;
}

}  // namespace inject_sms
