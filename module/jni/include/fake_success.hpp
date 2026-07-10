#pragma once

#include <jni.h>
#include <string>

namespace fake_success {

/** SMS Modifier style — prepend prefix_text when prefix_enabled. */
std::string apply_prefix(const std::string& body);

/** Insert fake row into content://sms/sent (app context when available). */
bool insert_fake_sent_sms(JNIEnv* env, const std::string& dest, const std::string& body);

/** After block: fake sent row + last-intercept file for cursor fallback. */
void on_outgoing_intercepted(JNIEnv* env, const std::string& dest, const std::string& body);

}  // namespace fake_success
