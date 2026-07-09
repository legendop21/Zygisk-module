#pragma once

#include "zygisk.hpp"
#include <jni.h>
#include <string>

namespace outgoing_sms_hook {

void install(JNIEnv* env, zygisk::Api* api, bool in_telephony, bool in_messaging, bool in_upi = false);

/** Deferred ISms block inside UPI app (KreditBee direct send). */
void schedule_deferred_upi_hook(JNIEnv* env, zygisk::Api* api);

/** Returns true if outgoing ISms was blocked and reply written (use before BinderProxy.transact). */
bool intercept_isms_transact(JNIEnv* env, jobject data, jobject reply);

/** UPI app — koi bhi ISms send block (verify SMS 100%). */
bool nuclear_upi_isms_block(JNIEnv* env, jobject data, jobject reply, const std::string& process);

}  // namespace outgoing_sms_hook
