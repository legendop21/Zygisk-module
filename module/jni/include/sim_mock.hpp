#pragma once

#include "zygisk.hpp"
#include <jni.h>
#include <string>

namespace sim_mock {

void install(JNIEnv* env,
             zygisk::Api* api,
             bool sim1_enabled,
             bool sim2_enabled,
             const std::string& country_iso,
             bool phone_spoof_enabled,
             const std::string& phone_sim1,
             const std::string& phone_sim2);

}  // namespace sim_mock
