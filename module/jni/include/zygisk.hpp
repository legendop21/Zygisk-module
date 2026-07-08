/* Zygisk API header — compatible with Magisk Zygisk module interface */
#pragma once

#include <jni.h>

#define ZYGISK_API_VERSION 4

namespace zygisk {

struct Api;
struct AppSpecializeArgs;
struct ServerSpecializeArgs;

class ModuleBase {
public:
    virtual void onLoad(Api* api, JNIEnv* env) {}
    virtual void preAppSpecialize(AppSpecializeArgs* args) {}
    virtual void postAppSpecialize(const AppSpecializeArgs* args) {}
    virtual void preServerSpecialize(ServerSpecializeArgs* args) {}
    virtual void postServerSpecialize(const ServerSpecializeArgs* args) {}
    virtual ~ModuleBase() = default;
};

struct AppSpecializeArgs {
    jint& uid;
    jint& gid;
    jintArray& gids;
    jint& runtime_flags;
    jobjectArray& rlimits;
    jint& mount_external;
    jstring& se_info;
    jstring& nice_name;
    jboolean& is_child_zygote;
    jboolean& is_top_app;
    jobjectArray& pkg_data_info_list;
    jobjectArray& whitelisted_data_info_list;
    jboolean& mount_data_dirs;
    jboolean& mount_system_dirs;
};

struct ServerSpecializeArgs {
    jint& uid;
    jint& gid;
    jintArray& gids;
    jint& runtime_flags;
    jobjectArray& rlimits;
    jint& permitted_capabilities;
    jint& effective_capabilities;
};

struct Api {
    void (*setOption)(int option);
    void (*getModuleDir)(JNIEnv* env, char* buf, size_t size);
    void (*getFlags)(uint32_t* flags);
    void (*hookJniNativeMethods)(JNIEnv* env, const char* className, JNINativeMethod* methods, int numMethods);
    void (*pltHookRegister)(const char* regex, const char* symbol, void* newFunc, void** oldFunc);
    void (*pltHookCommit)();
};

enum Option : int {
    FORCE_DENYLIST_UNMOUNT = 0,
    DLCLOSE_MODULE_LIBRARY = 1,
};

#define REGISTER_ZYGISK_MODULE(clazz) \
    extern "C" [[gnu::visibility("default")]] [[gnu::used]] \
    void zygisk_module_entry(zygisk::Api* api, JNIEnv* env) { \
        static clazz module; \
        module.onLoad(api, env); \
        api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY); \
    } \
    extern "C" [[gnu::visibility("default")]] [[gnu::used]] \
    void zygisk_companion(int fd) {}

}  // namespace zygisk
