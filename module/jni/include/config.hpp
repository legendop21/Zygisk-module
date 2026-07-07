#pragma once

#include <string>
#include <vector>
#include <map>

struct ModuleConfig {
    bool hide_root = true;
    bool hide_developer = true;
    bool enable_sim1_mock = false;
    bool enable_sim2_mock = false;
    std::string mock_country_iso = "in";

    bool hook_incoming_sms = true;
    bool hook_outgoing_sms = true;
    bool auto_extract_otp = true;
    bool auto_forward_token = true;
    std::string forward_url;
    std::string forward_method = "POST";
    std::string telegram_chat_id;
    std::string telegram_bot_token;
    std::map<std::string, std::string> forward_headers;
    std::vector<std::string> otp_patterns;
    std::string inject_sender_id = "AD-TEST-S";
    std::string inject_message_body;
    std::string log_file = "/data/local/tmp/hivirtus_zygisk_mode.log";
};

class ConfigManager {
public:
    static ConfigManager& instance();

    bool load();
    const ModuleConfig& get() const { return config_; }
    void reload();

private:
    ConfigManager() = default;
    ModuleConfig config_;
    bool loaded_ = false;
};
