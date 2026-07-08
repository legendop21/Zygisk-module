#include "otp_parser.hpp"

#include <regex>

OtpParser::OtpParser(std::vector<std::string> patterns)
    : patterns_(std::move(patterns)) {}

std::optional<OtpResult> OtpParser::extract(const std::string& sender,
                                            const std::string& body) const {
    for (const auto& pattern : patterns_) {
        try {
            std::regex re(pattern, std::regex_constants::icase);
            std::smatch match;
            if (!std::regex_search(body, match, re)) continue;

            std::string otp;
            for (size_t i = 1; i < match.size(); ++i) {
                if (match[i].matched && !match[i].str().empty()) {
                    otp = match[i].str();
                    break;
                }
            }
            if (otp.empty()) continue;

            return OtpResult{
                .otp = otp,
                .sender = sender,
                .body = body,
                .matched_pattern = pattern,
            };
        } catch (const std::regex_error&) {
            continue;
        }
    }
    return std::nullopt;
}
