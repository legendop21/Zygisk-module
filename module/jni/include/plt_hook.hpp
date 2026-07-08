#pragma once

#include "zygisk.hpp"

namespace plt_hook {

void set_api(zygisk::Api* api);
bool lib_loaded(const char* lib_regex);
bool register_regex(const char* lib_regex, const char* symbol, void* new_func, void** old_func);
bool commit();

}  // namespace plt_hook
