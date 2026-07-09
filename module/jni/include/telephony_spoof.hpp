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

/** Per-slot virtual subscriber (SubscriptionInfo / ISub). */
struct SubscriberSlotProfile {
    int slot_index = 0;
    bool enabled = false;
    std::string phone10;
    std::string phone_e164;
    std::string imsi;
    std::string iccid;
    std::string operator_name;
    std::string operator_numeric;
    std::string mcc;
    std::string mnc;
    std::string country_iso = "in";
};

struct VirtualSubscriberProfiles {
    SubscriberSlotProfile sim1;
    SubscriberSlotProfile sim2;
    bool dual_active = false;
};

SpoofPhoneFormats load_formats();
VirtualSubscriberProfiles load_subscriber_profiles();

bool phone_spoof_enabled();

bool is_telephony_binder_interface(const std::string& iface);
bool is_subscription_binder_interface(const std::string& iface);
bool is_subscriber_id_binder_interface(const std::string& iface);
bool is_itelephony_binder_interface(const std::string& iface);

/** Rewrite binder reply: SubscriptionInfo, ISub, IPhoneSubInfo, ITelephony getLine1Number. */
void scrub_reply_parcel(JNIEnv* env, jobject reply, const VirtualSubscriberProfiles& profiles,
                         const std::string& binder_iface);

std::string read_binder_interface(JNIEnv* env, jobject data);

}  // namespace telephony_spoof
