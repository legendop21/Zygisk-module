#pragma once

#include <jni.h>
#include <string>

namespace sim_mock {

void install(JNIEnv* env,
             bool sim1_enabled,
             bool sim2_enabled,
             const std::string& country_iso);

}  // namespace sim_mock
