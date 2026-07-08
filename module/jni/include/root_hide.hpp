#pragma once

#include <jni.h>
#include "config.hpp"
#include "zygisk.hpp"

namespace root_hide {

void install(JNIEnv* env, const ModuleConfig& config, zygisk::Api* api);

}  // namespace root_hide
