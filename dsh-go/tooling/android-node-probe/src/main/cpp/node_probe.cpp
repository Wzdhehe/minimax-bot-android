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
jmethodID file_method = nullptr;
jmethodID file_read_method = nullptr;
jmethodID file_size_method = nullptr;
jmethodID file_move_method = nullptr;
jmethodID file_remove_method = nullptr;
jmethodID file_append_method = nullptr;
std::atomic<int> opens{0};
std::atomic<int> file_commits{0};

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

struct FileWork {
  uv_work_t work{};
  v8::Isolate* isolate;
  v8::Global<v8::Promise::Resolver> resolver;
  v8::Global<v8::Context> context;
  std::u16string name, target;
  std::vector<unsigned char> bytes;
  int operation = 0;  // publish, replace, read, size, move, rename, remove, append; fixed callbacks.
  bool called = false;
  jlong size = 0;
  int result = -1;
};

void RejectFile(v8::Local<v8::Context> context, v8::Local<v8::Promise::Resolver> resolver, const char* code) {
  auto* isolate = context->GetIsolate();
  auto text = v8::String::NewFromUtf8(isolate, code).ToLocalChecked();
  auto error = v8::Exception::Error(text).As<v8::Object>();
  error->CreateDataProperty(context, v8::String::NewFromUtf8Literal(isolate, "code"), text).FromMaybe(false);
  resolver->Reject(context, error).FromMaybe(false);
}

bool FileMutation(int operation) { return operation < 2 || operation >= 4; }

void RunFile(uv_work_t* request) {
  auto* task = static_cast<FileWork*>(request->data);
  JNIEnv* env = nullptr;
  bool attached = false;
  if (java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
    if (java_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
    attached = true;
  }
  if (env) {
    auto name = env->NewString(reinterpret_cast<const jchar*>(task->name.data()), task->name.size());
    jbyteArray bytes = nullptr;
    jbyteArray response = nullptr;
    jstring target = nullptr;
    if (name && !env->ExceptionCheck() && (task->operation == 4 || task->operation == 5)) {
      target = env->NewString(reinterpret_cast<const jchar*>(task->target.data()), task->target.size());
      if (target && !env->ExceptionCheck()) {
        task->called = true;
        task->result = env->CallIntMethod(dispatcher, file_move_method, name, target, task->operation == 5 ? JNI_TRUE : JNI_FALSE);
      }
    } else if (name && !env->ExceptionCheck() && task->operation == 6) {
      task->called = true;
      task->result = env->CallIntMethod(dispatcher, file_remove_method, name);
    } else if (name && !env->ExceptionCheck() && task->operation == 2) {
      task->called = true;
      response = static_cast<jbyteArray>(env->CallObjectMethod(dispatcher, file_read_method, name));
      task->result = 6;
      if (response && !env->ExceptionCheck()) {
        const auto length = env->GetArrayLength(response);
        if (length >= 1 && length <= 1024 * 1024 + 1) {
          jbyte code = 6;
          env->GetByteArrayRegion(response, 0, 1, &code);
          if (code == 0) {
            task->result = 0; task->bytes.resize(length - 1);
            if (length > 1) env->GetByteArrayRegion(response, 1, length - 1, reinterpret_cast<jbyte*>(task->bytes.data()));
          } else if (code >= 3 && code <= 8 && length == 1) task->result = code;
        }
      }
    } else if (name && !env->ExceptionCheck() && task->operation == 3) {
      task->called = true;
      task->size = env->CallLongMethod(dispatcher, file_size_method, name);
      task->result = task->size >= 0 && task->size <= 9007199254740991LL ? 0 :
        (task->size >= -8 && task->size <= -3 ? static_cast<int>(-task->size) : 6);
    } else if (name && !env->ExceptionCheck()) {
      bytes = env->NewByteArray(static_cast<jsize>(task->bytes.size()));
      if (bytes && !task->bytes.empty() && !env->ExceptionCheck()) {
        env->SetByteArrayRegion(bytes, 0, task->bytes.size(), reinterpret_cast<const jbyte*>(task->bytes.data()));
      }
      if (bytes && !env->ExceptionCheck()) {
        task->called = true;
        task->result = task->operation == 7 ? env->CallIntMethod(dispatcher, file_append_method, name, bytes) :
          env->CallIntMethod(dispatcher, file_method, name, bytes, task->operation == 1 ? JNI_TRUE : JNI_FALSE);
      }
    }
    if (env->ExceptionCheck()) { env->ExceptionClear(); if (task->called) task->result = FileMutation(task->operation) ? 2 : 6; }
    if (name) env->DeleteLocalRef(name);
    if (bytes) env->DeleteLocalRef(bytes);
    if (response) env->DeleteLocalRef(response);
    if (target) env->DeleteLocalRef(target);
  }
  if (attached) java_vm->DetachCurrentThread();
}

void FinishFile(uv_work_t* request, int status) {
  auto* task = static_cast<FileWork*>(request->data);
  v8::HandleScope handles(task->isolate);
  auto context = task->context.Get(task->isolate);
  v8::Context::Scope entered(context);
  auto resolver = task->resolver.Get(task->isolate);
  if (status == 0 && task->result == 0 && task->operation == 2) {
    auto backing = v8::ArrayBuffer::NewBackingStore(task->isolate, task->bytes.size());
    if (!task->bytes.empty()) std::memcpy(backing->Data(), task->bytes.data(), task->bytes.size());
    auto buffer = v8::ArrayBuffer::New(task->isolate, std::move(backing));
    resolver->Resolve(context, v8::Uint8Array::New(buffer, 0, task->bytes.size())).FromMaybe(false);
  } else if (status == 0 && task->result == 0 && task->operation == 3) {
    resolver->Resolve(context, v8::Number::New(task->isolate, static_cast<double>(task->size))).FromMaybe(false);
  } else if (task->operation == 6 && status == 0 && task->result == 0) {
    resolver->Resolve(context, v8::String::NewFromUtf8Literal(task->isolate, "removed")).FromMaybe(false);
  } else if (FileMutation(task->operation) && status == 0 && task->result >= 0 && task->result <= 2) {
    const char* results[] = {"published", "already_exists", "outcome_unknown"};
    const bool exclusive = task->operation == 0 || task->operation == 4;
    const int result = task->operation == 6 || (task->result == 1 && !exclusive) ? 2 : task->result;
    resolver->Resolve(context, v8::String::NewFromUtf8(task->isolate, results[result]).ToLocalChecked()).FromMaybe(false);
  } else if (status == 0 && task->result >= 3 && task->result <= 8) {
    const char* errors[] = {"runtime_data_invalid", "runtime_data_denied", "runtime_data_closed", "runtime_data_unavailable", "runtime_data_not_found", "runtime_data_too_large"};
    RejectFile(context, resolver, errors[task->result - 3]);
  } else if (task->called && FileMutation(task->operation)) {
    resolver->Resolve(context, v8::String::NewFromUtf8Literal(task->isolate, "outcome_unknown")).FromMaybe(false);
  } else {
    RejectFile(context, resolver, "runtime_data_unavailable");
  }
  file_commits.fetch_sub(1);
  delete task;
}

void CommitFile(const v8::FunctionCallbackInfo<v8::Value>& args, int operation) {
  auto* isolate = args.GetIsolate();
  auto context = isolate->GetCurrentContext();
  auto resolver = v8::Promise::Resolver::New(context).ToLocalChecked();
  args.GetReturnValue().Set(resolver->GetPromise());
  const bool body = operation == 0 || operation == 1 || operation == 7;
  const bool move = operation == 4 || operation == 5;
  if (args.Length() != (body || move ? 2 : 1) || !args[0]->IsString() || args[0].As<v8::String>()->Length() > 128 ||
      (body && !args[1]->IsUint8Array()) || (move && (!args[1]->IsString() || args[1].As<v8::String>()->Length() > 128))) {
    RejectFile(context, resolver, "runtime_data_invalid"); return;
  }
  v8::Local<v8::Uint8Array> view;
  if (body) {
    view = args[1].As<v8::Uint8Array>();
    auto buffer = view->Buffer();
    auto backing = buffer->GetBackingStore();
    // A snapshot must not race another JS worker or detached/resizable storage.
    if (backing->IsShared() || backing->IsResizableByUserJavaScript() || buffer->WasDetached() || view->ByteLength() > 1024 * 1024) {
      RejectFile(context, resolver, "runtime_data_invalid"); return;
    }
  }
  int active = file_commits.load();
  bool admitted = false;
  while (active < 4) { if (file_commits.compare_exchange_weak(active, active + 1)) { admitted = true; break; } }
  if (!admitted) { RejectFile(context, resolver, "runtime_data_unavailable"); return; }
  auto* task = new FileWork;
  task->isolate = isolate; task->resolver.Reset(isolate, resolver); task->context.Reset(isolate, context);
  task->operation = operation;
  v8::String::Value name(isolate, args[0]);
  task->name.assign(reinterpret_cast<const char16_t*>(*name), name.length());
  if (move) {
    v8::String::Value target(isolate, args[1]);
    task->target.assign(reinterpret_cast<const char16_t*>(*target), target.length());
  }
  if (body) task->bytes.resize(view->ByteLength());
  if (!task->bytes.empty() && view->CopyContents(task->bytes.data(), task->bytes.size()) != task->bytes.size()) {
    file_commits.fetch_sub(1); delete task;
    RejectFile(context, resolver, "runtime_data_invalid"); return;
  }
  task->work.data = task;
  if (uv_queue_work(node::GetCurrentEventLoop(isolate), &task->work, RunFile, FinishFile) != 0) FinishFile(&task->work, UV_ECANCELED);
}
void PublishFile(const v8::FunctionCallbackInfo<v8::Value>& args) { CommitFile(args, 0); }
void ReplaceFile(const v8::FunctionCallbackInfo<v8::Value>& args) { CommitFile(args, 1); }
void ReadFile(const v8::FunctionCallbackInfo<v8::Value>& args) { CommitFile(args, 2); }
void SizeFile(const v8::FunctionCallbackInfo<v8::Value>& args) { CommitFile(args, 3); }
void MoveFile(const v8::FunctionCallbackInfo<v8::Value>& args) { CommitFile(args, 4); }
void RenameFile(const v8::FunctionCallbackInfo<v8::Value>& args) { CommitFile(args, 5); }
void RemoveFile(const v8::FunctionCallbackInfo<v8::Value>& args) { CommitFile(args, 6); }
void AppendFile(const v8::FunctionCallbackInfo<v8::Value>& args) { CommitFile(args, 7); }
void InitializeFiles(v8::Local<v8::Object> exports, v8::Local<v8::Value>, v8::Local<v8::Context> context, void*) {
  auto* isolate = context->GetIsolate();
  exports->Set(context, v8::String::NewFromUtf8Literal(isolate, "publish"), v8::Function::New(context, PublishFile).ToLocalChecked()).Check();
  exports->Set(context, v8::String::NewFromUtf8Literal(isolate, "replace"), v8::Function::New(context, ReplaceFile).ToLocalChecked()).Check();
  exports->Set(context, v8::String::NewFromUtf8Literal(isolate, "read"), v8::Function::New(context, ReadFile).ToLocalChecked()).Check();
  exports->Set(context, v8::String::NewFromUtf8Literal(isolate, "size"), v8::Function::New(context, SizeFile).ToLocalChecked()).Check();
  exports->Set(context, v8::String::NewFromUtf8Literal(isolate, "move"), v8::Function::New(context, MoveFile).ToLocalChecked()).Check();
  exports->Set(context, v8::String::NewFromUtf8Literal(isolate, "rename"), v8::Function::New(context, RenameFile).ToLocalChecked()).Check();
  exports->Set(context, v8::String::NewFromUtf8Literal(isolate, "remove"), v8::Function::New(context, RemoveFile).ToLocalChecked()).Check();
  exports->Set(context, v8::String::NewFromUtf8Literal(isolate, "append"), v8::Function::New(context, AppendFile).ToLocalChecked()).Check();
}
// Node's macro emits a TU-local `_module` regardless of the public module name.
// Keep the second registration in its own namespace rather than duplicating it.
namespace file_module_registration {
NODE_MODULE_LINKED(mbot_files_bridge, InitializeFiles)
}

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
           jint echo, jint result, jstring private_path, jstring native_dir, jstring provider_id,
           jint descriptor_fd, jint owner_uid, jintArray data_fds) {
  std::vector<std::string> args{
      "mbot-node-probe", "--no-warnings", "-e", ReadString(env, script),
      std::to_string(normal), std::to_string(stalled), std::to_string(echo),
      std::to_string(result), ReadString(env, private_path), ReadString(env, native_dir), ReadString(env, provider_id),
      std::to_string(descriptor_fd), std::to_string(owner_uid)};
  if (env->ExceptionCheck() || descriptor_fd < 3 || owner_uid < 0 || !data_fds || env->GetArrayLength(data_fds) != 4) return -1;
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
  file_method = env->GetMethodID(type, "commitRuntimeFile", "(Ljava/lang/String;[BZ)I");
  file_read_method = env->GetMethodID(type, "readRuntimeFile", "(Ljava/lang/String;)[B");
  file_size_method = env->GetMethodID(type, "sizeRuntimeFile", "(Ljava/lang/String;)J");
  file_move_method = env->GetMethodID(type, "moveRuntimeFile", "(Ljava/lang/String;Ljava/lang/String;Z)I");
  file_remove_method = env->GetMethodID(type, "removeRuntimeFile", "(Ljava/lang/String;)I");
  file_append_method = env->GetMethodID(type, "appendRuntimeFile", "(Ljava/lang/String;[B)I");
  if (!open_method || !cancel_method || !file_method || !file_read_method || !file_size_method || !file_move_method || !file_remove_method || !file_append_method) return JNI_ERR;
  JNINativeMethod method{const_cast<char*>("start"),
      const_cast<char*>("(Ljava/lang/String;IIIILjava/lang/String;Ljava/lang/String;Ljava/lang/String;II[I)I"), reinterpret_cast<void*>(Start)};
  if (env->RegisterNatives(type, &method, 1) != JNI_OK) return JNI_ERR;
  return JNI_VERSION_1_6;
}
