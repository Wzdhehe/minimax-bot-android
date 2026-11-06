// SPDX-License-Identifier: AGPL-3.0-only
// Host-only worker composition. The parent never opens SQLite; shape checks do
// not prove Android UID authentication, source policy or durable publication.
#include "fd_backup.h"
#include <sqlite3.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <unistd.h>
#include <array>
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <cstring>
#include <cerrno>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <chrono>
#ifdef NDEBUG
#error Assertions must stay enabled in this test
#endif
extern "C" int mbot_register_precreated_fd_vfs(const char* const*, const int*, int);
const char* names[] = {"state.db", "state.db-wal", "state.db-shm", "state.db-journal",
                       "snapshot.db", "snapshot.db-wal", "snapshot.db-shm", "snapshot.db-journal"};
using mbot::BackupResult;
using mbot::FdBackupStage;
// Host link-time faults only. The kernel accepts no injected syscall callback.
bool failSync = false, interruptSync = false, failVerify = false, faultSeen = false;
int retired = 0, syncCalls = 0;
thread_local bool pauseNextStat = false;
std::mutex pauseMutex;
std::condition_variable pauseCondition;
bool reachedAdmission = false, releaseAdmission = false;
extern "C" int __real_fsync(int);
extern "C" int __real_fstat(int, struct stat*);
extern "C" int __real_sqlite3_close(sqlite3*);
extern "C" int __real_sqlite3_prepare_v2(sqlite3*, const char*, int, sqlite3_stmt**, const char**);
extern "C" int __wrap_sqlite3_close(sqlite3* db) {
  const int result = __real_sqlite3_close(db);
  if (result == SQLITE_OK) ++retired;
  return result;
}
extern "C" int __wrap_fstat(int fd, struct stat* info) {
  if (pauseNextStat) {
    pauseNextStat = false;
    std::unique_lock<std::mutex> lock(pauseMutex);
    reachedAdmission = true; pauseCondition.notify_all();
    assert(pauseCondition.wait_for(lock, std::chrono::seconds(5), [] { return releaseAdmission; }));
  }
  return __real_fstat(fd, info);
}
extern "C" int __wrap_fsync(int fd) {
  if (retired >= 2) {
    ++syncCalls;
    if (!faultSeen && (failSync || interruptSync)) {
      faultSeen = true; errno = failSync ? EIO : EINTR; return -1;
    }
  }
  return __real_fsync(fd);
}
extern "C" int __wrap_sqlite3_prepare_v2(sqlite3* db, const char* sql, int length, sqlite3_stmt** statement, const char** tail) {
  if (failVerify && retired >= 2 && sql && !std::strcmp(sql, "PRAGMA integrity_check")) {
    faultSeen = true; *statement = nullptr; return SQLITE_AUTH;
  }
  return __real_sqlite3_prepare_v2(db, sql, length, statement, tail);
}
void sql(sqlite3* db, const char* query) { assert(sqlite3_exec(db, query, nullptr, nullptr, nullptr) == SQLITE_OK); }
int rows(sqlite3* db) {
  sqlite3_stmt* statement = nullptr;
  assert(sqlite3_prepare_v2(db, "SELECT count(*) FROM notes", -1, &statement, nullptr) == SQLITE_OK);
  assert(sqlite3_step(statement) == SQLITE_ROW);
  const int count = sqlite3_column_int(statement, 0);
  assert(sqlite3_step(statement) == SQLITE_DONE && sqlite3_finalize(statement) == SQLITE_OK);
  return count;
}

int main(int argc, char** argv) {
  if (argc == 10) {
    std::array<int, 8> fds;
    for (int i = 0; i < 8; ++i) fds[i] = std::atoi(argv[i+2]);
    const std::string mode(argv[1]);
    if (mode == "seed" || mode == "verify") {
      assert(mbot_register_precreated_fd_vfs(names, fds.data(), 8) == SQLITE_OK);
      sqlite3* db = nullptr;
      assert(sqlite3_open_v2(mode == "seed" ? "state.db" : "snapshot.db", &db,
        mode == "seed" ? SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE : SQLITE_OPEN_READONLY, nullptr) == SQLITE_OK);
      if (mode == "seed") sql(db, "PRAGMA journal_mode=WAL; CREATE TABLE notes(text TEXT, data BLOB); INSERT INTO notes VALUES('before',zeroblob(8192)),('before',zeroblob(8192))");
      else {
        assert(mbot::verifySqliteBackup(db, 1000) == BackupResult::ok);
        assert(rows(db) == 2);
      }
      assert(sqlite3_close(db) == SQLITE_OK);
      return 0;
    }
    auto invoke = [&](const std::array<int, 8>& grant, uid_t owner = getuid(), std::int64_t budget = 65536, int timeout = 1000) {
      return mbot::runGrantedSqliteBackup(grant, owner, getppid(), budget, timeout);
    };
    if (mode == "invalid") {
      assert(mbot::runGrantedSqliteBackup(fds, getuid(), getpid(), 65536, 1000).stage == FdBackupStage::invalid_worker);
      assert(invoke(fds, getuid() + 1).stage == FdBackupStage::invalid_grant);
      auto bad = fds; bad[4] = bad[0];
      assert(invoke(bad).stage == FdBackupStage::invalid_grant);
      bad = fds; bad[0] = -1;
      assert(invoke(bad).stage == FdBackupStage::invalid_grant);
      int pipeFds[2]; assert(pipe(pipeFds) == 0);
      bad = fds; bad[4] = pipeFds[0];
      assert(invoke(bad).stage == FdBackupStage::invalid_grant);
      close(pipeFds[0]); close(pipeFds[1]);
      const int directory = open("/tmp", O_RDONLY | O_DIRECTORY | O_CLOEXEC); assert(directory >= 0);
      bad = fds; bad[4] = directory;
      assert(invoke(bad).stage == FdBackupStage::invalid_grant);
      close(directory);
      const std::string path = "/proc/self/fd/" + std::to_string(fds[4]);
      const int readOnly = open(path.c_str(), O_RDONLY | O_CLOEXEC); assert(readOnly >= 0);
      bad = fds; bad[4] = readOnly;
      assert(invoke(bad).stage == FdBackupStage::invalid_grant);
      close(readOnly);
#ifdef O_PATH
      const int pathOnly = open(("/proc/self/fd/" + std::to_string(fds[0])).c_str(), O_PATH | O_CLOEXEC);
      assert(pathOnly >= 0); bad = fds; bad[0] = pathOnly;
      assert(invoke(bad).stage == FdBackupStage::invalid_grant);
      close(pathOnly);
#endif
      assert(fchmod(fds[4], 0644) == 0);
      assert(invoke(fds).stage == FdBackupStage::invalid_grant);
      assert(fchmod(fds[4], 0600) == 0);
      const int flags = fcntl(fds[4], F_GETFL); assert(flags >= 0);
      assert(fcntl(fds[4], F_SETFL, flags | O_APPEND) == 0);
      assert(invoke(fds).stage == FdBackupStage::invalid_grant);
      assert(fcntl(fds[4], F_SETFL, flags) == 0);
      assert(write(fds[4], "x", 1) == 1);
      assert(invoke(fds).stage == FdBackupStage::invalid_grant);
      assert(ftruncate(fds[4], 0) == 0);
      std::atomic_bool cancelled{true};
      const auto refusal = mbot::runGrantedSqliteBackup(fds, getuid(), getppid(), 65536, 1000, &cancelled);
      assert(refusal.result == BackupResult::cancelled);
      for (int fd : fds) assert(fcntl(fd, F_GETFD) >= 0);
      return 0;
    }
    if (mode == "hardlink" || mode == "nonempty") {
      assert(invoke(fds).stage == FdBackupStage::invalid_grant);
      return 0;
    }
    if (mode == "small") {
      const auto result = invoke(fds, getuid(), 4096);
      assert(result.stage == FdBackupStage::copy_failed && result.result == BackupResult::too_large);
      for (int i = 4; i < 8; ++i) { struct stat info{}; assert(fstat(fds[i], &info) == 0 && info.st_size == 0); }
      return 0;
    }
    if (mode == "racing") {
      // Pause after the first duplicate, before VFS registration. A separate
      // thread must refuse immediately, without admitting another FD family.
      assert(sqlite3_vfs_find("unix"));
      mbot::FdBackupReply first{FdBackupStage::invalid_grant, BackupResult::invalid, 0};
      std::thread active([&] { pauseNextStat = true; first = invoke(fds, getuid(), 65536, 10000); });
      {
        std::unique_lock<std::mutex> lock(pauseMutex);
        assert(pauseCondition.wait_for(lock, std::chrono::seconds(5), [] { return reachedAdmission; }));
      }
      assert(invoke(fds).stage == FdBackupStage::registry_unavailable);
      { std::lock_guard<std::mutex> lock(pauseMutex); releaseAdmission = true; }
      pauseCondition.notify_all(); active.join();
      assert(first.stage == FdBackupStage::ok && first.bytes > 0);
      return 0;
    }
    assert(mode == "copy" || mode == "sync-fail" || mode == "sync-interrupt" || mode == "verify-fail");
    failSync = mode == "sync-fail";
    interruptSync = mode == "sync-interrupt";
    failVerify = mode == "verify-fail";
    const auto result = invoke(fds);
    if (failSync) assert(faultSeen && result.stage == FdBackupStage::sync_failed && result.bytes == 0);
    else if (failVerify) assert(faultSeen && result.stage == FdBackupStage::verification_failed && result.result == BackupResult::integrity_failed);
    else {
      assert(result.stage == FdBackupStage::ok && result.result == BackupResult::ok && result.bytes > 0 && result.bytes <= 65536);
      if (interruptSync) assert(faultSeen && syncCalls >= 5);
    }
    // The registry keeps duplicates: it cannot be safely reused as another run.
    assert(invoke(fds).stage == FdBackupStage::registry_unavailable);
    for (int fd : fds) assert(fcntl(fd, F_GETFD) >= 0);
    return 0;
  }
  char folder[] = "/tmp/mbot-backup-grants-XXXXXX"; assert(mkdtemp(folder));
  const int directory = open(folder, O_RDONLY | O_DIRECTORY | O_CLOEXEC); assert(directory >= 0);
  std::array<int, 8> fds;
  for (int i = 0; i < 8; ++i) {
    fds[i] = openat(directory, names[i], O_RDWR | O_CREAT | O_EXCL, 0600);
    assert(fds[i] >= 0 && fsync(fds[i]) == 0);
  }
  assert(fsync(directory) == 0);
  auto child = [&](const char* mode) {
    const pid_t pid = fork(); assert(pid >= 0);
    if (!pid) {
      std::array<std::string, 8> text;
      for (int i = 0; i < 8; ++i) text[i] = std::to_string(fds[i]);
      execl(argv[0], argv[0], mode, text[0].c_str(), text[1].c_str(), text[2].c_str(), text[3].c_str(),
        text[4].c_str(), text[5].c_str(), text[6].c_str(), text[7].c_str(), static_cast<char*>(nullptr));
      _exit(99);
    }
    int status; assert(waitpid(pid, &status, 0) == pid && WIFEXITED(status) && WEXITSTATUS(status) == 0);
  };
  child("seed");
  child("invalid");
  assert(linkat(directory, names[4], directory, "alias", 0) == 0);
  child("hardlink"); assert(unlinkat(directory, "alias", 0) == 0);
  auto freshSnapshot = [&]() {
    for (int i = 4; i < 8; ++i) {
      close(fds[i]); assert(unlinkat(directory, names[i], 0) == 0);
      fds[i] = openat(directory, names[i], O_RDWR | O_CREAT | O_EXCL, 0600);
      assert(fds[i] >= 0 && fsync(fds[i]) == 0);
    }
    assert(fsync(directory) == 0);
  };
  // Main can attest that worker process exited before inspecting/publishing.
  child("small");
  child("sync-fail"); child("nonempty"); freshSnapshot();
  child("verify-fail"); child("nonempty"); freshSnapshot();
  child("sync-interrupt"); child("verify"); freshSnapshot();
  child("racing"); child("verify"); freshSnapshot();
  child("copy"); child("verify");
  for (int fd : fds) { assert(fcntl(fd, F_GETFD) >= 0); close(fd); }
  for (const char* name : names) assert(unlinkat(directory, name, 0) == 0);
  close(directory); assert(rmdir(folder) == 0);
  puts("PASS: granted-FD backup worker admission, private source/snapshot ownership, budget/cancel refusal, close/sync/read-only verification, process retirement and caller FD preservation; host only");
}
