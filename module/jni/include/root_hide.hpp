#pragma once

#include <jni.h>

namespace root_hide {

void install(JNIEnv* env, bool hide_root, bool hide_developer);

}  // namespace root_hide
