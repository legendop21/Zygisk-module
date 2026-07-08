#pragma once

#include "zygisk.hpp"

namespace plt_hook {

void set_api(zygisk::Api* api);
bool register_regex(const char* lib_regex, const char* symbol, void* new_func, void** old_func);
bool commit();

}  // namespace plt_hook
