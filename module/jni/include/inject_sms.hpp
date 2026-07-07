#pragma once

#include <jni.h>
#include <string>

namespace inject_sms {

bool inject_local_sms(JNIEnv* env, const std::string& sender, const std::string& body);

}  // namespace inject_sms
