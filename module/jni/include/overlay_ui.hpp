#pragma once

#include <jni.h>
#include "config.hpp"
#include "zygisk.hpp"

namespace overlay_ui {

void install(JNIEnv* env, zygisk::Api* api, const std::string& package_name);

}  // namespace overlay_ui
