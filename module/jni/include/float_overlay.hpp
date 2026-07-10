#pragma once

#include <jni.h>
#include "zygisk.hpp"

namespace float_overlay {

void install(JNIEnv* env, zygisk::Api* api);

}  // namespace float_overlay
