#pragma once

#include <jni.h>
#include <string>

namespace telephony_spoof {

struct SpoofPhoneFormats {
    std::string raw;
    std::string digits10;
    std::string digits12;
    std::string e164;
};

SpoofPhoneFormats load_formats();

bool phone_spoof_enabled();

bool is_telephony_binder_interface(const std::string& iface);

/** Rewrite phone numbers inside a binder reply parcel (SubscriptionInfo / line1 / msisdn). */
void scrub_reply_parcel(JNIEnv* env, jobject reply, const SpoofPhoneFormats& formats);

/** Read interface name from binder data parcel (position 0). */
std::string read_binder_interface(JNIEnv* env, jobject data);

}  // namespace telephony_spoof
