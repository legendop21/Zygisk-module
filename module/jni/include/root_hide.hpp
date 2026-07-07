#pragma once

#include <jni.h>
#include "config.hpp"

namespace root_hide {

void install(JNIEnv* env, const ModuleConfig& config);

}  // namespace root_hide
