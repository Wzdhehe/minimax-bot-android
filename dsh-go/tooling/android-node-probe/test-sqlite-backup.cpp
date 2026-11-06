// SPDX-License-Identifier: AGPL-3.0-only
// Host acceptance of the native backup kernel with real fixed FD SQLite VFS.
// No Android service/UID, publication or production launcher evidence here.
#include "backup.h"
#include <sqlite3.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <unistd.h>
#include <poll.h>
#include <cassert>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#ifdef NDEBUG
#error Assertions must stay enabled in this test
#endif
extern "C" int mbot_register_precreated_fd_vfs(const char* const*, const int*, int);
const char* names[] = {"state.db", "state.db-wal", "state.db-shm", "state.db-journal",
                       "snapshot.db", "snapshot.db-wal", "snapshot.db-shm", "snapshot.db-journal"};
using mbot::BackupResult;
void sql(sqlite3* db, const char* query) { assert(sqlite3_exec(db, query, nullptr, nullptr, nullptr) == SQLITE_OK); }
int scalar(sqlite3* db, const char* query) {
  sqlite3_stmt* statement = nullptr;
  assert(sqlite3_prepare_v2(db, query, -1, &statement, nullptr) == SQLITE_OK);
  assert(sqlite3_step(statement) == SQLITE_ROW);
  const int value = sqlite3_column_int(statement, 0);
  assert(sqlite3_step(statement) == SQLITE_DONE);
  assert(sqlite3_finalize(statement) == SQLITE_OK);
  return value;
}
void send(int fd) { assert(write(fd, "R", 1) == 1); }
void receive(int fd) {
  pollfd ready{fd, POLLIN, 0};
  assert(poll(&ready, 1, 5000) == 1 && (ready.revents & POLLIN));
  char byte; assert(read(fd, &byte, 1) == 1 && byte == 'R');
}
struct Trace { sqlite3* source; int trigger; int reply; bool fired = false; };
int afterRead(unsigned, void* data, void* statement, void*) {
  auto* state = static_cast<Trace*>(data);
  const char* query = sqlite3_sql(static_cast<sqlite3_stmt*>(statement));
  if (state->fired || !query || std::strcmp(query, "SELECT count(*) FROM sqlite_schema")) return 0;
  state->fired = true;
  assert(sqlite3_txn_state(state->source, "main") == SQLITE_TXN_READ);
  send(state->trigger); receive(state->reply);
  return 0;
}
int denyRead(void*, int action, const char*, const char*, const char*, const char*) {
  return action == SQLITE_READ ? SQLITE_DENY : SQLITE_OK;
}
int cancelAfterRead(unsigned, void* state, void* statement, void*) {
  const char* query = sqlite3_sql(static_cast<sqlite3_stmt*>(statement));
  if (query && !std::strcmp(query, "SELECT count(*) FROM sqlite_schema")) {
    static_cast<std::atomic_bool*>(state)->store(true);
  }
  return 0;
}
int integrityControl(void* state, int action, const char* name, const char*, const char*, const char*) {
  if (action != SQLITE_PRAGMA || !name || std::strcmp(name, "integrity_check")) return SQLITE_OK;
  if (state) { static_cast<std::atomic_bool*>(state)->store(true); return SQLITE_OK; }
  return SQLITE_DENY;
}

int main(int argc, char** argv) {
  if (argc == 8 && std::strcmp(argv[1], "writer") == 0) {
    int fds[4]; for (int i = 0; i < 4; ++i) fds[i] = std::atoi(argv[i+2]);
    assert(mbot_register_precreated_fd_vfs(names, fds, 4) == SQLITE_OK);
    sqlite3* writer = nullptr;
    assert(sqlite3_open_v2("state.db", &writer, SQLITE_OPEN_READWRITE, nullptr) == SQLITE_OK);
    send(std::atoi(argv[7])); receive(std::atoi(argv[6]));
    sql(writer, "BEGIN IMMEDIATE; UPDATE notes SET generation=2; CREATE TABLE grown(payload BLOB); INSERT INTO grown VALUES(zeroblob(131072)); COMMIT");
    send(std::atoi(argv[7]));
    assert(sqlite3_close(writer) == SQLITE_OK);
    return 0;
  }
  char folder[] = "/tmp/mbot-native-backup-XXXXXX";
  assert(mkdtemp(folder));
  const int directory = open(folder, O_RDONLY | O_DIRECTORY | O_CLOEXEC); assert(directory >= 0);
  int fds[8];
  for (int i = 0; i < 8; ++i) {
    fds[i] = openat(directory, names[i], O_CREAT | O_EXCL | O_RDWR, 0600);
    assert(fds[i] >= 0 && fsync(fds[i]) == 0);
  }
  assert(fsync(directory) == 0);
  assert(mbot_register_precreated_fd_vfs(names, fds, 8) == SQLITE_OK);
  sqlite3* writer = nullptr;
  assert(sqlite3_open("state.db", &writer) == SQLITE_OK);
  sql(writer, "PRAGMA journal_mode=WAL; CREATE TABLE notes(generation INTEGER, payload BLOB); INSERT INTO notes VALUES(1, zeroblob(8192)),(1, zeroblob(8192)),(1, zeroblob(8192))");
  sqlite3* source = nullptr; sqlite3* destination = nullptr;
  assert(sqlite3_open_v2("state.db", &source, SQLITE_OPEN_READONLY, nullptr) == SQLITE_OK);
  assert(sqlite3_open("snapshot.db", &destination) == SQLITE_OK);
  assert(mbot::copySqliteBackup(nullptr, destination, 65536, 1000) == BackupResult::invalid);
  assert(mbot::copySqliteBackup(source, source, 65536, 1000) == BackupResult::invalid);
  assert(mbot::copySqliteBackup(source, destination, 0, 1000) == BackupResult::invalid);
  assert(mbot::copySqliteBackup(source, destination, 513LL * 1024 * 1024, 1000) == BackupResult::invalid);
  assert(mbot::copySqliteBackup(source, destination, 65536, 0) == BackupResult::invalid);
  assert(mbot::copySqliteBackup(source, destination, 65536, 30001) == BackupResult::invalid);
  assert(mbot::copySqliteBackup(writer, destination, 65536, 1000) == BackupResult::source_not_readonly);
  sql(source, "BEGIN");
  assert(mbot::copySqliteBackup(source, destination, 65536, 1000) == BackupResult::invalid);
  assert(!sqlite3_get_autocommit(source)); sql(source, "ROLLBACK");
  assert(mbot::copySqliteBackup(source, destination, 4096, 1000) == BackupResult::too_large);
  assert(sqlite3_get_autocommit(source));
  assert(scalar(destination, "PRAGMA page_count") == 0);
  std::atomic_bool cancellation{true};
  assert(mbot::copySqliteBackup(source, destination, 65536, 1000, &cancellation) == BackupResult::cancelled);
  cancellation.store(false);
  sqlite3_trace_v2(source, SQLITE_TRACE_PROFILE, cancelAfterRead, &cancellation);
  assert(mbot::copySqliteBackup(source, destination, 65536, 1000, &cancellation) == BackupResult::cancelled);
  sqlite3_trace_v2(source, 0, nullptr, nullptr);
  assert(cancellation.load() && sqlite3_get_autocommit(source));
  assert(scalar(destination, "PRAGMA page_count") == 0);
  sqlite3_set_authorizer(source, denyRead, nullptr);
  assert(mbot::copySqliteBackup(source, destination, 65536, 1000) == BackupResult::copy_failed);
  sqlite3_set_authorizer(source, nullptr, nullptr);
  assert(sqlite3_get_autocommit(source));
  // These negative checks use private disposable in-memory destinations. The
  // positive copy below still uses real granted snapshot FDs across processes.
  sqlite3* rejected = nullptr;
  assert(sqlite3_open(":memory:", &rejected) == SQLITE_OK);
  sqlite3_set_authorizer(rejected, integrityControl, nullptr);
  assert(mbot::copySqliteBackup(source, rejected, 65536, 1000) == BackupResult::integrity_failed);
  assert(sqlite3_get_autocommit(source));
  assert(sqlite3_close(rejected) == SQLITE_OK);
  assert(sqlite3_open(":memory:", &rejected) == SQLITE_OK);
  std::atomic_bool lateCancellation{false};
  sqlite3_set_authorizer(rejected, integrityControl, &lateCancellation);
  assert(mbot::copySqliteBackup(source, rejected, 65536, 1000, &lateCancellation) == BackupResult::cancelled);
  assert(lateCancellation.load() && sqlite3_get_autocommit(source));
  // A valid-looking partial/result does not make a cancelled operation ok.
  sqlite3_set_authorizer(rejected, nullptr, nullptr);
  assert(mbot::copySqliteBackup(source, rejected, 65536, 1000) == BackupResult::destination_not_empty);
  assert(sqlite3_close(rejected) == SQLITE_OK);
  sqlite3* blocker = nullptr;
  assert(sqlite3_open("snapshot.db", &blocker) == SQLITE_OK);
  sql(blocker, "BEGIN EXCLUSIVE");
  const auto before = std::chrono::steady_clock::now();
  const auto busy = mbot::copySqliteBackup(source, destination, 65536, 20);
  assert(busy == BackupResult::busy || busy == BackupResult::timed_out);
  assert(std::chrono::steady_clock::now() - before < std::chrono::seconds(2));
  sql(blocker, "ROLLBACK"); assert(sqlite3_close(blocker) == SQLITE_OK);
  assert(sqlite3_get_autocommit(source));
  assert(scalar(destination, "PRAGMA page_count") == 0);
  assert(sqlite3_close(writer) == SQLITE_OK);

  int trigger[2], reply[2]; assert(pipe(trigger) == 0 && pipe(reply) == 0);
  const pid_t child = fork(); assert(child >= 0);
  if (!child) {
    close(trigger[1]); close(reply[0]);
    // The simulated Agent writer never receives the snapshot's writable FDs.
    for (int i = 4; i < 8; ++i) close(fds[i]);
    std::string args[6];
    for (int i = 0; i < 4; ++i) args[i] = std::to_string(fds[i]);
    args[4] = std::to_string(trigger[0]); args[5] = std::to_string(reply[1]);
    execl(argv[0], argv[0], "writer", args[0].c_str(), args[1].c_str(), args[2].c_str(), args[3].c_str(),
          args[4].c_str(), args[5].c_str(), static_cast<char*>(nullptr));
    _exit(99);
  }
  close(trigger[0]); close(reply[1]); receive(reply[0]);
  Trace trace{source, trigger[1], reply[0]};
  sqlite3_trace_v2(source, SQLITE_TRACE_PROFILE, afterRead, &trace);
  assert(mbot::copySqliteBackup(source, destination, 65536, 10000) == BackupResult::ok);
  sqlite3_trace_v2(source, 0, nullptr, nullptr);
  assert(trace.fired && sqlite3_get_autocommit(source));
  int status; assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
  close(trigger[1]); close(reply[0]);
  assert(scalar(destination, "SELECT count(*) FROM notes") == 3);
  assert(scalar(destination, "SELECT count(DISTINCT generation) FROM notes") == 1);
  assert(scalar(destination, "SELECT min(generation) FROM notes") == 1);
  assert(scalar(source, "SELECT min(generation) FROM notes") == 2);
  assert(scalar(source, "PRAGMA page_count") * scalar(source, "PRAGMA page_size") > 65536);
  assert(mbot::copySqliteBackup(source, destination, 65536, 1000) == BackupResult::destination_not_empty);
  assert(scalar(destination, "SELECT min(generation) FROM notes") == 1);
  assert(sqlite3_close(source) == SQLITE_OK && sqlite3_close(destination) == SQLITE_OK);
  for (int i = 4; i < 8; ++i) assert(fsync(fds[i]) == 0);
  sqlite3* checked = nullptr;
  assert(sqlite3_open_v2("snapshot.db", &checked, SQLITE_OPEN_READONLY, nullptr) == SQLITE_OK);
  assert(scalar(checked, "SELECT count(*) FROM notes") == 3);
  assert(sqlite3_close(checked) == SQLITE_OK);
  for (int fd : fds) close(fd);
  for (const char* name : names) assert(unlinkat(directory, name, 0) == 0);
  close(directory); assert(rmdir(folder) == 0);
  puts("PASS: native SQLite backup via held FDs, read-only source, frozen WAL snapshot despite concurrent growth, budgets/cancellation/busy/error cleanup, no overwrite and read-only reopen; host only");
}
