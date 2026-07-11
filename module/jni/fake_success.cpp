#include "fake_success.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "zygisk_utils.hpp"

#include <cstdio>
#include <ctime>
#include <sys/stat.h>

namespace fake_success {

namespace {

// Activity.RESULT_OK — Gamex XposedHook.firePendingIntentWithResultCode(..., -1, ...)
constexpr jint kResultOk = -1;

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

void send_ordered(JNIEnv* env, jobject ctx, const char* action, jint result_code) {
    if (!ctx || !action) return;
    jclass intent_cls = env->FindClass("android/content/Intent");
    if (!intent_cls) return;
    jmethodID ctor = env->GetMethodID(intent_cls, "<init>", "(Ljava/lang/String;)V");
    if (!ctor) return;
    jobject intent = env->NewObject(intent_cls, ctor, env->NewStringUTF(action));
    if (!intent) return;

    jclass ctx_cls = env->FindClass("android/content/Context");
    // sendOrderedBroadcast(Intent, String, BroadcastReceiver, Handler, int, String, Bundle)
    jmethodID send = env->GetMethodID(
        ctx_cls, "sendOrderedBroadcast",
        "(Landroid/content/Intent;Ljava/lang/String;Landroid/content/BroadcastReceiver;"
        "Landroid/os/Handler;ILjava/lang/String;Landroid/os/Bundle;)V");
    if (!send) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return;
    }
    env->CallVoidMethod(ctx, send, intent, nullptr, nullptr, nullptr, result_code, nullptr,
                        nullptr);
    if (env->ExceptionCheck()) env->ExceptionClear();
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

void fire_pending_intent_ok(JNIEnv* env, jobject pending_intent, const char* type) {
    if (!env || !pending_intent) return;
    const char* label = type ? type : "pi";

    jclass pi = env->FindClass("android/app/PendingIntent");
    if (!pi || !env->IsInstanceOf(pending_intent, pi)) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return;
    }

    jobject ctx = get_app_context(env);
    // Gamex primary: send(Context, int, Intent) with RESULT_OK
    jmethodID send3 = env->GetMethodID(
        pi, "send", "(Landroid/content/Context;ILandroid/content/Intent;)V");
    if (send3 && ctx) {
        env->CallVoidMethod(pending_intent, send3, ctx, kResultOk, nullptr);
        if (!env->ExceptionCheck()) {
            logger::info("FakeSuccess", "PendingIntent %s fired RESULT_OK via send(ctx,int,Intent)",
                         label);
            return;
        }
        env->ExceptionClear();
    }

    // Gamex fallback: send(int)
    jmethodID send1 = env->GetMethodID(pi, "send", "(I)V");
    if (send1) {
        env->CallVoidMethod(pending_intent, send1, kResultOk);
        if (!env->ExceptionCheck()) {
            logger::info("FakeSuccess", "PendingIntent %s fired RESULT_OK via send(int)", label);
            return;
        }
        env->ExceptionClear();
    }

    // Last resort: send()
    jmethodID send0 = env->GetMethodID(pi, "send", "()V");
    if (send0) {
        env->CallVoidMethod(pending_intent, send0);
        if (env->ExceptionCheck()) env->ExceptionClear();
        else logger::info("FakeSuccess", "PendingIntent %s fired via send()", label);
    }
}

void fire_sms_result_broadcasts(JNIEnv* env, const std::string& dest) {
    if (!env) return;
    jobject ctx = get_app_context(env);
    if (!ctx) {
        logger::info("FakeSuccess", "No context — skip SMS_SENT broadcasts");
        return;
    }

    send_ordered(env, ctx, "SMS_SENT", kResultOk);
    send_ordered(env, ctx, "SMS_DELIVERED", kResultOk);
    if (!dest.empty()) {
        std::string sent_num = std::string("SMS_SENT") + dest;
        std::string del_num = std::string("SMS_DELIVERY") + dest;
        send_ordered(env, ctx, sent_num.c_str(), kResultOk);
        send_ordered(env, ctx, del_num.c_str(), kResultOk);
    }
    logger::info("FakeSuccess", "Gamex SMS_SENT/DELIVERED broadcasts fired dest=%s", dest.c_str());
}

void on_outgoing_intercepted(JNIEnv* env, const std::string& dest, const std::string& body,
                             jobject sent_intent, jobject delivery_intent) {
    write_last_intercept(dest, body);
    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();
    if (config.intercept_fake_success) {
        insert_fake_sent_sms(env, dest, body);
    }
    // Always signal success like Gamex handleSmsResult — even if insert fails
    fire_pending_intent_ok(env, sent_intent, "sent");
    fire_pending_intent_ok(env, delivery_intent, "delivery");
    fire_sms_result_broadcasts(env, dest);
}

}  // namespace fake_success
