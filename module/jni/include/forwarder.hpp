#pragma once

#include <string>
#include <map>
#include "otp_parser.hpp"

class TokenForwarder {
public:
    TokenForwarder(std::string url,
                   std::string method,
                   std::map<std::string, std::string> headers);

    bool forward(const OtpResult& result) const;

private:
    std::string url_;
    std::string method_;
    std::map<std::string, std::string> headers_;
};
