#pragma once

#include <jni.h>
#include <string>

namespace sms_hook {

void install(JNIEnv* env, bool hook_incoming, bool hook_outgoing);
void handle_incoming_sms(JNIEnv* env, jstring sender, jstring body);
void handle_outgoing_sms(JNIEnv* env, jstring recipient, jstring body);

}  // namespace sms_hook
