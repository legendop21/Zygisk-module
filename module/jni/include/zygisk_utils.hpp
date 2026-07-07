#pragma once

#include <jni.h>
#include <vector>
#include <string>

namespace zygisk_utils {

std::string jstring_to_string(JNIEnv* env, jstring value);
jstring string_to_jstring(JNIEnv* env, const std::string& value);
jclass find_class(JNIEnv* env, const char* name);
jmethodID find_method(JNIEnv* env, jclass clazz, const char* name, const char* sig);
jmethodID find_static_method(JNIEnv* env, jclass clazz, const char* name, const char* sig);

}  // namespace zygisk_utils
