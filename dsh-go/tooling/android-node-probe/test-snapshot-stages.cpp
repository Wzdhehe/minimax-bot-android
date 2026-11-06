// SPDX-License-Identifier: AGPL-3.0-only
// Host I/O primitive tests, not SQLite integrity/Android/worker-exit evidence.
#include "runtime_snapshot_stage.h"
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <sys/wait.h>
#ifdef NDEBUG
#error Assertions must stay enabled
#endif
namespace s = mbot_snapshot_stage;
int failSync = -1;
extern "C" int __real_fsync(int);
extern "C" int __wrap_fsync(int fd) {
  if (fd == failSync) { errno = EIO; return -1; }
  return __real_fsync(fd);
}
std::string stage(std::string_view token, int slot) {
  std::array<char, 96> name{}; assert(s::name(token, slot, name)); return name.data();
}
int main() {
  char temporary[] = "/tmp/mbot-snapshot-stage-XXXXXX";
  assert(mkdtemp(temporary));
  const int directory = ::open(temporary, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
  assert(directory >= 3);
  const std::string token(32, 'a');
  const std::string target = "runtime-state-before-v2-migration-1794000000000-01234567-89ab-4def-8123-456789abcdef.sqlite";
  assert(s::target(target));
  for (const char* bad : {"../x", "runtime-state.sqlite", "", "runtime-state-before-v2-migration-0-01234567-89ab-4def-8123-456789abcdef.sqlite",
       "runtime-state-before-v2-migration-9999999999999999-01234567-89ab-4def-8123-456789abcdef.sqlite",
       "runtime-state-before-v2-migration-1794000000000-01234567-89ab-0def-8123-456789abcdef.sqlite"}) assert(!s::target(bad));
  assert(s::open(directory, "../private", 0, true) == -EINVAL);
  assert(s::open(directory, token, -1, true) == -EINVAL && s::open(directory, token, 4, true) == -EINVAL);
  assert(fchmod(directory, 0755) == 0);
  assert(s::open(directory, token, 0, true) == -EACCES);
  assert(fchmod(directory, 01700) == 0 && s::open(directory, token, 0, true) == -EACCES);
  assert(fchmod(directory, 04700) == 0 && s::open(directory, token, 0, true) == -EACCES);
  assert(fchmod(directory, 02750) == 0 && s::open(directory, token, 0, true) == -EACCES);
  // Match Android's inherited private directory mode. All positive create,
  // publish and removal checks below must work without clearing setgid.
  assert(fchmod(directory, 02700) == 0);
  struct stat privateDirectory{};
  assert(fstat(directory, &privateDirectory) == 0 && (privateDirectory.st_mode & 07777) == 02700);
  std::array<int, 4> writable;
  for (int i = 0; i < 4; ++i) { writable[i] = s::open(directory, token, i, true); assert(writable[i] >= 3); }
  assert(s::open(directory, token, 0, true) == -EEXIST);
  const char payload[] = "primitive test bytes, not a SQLite database";
  assert(write(writable[0], payload, sizeof(payload)) == static_cast<ssize_t>(sizeof(payload)));
  assert(fsync(writable[0]) == 0);
  assert(s::publish(directory, token, target, writable[0], sizeof(payload)) == s::REFUSED);
  const int readonly = s::open(directory, token, 0, false);
  assert(readonly >= 3 && (fcntl(readonly, F_GETFL) & O_ACCMODE) == O_RDONLY);
  assert(s::publish(directory, token, "../bad", readonly, sizeof(payload)) == s::REFUSED);
  assert(s::publish(directory, token, target, readonly, sizeof(payload) + 1) == s::REFUSED);
  assert(fchmod(writable[0], 0644) == 0);
  assert(s::publish(directory, token, target, readonly, sizeof(payload)) == s::REFUSED);
  assert(fchmod(writable[0], 0600) == 0);
  assert(fchmod(writable[0], 02600) == 0);
  assert(s::publish(directory, token, target, readonly, sizeof(payload)) == s::REFUSED);
  assert(fchmod(writable[0], 0600) == 0);
  assert(write(writable[1], "x", 1) == 1);
  assert(s::publish(directory, token, target, readonly, sizeof(payload)) == s::REFUSED);
  assert(ftruncate(writable[1], 0) == 0 && fsync(writable[1]) == 0);
  // This primitive cannot detect all other writable aliases. The real owner
  // must close them only after genuine worker retirement, before publication.
  for (int fd : writable) assert(close(fd) == 0);
  const int sidecar = s::open(directory, token, 1, false); assert(sidecar >= 3);
  assert(renameat(directory, stage(token, 1).c_str(), directory, "original-sidecar") == 0);
  assert(symlinkat(stage(token, 0).c_str(), directory, stage(token, 1).c_str()) == 0);
  assert(s::open(directory, token, 1, false) == -ELOOP);
  assert(s::publish(directory, token, target, readonly, sizeof(payload)) == s::REFUSED);
  assert(s::remove(directory, token, 1, sidecar) == EACCES);
  assert(unlinkat(directory, stage(token, 1).c_str(), 0) == 0);
  assert(renameat(directory, "original-sidecar", directory, stage(token, 1).c_str()) == 0 && close(sidecar) == 0);
  assert(linkat(directory, stage(token, 0).c_str(), directory, "extra", 0) == 0);
  assert(s::publish(directory, token, target, readonly, sizeof(payload)) == s::REFUSED);
  assert(unlinkat(directory, "extra", 0) == 0);
  assert(renameat(directory, stage(token, 0).c_str(), directory, "original") == 0);
  const int replacement = s::open(directory, token, 0, true); assert(replacement >= 3);
  assert(s::remove(directory, token, 0, readonly) == EACCES);
  assert(s::publish(directory, token, target, readonly, sizeof(payload)) == s::REFUSED);
  assert(s::remove(directory, token, 0, replacement) == 0 && close(replacement) == 0);
  assert(renameat(directory, "original", directory, stage(token, 0).c_str()) == 0);
  const int exists = openat(directory, target.c_str(), O_CREAT | O_EXCL | O_WRONLY, 0600);
  assert(exists >= 3 && write(exists, "old", 3) == 3 && close(exists) == 0);
  assert(s::publish(directory, token, target, readonly, sizeof(payload)) == s::ALREADY_EXISTS);
  struct stat collision{};
  assert(fstatat(directory, target.c_str(), &collision, AT_SYMLINK_NOFOLLOW) == 0 && collision.st_size == 3);
  assert(unlinkat(directory, target.c_str(), 0) == 0);
  failSync = directory;
  assert(s::publish(directory, token, target, readonly, sizeof(payload)) == s::OUTCOME_UNKNOWN);
  failSync = -1;
  struct stat published{}, held{};
  assert(fstatat(directory, target.c_str(), &published, AT_SYMLINK_NOFOLLOW) == 0 && fstat(readonly, &held) == 0);
  assert(published.st_ino == held.st_ino && published.st_size == static_cast<off_t>(sizeof(payload)));
  assert(s::remove(directory, token, 0, readonly) == ENOENT); // Never removes the published target.
  assert(close(readonly) == 0);
  for (int i = 1; i < 4; ++i) {
    const int fd = s::open(directory, token, i, false); assert(fd >= 3);
    assert(s::remove(directory, token, i, fd) == 0 && close(fd) == 0);
  }
  assert(unlinkat(directory, target.c_str(), 0) == 0);
  // Pre-publication create sync failure can also leave an entry behind.
  failSync = directory;
  assert(s::open(directory, token, 0, true) == -EIO);
  failSync = -1;
  const int remaining = s::open(directory, token, 0, false); assert(remaining >= 3);
  failSync = directory;
  assert(s::remove(directory, token, 0, remaining) == EIO);
  failSync = -1;
  assert(s::open(directory, token, 0, false) == -ENOENT);
  assert(close(remaining) == 0);
  const std::string fresh(32, 'b');
  for (int i = 0; i < 4; ++i) {
    const int fd = s::open(directory, fresh, i, true); assert(fd >= 3);
    if (i == 0) assert(write(fd, payload, sizeof(payload)) == static_cast<ssize_t>(sizeof(payload)) && fsync(fd) == 0);
    assert(close(fd) == 0);
  }
  const int good = s::open(directory, fresh, 0, false); assert(good >= 3);
  assert(s::publish(directory, fresh, target, good, sizeof(payload)) == s::PUBLISHED);
  assert(fstatat(directory, target.c_str(), &published, AT_SYMLINK_NOFOLLOW) == 0 && fstat(good, &held) == 0 && published.st_ino == held.st_ino);
  assert(s::remove(directory, fresh, 0, good) == ENOENT);
  const int wrong = s::open(directory, fresh, 1, false); assert(wrong >= 3);
  assert(s::remove_published(directory, target, wrong) == EACCES && close(wrong) == 0);
  assert(s::remove_published(directory, "../other", good) == EINVAL);
  failSync = directory;
  assert(s::remove_published(directory, target, good) == EIO);
  failSync = -1;
  assert(fstatat(directory, target.c_str(), &published, AT_SYMLINK_NOFOLLOW) != 0 && errno == ENOENT);
  char preserved[sizeof(payload)];
  assert(pread(good, preserved, sizeof(preserved), 0) == static_cast<ssize_t>(sizeof(payload)) && !std::memcmp(payload, preserved, sizeof(payload)));
  assert(close(good) == 0);
  for (int i = 1; i < 4; ++i) {
    const int fd = s::open(directory, fresh, i, false); assert(fd >= 3);
    assert(s::remove(directory, fresh, i, fd) == 0 && close(fd) == 0);
  }
  assert(close(directory) == 0 && rmdir(temporary) == 0);
  puts("PASS: private snapshot stage creation/identity/permissions, no-overwrite publication and post-mutation sync uncertainty; Linux I/O primitives only");
}
