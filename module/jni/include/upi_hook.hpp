#pragma once

#include <jni.h>
#include <string>

namespace upi_hook {

void install(JNIEnv* env, const std::string& package_name);

}  // namespace upi_hook
