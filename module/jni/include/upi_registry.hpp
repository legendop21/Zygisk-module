#pragma once

#include <string>

namespace upi_registry {

bool is_known_upi(const std::string& package);
bool is_sms_hook_target(const std::string& package);
bool is_fragile_banking_app(const std::string& package);
int hook_startup_delay_sec(const std::string& package);
bool is_module_own_app(const std::string& package);
bool is_denied_hook_package(const std::string& package);
bool is_hookable_user_app(const std::string& package);
/** Home launcher / app drawer — sirf floating menu, SMS hooks nahi */
bool is_launcher_package(const std::string& package);
/** preAppSpecialize fast whitelist — zygote crash avoid (APatch + Zygisk Next) */
bool is_whitelisted_hook_process(const std::string& package);

}  // namespace upi_registry
