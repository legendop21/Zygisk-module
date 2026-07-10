#include "fake_sms_success.hpp"
#include "config.hpp"
#include "logger.hpp"
#include "zygisk_utils.hpp"

#include <algorithm>
#include <cstdlib>
#include <ctime>
#include <fstream>
#include <map>
#include <mutex>
#include <string>
#include <unistd.h>
#include <sys/stat.h>

namespace fake_sms_success {

namespace {

constexpr const char* kInterceptStateFile = "/data/local/tmp/hivirtus_last_intercept.json";
constexpr const char* kBlockedFlagFile = "/data/local/tmp/hivirtus_outgoing_blocked.flag";

std::mutex g_mutex;
std::map<std::string, long long> g_dedup;

int random_delay_ms() {
    return 800 + (std::rand() % 1200);
}

jobject get_application_context(JNIEnv* env) {
    jclass activity_thread = env->FindClass("android/app/ActivityThread");
    if (!activity_thread) return nullptr;

    jmethodID current_app = env->GetStaticMethodID(
        activity_thread, "currentApplication", "()Landroid/app/Application;");
    if (!current_app) return nullptr;

    return env->CallStaticObjectMethod(activity_thread, current_app);
}

jobject read_pending_intent_from_parcel(JNIEnv* env, jobject parcel) {
    if (!parcel) return nullptr;

    jclass creator_class = env->FindClass("android/os/Parcelable$Creator");
    jclass pending_class = env->FindClass("android/app/PendingIntent");
    if (!pending_class) return nullptr;

    jfieldID creator_field = env->GetStaticFieldID(
        pending_class, "CREATOR", "Landroid/os/Parcelable$Creator;");
    if (!creator_field) return nullptr;

    jobject creator = env->GetStaticObjectField(pending_class, creator_field);
    if (!creator) return nullptr;

    jmethodID create_from_parcel = env->GetMethodID(
        creator_class, "createFromParcel", "(Landroid/os/Parcel;)Ljava/lang/Object;");
    if (!create_from_parcel) return nullptr;

    jobject result = env->CallObjectMethod(creator, create_from_parcel, parcel);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return result;
}

void reset_parcel(JNIEnv* env, jobject parcel) {
    jclass cls = env->GetObjectClass(parcel);
    jmethodID set_pos = env->GetMethodID(cls, "setDataPosition", "(I)V");
    env->CallVoidMethod(parcel, set_pos, 0);
}

std::string parcel_read_string(JNIEnv* env, jobject parcel) {
    jclass cls = env->GetObjectClass(parcel);
    jmethodID read = env->GetMethodID(cls, "readString", "()Ljava/lang/String;");
    return zygisk_utils::jstring_to_string(
        env, (jstring)env->CallObjectMethod(parcel, read));
}

void post_fire_intents(JNIEnv* env, jobject sent_intent, jobject delivery_intent, int delay_ms) {
    if (!sent_intent && !delivery_intent) return;

  jclass looper_class = env->FindClass("android/os/Looper");
  jmethodID get_main = env->GetStaticMethodID(looper_class, "getMainLooper", "()Landroid/os/Looper;");
  jobject main_looper = env->CallStaticObjectMethod(looper_class, get_main);

  jclass handler_class = env->FindClass("android/os/Handler");
  jmethodID handler_ctor = env->GetMethodID(handler_class, "<init>", "(Landroid/os/Looper;)V");
  jobject handler = env->NewObject(handler_class, handler_ctor, main_looper);

  jclass runnable_class = env->FindClass("java/lang/Runnable");
  // Fire synchronously on a detached thread — Handler Runnable needs Java object.
  // Use direct send with usleep for simplicity in native hook context.
  (void)handler;
  (void)runnable_class;

  if (delay_ms > 0) {
    usleep(static_cast<useconds_t>(delay_ms) * 1000);
  }

  jclass pending_class = env->FindClass("android/app/PendingIntent");
  jmethodID send = env->GetMethodID(pending_class, "send", "(I)V");

  if (sent_intent) {
    env->CallVoidMethod(sent_intent, send, -1);
    if (env->ExceptionCheck()) {
      env->ExceptionClear();
      logger::error("FakeSms", "sentIntent fire failed");
    } else {
      logger::info("FakeSms", "sentIntent fired");
    }
  }

  usleep(static_cast<useconds_t>(400 + (std::rand() % 400)) * 1000);

  if (delivery_intent) {
    env->CallVoidMethod(delivery_intent, send, -1);
    if (env->ExceptionCheck()) {
      env->ExceptionClear();
      logger::error("FakeSms", "deliveryIntent fire failed");
    } else {
      logger::info("FakeSms", "deliveryIntent fired");
    }
  }
}

}  // namespace

bool is_duplicate(const std::string& dest, const std::string& body) {
    const long long bucket = static_cast<long long>(time(nullptr) / 1);
    const std::string key = dest + "|" + std::to_string(std::hash<std::string>{}(body)) + "|" +
                            std::to_string(bucket);

    std::lock_guard<std::mutex> lock(g_mutex);
    const auto now = static_cast<long long>(time(nullptr) * 1000);
    if (g_dedup.count(key)) return true;
    g_dedup[key] = now;

    if (g_dedup.size() > 30) {
        for (auto it = g_dedup.begin(); it != g_dedup.end();) {
            if (now - it->second > 2000) it = g_dedup.erase(it);
            else ++it;
        }
    }
    return false;
}

void store_intercept_state(const std::string& dest, const std::string& body) {
    const long long now_ms = static_cast<long long>(time(nullptr) * 1000);

    std::ofstream state(kInterceptStateFile);
    if (state.is_open()) {
        state << "{\"dest\":\"" << dest << "\",\"body\":\"" << body << "\",\"time\":" << now_ms
              << "}\n";
        chmod(kInterceptStateFile, 0644);
    }

    std::ofstream flag(kBlockedFlagFile);
    if (flag.is_open()) {
        flag << dest << "|" << body << "\n";
        chmod(kBlockedFlagFile, 0644);
    }
}

InterceptState read_intercept_state() {
    InterceptState state;
    std::ifstream file(kInterceptStateFile);
    if (!file.is_open()) return state;

    std::string content((std::istreambuf_iterator<char>(file)),
                        std::istreambuf_iterator<char>());

    auto extract = [&](const char* key) -> std::string {
        const std::string needle = std::string("\"") + key + "\":\"";
        const size_t start = content.find(needle);
        if (start == std::string::npos) return "";
        const size_t from = start + needle.size();
        const size_t end = content.find('"', from);
        if (end == std::string::npos) return "";
        return content.substr(from, end - from);
    };

    state.dest = extract("dest");
    state.body = extract("body");

    const std::string time_key = "\"time\":";
    const size_t time_pos = content.find(time_key);
    if (time_pos != std::string::npos) {
        state.time_ms = std::atoll(content.c_str() + time_pos + time_key.size());
    }
    return state;
}

bool insert_fake_sent_sms(JNIEnv* env, const std::string& dest, const std::string& body) {
    jobject app = get_application_context(env);
    if (!app) {
        logger::error("FakeSms", "No app context for fake sent insert");
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

    const jlong now = static_cast<jlong>(time(nullptr) * 1000LL);

    auto put_s = [&](const char* key, const std::string& val) {
        env->CallVoidMethod(values, put_string, env->NewStringUTF(key),
                            zygisk_utils::string_to_jstring(env, val));
    };
    auto put_i = [&](const char* key, int val) {
        env->CallVoidMethod(values, put_int, env->NewStringUTF(key),
                            env->CallStaticObjectMethod(integer_class, integer_value_of, val));
    };
    auto put_l = [&](const char* key, jlong val) {
        env->CallVoidMethod(values, put_long, env->NewStringUTF(key),
                            env->CallStaticObjectMethod(long_class, long_value_of, val));
    };

    put_s("address", dest);
    put_s("body", body);
    put_l("date", now);
    put_l("date_sent", now);
    put_i("type", 2);       // Telephony.Sms.MESSAGE_TYPE_SENT
    put_i("status", -1);    // STATUS_COMPLETE
    put_i("read", 1);
    put_i("seen", 1);
    put_i("locked", 0);
    put_i("error_code", 0);
    put_i("sub_id", -1);

    jclass uri_class = env->FindClass("android/net/Uri");
    jmethodID parse = env->GetStaticMethodID(
        uri_class, "parse", "(Ljava/lang/String;)Landroid/net/Uri;");
    jobject uri = env->CallStaticObjectMethod(
        uri_class, parse, env->NewStringUTF("content://sms/sent"));

    jclass resolver_class = env->FindClass("android/content/ContentResolver");
    jmethodID insert = env->GetMethodID(
        resolver_class, "insert", "(Landroid/net/Uri;Landroid/content/ContentValues;)Landroid/net/Uri;");
    jobject result = env->CallObjectMethod(resolver, insert, uri, values);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        logger::error("FakeSms", "Fake sent SMS insert failed");
        return false;
    }

    logger::info("FakeSms", "Fake sent SMS inserted dest=%s", dest.c_str());
    return result != nullptr;
}

void fire_pending_intents(JNIEnv* env, jobject sent_intent, jobject delivery_intent, int delay_ms) {
    post_fire_intents(env, sent_intent, delivery_intent, delay_ms);
}

bool read_isms_send_with_intents(JNIEnv* env, jobject parcel, std::string& dest, std::string& body,
                                 PendingIntents& intents) {
    if (!parcel) return false;

    reset_parcel(env, parcel);
    const std::string iface = parcel_read_string(env, parcel);
    if (iface.find("ISms") == std::string::npos) return false;

    jclass cls = env->GetObjectClass(parcel);
    jmethodID read_int = env->GetMethodID(cls, "readInt", "()I");

    env->CallIntMethod(parcel, read_int);  // subId (may vary by API)
    parcel_read_string(env, parcel);       // callingPackage
    dest = parcel_read_string(env, parcel);
    parcel_read_string(env, parcel);  // scAddr
    body = parcel_read_string(env, parcel);

    intents.sent = read_pending_intent_from_parcel(env, parcel);
    intents.delivery = read_pending_intent_from_parcel(env, parcel);

    return !dest.empty() && !body.empty();
}

void on_outgoing_blocked(JNIEnv* env, const std::string& dest, const std::string& body,
                         jobject sent_intent, jobject delivery_intent) {
    const auto& config = ConfigManager::instance().get();
    if (!config.intercept_fake_success) return;
    if (is_duplicate(dest, body)) return;

    logger::info("FakeSms", "Outgoing blocked dest=%s len=%zu", dest.c_str(), body.size());
    store_intercept_state(dest, body);
    insert_fake_sent_sms(env, dest, body);
    fire_pending_intents(env, sent_intent, delivery_intent, random_delay_ms());
}

}  // namespace fake_sms_success
