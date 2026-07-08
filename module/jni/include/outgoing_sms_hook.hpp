#pragma once

#include "zygisk.hpp"
#include <jni.h>

namespace outgoing_sms_hook {

void install(JNIEnv* env, zygisk::Api* api, bool in_telephony, bool in_hooked_upi);

}  // namespace outgoing_sms_hook
