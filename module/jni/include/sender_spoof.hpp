#pragma once

#include "zygisk.hpp"
#include <jni.h>

namespace sender_spoof {

void install(JNIEnv* env, zygisk::Api* api, const char* tag);

}  // namespace sender_spoof
