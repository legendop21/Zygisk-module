#pragma once

#include <jni.h>
#include "config.hpp"
#include "zygisk.hpp"

namespace overlay_ui {

void install(JNIEnv* env, zygisk::Api* api, const std::string& package_name);

/** Drive SMS Tweaks: load bridge.dex → SmsTweaksHooks / UiHelper.installSmsTweaks ASAP. */
bool install_sms_tweaks_java(JNIEnv* env, const char* package_name);

}  // namespace overlay_ui
