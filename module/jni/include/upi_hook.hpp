#pragma once

#include "zygisk.hpp"
#include <jni.h>
#include <string>

namespace upi_hook {

void install(JNIEnv* env, zygisk::Api* api, const std::string& package_name);

}  // namespace upi_hook
