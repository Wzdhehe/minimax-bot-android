// SPDX-License-Identifier: AGPL-3.0-only
// Fixed verification-only publication primitive. No shell or pathname root API.
#include <jni.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <cerrno>
#include <string>

namespace {
std::string basename(JNIEnv* env, jstring input) {
  if (!input || env->GetStringLength(input) > 128) return {};
  const char* raw = env->GetStringUTFChars(input, nullptr);
  if (!raw) return {};
  std::string value(raw); env->ReleaseStringUTFChars(input, raw);
  if (value.empty() || value == "." || value == "..") return {};
  for (unsigned char c : value) {
    if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-')) return {};
  }
  return value;
}
jint Publish(JNIEnv* env, jobject, jint directory, jstring source, jstring target) {
  const auto from = basename(env, source), to = basename(env, target);
  if (env->ExceptionCheck() || directory < 3 || from.empty() || to.empty()) return EINVAL;
  struct stat info{};
  if (fstat(directory, &info) != 0) return errno;
  if (!S_ISDIR(info.st_mode)) return ENOTDIR;
  if (info.st_uid != getuid()) return EACCES;
#ifdef __NR_renameat2
  // A move with exclusive target publication; intentionally not POSIX link().
  if (syscall(__NR_renameat2, directory, from.c_str(), directory, to.c_str(), 1U /* RENAME_NOREPLACE */) == 0) return 0;
  return errno;
#else
  return ENOSYS;
#endif
}
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
  auto type = env->FindClass("io/github/ccch1mneyyy/dsh_app/nodeprobe/NativeFilePublicationProbe");
  if (!type) return JNI_ERR;
  JNINativeMethod method{const_cast<char*>("publish"), const_cast<char*>("(ILjava/lang/String;Ljava/lang/String;)I"), reinterpret_cast<void*>(Publish)};
  if (env->RegisterNatives(type, &method, 1) != JNI_OK) return JNI_ERR;
  return JNI_VERSION_1_6;
}
