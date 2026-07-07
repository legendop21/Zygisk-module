#include "forwarder.hpp"
#include "logger.hpp"

#include <arpa/inet.h>
#include <netdb.h>
#include <sys/socket.h>
#include <unistd.h>

#include <sstream>

namespace {

bool parse_url(const std::string& url, std::string& host, std::string& path, int& port, bool& use_tls) {
    use_tls = url.rfind("https://", 0) == 0;
    const std::string prefix = use_tls ? "https://" : "http://";
    if (url.rfind(prefix, 0) != 0) return false;

    std::string remainder = url.substr(prefix.size());
    auto slash = remainder.find('/');
    std::string host_port = slash == std::string::npos ? remainder : remainder.substr(0, slash);
    path = slash == std::string::npos ? "/" : remainder.substr(slash);

    auto colon = host_port.find(':');
    if (colon == std::string::npos) {
        host = host_port;
        port = use_tls ? 443 : 80;
    } else {
        host = host_port.substr(0, colon);
        port = std::stoi(host_port.substr(colon + 1));
    }
    return true;
}

std::string json_escape(const std::string& input) {
    std::ostringstream out;
    for (char c : input) {
        switch (c) {
            case '"': out << "\\\""; break;
            case '\\': out << "\\\\"; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default: out << c; break;
        }
    }
    return out.str();
}

}  // namespace

TokenForwarder::TokenForwarder(std::string url,
                               std::string method,
                               std::map<std::string, std::string> headers)
    : url_(std::move(url)), method_(std::move(method)), headers_(std::move(headers)) {}

bool TokenForwarder::forward(const OtpResult& result) const {
    if (url_.empty()) {
        logger::error("Forwarder", "Forward URL is empty");
        return false;
    }

    std::string host;
    std::string path;
    int port = 0;
    bool use_tls = false;
    if (!parse_url(url_, host, path, port, use_tls)) {
        logger::error("Forwarder", "Invalid forward URL: %s", url_.c_str());
        return false;
    }

    if (use_tls) {
        logger::error("Forwarder", "HTTPS not supported in native forwarder; use HTTP or app layer");
        return false;
    }

    std::ostringstream payload;
    payload << "{"
            << "\"otp\":\"" << json_escape(result.otp) << "\","
            << "\"sender\":\"" << json_escape(result.sender) << "\","
            << "\"body\":\"" << json_escape(result.body) << "\","
            << "\"pattern\":\"" << json_escape(result.matched_pattern) << "\""
            << "}";

    const std::string body = payload.str();

    addrinfo hints{};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;

    addrinfo* res = nullptr;
    if (getaddrinfo(host.c_str(), std::to_string(port).c_str(), &hints, &res) != 0) {
        logger::error("Forwarder", "DNS lookup failed for %s", host.c_str());
        return false;
    }

    int sock = -1;
    for (addrinfo* p = res; p != nullptr; p = p->ai_next) {
        sock = socket(p->ai_family, p->ai_socktype, p->ai_protocol);
        if (sock < 0) continue;
        if (connect(sock, p->ai_addr, p->ai_addrlen) == 0) break;
        close(sock);
        sock = -1;
    }
    freeaddrinfo(res);

    if (sock < 0) {
        logger::error("Forwarder", "Connection failed to %s:%d", host.c_str(), port);
        return false;
    }

    std::ostringstream request;
    request << method_ << " " << path << " HTTP/1.1\r\n"
            << "Host: " << host << "\r\n"
            << "Content-Type: application/json\r\n"
            << "Content-Length: " << body.size() << "\r\n";

    for (const auto& [key, value] : headers_) {
        request << key << ": " << value << "\r\n";
    }
    request << "Connection: close\r\n\r\n" << body;

    const std::string request_str = request.str();
    if (send(sock, request_str.c_str(), request_str.size(), 0) < 0) {
        logger::error("Forwarder", "Failed to send HTTP request");
        close(sock);
        return false;
    }

    char buffer[256];
    recv(sock, buffer, sizeof(buffer) - 1, 0);
    close(sock);

    logger::info("Forwarder", "Forwarded OTP %s from %s", result.otp.c_str(), result.sender.c_str());
    return true;
}
