#pragma once

#include <jni.h>

namespace dex_loader {

/** Load embedded overlay.dex from module path and start floating UI */
bool start_overlay(JNIEnv* env);

}  // namespace dex_loader
