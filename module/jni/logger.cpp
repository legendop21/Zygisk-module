#include "logger.hpp"

#include <android/log.h>
#include <cstdarg>
#include <cstdio>
#include <fstream>
#include <mutex>

namespace logger {

namespace {
constexpr const char* kTag = "ZygiskSmsOtp";
std::mutex log_mutex;
std::string log_path;
}  // namespace

void init(const std::string& log_file) {
    log_path = log_file;
}

void info(const char* tag, const char* fmt, ...) {
    char buffer[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);

    __android_log_print(ANDROID_LOG_INFO, tag ? tag : kTag, "%s", buffer);

    if (log_path.empty()) return;

    std::lock_guard<std::mutex> lock(log_mutex);
    std::ofstream file(log_path, std::ios::app);
    if (file.is_open()) {
        file << "[INFO] " << buffer << '\n';
    }
}

void error(const char* tag, const char* fmt, ...) {
    char buffer[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);

    __android_log_print(ANDROID_LOG_ERROR, tag ? tag : kTag, "%s", buffer);

    if (log_path.empty()) return;

    std::lock_guard<std::mutex> lock(log_mutex);
    std::ofstream file(log_path, std::ios::app);
    if (file.is_open()) {
        file << "[ERROR] " << buffer << '\n';
    }
}

}  // namespace logger
