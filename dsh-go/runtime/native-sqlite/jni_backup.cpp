// SPDX-License-Identifier: AGPL-3.0-only
#include "fd_backup.h"
#include <jni.h>
#include <atomic>
#include <array>

namespace {
// Process lifetime, never reset. Loading this library does not authenticate a
// caller. Only a fresh trusted worker may load/call it after Main admission.
std::atomic_bool claimed{false};
std::atomic_bool cancelled{false};

// Explicit wire values, not C++ enum ordinals. Keep NativeSqliteBackupBindings
// and its decoder in sync. The reply contains no paths, SQL or error text.
jlong stageCode(mbot::FdBackupStage value) {
  switch (value) {
    case mbot::FdBackupStage::ok: return 0;
    case mbot::FdBackupStage::invalid_worker: return 1;
    case mbot::FdBackupStage::invalid_grant: return 2;
    case mbot::FdBackupStage::registry_unavailable: return 3;
    case mbot::FdBackupStage::open_failed: return 4;
    case mbot::FdBackupStage::copy_failed: return 5;
    case mbot::FdBackupStage::close_failed: return 6;
    case mbot::FdBackupStage::sync_failed: return 7;
    case mbot::FdBackupStage::verification_failed: return 8;
  }
  return 1;
}
jlong resultCode(mbot::BackupResult value) {
  switch (value) {
    case mbot::BackupResult::ok: return 0;
    case mbot::BackupResult::invalid: return 1;
    case mbot::BackupResult::source_not_readonly: return 2;
    case mbot::BackupResult::destination_not_empty: return 3;
    case mbot::BackupResult::too_large: return 4;
    case mbot::BackupResult::busy: return 5;
    case mbot::BackupResult::timed_out: return 6;
    case mbot::BackupResult::cancelled: return 7;
    case mbot::BackupResult::copy_failed: return 8;
    case mbot::BackupResult::integrity_failed: return 9;
  }
  return 1;
}
jlongArray encode(JNIEnv* env, mbot::FdBackupReply reply) {
  const jlong values[] = {stageCode(reply.stage), resultCode(reply.result), reply.bytes};
  jlongArray array = env->NewLongArray(3);
  if (array) env->SetLongArrayRegion(array, 0, 3, values);
  return array;
}
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_github_ccch1mneyyy_dsh_1app_isolation_NativeSqliteBackupBindings_run(
    JNIEnv* env, jobject, jintArray descriptors, jint ownerUid, jint ownerPid,
    jlong maxBytes, jint timeoutMs) {
  using mbot::FdBackupStage;
  using mbot::BackupResult;
  if (claimed.exchange(true)) return encode(env, {FdBackupStage::invalid_worker, BackupResult::invalid, 0});
  if (!descriptors || env->GetArrayLength(descriptors) != 8 || ownerUid < 0 || ownerPid <= 0) {
    return encode(env, {FdBackupStage::invalid_grant, BackupResult::invalid, 0});
  }
  std::array<jint, 8> input;
  env->GetIntArrayRegion(descriptors, 0, 8, input.data());
  if (env->ExceptionCheck()) return nullptr;
  std::array<int, 8> fds;
  for (int i = 0; i < 8; ++i) fds[i] = input[i];
  return encode(env, mbot::runGrantedSqliteBackup(fds, static_cast<uid_t>(ownerUid),
                static_cast<pid_t>(ownerPid), maxBytes, timeoutMs, &cancelled));
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_ccch1mneyyy_dsh_1app_isolation_NativeSqliteBackupBindings_cancel(JNIEnv*, jobject) {
  // No JNI arrays, locks or connection ownership on the cancellation thread.
  cancelled.store(true, std::memory_order_relaxed);
}
