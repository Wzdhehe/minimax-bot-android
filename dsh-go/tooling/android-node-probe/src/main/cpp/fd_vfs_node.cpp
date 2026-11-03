// SPDX-License-Identifier: AGPL-3.0-only
// Verification-addon binding only; receives explicit FDs, never filesystem paths.
#include <node.h>
#include <sqlite3.h>
extern "C" int mbot_register_fd_vfs(int, const char* const*, const int*, int);
namespace {
void Configure(const v8::FunctionCallbackInfo<v8::Value>& args) {
  auto* isolate = args.GetIsolate();
  if (args.Length() != 5) { isolate->ThrowException(v8::Exception::Error(v8::String::NewFromUtf8Literal(isolate, "fd_vfs_input_invalid"))); return; }
  int fds[5];
  for (int i=0; i<5; ++i) {
    if (!args[i]->IsInt32() || (fds[i] = args[i].As<v8::Int32>()->Value()) < 3) {
      isolate->ThrowException(v8::Exception::Error(v8::String::NewFromUtf8Literal(isolate, "fd_vfs_input_invalid"))); return;
    }
  }
  const char* names[] = {"state.db", "state.db-wal", "state.db-shm", "state.db-journal"};
  if (mbot_register_fd_vfs(fds[4], names, fds, 4) != SQLITE_OK) {
    isolate->ThrowException(v8::Exception::Error(v8::String::NewFromUtf8Literal(isolate, "fd_vfs_unavailable"))); return;
  }
  args.GetReturnValue().Set(true);
}
}
void MbotInstallFdVfsBinding(v8::Local<v8::Object> exports, v8::Local<v8::Context> context) {
  auto* isolate = context->GetIsolate();
  exports->Set(context, v8::String::NewFromUtf8Literal(isolate, "setFdVfs"), v8::Function::New(context, Configure).ToLocalChecked()).Check();
}
