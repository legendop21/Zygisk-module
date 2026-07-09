#pragma once

#include <string>

namespace upi_registry {

bool is_known_upi(const std::string& package);
bool is_denied_hook_package(const std::string& package);
bool is_hookable_user_app(const std::string& package);

}  // namespace upi_registry
