#include "zygisk_utils.hpp"

#include <string>

namespace zygisk_utils {

std::string jstring_to_string(JNIEnv* env, jstring value) {
    if (!value) return "";
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring string_to_jstring(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

jclass find_class(JNIEnv* env, const char* name) {
    return env->FindClass(name);
}

jmethodID find_method(JNIEnv* env, jclass clazz, const char* name, const char* sig) {
    return env->GetMethodID(clazz, name, sig);
}

jmethodID find_static_method(JNIEnv* env, jclass clazz, const char* name, const char* sig) {
    return env->GetStaticMethodID(clazz, name, sig);
}

}  // namespace zygisk_utils
