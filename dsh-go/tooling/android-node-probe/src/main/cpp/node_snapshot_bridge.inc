// SPDX-License-Identifier: AGPL-3.0-only
// Fixed private snapshot operations, sharing the verification Node dispatcher's
// existing VM/global-reference lifetime. No paths, SQL, UIDs or budgets in JS.
jmethodID snapshot_copy_method = nullptr;
jmethodID snapshot_catalog_method = nullptr;
jmethodID snapshot_open_method = nullptr;
jmethodID snapshot_remove_method = nullptr;
std::atomic<bool> snapshot_mutation_active{false};

void SnapshotFailure(v8::Isolate* isolate) {
  auto context = isolate->GetCurrentContext();
  auto error = v8::Exception::Error(v8::String::NewFromUtf8Literal(isolate, "runtime_snapshot_unavailable")).As<v8::Object>();
  error->Set(context, v8::String::NewFromUtf8Literal(isolate, "code"),
    v8::String::NewFromUtf8Literal(isolate, "runtime_snapshot_unavailable")).Check();
  isolate->ThrowException(error);
}

struct SnapshotWork {
  uv_work_t work{};
  v8::Isolate* isolate;
  v8::Global<v8::Promise::Resolver> resolver;
  v8::Global<v8::Context> context;
  std::u16string name, identity;
  bool remove = false;
  int result = -1;
};
void RunSnapshotMutation(uv_work_t* request) {
  auto* task = static_cast<SnapshotWork*>(request->data);
  JNIEnv* env = nullptr;
  bool attached = false;
  if (java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
    if (java_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
    attached = true;
  }
  if (env) {
    auto name = env->NewString(reinterpret_cast<const jchar*>(task->name.data()), task->name.size());
    auto identity = task->remove ? env->NewString(reinterpret_cast<const jchar*>(task->identity.data()), task->identity.size()) : nullptr;
    if (name && (!task->remove || identity) && !env->ExceptionCheck()) {
      task->result = task->remove ? env->CallIntMethod(dispatcher, snapshot_remove_method, name, identity)
        : env->CallIntMethod(dispatcher, snapshot_copy_method, name);
    }
    if (env->ExceptionCheck()) { env->ExceptionClear(); task->result = -1; }
    if (name) env->DeleteLocalRef(name);
    if (identity) env->DeleteLocalRef(identity);
  }
  if (attached) java_vm->DetachCurrentThread();
}
void FinishSnapshotMutation(uv_work_t* request, int status) {
  auto* task = static_cast<SnapshotWork*>(request->data);
  v8::HandleScope handles(task->isolate);
  auto context = task->context.Get(task->isolate);
  v8::Context::Scope entered(context);
  const char* result = "outcome_unknown";
  if (status == 0) {
    if (task->remove) { if (task->result == 0) result = "removed"; }
    else {
      const char* outcomes[] = {"published", "already_exists", "not_published", "outcome_unknown"};
      if (task->result >= 0 && task->result <= 3) result = outcomes[task->result];
    }
  }
  // Lost JNI/IPC results are never a success or an invitation to retry.
  task->resolver.Get(task->isolate)->Resolve(context,
    v8::String::NewFromUtf8(task->isolate, result).ToLocalChecked()).FromMaybe(false);
  snapshot_mutation_active.store(false);
  delete task;
}
void SnapshotMutation(const v8::FunctionCallbackInfo<v8::Value>& args, bool remove) {
  auto* isolate = args.GetIsolate();
  auto context = isolate->GetCurrentContext();
  if (args.Length() != (remove ? 2 : 1) || !args[0]->IsString() || args[0].As<v8::String>()->Length() > 128 ||
      (remove && (!args[1]->IsString() || args[1].As<v8::String>()->Length() != 32))) { SnapshotFailure(isolate); return; }
  bool expected = false;
  if (!snapshot_mutation_active.compare_exchange_strong(expected, true)) { SnapshotFailure(isolate); return; }
  auto resolver = v8::Promise::Resolver::New(context).ToLocalChecked();
  args.GetReturnValue().Set(resolver->GetPromise());
  auto* task = new SnapshotWork;
  task->isolate = isolate; task->remove = remove;
  task->context.Reset(isolate, context); task->resolver.Reset(isolate, resolver);
  v8::String::Value name(isolate, args[0]);
  task->name.assign(reinterpret_cast<const char16_t*>(*name), name.length());
  if (remove) {
    v8::String::Value identity(isolate, args[1]);
    task->identity.assign(reinterpret_cast<const char16_t*>(*identity), identity.length());
  }
  task->work.data = task;
  if (uv_queue_work(node::GetCurrentEventLoop(isolate), &task->work, RunSnapshotMutation, FinishSnapshotMutation) != 0)
    FinishSnapshotMutation(&task->work, UV_ECANCELED);
}
void CopySnapshot(const v8::FunctionCallbackInfo<v8::Value>& args) { SnapshotMutation(args, false); }
void RemoveSnapshot(const v8::FunctionCallbackInfo<v8::Value>& args) { SnapshotMutation(args, true); }

void CatalogSnapshots(const v8::FunctionCallbackInfo<v8::Value>& args) {
  auto* isolate = args.GetIsolate();
  JNIEnv* env = nullptr;
  if (args.Length() != 0 || java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    SnapshotFailure(isolate); return;
  }
  auto text = static_cast<jstring>(env->CallObjectMethod(dispatcher, snapshot_catalog_method));
  if (env->ExceptionCheck()) { env->ExceptionClear(); if (text) env->DeleteLocalRef(text); SnapshotFailure(isolate); return; }
  if (!text) { SnapshotFailure(isolate); return; }
  const auto length = env->GetStringLength(text);
  const jchar* chars = length <= 4096 ? env->GetStringChars(text, nullptr) : nullptr;
  if (!chars) {
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(text); SnapshotFailure(isolate); return;
  }
  const auto result = v8::String::NewFromTwoByte(isolate, reinterpret_cast<const uint16_t*>(chars), v8::NewStringType::kNormal, length).ToLocalChecked();
  env->ReleaseStringChars(text, chars); env->DeleteLocalRef(text);
  args.GetReturnValue().Set(result);
}
void OpenSnapshot(const v8::FunctionCallbackInfo<v8::Value>& args) {
  auto* isolate = args.GetIsolate();
  JNIEnv* env = nullptr;
  if (args.Length() != 2 || !args[0]->IsString() || args[0].As<v8::String>()->Length() > 128 ||
      !args[1]->IsString() || args[1].As<v8::String>()->Length() != 32 ||
      java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) { SnapshotFailure(isolate); return; }
  v8::String::Value name(isolate, args[0]), identity(isolate, args[1]);
  auto java_name = env->NewString(reinterpret_cast<const jchar*>(*name), name.length());
  auto java_identity = env->NewString(reinterpret_cast<const jchar*>(*identity), identity.length());
  int fd = -1;
  if (java_name && java_identity && !env->ExceptionCheck()) fd = env->CallIntMethod(dispatcher, snapshot_open_method, java_name, java_identity);
  bool failed = env->ExceptionCheck();
  if (failed) env->ExceptionClear();
  if (java_name) env->DeleteLocalRef(java_name);
  if (java_identity) env->DeleteLocalRef(java_identity);
  if (failed || fd < 3) {
    // Only a fresh FD returned by the private Main capability can reach here,
    // never a raw tool-supplied primary-family descriptor.
    if (fd >= 0) close(fd);
    SnapshotFailure(isolate); return;
  }
  args.GetReturnValue().Set(v8::Integer::New(isolate, fd));
}
void InitializeSnapshots(v8::Local<v8::Object> exports, v8::Local<v8::Value>, v8::Local<v8::Context> context, void*) {
  auto* isolate = context->GetIsolate();
  exports->Set(context, v8::String::NewFromUtf8Literal(isolate, "copy"), v8::Function::New(context, CopySnapshot).ToLocalChecked()).Check();
  exports->Set(context, v8::String::NewFromUtf8Literal(isolate, "catalog"), v8::Function::New(context, CatalogSnapshots).ToLocalChecked()).Check();
  exports->Set(context, v8::String::NewFromUtf8Literal(isolate, "openReadOnly"), v8::Function::New(context, OpenSnapshot).ToLocalChecked()).Check();
  exports->Set(context, v8::String::NewFromUtf8Literal(isolate, "remove"), v8::Function::New(context, RemoveSnapshot).ToLocalChecked()).Check();
}
namespace snapshot_module_registration {
NODE_MODULE_LINKED(mbot_snapshots_bridge, InitializeSnapshots)
}
