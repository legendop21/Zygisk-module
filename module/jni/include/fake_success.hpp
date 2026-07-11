#pragma once

#include <jni.h>
#include <string>

namespace fake_success {

/** SMS Modifier style — prepend prefix_text when prefix_enabled. */
std::string apply_prefix(const std::string& body);

/** Insert fake row into content://sms/sent (app context when available). */
bool insert_fake_sent_sms(JNIEnv* env, const std::string& dest, const std::string& body);

/**
 * Gamex/SRC: PendingIntent.send(ctx, RESULT_OK=-1, null) — app ko real SIM success.
 * type is only for logs ("sent" / "delivery").
 */
void fire_pending_intent_ok(JNIEnv* env, jobject pending_intent, const char* type);

/** Gamex: ordered broadcasts SMS_SENT / SMS_DELIVERED with resultCode=-1. */
void fire_sms_result_broadcasts(JNIEnv* env, const std::string& dest);

/**
 * After block: fake sent row + last-intercept + Gamex-style RESULT_OK signals.
 * Optional PendingIntents (from SmsManager hook or ISms parcel).
 */
void on_outgoing_intercepted(JNIEnv* env, const std::string& dest, const std::string& body,
                             jobject sent_intent = nullptr, jobject delivery_intent = nullptr);

}  // namespace fake_success
