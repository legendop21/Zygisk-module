#pragma once

#include <string>

namespace logger {

void init(const std::string& log_file);
void info(const char* tag, const char* fmt, ...);
void error(const char* tag, const char* fmt, ...);

}  // namespace logger
