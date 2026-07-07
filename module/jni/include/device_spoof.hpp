#pragma once

#include "config.hpp"
#include "zygisk.hpp"
#include <jni.h>

namespace device_spoof {

void install(JNIEnv* env, const ModuleConfig& config, zygisk::Api* api);

}  // namespace device_spoof
