#include "device_spoof.hpp"
#include "logger.hpp"
#include "zygisk_utils.hpp"

#include <cstring>
#include <cstdio>
#include <sys/stat.h>

namespace device_spoof {

namespace {

const ModuleConfig* g_config = nullptr;
std::string g_spoof_id;

static int (*orig___system_property_get)(const char*, char*) = nullptr;

int hook___system_property_get(const char* name, char* value) {
    int result = orig___system_property_get ? orig___system_property_get(name, value) : 0;
    if (!name || !value || g_spoof_id.empty()) return result;

    if (strcmp(name, "ro.serialno") == 0 ||
        strcmp(name, "ro.boot.serialno") == 0 ||
        strcmp(name, "ril.serialnumber") == 0) {
        strncpy(value, g_spoof_id.c_str(), 91);
        value[91] = '\0';
        return static_cast<int>(strlen(value));
    }
    return result;
}

void write_runtime_marker(const std::string& id) {
    FILE* f = fopen("/data/local/tmp/hivirtus_spoof_android_id.txt", "w");
    if (f) {
        fprintf(f, "%s\n", id.c_str());
        fclose(f);
        chmod("/data/local/tmp/hivirtus_spoof_android_id.txt", 0644);
    }
}

void install_property_hooks(zygisk::Api* api) {
    if (!api || !api->pltHookRegister || !api->pltHookCommit) return;
    api->pltHookRegister(".*libc\\.so$", "__system_property_get",
                         reinterpret_cast<void*>(hook___system_property_get),
                         reinterpret_cast<void**>(&orig___system_property_get));
    api->pltHookCommit();
}

}  // namespace

void install(JNIEnv* env, const ModuleConfig& config, zygisk::Api* api) {
    (void)env;
    if (!config.enable_device_id_spoof) return;

    g_config = &config;
    g_spoof_id = config.spoof_android_id;
    if (g_spoof_id.empty()) {
        char buf[32] = {};
        FILE* f = fopen("/data/local/tmp/hivirtus_spoof_android_id.txt", "r");
        if (f) {
            fgets(buf, sizeof(buf), f);
            fclose(f);
            g_spoof_id = buf;
            if (!g_spoof_id.empty() && g_spoof_id.back() == '\n') g_spoof_id.pop_back();
        }
    }
    if (g_spoof_id.empty()) return;

    write_runtime_marker(g_spoof_id);
    install_property_hooks(api);
    logger::info("DeviceSpoof", "Android ID spoof active: %s", g_spoof_id.c_str());
}

}  // namespace device_spoof
