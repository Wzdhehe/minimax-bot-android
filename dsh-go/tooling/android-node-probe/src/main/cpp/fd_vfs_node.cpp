// SPDX-License-Identifier: AGPL-3.0-only
// Verification-addon binding only; receives explicit FDs, never filesystem paths.
#include <node.h>
#include <sqlite3.h>
extern "C" int mbot_register_precreated_fd_vfs(const char* const*, const int*, int);
namespace {
void Configure(const v8::FunctionCallbackInfo<v8::Value>& args) {
  auto* isolate = args.GetIsolate();
  if (args.Length() != 4) { isolate->ThrowException(v8::Exception::Error(v8::String::NewFromUtf8Literal(isolate, "fd_vfs_input_invalid"))); return; }
  int fds[4];
  for (int i=0; i<4; ++i) {
    if (!args[i]->IsInt32() || (fds[i] = args[i].As<v8::Int32>()->Value()) < 3) {
      isolate->ThrowException(v8::Exception::Error(v8::String::NewFromUtf8Literal(isolate, "fd_vfs_input_invalid"))); return;
    }
  }
  const char* names[] = {"state.db", "state.db-wal", "state.db-shm", "state.db-journal"};
  const int result = mbot_register_precreated_fd_vfs(names, fds, 4);
  if (result != SQLITE_OK) {
    const char* code = result == SQLITE_MISUSE ? "SQLITE_MISUSE" : result == SQLITE_NOTFOUND ? "SQLITE_NOTFOUND" :
      result == SQLITE_CANTOPEN ? "SQLITE_CANTOPEN" : "fd_vfs_unavailable";
    auto error = v8::Exception::Error(v8::String::NewFromUtf8Literal(isolate, "fd_vfs_unavailable")).As<v8::Object>();
    error->Set(isolate->GetCurrentContext(), v8::String::NewFromUtf8Literal(isolate, "code"),
      v8::String::NewFromUtf8(isolate, code).ToLocalChecked()).Check();
    isolate->ThrowException(error); return;
  }
  args.GetReturnValue().Set(true);
}
}
void MbotInstallFdVfsBinding(v8::Local<v8::Object> exports, v8::Local<v8::Context> context) {
  auto* isolate = context->GetIsolate();
  exports->Set(context, v8::String::NewFromUtf8Literal(isolate, "setFdVfs"), v8::Function::New(context, Configure).ToLocalChecked()).Check();
}
