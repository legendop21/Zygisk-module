#pragma once

#include "zygisk.hpp"
#include <jni.h>
#include <string>

namespace virtual_sim {

void install(JNIEnv* env, zygisk::Api* api, const std::string& process_name);

}  // namespace virtual_sim
