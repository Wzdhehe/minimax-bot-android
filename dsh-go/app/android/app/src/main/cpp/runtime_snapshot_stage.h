// SPDX-License-Identifier: AGPL-3.0-only
#pragma once
#include <array>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <string_view>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

// Trusted Main I/O only. Caller must hold its directory lease, have observed
// writer process retirement and closed ALL writable snapshot copies before
// publishing. Read-only FD admission here cannot prove those lifecycle facts.
// No directory/file capability or stage token may be exposed to Agent code.
namespace mbot_snapshot_stage {
enum Publish { PUBLISHED = 0, ALREADY_EXISTS = 1, OUTCOME_UNKNOWN = 2, REFUSED = 3 };
inline bool token(std::string_view value) {
  if (value.size() != 32) return false;
  for (char c : value) if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
  return true;
}
inline bool name(std::string_view value, int slot, std::array<char, 96>& result) {
  if (!token(value) || slot < 0 || slot > 3) return false;
  constexpr char prefix[] = ".sqlite-backup-";
  const char* tails[] = {".sqlite", ".sqlite-wal", ".sqlite-shm", ".sqlite-journal"};
  std::memcpy(result.data(), prefix, sizeof(prefix) - 1);
  std::memcpy(result.data() + sizeof(prefix) - 1, value.data(), value.size());
  std::strcpy(result.data() + sizeof(prefix) - 1 + value.size(), tails[slot]);
  return true;
}
inline bool target(std::string_view value) {
  constexpr std::string_view prefix = "runtime-state-before-v2-migration-";
  constexpr std::string_view suffix = ".sqlite";
  if (value.size() < prefix.size() + 1 + 1 + 36 + suffix.size() ||
      value.substr(0, prefix.size()) != prefix || value.substr(value.size() - suffix.size()) != suffix) return false;
  const auto end = value.size() - suffix.size() - 36 - 1;
  if (value[end] != '-') return false;
  const auto timestamp = value.substr(prefix.size(), end - prefix.size());
  if (timestamp.empty() || timestamp.size() > 16 || timestamp[0] == '0') return false;
  std::uint64_t time = 0;
  for (char c : timestamp) { if (c < '0' || c > '9') return false; time = time * 10 + static_cast<unsigned>(c - '0'); }
  if (time > 9007199254740991ULL) return false;
  const auto uuid = value.substr(end + 1, 36);
  for (std::size_t i = 0; i < uuid.size(); ++i) {
    if (i == 8 || i == 13 || i == 18 || i == 23) { if (uuid[i] != '-') return false; }
    else if (!((uuid[i] >= '0' && uuid[i] <= '9') || (uuid[i] >= 'a' && uuid[i] <= 'f'))) return false;
  }
  return uuid[14] >= '1' && uuid[14] <= '5' && std::strchr("89ab", uuid[19]) != nullptr;
}
inline int directory_error(int directory) {
  if (directory < 3) return EINVAL;
  struct stat info{};
  if (fstat(directory, &info) != 0) return errno;
  return S_ISDIR(info.st_mode) && info.st_uid == getuid() && (info.st_mode & 07777) == 0700 ? 0 : EACCES;
}
inline bool private_file(const struct stat& info) {
  return S_ISREG(info.st_mode) && info.st_uid == getuid() && info.st_nlink == 1 &&
    (info.st_mode & 07777) == 0600 && info.st_size >= 0;
}
inline int open(int directory, std::string_view value, int slot, bool create) {
  std::array<char, 96> file{};
  if (!name(value, slot, file)) return -EINVAL;
  const int error = directory_error(directory);
  if (error) return -error;
  const int flags = create ? O_RDWR | O_CREAT | O_EXCL : O_RDONLY;
  const int fd = openat(directory, file.data(), flags | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK, 0600);
  if (fd < 0) return -errno;
  struct stat info{};
  int failure = 0;
  if (fstat(fd, &info) != 0) failure = errno;
  else if (!private_file(info) || (create && info.st_size != 0)) failure = EACCES;
  if (!failure && create && (fsync(fd) != 0 || fsync(directory) != 0)) failure = errno;
  if (failure) { close(fd); return -failure; }
  return fd; // A failed create may leave a private file; never overwrite/retry it.
}
inline bool matches(int directory, const char* file, int held, struct stat& info) {
  struct stat entry{};
  return held >= 3 && fstat(held, &info) == 0 && private_file(info) &&
    fstatat(directory, file, &entry, AT_SYMLINK_NOFOLLOW) == 0 && private_file(entry) &&
    entry.st_dev == info.st_dev && entry.st_ino == info.st_ino;
}
inline int remove(int directory, std::string_view value, int slot, int held) {
  std::array<char, 96> file{};
  if (!name(value, slot, file)) return EINVAL;
  const int error = directory_error(directory);
  if (error) return error;
  struct stat entry{};
  if (fstatat(directory, file.data(), &entry, AT_SYMLINK_NOFOLLOW) != 0) return errno;
  struct stat info{};
  if (!matches(directory, file.data(), held, info)) return EACCES;
  if (unlinkat(directory, file.data(), 0) != 0 || fsync(directory) != 0) return errno;
  return 0; // Failure after unlink can be unknown. Caller must not recreate it.
}
inline Publish publish(int directory, std::string_view value, std::string_view destination, int readonly,
                       std::int64_t expectedBytes) {
  if (!target(destination) || destination.size() >= 128 || directory_error(directory) ||
      expectedBytes <= 0 || expectedBytes > 512LL * 1024 * 1024) return REFUSED;
  const int flags = fcntl(readonly, F_GETFL);
  if (flags < 0 || (flags & O_ACCMODE) != O_RDONLY) return REFUSED;
#ifdef O_PATH
  if (flags & O_PATH) return REFUSED;
#endif
  std::array<char, 96> file{};
  if (!name(value, 0, file)) return REFUSED;
  struct stat info{};
  if (!matches(directory, file.data(), readonly, info) || info.st_size != expectedBytes) return REFUSED;
  for (int slot = 1; slot < 4; ++slot) {
    std::array<char, 96> sidecar{};
    struct stat other{};
    if (!name(value, slot, sidecar) || fstatat(directory, sidecar.data(), &other, AT_SYMLINK_NOFOLLOW) != 0 ||
        !private_file(other) || other.st_size != 0) return REFUSED;
  }
  std::array<char, 128> to{};
  std::memcpy(to.data(), destination.data(), destination.size());
#ifdef __NR_renameat2
  if (syscall(__NR_renameat2, directory, file.data(), directory, to.data(), 1U /* RENAME_NOREPLACE */) != 0) {
    return errno == EEXIST ? ALREADY_EXISTS : OUTCOME_UNKNOWN;
  }
  return fsync(directory) == 0 ? PUBLISHED : OUTCOME_UNKNOWN;
#else
  return OUTCOME_UNKNOWN; // No racy exists+rename fallback.
#endif
}
inline int remove_published(int directory, std::string_view destination, int held) {
  if (!target(destination) || destination.size() >= 128) return EINVAL;
  const int error = directory_error(directory);
  if (error) return error;
  const int flags = fcntl(held, F_GETFL);
  if (flags < 0 || (flags & O_ACCMODE) != O_RDONLY) return EACCES;
#ifdef O_PATH
  if (flags & O_PATH) return EACCES;
#endif
  std::array<char, 128> file{};
  std::memcpy(file.data(), destination.data(), destination.size());
  struct stat info{};
  if (!matches(directory, file.data(), held, info)) return EACCES;
  if (unlinkat(directory, file.data(), 0) != 0 || fsync(directory) != 0) return errno;
  return 0; // Removes a name, never revokes already transferred read-only FDs.
}
}
