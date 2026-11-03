// SPDX-License-Identifier: AGPL-3.0-only
#include <jni.h>
#include <node.h>
#include <uv.h>
#include <unistd.h>
#include <atomic>
#include <cstring>
#include <string>
#include <vector>

namespace {
JavaVM* java_vm = nullptr;
jobject dispatcher = nullptr;
jmethodID open_method = nullptr;
jmethodID cancel_method = nullptr;
std::atomic<int> opens{0};

struct OpenWork {
  uv_work_t work{};
  v8::Isolate* isolate;
  v8::Global<v8::Promise::Resolver> resolver;
  v8::Global<v8::Context> context;
  std::u16string id, payload;
  int fd = -1;
};

void RunOpen(uv_work_t* request) {
  auto* task = static_cast<OpenWork*>(request->data);
  JNIEnv* env = nullptr;
  bool attached = false;
  if (java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
    if (java_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
    attached = true;
  }
  if (env) {
    auto id = env->NewString(reinterpret_cast<const jchar*>(task->id.data()), task->id.size());
    auto payload = env->NewString(reinterpret_cast<const jchar*>(task->payload.data()), task->payload.size());
    if (id && payload && !env->ExceptionCheck()) task->fd = env->CallIntMethod(dispatcher, open_method, id, payload);
    if (env->ExceptionCheck()) { env->ExceptionClear(); if (task->fd >= 0) close(task->fd); task->fd = -1; }
    if (id) env->DeleteLocalRef(id);
    if (payload) env->DeleteLocalRef(payload);
  }
  if (attached) java_vm->DetachCurrentThread();
}

void FinishOpen(uv_work_t* request, int status) {
  auto* task = static_cast<OpenWork*>(request->data);
  v8::HandleScope handles(task->isolate);
  auto context = task->context.Get(task->isolate);
  v8::Context::Scope entered(context);
  auto resolver = task->resolver.Get(task->isolate);
  if (status == 0 && task->fd >= 3) {
    if (!resolver->Resolve(context, v8::Integer::New(task->isolate, task->fd)).FromMaybe(false)) close(task->fd);
  } else {
    if (task->fd >= 0) close(task->fd);
    resolver->Reject(context, v8::Exception::Error(v8::String::NewFromUtf8Literal(task->isolate, "broker_unavailable"))).FromMaybe(false);
  }
  opens.fetch_sub(1);
  delete task;
}

void OpenModel(const v8::FunctionCallbackInfo<v8::Value>& args) {
  auto* isolate = args.GetIsolate();
  auto context = isolate->GetCurrentContext();
  auto resolver = v8::Promise::Resolver::New(context).ToLocalChecked();
  args.GetReturnValue().Set(resolver->GetPromise());
  int active = opens.load();
  bool admitted = false;
  while (active < 4) { if (opens.compare_exchange_weak(active, active + 1)) { admitted = true; break; } }
  if (!admitted || args.Length() != 2 || !args[0]->IsString() || !args[1]->IsString() ||
      args[0].As<v8::String>()->Length() > 128 || args[1].As<v8::String>()->Utf8LengthV2(isolate) > 1024 * 1024) {
    if (admitted) opens.fetch_sub(1);
    resolver->Reject(context, v8::Exception::Error(v8::String::NewFromUtf8Literal(isolate, "broker_request_invalid"))).FromMaybe(false);
    return;
  }
  auto* task = new OpenWork;
  task->isolate = isolate; task->resolver.Reset(isolate, resolver); task->context.Reset(isolate, context);
  v8::String::Value id(isolate, args[0]);
  v8::String::Value payload(isolate, args[1]);
  task->id.assign(reinterpret_cast<const char16_t*>(*id), id.length());
  task->payload.assign(reinterpret_cast<const char16_t*>(*payload), payload.length());
  task->work.data = task;
  if (uv_queue_work(node::GetCurrentEventLoop(isolate), &task->work, RunOpen, FinishOpen) != 0) {
    FinishOpen(&task->work, UV_ECANCELED);
  }
}

void CancelModel(const v8::FunctionCallbackInfo<v8::Value>& args) {
  if (args.Length() != 1 || !args[0]->IsString() || args[0].As<v8::String>()->Length() > 128) return;
  JNIEnv* env = nullptr;
  if (java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return;
  v8::String::Value id(args.GetIsolate(), args[0]);
  auto java_id = env->NewString(reinterpret_cast<const jchar*>(*id), id.length());
  if (java_id && !env->ExceptionCheck()) env->CallVoidMethod(dispatcher, cancel_method, java_id);
  if (env->ExceptionCheck()) env->ExceptionClear();
  if (java_id) env->DeleteLocalRef(java_id);
}

void Initialize(v8::Local<v8::Object> exports, v8::Local<v8::Value>, v8::Local<v8::Context> context, void*) {
  auto* isolate = context->GetIsolate();
  exports->Set(context, v8::String::NewFromUtf8Literal(isolate, "open"), v8::Function::New(context, OpenModel).ToLocalChecked()).Check();
  exports->Set(context, v8::String::NewFromUtf8Literal(isolate, "cancel"), v8::Function::New(context, CancelModel).ToLocalChecked()).Check();
}
NODE_MODULE_LINKED(mbot_model_bridge, Initialize)

std::string ReadString(JNIEnv* env, jstring value) {
  const char* text = env->GetStringUTFChars(value, nullptr);
  if (!text) return {};
  std::string copy(text);
  env->ReleaseStringUTFChars(value, text);
  return copy;
}

// This is a fixed-script test entry, loaded only by a private isolated service.
// No credential, network endpoint or arbitrary command is accepted here.
jint Start(JNIEnv* env, jobject owner, jstring script, jint normal, jint stalled,
           jint echo, jint result, jstring private_path, jstring native_dir, jstring provider_id, jintArray data_fds) {
  std::vector<std::string> args{
      "mbot-node-probe", "--no-warnings", "-e", ReadString(env, script),
      std::to_string(normal), std::to_string(stalled), std::to_string(echo),
      std::to_string(result), ReadString(env, private_path), ReadString(env, native_dir), ReadString(env, provider_id)};
  if (env->ExceptionCheck() || !data_fds || env->GetArrayLength(data_fds) != 4) return -1;
  jint descriptors[4];
  env->GetIntArrayRegion(data_fds, 0, 4, descriptors);
  if (env->ExceptionCheck()) return -1;
  for (int fd : descriptors) { if (fd < 3) return -1; args.push_back(std::to_string(fd)); }
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
  dispatcher = env->NewGlobalRef(owner);
  if (!dispatcher) return -1;
  const int exit_code = node::Start(static_cast<int>(args.size()), argv.data());
  env->DeleteGlobalRef(dispatcher); dispatcher = nullptr;
  return exit_code;
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  java_vm = vm;
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
  jclass type = env->FindClass("io/github/ccch1mneyyy/dsh_app/nodeprobe/NodeProbeRuntime");
  if (!type) return JNI_ERR;
  open_method = env->GetMethodID(type, "openModelFd", "(Ljava/lang/String;Ljava/lang/String;)I");
  cancel_method = env->GetMethodID(type, "cancelModelFd", "(Ljava/lang/String;)V");
  if (!open_method || !cancel_method) return JNI_ERR;
  JNINativeMethod method{const_cast<char*>("start"),
      const_cast<char*>("(Ljava/lang/String;IIIILjava/lang/String;Ljava/lang/String;Ljava/lang/String;[I)I"), reinterpret_cast<void*>(Start)};
  if (env->RegisterNatives(type, &method, 1) != JNI_OK) return JNI_ERR;
  return JNI_VERSION_1_6;
}
