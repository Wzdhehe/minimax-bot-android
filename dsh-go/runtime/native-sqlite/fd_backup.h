// SPDX-License-Identifier: AGPL-3.0-only
#pragma once
#include "backup.h"
#include <array>
#include <sys/types.h>

namespace mbot {
enum class FdBackupStage {
  ok, invalid_worker, invalid_grant, registry_unavailable, open_failed, copy_failed,
  close_failed, sync_failed, verification_failed,
};
struct FdBackupReply {
  FdBackupStage stage;
  BackupResult result;
  // Only meaningful on ok. Zero on failure does NOT mean nothing was written.
  std::int64_t bytes = 0;
};

// One-shot trusted native worker in a FRESH execution process, never a live
// Agent/Main SQLite process. Caller retains input ownership; only duplicates
// are adopted. Slots: source db/wal/shm/journal, private new snapshot family.
// Main must select these capabilities and authenticate the receiver itself;
// shape/UID/PID checks are NOT permission to accept Agent-selected descriptors.
// Snapshot writable FDs must never have been given to an Agent. No path/SQL or
// directory FD is accepted. The expected owner comes from trusted Main policy.
// The FD VFS keeps registry duplicates: after ANY admitted operation the whole
// worker must retire before Main considers durable publication/read-only grants.
// This function does NOT publish, grant, attest process exit or implement IPC.
FdBackupReply runGrantedSqliteBackup(const std::array<int, 8>& descriptors, uid_t expectedOwner, pid_t ownerProcess,
                                     std::int64_t maxBytes, int timeoutMs,
                                     const std::atomic_bool* cancelled = nullptr);
}
