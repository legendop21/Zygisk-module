#include "plt_hook.hpp"

#include <fstream>
#include <regex>
#include <sstream>
#include <string>
#include <sys/sysmacros.h>

namespace plt_hook {

namespace {

zygisk::Api* g_api = nullptr;

bool parse_maps_line(const std::string& line, dev_t* out_dev, ino_t* out_inode, std::string* out_path) {
    if (!out_dev || !out_inode || !out_path) return false;
    std::istringstream iss(line);
    std::string addr, perms, offset, dev, inode;
    if (!(iss >> addr >> perms >> offset >> dev >> inode)) return false;
    if (perms.find('x') == std::string::npos) return false;

  std::string path;
    std::getline(iss, path);
    if (!path.empty() && path[0] == ' ') path.erase(0, 1);
    if (path.empty()) return false;

    unsigned int major = 0;
    unsigned int minor = 0;
    if (sscanf(dev.c_str(), "%x:%x", &major, &minor) != 2) return false;

    *out_dev = makedev(major, minor);
    *out_inode = static_cast<ino_t>(std::stoul(inode));
    *out_path = path;
    return true;
}

}  // namespace

void set_api(zygisk::Api* api) {
    g_api = api;
}

bool lib_loaded(const char* lib_regex) {
    if (!lib_regex) return false;
    std::ifstream maps("/proc/self/maps");
    if (!maps.is_open()) return false;
    std::regex pattern(lib_regex);
    std::string line;
    while (std::getline(maps, line)) {
        dev_t dev = 0;
        ino_t inode = 0;
        std::string path;
        if (!parse_maps_line(line, &dev, &inode, &path)) continue;
        if (std::regex_search(path, pattern)) return true;
    }
    return false;
}

bool register_regex(const char* lib_regex, const char* symbol, void* new_func, void** old_func) {
    if (!g_api || !lib_regex || !symbol || !new_func) return false;

    std::ifstream maps("/proc/self/maps");
    if (!maps.is_open()) return false;

    std::regex pattern(lib_regex);
    bool registered = false;
    std::string line;
    while (std::getline(maps, line)) {
        dev_t dev = 0;
        ino_t inode = 0;
        std::string path;
        if (!parse_maps_line(line, &dev, &inode, &path)) continue;
        if (!std::regex_search(path, pattern)) continue;
        g_api->pltHookRegister(dev, inode, symbol, new_func, old_func);
        registered = true;
    }
    return registered;
}

bool commit() {
    if (!g_api) return false;
    return g_api->pltHookCommit();
}

}  // namespace plt_hook
