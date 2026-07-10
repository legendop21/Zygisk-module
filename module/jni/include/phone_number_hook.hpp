#pragma once

#include "zygisk.hpp"
#include <jni.h>

namespace phone_number_hook {

/** getLine1Number / getMsisdn JNI hooks + binder spoof companion. */
void install(JNIEnv* env, zygisk::Api* api, const char* tag);

}  // namespace phone_number_hook
