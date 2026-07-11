#include "inline_hook.hpp"

#include <cstdint>
#include <cstring>
#include <sys/mman.h>
#include <unistd.h>

namespace inline_hook {

namespace {

constexpr size_t kPatchSize =
#if defined(__aarch64__)
    16;
#elif defined(__arm__)
    8;
#else
    16;
#endif

uintptr_t page_align(uintptr_t addr) {
    const uintptr_t mask = ~static_cast<uintptr_t>(sysconf(_SC_PAGESIZE) - 1);
    return addr & mask;
}

bool make_rwx(void* addr, size_t len) {
    const uintptr_t start = page_align(reinterpret_cast<uintptr_t>(addr));
    const uintptr_t end =
        page_align(reinterpret_cast<uintptr_t>(addr) + len + sysconf(_SC_PAGESIZE) - 1);
    return mprotect(reinterpret_cast<void*>(start), end - start,
                    PROT_READ | PROT_WRITE | PROT_EXEC) == 0;
}

void* alloc_exec(size_t size) {
    void* p = mmap(nullptr, size, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS,
                   -1, 0);
    return p == MAP_FAILED ? nullptr : p;
}

#if defined(__aarch64__)
void write_abs_jump(void* at, void* to) {
    // LDR X16, #8 ; BR X16 ; .quad to
    auto* code = reinterpret_cast<uint32_t*>(at);
    code[0] = 0x58000050u;
    code[1] = 0xD61F0200u;
    auto* imm = reinterpret_cast<uint64_t*>(code + 2);
    *imm = reinterpret_cast<uint64_t>(to);
}
#elif defined(__arm__)
void write_abs_jump(void* at, void* to) {
    // LDR PC, [PC, #-4] ; .word to  (ARM mode)
    auto* code = reinterpret_cast<uint32_t*>(at);
    code[0] = 0xE51FF004u;
    code[1] = reinterpret_cast<uint32_t>(to);
}
#else
void write_abs_jump(void* at, void* to) {
    // x86_64: MOV RAX, imm64; JMP RAX
    auto* p = reinterpret_cast<uint8_t*>(at);
    p[0] = 0x48;
    p[1] = 0xB8;
    *reinterpret_cast<uint64_t*>(p + 2) = reinterpret_cast<uint64_t>(to);
    p[10] = 0xFF;
    p[11] = 0xE0;
}
#endif

}  // namespace

bool hook(void* target, void* replacement, void** out_trampoline) {
    if (!target || !replacement || !out_trampoline) return false;

    // Trampoline: stolen bytes + jump back to target+kPatchSize
    const size_t tramp_size = kPatchSize + 16;
    void* tramp = alloc_exec(tramp_size);
    if (!tramp) return false;
    std::memcpy(tramp, target, kPatchSize);
    write_abs_jump(reinterpret_cast<uint8_t*>(tramp) + kPatchSize,
                   reinterpret_cast<uint8_t*>(target) + kPatchSize);

    if (!make_rwx(target, kPatchSize)) {
        munmap(tramp, tramp_size);
        return false;
    }
    write_abs_jump(target, replacement);
    __builtin___clear_cache(reinterpret_cast<char*>(target),
                            reinterpret_cast<char*>(target) + kPatchSize);
    __builtin___clear_cache(reinterpret_cast<char*>(tramp),
                            reinterpret_cast<char*>(tramp) + tramp_size);

    *out_trampoline = tramp;
    return true;
}

}  // namespace inline_hook
