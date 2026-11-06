// SPDX-License-Identifier: AGPL-3.0-only
// Node's synchronous config filesystem uses the SAME private Binder client
// and Main atomic publisher as asynchronous Bot persistence. No direct paths.
void SyncFileError(v8::Isolate* isolate, int result) {
  const char* code = result == 7 ? "runtime_data_not_found" : "runtime_data_unavailable";
  auto context = isolate->GetCurrentContext();
  auto error = v8::Exception::Error(v8::String::NewFromUtf8(isolate, code).ToLocalChecked()).As<v8::Object>();
  error->Set(context, v8::String::NewFromUtf8Literal(isolate, "code"), v8::String::NewFromUtf8(isolate, code).ToLocalChecked()).Check();
  isolate->ThrowException(error);
}
void ReadFileSync(const v8::FunctionCallbackInfo<v8::Value>& args) {
  auto* isolate = args.GetIsolate(); JNIEnv* env = nullptr;
  if (args.Length() != 1 || !args[0]->IsString() || args[0].As<v8::String>()->Length() > 128 ||
      java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) { SyncFileError(isolate, 6); return; }
  v8::String::Value value(isolate, args[0]);
  auto name = env->NewString(reinterpret_cast<const jchar*>(*value), value.length());
  auto bytes = name && !env->ExceptionCheck() ? static_cast<jbyteArray>(env->CallObjectMethod(dispatcher, file_read_method, name)) : nullptr;
  if (name) env->DeleteLocalRef(name);
  if (env->ExceptionCheck()) { env->ExceptionClear(); if (bytes) env->DeleteLocalRef(bytes); SyncFileError(isolate, 6); return; }
  const int size = bytes ? env->GetArrayLength(bytes) : 0;
  jbyte code = 6;
  if (size > 0 && size <= 1024 * 1024 + 1) env->GetByteArrayRegion(bytes, 0, 1, &code);
  if (env->ExceptionCheck() || code != 0) {
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (bytes) env->DeleteLocalRef(bytes);
    SyncFileError(isolate, code); return;
  }
  auto backing = v8::ArrayBuffer::NewBackingStore(isolate, size - 1);
  if (size > 1) env->GetByteArrayRegion(bytes, 1, size - 1, static_cast<jbyte*>(backing->Data()));
  env->DeleteLocalRef(bytes);
  if (env->ExceptionCheck()) { env->ExceptionClear(); SyncFileError(isolate, 6); return; }
  auto buffer = v8::ArrayBuffer::New(isolate, std::move(backing));
  args.GetReturnValue().Set(v8::Uint8Array::New(buffer, 0, size - 1));
}
void ReplaceFileSync(const v8::FunctionCallbackInfo<v8::Value>& args) {
  auto* isolate = args.GetIsolate(); JNIEnv* env = nullptr;
  if (args.Length() != 2 || !args[0]->IsString() || args[0].As<v8::String>()->Length() > 128 || !args[1]->IsUint8Array() ||
      java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) { SyncFileError(isolate, 6); return; }
  auto view = args[1].As<v8::Uint8Array>(); auto buffer = view->Buffer(); auto backing = buffer->GetBackingStore();
  const auto size = view->ByteLength();
  if (size > 1024 * 1024 || buffer->WasDetached() || backing->IsShared() || backing->IsResizableByUserJavaScript()) { SyncFileError(isolate, 6); return; }
  v8::String::Value value(isolate, args[0]);
  auto name = env->NewString(reinterpret_cast<const jchar*>(*value), value.length());
  auto bytes = env->NewByteArray(size);
  std::vector<jbyte> copy(size);
  if (size && view->CopyContents(copy.data(), size) != size) { if (name) env->DeleteLocalRef(name); if (bytes) env->DeleteLocalRef(bytes); SyncFileError(isolate, 6); return; }
  if (bytes && size && !env->ExceptionCheck()) env->SetByteArrayRegion(bytes, 0, size, copy.data());
  int result = 2;
  if (name && bytes && !env->ExceptionCheck()) result = env->CallIntMethod(dispatcher, file_method, name, bytes, JNI_TRUE);
  if (env->ExceptionCheck()) { env->ExceptionClear(); result = 2; }
  if (name) env->DeleteLocalRef(name); if (bytes) env->DeleteLocalRef(bytes);
  args.GetReturnValue().Set(v8::String::NewFromUtf8Literal(isolate, "outcome_unknown"));
  if (result == 0) args.GetReturnValue().Set(v8::String::NewFromUtf8Literal(isolate, "published"));
}

void RemoveFileSync(const v8::FunctionCallbackInfo<v8::Value>& args) {
  auto* isolate = args.GetIsolate(); JNIEnv* env = nullptr;
  if (args.Length() != 1 || !args[0]->IsString() || args[0].As<v8::String>()->Length() > 128 ||
      java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) { SyncFileError(isolate, 6); return; }
  v8::String::Value value(isolate, args[0]);
  auto name = env->NewString(reinterpret_cast<const jchar*>(*value), value.length());
  int result = 2;
  if (name && !env->ExceptionCheck()) result = env->CallIntMethod(dispatcher, file_remove_method, name);
  if (env->ExceptionCheck()) { env->ExceptionClear(); result = 2; }
  if (name) env->DeleteLocalRef(name);
  args.GetReturnValue().Set(v8::String::NewFromUtf8(isolate, result == 0 ? "removed" : result == 7 ? "not_found" : "outcome_unknown").ToLocalChecked());
}
