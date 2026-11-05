// SPDX-License-Identifier: AGPL-3.0-only
// Main-only directory/file primitives. No shell or pathname root API.
#include <jni.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <cerrno>
#include <fcntl.h>
#include <string>
#include "runtime_directory_lease.h"
#include "runtime_stage_cleanup.h"

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
int directory_error(int directory) {
  if (directory < 3) return EINVAL;
  struct stat info{};
  if (fstat(directory, &info) != 0) return errno;
  if (!S_ISDIR(info.st_mode)) return ENOTDIR;
  return info.st_uid == getuid() ? 0 : EACCES;
}
jint Commit(JNIEnv* env, jint directory, jstring source, jstring target, unsigned flags) {
  const auto from = basename(env, source), to = basename(env, target);
  if (env->ExceptionCheck() || directory < 3 || from.empty() || to.empty()) return EINVAL;
  const int error = directory_error(directory);
  if (error) return error;
#ifdef __NR_renameat2
  // The trusted entry points select exclusive publication or atomic replace.
  // Neither operation promises general POSIX link() semantics.
  if (syscall(__NR_renameat2, directory, from.c_str(), directory, to.c_str(), flags) == 0) return 0;
  return errno;
#else
  return ENOSYS;
#endif
}
jint Publish(JNIEnv* env, jobject, jint directory, jstring source, jstring target) {
  return Commit(env, directory, source, target, 1U /* RENAME_NOREPLACE */);
}
jint Replace(JNIEnv* env, jobject, jint directory, jstring source, jstring target) {
  return Commit(env, directory, source, target, 0U);
}
jint TryDirectoryLease(JNIEnv*, jobject, jint directory) {
  return try_runtime_directory_lease(directory);
}
jint CleanupBotStages(JNIEnv*, jobject, jint directory) {
  return mbot_stage_cleanup::sweep(directory);
}
jint OpenRead(JNIEnv* env, jobject, jint directory, jstring name) {
  const auto file = basename(env, name);
  if (env->ExceptionCheck() || directory < 3 || file.empty()) return -EINVAL;
  const int error = directory_error(directory);
  if (error) return -error;
  // Only the trusted Main directory capability, never a parent pathname.
  const int fd = openat(directory, file.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
  return fd >= 0 ? fd : -errno;
}
jint OpenChildDirectory(JNIEnv* env, jobject, jint directory, jstring name, jboolean create) {
  const auto child = basename(env, name);
  if (env->ExceptionCheck() || child.empty() || child.rfind(".native-publish-", 0) == 0) return -EINVAL;
  const int error = directory_error(directory);
  if (error) return -error;
  struct stat parent{};
  if (fstat(directory, &parent) != 0) return -errno;
  if ((parent.st_mode & 077) != 0) return -EACCES;
  if (create && mkdirat(directory, child.c_str(), 0700) != 0 && errno != EEXIST) return -errno;
  const int fd = openat(directory, child.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
  if (fd < 0) return -errno;
  struct stat info{};
  int failure = 0;
  if (fstat(fd, &info) != 0) failure = errno;
  else if (!S_ISDIR(info.st_mode) || info.st_uid != getuid() || (info.st_mode & 077) != 0) failure = EACCES;
  // A failed create may leave a directory behind. Never erase it or claim
  // absence; trusted Main may reopen the same idempotent child operation.
  if (!failure && create && (fsync(fd) != 0 || fsync(directory) != 0)) failure = errno;
  if (failure) { close(fd); return -failure; }
  return fd;
}
jint OpenStage(JNIEnv* env, jobject, jint directory, jstring name) {
  const auto file = basename(env, name);
  if (env->ExceptionCheck() || file.rfind(".native-publish-", 0) != 0) return -EINVAL;
  const int error = directory_error(directory);
  if (error) return -error;
  const int fd = openat(directory, file.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
  return fd >= 0 ? fd : -errno;
}
jint RemoveStage(JNIEnv* env, jobject, jint directory, jstring name) {
  const auto file = basename(env, name);
  if (env->ExceptionCheck() || file.rfind(".native-publish-", 0) != 0) return EINVAL;
  const int error = directory_error(directory);
  if (error) return error;
  return unlinkat(directory, file.c_str(), 0) == 0 ? 0 : errno;
}
jint RemoveRegular(JNIEnv* env, jobject, jint directory, jstring name) {
  const auto file = basename(env, name);
  if (env->ExceptionCheck() || file.empty() || file.rfind(".native-publish-", 0) == 0) return EINVAL;
  const int error = directory_error(directory);
  if (error) return error;
  struct stat info{};
  if (fstatat(directory, file.c_str(), &info, AT_SYMLINK_NOFOLLOW) != 0) return errno;
  if (!S_ISREG(info.st_mode) || info.st_uid != getuid() || info.st_nlink != 1 || (info.st_mode & 077) != 0) return EACCES;
  return unlinkat(directory, file.c_str(), 0) == 0 ? 0 : errno;
}
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
  auto type = env->FindClass("io/github/ccch1mneyyy/dsh_app/isolation/NativeRuntimePublication");
  if (!type) return JNI_ERR;
  JNINativeMethod methods[] = {
    {const_cast<char*>("publishNoReplace"), const_cast<char*>("(ILjava/lang/String;Ljava/lang/String;)I"), reinterpret_cast<void*>(Publish)},
    {const_cast<char*>("replace"), const_cast<char*>("(ILjava/lang/String;Ljava/lang/String;)I"), reinterpret_cast<void*>(Replace)},
    {const_cast<char*>("tryDirectoryLease"), const_cast<char*>("(I)I"), reinterpret_cast<void*>(TryDirectoryLease)},
    {const_cast<char*>("cleanupBotStages"), const_cast<char*>("(I)I"), reinterpret_cast<void*>(CleanupBotStages)},
    {const_cast<char*>("openRead"), const_cast<char*>("(ILjava/lang/String;)I"), reinterpret_cast<void*>(OpenRead)},
    {const_cast<char*>("openChildDirectory"), const_cast<char*>("(ILjava/lang/String;Z)I"), reinterpret_cast<void*>(OpenChildDirectory)},
    {const_cast<char*>("openStage"), const_cast<char*>("(ILjava/lang/String;)I"), reinterpret_cast<void*>(OpenStage)},
    {const_cast<char*>("removeStage"), const_cast<char*>("(ILjava/lang/String;)I"), reinterpret_cast<void*>(RemoveStage)},
    {const_cast<char*>("remove"), const_cast<char*>("(ILjava/lang/String;)I"), reinterpret_cast<void*>(RemoveRegular)},
  };
  if (env->RegisterNatives(type, methods, static_cast<jint>(sizeof(methods) / sizeof(methods[0]))) != JNI_OK) return JNI_ERR;
  return JNI_VERSION_1_6;
}
