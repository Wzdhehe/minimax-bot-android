// SPDX-License-Identifier: AGPL-3.0-only
#include "fd_backup.h"
#include <sqlite3.h>
#include <sys/stat.h>
#include <unistd.h>
#include <fcntl.h>
#include <cerrno>
#include <chrono>
#include <set>
#include <utility>
#include <mutex>

extern "C" int mbot_register_precreated_fd_vfs(const char* const*, const int*, int);
namespace mbot {
namespace {
using Clock = std::chrono::steady_clock;
std::mutex admission;
struct Files {
  std::array<int, 8> fds;
  Files() { fds.fill(-1); }
  ~Files() { for (int fd : fds) if (fd >= 0) close(fd); }
};
struct Database {
  sqlite3* db = nullptr;
  ~Database() { if (db) sqlite3_close_v2(db); }
  bool retire() {
    if (!db) return true;
    if (sqlite3_close(db) != SQLITE_OK) return false;
    db = nullptr; return true;
  }
};
FdBackupReply failure(FdBackupStage stage, BackupResult result = BackupResult::invalid) { return {stage, result, 0}; }
}

FdBackupReply runGrantedSqliteBackup(const std::array<int, 8>& descriptors, uid_t expectedOwner, pid_t ownerProcess,
                                     std::int64_t maxBytes, int timeoutMs, const std::atomic_bool* cancelled) {
  try {
    if (ownerProcess <= 0 || ownerProcess == getpid()) return failure(FdBackupStage::invalid_worker);
    // Serialize admission through teardown. A concurrent loser must not dup
    // and close aliases while the admitted operation owns SQLite locks.
    std::unique_lock<std::mutex> sole(admission, std::try_to_lock);
    if (!sole.owns_lock()) return failure(FdBackupStage::registry_unavailable);
    if (maxBytes < 4096 || maxBytes > 512LL * 1024 * 1024 || timeoutMs < 1 || timeoutMs > 30000) {
      return failure(FdBackupStage::invalid_grant);
    }
    // Reject reuse BEFORE creating/closing aliases to any live SQLite files.
    // This known-registry check does not replace the fresh-process precondition.
    if (sqlite3_vfs_find("mbot-fd-prototype")) return failure(FdBackupStage::registry_unavailable);
    const auto deadline = Clock::now() + std::chrono::milliseconds(timeoutMs);
    auto stopped = [&]() { return (cancelled && cancelled->load(std::memory_order_relaxed)) || Clock::now() >= deadline; };
    auto stopResult = [&]() { return cancelled && cancelled->load(std::memory_order_relaxed) ? BackupResult::cancelled : BackupResult::timed_out; };
    auto remaining = [&]() { return static_cast<int>(std::chrono::duration_cast<std::chrono::milliseconds>(deadline - Clock::now()).count()); };
    if (stopped()) return failure(FdBackupStage::copy_failed, stopResult());
    Files owned;
    std::set<std::pair<dev_t, ino_t>> identities;
    for (int i = 0; i < 8; ++i) {
      if (descriptors[i] < 3) return failure(FdBackupStage::invalid_grant);
      // Inspect duplicates, never a caller FD that could be recycled midway.
      const int fd = fcntl(descriptors[i], F_DUPFD_CLOEXEC, 3);
      if (fd < 0) return failure(FdBackupStage::invalid_grant);
      owned.fds[i] = fd;
      struct stat info{};
      const int flags = fcntl(fd, F_GETFL);
      const int access = flags & O_ACCMODE;
      if (fstat(fd, &info) != 0 || !S_ISREG(info.st_mode) || info.st_uid != expectedOwner ||
          info.st_nlink != 1 || (info.st_mode & 07777) != 0600 || info.st_size < 0 ||
          !identities.emplace(info.st_dev, info.st_ino).second || flags < 0 ||
          (access != O_RDONLY && access != O_RDWR) || (flags & O_APPEND) ||
          (i >= 4 && (access != O_RDWR || info.st_size != 0))) {
        return failure(FdBackupStage::invalid_grant);
      }
#ifdef O_PATH
      if (flags & O_PATH) return failure(FdBackupStage::invalid_grant);
#endif
#ifdef O_DIRECT
      if (flags & O_DIRECT) return failure(FdBackupStage::invalid_grant);
#endif
    }
    if (stopped() || remaining() < 1) return failure(FdBackupStage::copy_failed, stopResult());
    const char* names[] = {"state.db", "state.db-wal", "state.db-shm", "state.db-journal",
                           "snapshot.db", "snapshot.db-wal", "snapshot.db-shm", "snapshot.db-journal"};
    if (mbot_register_precreated_fd_vfs(names, owned.fds.data(), 8) != SQLITE_OK) {
      return failure(FdBackupStage::registry_unavailable);
    }
    Database source, destination;
    if (sqlite3_open_v2("state.db", &source.db, SQLITE_OPEN_READONLY, nullptr) != SQLITE_OK ||
        sqlite3_open_v2("snapshot.db", &destination.db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE, nullptr) != SQLITE_OK) {
      return failure(FdBackupStage::open_failed);
    }
    if (stopped() || remaining() < 1) return failure(FdBackupStage::copy_failed, stopResult());
    const auto copied = copySqliteBackup(source.db, destination.db, maxBytes, remaining(), cancelled);
    if (copied != BackupResult::ok) return failure(FdBackupStage::copy_failed, copied);
    // Close BOTH connections before syncing or readonly validation. All error
    // paths still close handles before owned FD aliases can be destroyed.
    if (!destination.retire() || !source.retire()) return failure(FdBackupStage::close_failed);
    struct stat snapshot{};
    if (fstat(owned.fds[4], &snapshot) != 0 || snapshot.st_size <= 0 || snapshot.st_size > maxBytes) {
      return failure(FdBackupStage::verification_failed);
    }
    for (int i = 4; i < 8; ++i) {
      if (stopped() || remaining() < 1) return failure(FdBackupStage::sync_failed, stopResult());
      int result;
      do { result = fsync(owned.fds[i]); } while (result < 0 && errno == EINTR && !stopped());
      if (result != 0) return failure(FdBackupStage::sync_failed, stopped() ? stopResult() : BackupResult::copy_failed);
    }
    if (stopped() || remaining() < 1) return failure(FdBackupStage::verification_failed, stopResult());
    Database checked;
    if (sqlite3_open_v2("snapshot.db", &checked.db, SQLITE_OPEN_READONLY, nullptr) != SQLITE_OK) {
      return failure(FdBackupStage::verification_failed);
    }
    if (stopped() || remaining() < 1) return failure(FdBackupStage::verification_failed, stopResult());
    const auto verified = verifySqliteBackup(checked.db, remaining(), cancelled);
    if (verified != BackupResult::ok) return failure(FdBackupStage::verification_failed, verified);
    if (!checked.retire()) return failure(FdBackupStage::close_failed);
    if (stopped()) return failure(FdBackupStage::verification_failed, stopResult());
    return {FdBackupStage::ok, BackupResult::ok, snapshot.st_size};
  } catch (...) { return failure(FdBackupStage::copy_failed, BackupResult::copy_failed); }
}
}
