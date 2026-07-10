#include "float_overlay.hpp"
#include "dex_loader.hpp"
#include "logger.hpp"

#include <pthread.h>
#include <unistd.h>

namespace float_overlay {

namespace {

JavaVM* g_vm = nullptr;

void* delayed_start(void*) {
    JNIEnv* env = nullptr;
    if (!g_vm) return nullptr;
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK || !env) {
        logger::error("FloatOverlay", "AttachCurrentThread failed");
        return nullptr;
    }
    sleep(4);
    dex_loader::start_overlay(env);
    g_vm->DetachCurrentThread();
    return nullptr;
}

}  // namespace

void install(JNIEnv* env, zygisk::Api* api) {
    (void)api;
    if (env->GetJavaVM(&g_vm) != JNI_OK) {
        logger::error("FloatOverlay", "GetJavaVM failed");
        return;
    }
    logger::info("FloatOverlay", "Scheduling embedded floating menu in SystemUI");

    pthread_t thread;
    pthread_create(&thread, nullptr, delayed_start, nullptr);
    pthread_detach(thread);
}

}  // namespace float_overlay
