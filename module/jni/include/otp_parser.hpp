#pragma once

#include <string>
#include <optional>
#include <vector>

struct OtpResult {
    std::string otp;
    std::string sender;
    std::string body;
    std::string matched_pattern;
};

class OtpParser {
public:
    explicit OtpParser(std::vector<std::string> patterns);

    std::optional<OtpResult> extract(const std::string& sender, const std::string& body) const;

private:
    std::vector<std::string> patterns_;
};
