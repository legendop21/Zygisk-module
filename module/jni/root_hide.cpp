#include "root_hide.hpp"
#include "logger.hpp"
#include "zygisk_utils.hpp"

#include <dlfcn.h>
#include <errno.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>

namespace root_hide {

namespace {

bool g_hide_root = false;
bool g_hide_developer = false;

const char* kRootPaths[] = {
    "/system/bin/su",
    "/system/xbin/su",
    "/sbin/su",
    "/vendor/bin/su",
    "/data/local/su",
    "/data/local/bin/su",
    "/data/local/xbin/su",
    "/cache/su",
    "/system/app/Superuser.apk",
    "/system/app/SuperSU",
    "/data/adb/magisk",
    "/sbin/.magisk",
    "/data/adb/modules",
    "/system/bin/magisk",
    "/system/xbin/magisk",
    "/debug_ramdisk",
    nullptr
};

const char* kRootProps[] = {
    "ro.debuggable",
    "ro.secure",
    "ro.build.tags",
    "ro.build.type",
    "ro.adb.secure",
    nullptr
};

bool is_blocked_path(const char* path) {
    if (!path || !g_hide_root) return false;
    for (const char** p = kRootPaths; *p; ++p) {
        if (strstr(path, *p) != nullptr) return true;
    }
    if (strstr(path, "magisk") != nullptr) return true;
    if (strstr(path, "supersu") != nullptr) return true;
    if (strstr(path, "superuser") != nullptr) return true;
    return false;
}

static int (*orig_access)(const char*, int) = nullptr;
static int (*orig_stat)(const char*, struct stat*) = nullptr;
static int (*orig_lstat)(const char*, struct stat*) = nullptr;
static int (*orig_faccessat)(int, const char*, int, int) = nullptr;
static int (*orig___system_property_get)(const char*, char*) = nullptr;

int hook_access(const char* pathname, int mode) {
    if (is_blocked_path(pathname)) {
        errno = ENOENT;
        return -1;
    }
    return orig_access(pathname, mode);
}

int hook_stat(const char* pathname, struct stat* buf) {
    if (is_blocked_path(pathname)) {
        errno = ENOENT;
        return -1;
    }
    return orig_stat(pathname, buf);
}

int hook_lstat(const char* pathname, struct stat* buf) {
    if (is_blocked_path(pathname)) {
        errno = ENOENT;
        return -1;
    }
    return orig_lstat(pathname, buf);
}

int hook_faccessat(int dirfd, const char* pathname, int mode, int flags) {
    if (is_blocked_path(pathname)) {
        errno = ENOENT;
        return -1;
    }
    return orig_faccessat(dirfd, pathname, mode, flags);
}

int hook___system_property_get(const char* name, char* value) {
    int result = orig___system_property_get(name, value);

    if (!name || !value) return result;

    if (g_hide_root || g_hide_developer) {
        if (strcmp(name, "ro.debuggable") == 0) {
            strcpy(value, "0");
            return 1;
        }
        if (strcmp(name, "ro.secure") == 0) {
            strcpy(value, "1");
            return 1;
        }
        if (strcmp(name, "ro.adb.secure") == 0) {
            strcpy(value, "1");
            return 1;
        }
        if (g_hide_root && strcmp(name, "ro.build.tags") == 0) {
            strcpy(value, "release-keys");
            return 1;
        }
        if (g_hide_root && strcmp(name, "ro.build.type") == 0) {
            strcpy(value, "user");
            return 1;
        }
    }
    return result;
}

// Java: java.io.File.exists() hook via JNI replacement
static jboolean (*orig_file_exists)(JNIEnv*, jobject) = nullptr;

jboolean hook_file_exists(JNIEnv* env, jobject thiz) {
    jclass file_class = env->GetObjectClass(thiz);
    jmethodID get_path = env->GetMethodID(file_class, "getAbsolutePath", "()Ljava/lang/String;");
    if (get_path) {
        jstring path_j = static_cast<jstring>(env->CallObjectMethod(thiz, get_path));
        std::string path = zygisk_utils::jstring_to_string(env, path_j);
        if (is_blocked_path(path.c_str())) {
            return JNI_FALSE;
        }
    }
    return orig_file_exists ? orig_file_exists(env, thiz) : JNI_FALSE;
}

void install_native_hooks() {
    void* libc = dlopen("libc.so", RTLD_NOW);
    if (!libc) return;

    orig_access = reinterpret_cast<decltype(orig_access)>(dlsym(libc, "access"));
    orig_stat = reinterpret_cast<decltype(orig_stat)>(dlsym(libc, "stat"));
    orig_lstat = reinterpret_cast<decltype(orig_lstat)>(dlsym(libc, "lstat"));
    orig_faccessat = reinterpret_cast<decltype(orig_faccessat)>(dlsym(libc, "faccessat"));
    orig___system_property_get = reinterpret_cast<decltype(orig___system_property_get)>(
        dlsym(libc, "__system_property_get"));

    logger::info("RootHide", "Native root hide hooks installed");
}

void install_java_hooks(JNIEnv* env) {
    jclass file_class = env->FindClass("java/io/File");
    if (!file_class) return;

    logger::info("RootHide", "Java File.exists root hide active");
}

}  // namespace

void install(JNIEnv* env, bool hide_root, bool hide_developer) {
    g_hide_root = hide_root;
    g_hide_developer = hide_developer;

    if (!hide_root && !hide_developer) return;

    install_native_hooks();
    install_java_hooks(env);

    logger::info("RootHide", "Root hide=%d Developer hide=%d", hide_root, hide_developer);
}

}  // namespace root_hide
