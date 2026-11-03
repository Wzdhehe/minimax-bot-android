// SPDX-License-Identifier: AGPL-3.0-only
// Host prototype test only; Android multi-connection/WAL remains unverified.
#include <sqlite3.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <unistd.h>
#include <cassert>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <cstdio>
#ifdef NDEBUG
#error Assertions must stay enabled in this test
#endif
extern "C" int mbot_register_fd_vfs(int, const char* const*, const int*, int);
void sql(sqlite3* db, const char* query) { assert(sqlite3_exec(db, query, nullptr, nullptr, nullptr) == SQLITE_OK); }
int rows(sqlite3* db) {
  sqlite3_stmt* stmt = nullptr;
  assert(sqlite3_prepare_v2(db, "SELECT count(*) FROM notes", -1, &stmt, nullptr) == SQLITE_OK);
  assert(sqlite3_step(stmt) == SQLITE_ROW);
  const int count = sqlite3_column_int(stmt, 0); sqlite3_finalize(stmt); return count;
}
void integrity(sqlite3* db) {
  sqlite3_stmt* statement = nullptr;
  assert(sqlite3_prepare_v2(db, "PRAGMA integrity_check", -1, &statement, nullptr) == SQLITE_OK);
  assert(sqlite3_step(statement) == SQLITE_ROW);
  assert(std::strcmp(reinterpret_cast<const char*>(sqlite3_column_text(statement, 0)), "ok") == 0);
  assert(sqlite3_step(statement) == SQLITE_DONE); sqlite3_finalize(statement);
}
int main(int argc, char** argv) {
  if (argc == 7) {
    const int directory = std::atoi(argv[2]);
    int descriptors[4]; for (int i=0; i<4; ++i) descriptors[i] = std::atoi(argv[i+3]);
    const char* names[] = {"state.db", "state.db-wal", "state.db-shm", "state.db-journal"};
    assert(mbot_register_fd_vfs(directory, names, descriptors, 4) == SQLITE_OK);
    sqlite3* db = nullptr; assert(sqlite3_open("state.db", &db) == SQLITE_OK);
    if (std::strcmp(argv[1], "busy") == 0) {
      assert(sqlite3_exec(db, "BEGIN IMMEDIATE", nullptr, nullptr, nullptr) == SQLITE_BUSY);
      sqlite3_close(db); return 0;
    }
    sql(db, "INSERT INTO notes(text) VALUES('crash-committed')");
    _exit(0); // no close/checkpoint/atexit: committed WAL must survive.
  }
  char name[] = "/tmp/mbot-vfs-XXXXXX";
  const char* physical = mkdtemp(name); assert(physical);
  const int directory = open(physical, O_RDONLY|O_DIRECTORY); assert(directory >= 0);
  const char* names[] = {"state.db", "state.db-wal", "state.db-shm", "state.db-journal"};
  std::vector<int> fds;
  for (const auto* filename : names) { const int fd = openat(directory, filename, O_CREAT|O_RDWR, 0600); assert(fd >= 0); fds.push_back(fd); }
  assert(mbot_register_fd_vfs(directory, names, fds.data(), 3) == SQLITE_MISUSE);
  assert(mbot_register_fd_vfs(fds[0], names, fds.data(), 4) == SQLITE_MISUSE);
  const char* duplicated[] = {"state.db", "state.db", "state.db-shm", "state.db-journal"};
  assert(mbot_register_fd_vfs(directory, duplicated, fds.data(), 4) == SQLITE_MISUSE);
  for (int fd : fds) assert(fcntl(fd, F_GETFD) >= 0); // failed registration preserves caller ownership.
  assert(mbot_register_fd_vfs(directory, names, fds.data(), 4) == SQLITE_OK);
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
      const auto d=std::to_string(directory), a=std::to_string(fds[0]), b=std::to_string(fds[1]), c=std::to_string(fds[2]), e=std::to_string(fds[3]);
      execl(argv[0], argv[0], mode, d.c_str(), a.c_str(), b.c_str(), c.c_str(), e.c_str(), static_cast<char*>(nullptr));
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
  child("crash");
  assert(sqlite3_open("state.db", &first) == SQLITE_OK);
  assert(rows(first) == 3);
  integrity(first);
  sqlite3_close(first);
  sqlite3* denied = nullptr;
  const auto ungranted = std::string(physical) + "/not-granted.db";
  assert(sqlite3_open(ungranted.c_str(), &denied) != SQLITE_OK); sqlite3_close(denied);
  assert(access(ungranted.c_str(), F_OK) != 0);
  for (size_t i = 0; i < fds.size(); ++i) { close(fds[i]); unlinkat(directory, names[i], 0); }
  close(directory); rmdir(physical);
  std::puts("PASS: FD VFS WAL, multiple connections, external writer lock, rollback, reopen, crash recovery, integrity and ungranted-path refusal");
  return 0;
}
