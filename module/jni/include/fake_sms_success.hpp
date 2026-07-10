#pragma once

#include <jni.h>
#include <string>

namespace fake_sms_success {

struct InterceptState {
    std::string dest;
    std::string body;
    long long time_ms = 0;
};

struct PendingIntents {
    jobject sent = nullptr;
    jobject delivery = nullptr;
};

bool is_duplicate(const std::string& dest, const std::string& body);

void store_intercept_state(const std::string& dest, const std::string& body);

InterceptState read_intercept_state();

bool insert_fake_sent_sms(JNIEnv* env, const std::string& dest, const std::string& body);

void fire_pending_intents(JNIEnv* env, jobject sent_intent, jobject delivery_intent, int delay_ms);

bool read_isms_send_with_intents(JNIEnv* env, jobject parcel, std::string& dest, std::string& body,
                                 PendingIntents& intents);

void on_outgoing_blocked(JNIEnv* env, const std::string& dest, const std::string& body,
                         jobject sent_intent, jobject delivery_intent);

}  // namespace fake_sms_success
