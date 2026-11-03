// SPDX-License-Identifier: AGPL-3.0-only
#include <jni.h>
#include <node.h>
#include <cstring>
#include <string>
#include <vector>

namespace {
std::string ReadString(JNIEnv* env, jstring value) {
  const char* text = env->GetStringUTFChars(value, nullptr);
  if (!text) return {};
  std::string copy(text);
  env->ReleaseStringUTFChars(value, text);
  return copy;
}

// This is a fixed-script test entry, loaded only by a private isolated service.
// No credential, network endpoint or arbitrary command is accepted here.
jint Start(JNIEnv* env, jobject, jstring script, jint normal, jint stalled,
           jint echo, jint result, jstring private_path, jstring native_dir) {
  std::vector<std::string> args{
      "mbot-node-probe", "--no-warnings", "-e", ReadString(env, script),
      std::to_string(normal), std::to_string(stalled), std::to_string(echo),
      std::to_string(result), ReadString(env, private_path), ReadString(env, native_dir)};
  if (env->ExceptionCheck()) return -1;
  size_t size = 0;
  for (const auto& arg : args) size += arg.size() + 1;
  std::vector<char> storage(size);
  std::vector<char*> argv(args.size() + 1, nullptr);
  size_t offset = 0;
  for (size_t i = 0; i < args.size(); ++i) {
    argv[i] = storage.data() + offset;
    std::memcpy(argv[i], args[i].c_str(), args[i].size() + 1);
    offset += args[i].size() + 1;
  }
  return node::Start(static_cast<int>(args.size()), argv.data());
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
  jclass type = env->FindClass("io/github/ccch1mneyyy/dsh_app/nodeprobe/NodeProbeRuntime");
  if (!type) return JNI_ERR;
  JNINativeMethod method{const_cast<char*>("start"),
      const_cast<char*>("(Ljava/lang/String;IIIILjava/lang/String;Ljava/lang/String;)I"), reinterpret_cast<void*>(Start)};
  if (env->RegisterNatives(type, &method, 1) != JNI_OK) return JNI_ERR;
  return JNI_VERSION_1_6;
}
