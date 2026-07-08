#include "root_hide.hpp"
#include "logger.hpp"
#include "zygisk_utils.hpp"

#include <dlfcn.h>
#include <errno.h>
#include <string.h>
#include <strings.h>
#include <unistd.h>
#include <sys/stat.h>

namespace root_hide {

namespace {

const ModuleConfig* g_config = nullptr;

const char* kMagiskPaths[] = {
    "/data/adb/magisk", "/sbin/.magisk", "/cache/.magisk",
    "/data/adb/magisk.db", "/system/bin/magisk", "/system/xbin/magisk",
    "/debug_ramdisk/.magisk", nullptr
};

const char* kKernelSuPaths[] = {
    "/data/adb/ksu", "/dev/kernelsu", "/data/adb/ksud",
    "/data/adb/kernel_su", "/sys/fs/selinux/ksu", nullptr
};

const char* kApatchPaths[] = {
    "/data/adb/apatch", "/data/adb/apd", "/data/adb/ap",
    "/system/bin/apd", "/system/xbin/apd", nullptr
};

const char* kSukisuPaths[] = {
    "/data/adb/sukisu", "/data/adb/suki", "/data/adb/sukisu_ultra",
    "/dev/sukisu", nullptr
};

const char* kUniversalSuPaths[] = {
    "/system/bin/su", "/system/xbin/su", "/sbin/su", "/vendor/bin/su",
    "/data/local/su", "/data/local/bin/su", "/data/local/xbin/su",
    "/cache/su", "/system/app/Superuser.apk", "/system/app/SuperSU",
    nullptr
};

const char* kRootPackages[] = {
    "com.topjohnwu.magisk", "io.github.huskydg.magisk", "io.github.vvb2060.magisk",
    "me.weishu.kernelsu", "me.bmax.apatch", "com.omarea.vtools",
    "eu.chainfire.supersu", "com.noshufou.android.su", "com.koushikdutta.superuser",
    "com.thirdparty.superuser", "com.yellowes.su", "com.kingroot.kinguser",
    "com.kingo.root", "com.smedialink.oneclickroot", "com.alephzain.framaroot",
    "com.devadvance.rootcloak", "com.devadvance.rootcloakplus",
    "de.robv.android.xposed.installer", "org.lsposed.manager",
    "com.sukisu.ultra", "com.sukisu", nullptr
};

bool path_in_list(const char* path, const char** list) {
    if (!path) return false;
    for (const char** p = list; *p; ++p) {
        if (strstr(path, *p) != nullptr) return true;
    }
    return false;
}

bool contains_keyword(const char* path, const char* keyword) {
    return path && keyword && strcasestr(path, keyword) != nullptr;
}

// Zygisk Next / LSPosed framework paths — inhe kabhi hide mat karo (crash/boot loop).
bool is_framework_whitelist(const char* path) {
    if (!path) return false;
    if (contains_keyword(path, "/data/misc/zygisk")) return true;
    if (contains_keyword(path, "/data/adb/lspd")) return true;
    if (contains_keyword(path, "/data/adb/modules/zygisk")) return true;
    if (contains_keyword(path, "zygisk-next") || contains_keyword(path, "rezygisk")) return true;
    if (contains_keyword(path, "org.lsposed")) return true;
    if (contains_keyword(path, "hivirtus_zygisk_mode")) return true;
    return false;
}

bool is_blocked_path(const char* path) {
    if (!path || !g_config || !g_config->hide_root) return false;
    if (is_framework_whitelist(path)) return false;

    if (contains_keyword(path, "com.hivirtus.zygiskmode")) {
        return true;
    }

    if (contains_keyword(path, "/proc/self/maps") && contains_keyword(path, "magisk")) {
        return true;
    }

    if (g_config->hide_magisk) {
        if (path_in_list(path, kMagiskPaths)) return true;
        if (contains_keyword(path, "magisk")) return true;
    }

    if (g_config->hide_kernelsu) {
        if (path_in_list(path, kKernelSuPaths)) return true;
        if (contains_keyword(path, "kernelsu") || contains_keyword(path, "/ksu")) return true;
    }

    if (g_config->hide_apatch) {
        if (path_in_list(path, kApatchPaths)) return true;
        if (contains_keyword(path, "apatch") || contains_keyword(path, "/apd")) return true;
    }

    if (g_config->hide_sukisu) {
        if (path_in_list(path, kSukisuPaths)) return true;
        if (contains_keyword(path, "sukisu") || contains_keyword(path, "suki")) return true;
    }

    if (g_config->hide_all_root_apps) {
        if (path_in_list(path, kUniversalSuPaths)) return true;
        if (contains_keyword(path, "supersu") || contains_keyword(path, "superuser")) return true;
        // xposed/lsposed/zygisk framework paths whitelist me hain — yahan block mat karo.
        for (const char** pkg = kRootPackages; *pkg; ++pkg) {
            if (strcmp(*pkg, "org.lsposed.manager") == 0) continue;
            if (strstr(path, *pkg) != nullptr) return true;
        }
    }

    return false;
}

static int (*orig_access)(const char*, int) = nullptr;
static int (*orig_stat)(const char*, struct stat*) = nullptr;
static int (*orig_lstat)(const char*, struct stat*) = nullptr;
static int (*orig_faccessat)(int, const char*, int, int) = nullptr;
static FILE* (*orig_fopen)(const char*, const char*) = nullptr;
static int (*orig___system_property_get)(const char*, char*) = nullptr;

int hook_access(const char* pathname, int mode) {
    if (is_blocked_path(pathname)) {
        errno = ENOENT;
        return -1;
    }
    return orig_access ? orig_access(pathname, mode) : -1;
}

int hook_stat(const char* pathname, struct stat* buf) {
    if (is_blocked_path(pathname)) {
        errno = ENOENT;
        return -1;
    }
    return orig_stat ? orig_stat(pathname, buf) : -1;
}

int hook_lstat(const char* pathname, struct stat* buf) {
    if (is_blocked_path(pathname)) {
        errno = ENOENT;
        return -1;
    }
    return orig_lstat ? orig_lstat(pathname, buf) : -1;
}

int hook_faccessat(int dirfd, const char* pathname, int mode, int flags) {
    if (is_blocked_path(pathname)) {
        errno = ENOENT;
        return -1;
    }
    return orig_faccessat ? orig_faccessat(dirfd, pathname, mode, flags) : -1;
}

FILE* hook_fopen(const char* pathname, const char* mode) {
    if (is_blocked_path(pathname)) {
        errno = ENOENT;
        return nullptr;
    }
    return orig_fopen ? orig_fopen(pathname, mode) : nullptr;
}

int hook___system_property_get(const char* name, char* value) {
    int result = orig___system_property_get ? orig___system_property_get(name, value) : 0;
    if (!name || !value || !g_config) return result;

    if (g_config->hide_root || g_config->hide_developer) {
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
        if (strcmp(name, "ro.build.selinux") == 0) {
            strcpy(value, "1");
            return 1;
        }
    }

    if (g_config->hide_root) {
        if (strcmp(name, "ro.build.tags") == 0) {
            strcpy(value, "release-keys");
            return 1;
        }
        if (strcmp(name, "ro.build.type") == 0) {
            strcpy(value, "user");
            return 1;
        }
        if (strcmp(name, "ro.boot.vbmeta.device_state") == 0) {
            strcpy(value, "locked");
            return 1;
        }
        if (strcmp(name, "ro.boot.verifiedbootstate") == 0) {
            strcpy(value, "green");
            return 1;
        }
        if (strcmp(name, "sys.oem_unlock_allowed") == 0) {
            strcpy(value, "0");
            return 1;
        }
        // zygisk props blank mat karo — Zygisk Next runtime break ho sakta hai.
        if (strstr(name, "magisk") || strstr(name, "kernelsu")) {
            value[0] = '\0';
            return 0;
        }
    }

    if (g_config->hide_developer) {
        if (strcmp(name, "init.svc.adbd") == 0) {
            strcpy(value, "stopped");
            return 1;
        }
        if (strcmp(name, "persist.sys.usb.config") == 0) {
            strcpy(value, "none");
            return 1;
        }
        if (strcmp(name, "ro.debuggable") == 0) {
            strcpy(value, "0");
            return 1;
        }
    }

    return result;
}

void install_plt_hooks(zygisk::Api* api) {
    if (!api || !api->pltHookRegister || !api->pltHookCommit) return;

    api->pltHookRegister(".*libc\\.so$", "access",
                         reinterpret_cast<void*>(hook_access),
                         reinterpret_cast<void**>(&orig_access));
    api->pltHookRegister(".*libc\\.so$", "stat",
                         reinterpret_cast<void*>(hook_stat),
                         reinterpret_cast<void**>(&orig_stat));
    api->pltHookRegister(".*libc\\.so$", "lstat",
                         reinterpret_cast<void*>(hook_lstat),
                         reinterpret_cast<void**>(&orig_lstat));
    api->pltHookRegister(".*libc\\.so$", "faccessat",
                         reinterpret_cast<void*>(hook_faccessat),
                         reinterpret_cast<void**>(&orig_faccessat));
    api->pltHookRegister(".*libc\\.so$", "fopen",
                         reinterpret_cast<void*>(hook_fopen),
                         reinterpret_cast<void**>(&orig_fopen));
    api->pltHookRegister(".*libc\\.so$", "__system_property_get",
                         reinterpret_cast<void*>(hook___system_property_get),
                         reinterpret_cast<void**>(&orig___system_property_get));
    api->pltHookCommit();
    logger::info("RootHide", "PLT hooks committed (access/stat/fopen/props)");
}

}  // namespace

void install(JNIEnv* env, const ModuleConfig& config, zygisk::Api* api) {
    (void)env;
    g_config = &config;

    if (!config.hide_root && !config.hide_developer) return;

    install_plt_hooks(api);

    logger::info("RootHide",
                 "Active Magisk=%d KSU=%d APatch=%d SukiSU=%d DevHide=%d",
                 config.hide_magisk, config.hide_kernelsu, config.hide_apatch,
                 config.hide_sukisu, config.hide_developer);
}

}  // namespace root_hide
