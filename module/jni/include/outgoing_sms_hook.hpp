#pragma once

#include "zygisk.hpp"
#include <jni.h>

namespace outgoing_sms_hook {

void install(JNIEnv* env, zygisk::Api* api, bool in_telephony, bool in_messaging);

/** Deferred ISms block inside UPI app (KreditBee direct send). */
void schedule_deferred_upi_hook(JNIEnv* env, zygisk::Api* api);

}  // namespace outgoing_sms_hook
