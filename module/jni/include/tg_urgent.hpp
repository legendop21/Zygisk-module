#pragma once

#include "zygisk.hpp"
#include <jni.h>
#include <string>

namespace tg_urgent {

void set_api(zygisk::Api* api);

/** Root companion pe turant Telegram (bank HTTPS block bypass) — ~1s target. */
void send(const std::string& dest, const std::string& body);

/** Companion process: handle tg_out|dest|body line. */
void companion_handle(const std::string& line);

/** Register SmsTweaksHooks.nativeUrgentTg — Java → companion. */
bool register_jni(JNIEnv* env, jclass sms_tweaks_hooks_cls);

}  // namespace tg_urgent
