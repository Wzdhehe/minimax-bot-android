// SPDX-License-Identifier: AGPL-3.0-only
// Real Linux SQLite/FD acceptance only, not Android isolation evidence.
#include <sqlite3.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <cassert>
#include <cstring>
#include <cstdlib>
#include <string>
#include <vector>
#include <cstdio>
#ifdef NDEBUG
#error Assertions must remain enabled
#endif
extern "C" int mbot_register_precreated_fd_vfs(const char* const*, const int*, int);
extern "C" int mbot_register_readonly_snapshot_fd(int, char*, int);
// Fault injection only in this host test. Production has no injected metadata.
int ownerMismatchFd = -1, raceOriginalFd = -1, retainedRaceFd = -1;
extern "C" int __real_fstat(int, struct stat*);
extern "C" int __wrap_fstat(int fd, struct stat* info) {
  const int result = __real_fstat(fd, info);
  if (result != 0) return result;
  if (fd == ownerMismatchFd) ++info->st_uid;
  if (raceOriginalFd >= 0 && fd != raceOriginalFd) {
    struct stat original{};
    assert(__real_fstat(raceOriginalFd, &original) == 0);
    if (info->st_dev == original.st_dev && info->st_ino == original.st_ino) {
      retainedRaceFd = fd; raceOriginalFd = -1; ++info->st_uid;
    }
  }
  return result;
}

void sql(sqlite3* db, const char* text) { assert(sqlite3_exec(db, text, nullptr, nullptr, nullptr) == SQLITE_OK); }
void verify(sqlite3* db) {
  sqlite3_stmt* stmt = nullptr;
  assert(sqlite3_prepare_v2(db, "SELECT text FROM notes", -1, &stmt, nullptr) == SQLITE_OK);
  assert(sqlite3_step(stmt) == SQLITE_ROW);
  assert(std::strcmp(reinterpret_cast<const char*>(sqlite3_column_text(stmt, 0)), "真实快照") == 0);
  assert(sqlite3_step(stmt) == SQLITE_DONE); sqlite3_finalize(stmt);
  assert(sqlite3_prepare_v2(db, "PRAGMA integrity_check", -1, &stmt, nullptr) == SQLITE_OK);
  assert(sqlite3_step(stmt) == SQLITE_ROW);
  assert(std::strcmp(reinterpret_cast<const char*>(sqlite3_column_text(stmt, 0)), "ok") == 0);
  sqlite3_finalize(stmt);
  assert(sqlite3_db_readonly(db, "main") == 1);
  assert(sqlite3_exec(db, "INSERT INTO notes VALUES ('refused')", nullptr, nullptr, nullptr) == SQLITE_READONLY);
}
int main() {
  char directory[] = "/tmp/mbot-ro-snapshot-XXXXXX";
  assert(mkdtemp(directory));
  const std::string root(directory), physical = root + "/published.sqlite";
  // Trusted owner fixture creates/checkpoints the database before VFS install.
  sqlite3* seed = nullptr;
  assert(sqlite3_open(physical.c_str(), &seed) == SQLITE_OK);
  sql(seed, "PRAGMA journal_mode=DELETE; CREATE TABLE notes(text TEXT); INSERT INTO notes VALUES ('真实快照')");
  assert(sqlite3_close(seed) == SQLITE_OK);
  assert(chmod(physical.c_str(), 0600) == 0);
  std::vector<std::string> paths{physical};
  const int snapshot = open(physical.c_str(), O_RDONLY | O_CLOEXEC); assert(snapshot >= 3);
  const int writable = open(physical.c_str(), O_RDWR | O_CLOEXEC); assert(writable >= 3);
  char name[64]{};
  assert(mbot_register_readonly_snapshot_fd(snapshot, name, sizeof(name)) == SQLITE_MISUSE);
  const char* family[] = {"state.db", "state.db-wal", "state.db-shm", "state.db-journal"};
  std::vector<int> source;
  for (const auto* item : family) {
    paths.push_back(root + "/" + item);
    source.push_back(open(paths.back().c_str(), O_CREAT | O_EXCL | O_RDWR, 0600)); assert(source.back() >= 3);
  }
  assert(mbot_register_precreated_fd_vfs(family, source.data(), 4) == SQLITE_OK);
  assert(mbot_register_readonly_snapshot_fd(-1, name, sizeof(name)) == SQLITE_MISUSE);
  assert(mbot_register_readonly_snapshot_fd(snapshot, nullptr, sizeof(name)) == SQLITE_MISUSE);
  assert(mbot_register_readonly_snapshot_fd(snapshot, name, 1) == SQLITE_MISUSE);
  assert(mbot_register_readonly_snapshot_fd(writable, name, sizeof(name)) == SQLITE_MISUSE);
  const int dir = open(directory, O_RDONLY | O_DIRECTORY); assert(dir >= 3);
  assert(mbot_register_readonly_snapshot_fd(dir, name, sizeof(name)) == SQLITE_MISUSE); close(dir);
  assert(fchmod(writable, 0640) == 0);
  assert(mbot_register_readonly_snapshot_fd(snapshot, name, sizeof(name)) == SQLITE_MISUSE);
  assert(fchmod(writable, 02600) == 0);
  assert(mbot_register_readonly_snapshot_fd(snapshot, name, sizeof(name)) == SQLITE_MISUSE);
  assert(fchmod(writable, 0600) == 0);
  const auto empty = root + "/empty.sqlite"; paths.push_back(empty);
  int emptyFd = open(empty.c_str(), O_CREAT | O_EXCL | O_RDONLY, 0600); assert(emptyFd >= 3);
  assert(mbot_register_readonly_snapshot_fd(emptyFd, name, sizeof(name)) == SQLITE_MISUSE); close(emptyFd);
  int pipeFds[2]; assert(pipe(pipeFds) == 0);
  assert(mbot_register_readonly_snapshot_fd(pipeFds[0], name, sizeof(name)) == SQLITE_MISUSE);
  close(pipeFds[0]); close(pipeFds[1]);
#ifdef O_PATH
  const int pathOnly = open(physical.c_str(), O_PATH); assert(pathOnly >= 3);
  assert(mbot_register_readonly_snapshot_fd(pathOnly, name, sizeof(name)) == SQLITE_MISUSE); close(pathOnly);
#endif
  const auto hardlink = root + "/alias.sqlite";
  assert(link(physical.c_str(), hardlink.c_str()) == 0);
  assert(mbot_register_readonly_snapshot_fd(snapshot, name, sizeof(name)) == SQLITE_MISUSE);
  assert(unlink(hardlink.c_str()) == 0);
  // Even a read-only alias of a mutable primary file is not a snapshot. The
  // rejected descriptor remains owned by its caller and was never closed.
  assert(write(source[0], "primary", 7) == 7);
  const int alias = open(paths[1].c_str(), O_RDONLY); assert(alias >= 3);
  assert(mbot_register_readonly_snapshot_fd(alias, name, sizeof(name)) == SQLITE_MISUSE);
  assert(fcntl(alias, F_GETFD) >= 0); close(alias);
  ownerMismatchFd = snapshot;
  assert(mbot_register_readonly_snapshot_fd(snapshot, name, sizeof(name)) == SQLITE_MISUSE);
  ownerMismatchFd = -1;
  raceOriginalFd = snapshot;
  assert(mbot_register_readonly_snapshot_fd(snapshot, name, sizeof(name)) == SQLITE_MISUSE);
  assert(retainedRaceFd >= 3 && fcntl(retainedRaceFd, F_GETFD) >= 0);
  assert(mbot_register_readonly_snapshot_fd(snapshot, name, sizeof(name)) == SQLITE_OK);
  assert(std::strcmp(name, "snapshot-1.db") == 0);
  char cached[64]{};
  assert(mbot_register_readonly_snapshot_fd(snapshot, cached, sizeof(cached)) == SQLITE_OK);
  assert(std::strcmp(name, cached) == 0);
  close(snapshot); close(writable); // Registry now owns its independent RO copy.
  sqlite3* reader = nullptr;
  assert(sqlite3_open_v2(name, &reader, SQLITE_OPEN_READONLY, nullptr) == SQLITE_OK);
  verify(reader); assert(sqlite3_close(reader) == SQLITE_OK);
  auto* vfs = sqlite3_vfs_find("mbot-fd-prototype"); assert(vfs);
  assert(vfs->xDelete(vfs, name, 1) == SQLITE_IOERR_DELETE);
  assert(sqlite3_open_v2(name, &reader, SQLITE_OPEN_READWRITE, nullptr) != SQLITE_OK || sqlite3_db_readonly(reader, "main") == 1);
  sqlite3_close(reader);
  assert(sqlite3_open_v2("snapshot-1.db-wal", &reader, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE, nullptr) != SQLITE_OK);
  sqlite3_close(reader);
  // A private regular FD is not evidence of SQLite integrity. Actual SQLite
  // rejects corrupt bytes; the adapter never manufactures a successful check.
  const std::string corrupt = root + "/corrupt.sqlite"; paths.push_back(corrupt);
  int fd = open(corrupt.c_str(), O_CREAT | O_EXCL | O_WRONLY, 0600); assert(fd >= 3);
  assert(write(fd, "not a sqlite database", 21) == 21); close(fd);
  fd = open(corrupt.c_str(), O_RDONLY); assert(fd >= 3);
  assert(mbot_register_readonly_snapshot_fd(fd, name, sizeof(name)) == SQLITE_OK); close(fd);
  assert(sqlite3_open_v2(name, &reader, SQLITE_OPEN_READONLY, nullptr) == SQLITE_OK);
  assert(sqlite3_exec(reader, "PRAGMA integrity_check", nullptr, nullptr, nullptr) == SQLITE_NOTADB);
  sqlite3_close(reader);
  for (int slot = 3; slot <= 9; ++slot) {
    paths.push_back(root + "/extra-" + std::to_string(slot));
    int out = open(paths.back().c_str(), O_CREAT | O_EXCL | O_WRONLY, 0600); assert(out >= 3);
    assert(write(out, "x", 1) == 1); close(out);
    fd = open(paths.back().c_str(), O_RDONLY); assert(fd >= 3);
    // One attempt was quarantined after the duplicate metadata fault above.
    assert(mbot_register_readonly_snapshot_fd(fd, name, sizeof(name)) == (slot <= 7 ? SQLITE_OK : SQLITE_FULL));
    assert(fcntl(fd, F_GETFD) >= 0); close(fd);
  }
  for (int value : source) close(value);
  for (const auto& path : paths) assert(unlink(path.c_str()) == 0);
  assert(rmdir(directory) == 0);
  std::puts("PASS: real SQLite snapshot read/integrity, read-only enforcement, primary-alias refusal, private-file admission, bounded registry and corrupt-byte rejection; host only");
}
