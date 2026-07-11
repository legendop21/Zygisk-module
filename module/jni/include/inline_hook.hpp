#pragma once

#include <cstddef>

namespace inline_hook {

/** Replace function entry with jump to replacement; original callable via *out_trampoline.
 *  Messages BinderProxy nuclear path when Zygisk JNI/PLT miss. */
bool hook(void* target, void* replacement, void** out_trampoline);

}  // namespace inline_hook
