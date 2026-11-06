// SPDX-License-Identifier: AGPL-3.0-only
#pragma once
#include <cstdint>
#include <atomic>
struct sqlite3;

namespace mbot {
enum class BackupResult {
  ok, invalid, source_not_readonly, destination_not_empty, too_large,
  busy, timed_out, cancelled, copy_failed, integrity_failed,
};

// Trusted native composition only, NOT an IPC method or a pathname API.
// Caller exclusively owns these fresh, dedicated connections and must close
// them afterwards. Source is read-only; destination is newly created/empty,
// private and NEVER writable by an Agent. No other thread uses either handle.
// This copies/checks SQLite only: caller still owns FD admission, final close,
// file/directory fsync, durable publication, read-only grants and cancellation
// of the execution unit. An optional one-way cancellation flag must outlive
// this call. Never publish partial output after a non-ok result. Time limits
// interrupt SQLite work/waits; they cannot bound an uninterruptible OS I/O.
BackupResult copySqliteBackup(sqlite3* source, sqlite3* destination,
                              std::int64_t maxBytes, int timeoutMs,
                              const std::atomic_bool* cancelled = nullptr);
}
