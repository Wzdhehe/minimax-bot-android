// SPDX-License-Identifier: AGPL-3.0-only
// Product profile of the shared Node bridge. Included inside its private
// namespace; no arbitrary script, endpoint or path is received over IPC.
jmethodID asset_read_method = nullptr, ready_method = nullptr, wait_stop_method = nullptr, failure_method = nullptr;
std::atomic<bool> control_waiting{false};

void ReadCodeAsset(const v8::FunctionCallbackInfo<v8::Value>& args) {
  auto* isolate = args.GetIsolate();
  JNIEnv* env = nullptr;
  if (args.Length() != 1 || !args[0]->IsString() || args[0].As<v8::String>()->Length() > 32 ||
      java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) { SnapshotFailure(isolate); return; }
  v8::String::Value value(isolate, args[0]);
  auto name = env->NewString(reinterpret_cast<const jchar*>(*value), value.length());
  auto data = name && !env->ExceptionCheck() ? static_cast<jbyteArray>(env->CallObjectMethod(dispatcher, asset_read_method, name)) : nullptr;
  if (name) env->DeleteLocalRef(name);
  if (env->ExceptionCheck()) { env->ExceptionClear(); if (data) env->DeleteLocalRef(data); SnapshotFailure(isolate); return; }
  const int size = data ? env->GetArrayLength(data) : -1;
  if (size < 0 || size > 64 * 1024 * 1024) { if (data) env->DeleteLocalRef(data); SnapshotFailure(isolate); return; }
  auto backing = v8::ArrayBuffer::NewBackingStore(isolate, size);
  if (size) env->GetByteArrayRegion(data, 0, size, static_cast<jbyte*>(backing->Data()));
  env->DeleteLocalRef(data);
  if (env->ExceptionCheck()) { env->ExceptionClear(); SnapshotFailure(isolate); return; }
  auto buffer = v8::ArrayBuffer::New(isolate, std::move(backing));
  args.GetReturnValue().Set(v8::Uint8Array::New(buffer, 0, size));
}
void MarkReady(const v8::FunctionCallbackInfo<v8::Value>& args) {
  JNIEnv* env = nullptr;
  if (args.Length() || java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) { SnapshotFailure(args.GetIsolate()); return; }
  const bool ok = env->CallBooleanMethod(dispatcher, ready_method);
  if (env->ExceptionCheck()) { env->ExceptionClear(); SnapshotFailure(args.GetIsolate()); return; }
  args.GetReturnValue().Set(ok);
}
void ReportBootFailure(const v8::FunctionCallbackInfo<v8::Value>& args) {
  JNIEnv* env = nullptr;
  if (args.Length() != 1 || !args[0]->IsString() || args[0].As<v8::String>()->Length() > 40 ||
      java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return;
  v8::String::Value value(args.GetIsolate(), args[0]);
  auto phase = env->NewString(reinterpret_cast<const jchar*>(*value), value.length());
  if (phase && !env->ExceptionCheck()) env->CallVoidMethod(dispatcher, failure_method, phase);
  if (env->ExceptionCheck()) env->ExceptionClear();
  if (phase) env->DeleteLocalRef(phase);
}
struct StopWork {
  uv_work_t work{};
  v8::Isolate* isolate;
  v8::Global<v8::Context> context;
  v8::Global<v8::Promise::Resolver> resolver;
};
void RunStopWait(uv_work_t*) {
  JNIEnv* env = nullptr;
  bool attached = false;
  if (java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
    if (java_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
    attached = true;
  }
  if (env) { env->CallIntMethod(dispatcher, wait_stop_method); if (env->ExceptionCheck()) env->ExceptionClear(); }
  if (attached) java_vm->DetachCurrentThread();
}
void FinishStopWait(uv_work_t* request, int) {
  auto* task = static_cast<StopWork*>(request->data);
  v8::HandleScope handles(task->isolate);
  auto context = task->context.Get(task->isolate);
  v8::Context::Scope entered(context);
  task->resolver.Get(task->isolate)->Resolve(context, v8::Undefined(task->isolate)).FromMaybe(false);
  delete task;
}
void WaitForStop(const v8::FunctionCallbackInfo<v8::Value>& args) {
  bool expected = false;
  if (args.Length() || !control_waiting.compare_exchange_strong(expected, true)) { SnapshotFailure(args.GetIsolate()); return; }
  auto* isolate = args.GetIsolate();
  auto context = isolate->GetCurrentContext();
  auto resolver = v8::Promise::Resolver::New(context).ToLocalChecked();
  auto* task = new StopWork;
  task->isolate = isolate; task->context.Reset(isolate, context); task->resolver.Reset(isolate, resolver); task->work.data = task;
  args.GetReturnValue().Set(resolver->GetPromise());
  if (uv_queue_work(node::GetCurrentEventLoop(isolate), &task->work, RunStopWait, FinishStopWait) != 0) FinishStopWait(&task->work, UV_ECANCELED);
}
void InitializeProduct(v8::Local<v8::Object> exports, v8::Local<v8::Value>, v8::Local<v8::Context> context, void*) {
  auto* isolate = context->GetIsolate();
  exports->Set(context, v8::String::NewFromUtf8Literal(isolate, "read"), v8::Function::New(context, ReadCodeAsset).ToLocalChecked()).Check();
  exports->Set(context, v8::String::NewFromUtf8Literal(isolate, "ready"), v8::Function::New(context, MarkReady).ToLocalChecked()).Check();
  exports->Set(context, v8::String::NewFromUtf8Literal(isolate, "failed"), v8::Function::New(context, ReportBootFailure).ToLocalChecked()).Check();
  exports->Set(context, v8::String::NewFromUtf8Literal(isolate, "waitForStop"), v8::Function::New(context, WaitForStop).ToLocalChecked()).Check();
}
namespace product_module_registration {
NODE_MODULE_LINKED(mbot_product_bridge, InitializeProduct)
}
jint StartProduct(JNIEnv* env, jobject owner, jstring script, jint descriptor_fd, jint owner_uid,
                  jstring libraries, jintArray data_fds) {
  const auto app_id = getuid() % 100000;
  if (app_id < 99000 || app_id > 99999 || getuid() == static_cast<uid_t>(owner_uid)) return -1;
  if (descriptor_fd < 3 || owner_uid < 0 || !data_fds || env->GetArrayLength(data_fds) != 4) return -1;
  jint data[4]; env->GetIntArrayRegion(data_fds, 0, 4, data);
  std::vector<std::string> args{"mbot-node", "--no-warnings", "-e", ReadString(env, script),
    std::to_string(descriptor_fd), std::to_string(owner_uid), ReadString(env, libraries)};
  if (env->ExceptionCheck()) return -1;
  for (int fd : data) { if (fd < 3) return -1; args.push_back(std::to_string(fd)); }
  size_t size = 0; for (const auto& arg : args) size += arg.size() + 1;
  std::vector<char> storage(size); std::vector<char*> argv(args.size() + 1, nullptr);
  size_t offset = 0;
  for (size_t index = 0; index < args.size(); ++index) {
    argv[index] = storage.data() + offset; std::memcpy(argv[index], args[index].c_str(), args[index].size() + 1); offset += args[index].size() + 1;
  }
  dispatcher = env->NewGlobalRef(owner);
  if (!dispatcher) return -1;
  const int result = node::Start(static_cast<int>(args.size()), argv.data());
  env->DeleteGlobalRef(dispatcher); dispatcher = nullptr;
  return result;
}
