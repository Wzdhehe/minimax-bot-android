// SPDX-License-Identifier: AGPL-3.0-only
// Host prototype test only; Android multi-connection/WAL remains unverified.
#include <sqlite3.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <unistd.h>
#include <poll.h>
#include <cassert>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <cstdio>
#include <cerrno>
#ifdef NDEBUG
#error Assertions must stay enabled in this test
#endif
extern "C" int mbot_register_precreated_fd_vfs(const char* const*, const int*, int);
// GNU ld wraps only this host executable, never the Android/Node addon.
int sync_error = 0, sync_calls = 0;
extern "C" int __real_fsync(int);
extern "C" int __wrap_fsync(int fd) {
  ++sync_calls;
  if (sync_error) { errno = sync_error; sync_error = 0; return -1; }
  return __real_fsync(fd);
}
void sql(sqlite3* db, const char* query) { assert(sqlite3_exec(db, query, nullptr, nullptr, nullptr) == SQLITE_OK); }
int rows(sqlite3* db) {
  sqlite3_stmt* stmt = nullptr;
  assert(sqlite3_prepare_v2(db, "SELECT count(*) FROM notes", -1, &stmt, nullptr) == SQLITE_OK);
  assert(sqlite3_step(stmt) == SQLITE_ROW);
  const int count = sqlite3_column_int(stmt, 0); sqlite3_finalize(stmt); return count;
}
void receive(int fd) {
  pollfd ready{fd, POLLIN, 0};
  assert(poll(&ready, 1, 5000) == 1 && (ready.revents & POLLIN));
  char value; assert(read(fd, &value, 1) == 1 && value == 'R');
}
void integrity(sqlite3* db) {
  sqlite3_stmt* statement = nullptr;
  assert(sqlite3_prepare_v2(db, "PRAGMA integrity_check", -1, &statement, nullptr) == SQLITE_OK);
  assert(sqlite3_step(statement) == SQLITE_ROW);
  assert(std::strcmp(reinterpret_cast<const char*>(sqlite3_column_text(statement, 0)), "ok") == 0);
  assert(sqlite3_step(statement) == SQLITE_DONE); sqlite3_finalize(statement);
}
int main(int argc, char** argv) {
  if (argc == 6 || argc == 8) {
    int descriptors[4]; for (int i=0; i<4; ++i) descriptors[i] = std::atoi(argv[i+2]);
    const char* names[] = {"state.db", "state.db-wal", "state.db-shm", "state.db-journal"};
    assert(mbot_register_precreated_fd_vfs(names, descriptors, 4) == SQLITE_OK);
    sqlite3* db = nullptr;
    const bool reading = std::strcmp(argv[1], "reader") == 0;
    assert(sqlite3_open_v2("state.db", &db, reading ? SQLITE_OPEN_READONLY : SQLITE_OPEN_READWRITE, nullptr) == SQLITE_OK);
    if (std::strcmp(argv[1], "busy") == 0) {
      assert(sqlite3_exec(db, "BEGIN IMMEDIATE", nullptr, nullptr, nullptr) == SQLITE_BUSY);
      sqlite3_close(db); return 0;
    }
    if (reading) {
      assert(argc == 8);
      sql(db, "BEGIN"); assert(rows(db) == 2);
      assert(write(std::atoi(argv[6]), "R", 1) == 1);
      receive(std::atoi(argv[7]));
      assert(rows(db) == 2); // writer commit/close cannot change this snapshot.
      sql(db, "COMMIT"); sqlite3_close(db); return 0;
    }
    sql(db, "INSERT INTO notes(text) VALUES('crash-committed')");
    _exit(0); // no close/checkpoint/atexit: committed WAL must survive.
  }
  char name[] = "/tmp/mbot-vfs-XXXXXX";
  const char* physical = mkdtemp(name); assert(physical);
  const int directory = open(physical, O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(directory >= 0);
  const char* names[] = {"state.db", "state.db-wal", "state.db-shm", "state.db-journal"};
  std::vector<int> fds;
  for (const auto* filename : names) { const int fd = openat(directory, filename, O_CREAT|O_RDWR, 0600); assert(fd >= 0); fds.push_back(fd); }
  assert(mbot_register_precreated_fd_vfs(names, fds.data(), 3) == SQLITE_MISUSE);
  const int directory_as_file[] = {directory, fds[1], fds[2], fds[3]};
  assert(mbot_register_precreated_fd_vfs(names, directory_as_file, 4) == SQLITE_MISUSE);
  const char* duplicated[] = {"state.db", "state.db", "state.db-shm", "state.db-journal"};
  assert(mbot_register_precreated_fd_vfs(duplicated, fds.data(), 4) == SQLITE_MISUSE);
  for (int fd : fds) assert(fcntl(fd, F_GETFD) >= 0); // failed registration preserves caller ownership.
  for (int fd : fds) assert(fsync(fd) == 0);
  assert(fsync(directory) == 0); // owner persists entries; no directory grant.
  assert(mbot_register_precreated_fd_vfs(names, fds.data(), 4) == SQLITE_OK);
  auto* vfs = sqlite3_vfs_find("mbot-fd-prototype"); assert(vfs);
  assert(write(fds[3], "journal", 7) == 7);
  sync_error = EIO;
  assert(vfs->xDelete(vfs, "/mbot-data/state.db-journal", 1) == SQLITE_IOERR_DELETE);
  struct stat after_delete{}; assert(fstat(fds[3], &after_delete) == 0 && after_delete.st_size == 0);
  sync_error = EINTR; const int before_retry = sync_calls;
  assert(vfs->xDelete(vfs, "/mbot-data/state.db-journal", 1) == SQLITE_OK);
  assert(sync_calls == before_retry + 2); // interrupted sync must retry, not succeed silently.
  sqlite3* first = nullptr; sqlite3* second = nullptr;
  assert(sqlite3_open("state.db", &first) == SQLITE_OK);
  sql(first, "PRAGMA journal_mode=WAL; PRAGMA temp_store=MEMORY; CREATE TABLE notes(id INTEGER PRIMARY KEY, text TEXT); INSERT INTO notes(text) VALUES('你好')");
  assert(sqlite3_open_v2("state.db", &second, SQLITE_OPEN_READONLY, nullptr) == SQLITE_OK);
  assert(rows(second) == 1);
  sql(first, "BEGIN IMMEDIATE; INSERT INTO notes(text) VALUES('rollback'); ROLLBACK");
  assert(rows(second) == 1);
  sql(first, "BEGIN IMMEDIATE; INSERT INTO notes(text) VALUES('held')");
  sqlite3_close(second);
  auto child = [&](const char* mode) {
    const pid_t pid = fork(); assert(pid >= 0);
    if (pid == 0) {
      const auto a=std::to_string(fds[0]), b=std::to_string(fds[1]), c=std::to_string(fds[2]), e=std::to_string(fds[3]);
      execl(argv[0], argv[0], mode, a.c_str(), b.c_str(), c.c_str(), e.c_str(), static_cast<char*>(nullptr));
      _exit(99);
    }
    int status = 0; assert(waitpid(pid, &status, 0) == pid); assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
  };
  child("busy"); // closing a sibling connection must not drop the writer lock.
  sql(first, "ROLLBACK");
  sql(first, "INSERT INTO notes(text) VALUES('second')");
  assert(rows(first) == 2);
  sqlite3_close(first);
  assert(sqlite3_open("state.db", &first) == SQLITE_OK);
  assert(rows(first) == 2);
  sqlite3_close(first);
  // A separate process holds a read snapshot while the writer commits/closes.
  // WAL and SHM lifecycle must not discard a still-observed snapshot.
  int ready[2], release[2]; assert(pipe(ready) == 0 && pipe(release) == 0);
  const pid_t reader = fork(); assert(reader >= 0);
  if (reader == 0) {
    const auto a=std::to_string(fds[0]), b=std::to_string(fds[1]), c=std::to_string(fds[2]), e=std::to_string(fds[3]);
    const auto send=std::to_string(ready[1]), wait=std::to_string(release[0]);
    execl(argv[0], argv[0], "reader", a.c_str(), b.c_str(), c.c_str(), e.c_str(), send.c_str(), wait.c_str(), static_cast<char*>(nullptr));
    _exit(99);
  }
  receive(ready[0]);
  assert(sqlite3_open("state.db", &first) == SQLITE_OK);
  sql(first, "INSERT INTO notes(text) VALUES('reader-held')");
  int log_frames = -1, checkpointed = -1;
  assert(sqlite3_wal_checkpoint_v2(first, nullptr, SQLITE_CHECKPOINT_TRUNCATE, &log_frames, &checkpointed) == SQLITE_BUSY);
  sqlite3_close(first);
  assert(write(release[1], "R", 1) == 1);
  int reader_status = 0; assert(waitpid(reader, &reader_status, 0) == reader && WIFEXITED(reader_status) && WEXITSTATUS(reader_status) == 0);
  for (int fd : {ready[0], ready[1], release[0], release[1]}) close(fd);
  assert(sqlite3_open("state.db", &first) == SQLITE_OK);
  assert(rows(first) == 3); integrity(first); sqlite3_close(first);
  child("crash");
  assert(sqlite3_open("state.db", &first) == SQLITE_OK);
  assert(rows(first) == 4);
  integrity(first);
  sqlite3_close(first);
  sqlite3* denied = nullptr;
  const auto ungranted = std::string(physical) + "/not-granted.db";
  assert(sqlite3_open(ungranted.c_str(), &denied) != SQLITE_OK); sqlite3_close(denied);
  assert(access(ungranted.c_str(), F_OK) != 0);
  for (size_t i = 0; i < fds.size(); ++i) { close(fds[i]); unlinkat(directory, names[i], 0); }
  close(directory); rmdir(physical);
  std::puts("PASS: FD VFS WAL, multiple connections, external writer lock, external reader snapshot/checkpoint contention, delete sync failure/retry, rollback, reopen, crash recovery, integrity and ungranted-path refusal");
  return 0;
}
