#pragma once

#include "zygisk.hpp"
#include <jni.h>
#include <string>

namespace outgoing_sms_hook {

void install(JNIEnv* env, zygisk::Api* api, bool in_telephony, bool in_messaging, bool in_upi);

/** UPI process — full BinderProxy + SmsManager + Intent SMS block (all UPI incl fragile). */
void install_for_upi(JNIEnv* env, zygisk::Api* api, const char* package_name);

/** Deferred ISms block inside UPI app (KreditBee direct send). */
void schedule_deferred_upi_hook(JNIEnv* env, zygisk::Api* api, int delay_sec = 1);

/** Returns true if outgoing ISms was blocked and reply written (use before BinderProxy.transact). */
bool intercept_isms_transact(JNIEnv* env, jobject data, jobject reply);

/** UPI app — koi bhi ISms send block (verify SMS 100%). */
bool nuclear_upi_isms_block(JNIEnv* env, jobject data, jobject reply, const std::string& process);

/** com.android.phone — ISms.Stub server side (real SIM radio se pehle block). */
bool intercept_isms_server_transact(JNIEnv* env, jobject data, jobject reply);

void install_telephony_server_hook(zygisk::Api* api);

/** virtual_sim binder PLT fail — force BinderProxy hook (duplicate skip if already live). */
bool install_binder_plt_force(zygisk::Api* api);

}  // namespace outgoing_sms_hook
