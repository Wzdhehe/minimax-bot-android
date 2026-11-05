// SPDX-License-Identifier: AGPL-3.0-only
#pragma once

#include <cerrno>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace mbot_stage_cleanup {
inline bool hex(char c) { return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'); }

// Only these exact namespaces are transient. In particular, an old
// bot-memory-<hash>.bin may be durable data and must never be swept.
inline bool transient(const std::string& name) {
  const std::string memory = "bot-memory-stage-", native = ".native-publish-";
  if (name.size() == memory.size() + 64 + 4 && name.compare(0, memory.size(), memory) == 0 &&
      name.compare(name.size() - 4, 4, ".bin") == 0) {
    for (size_t i = memory.size(); i < memory.size() + 64; ++i) if (!hex(name[i])) return false;
    return true;
  }
  if (name.size() != native.size() + 36 + 4 || name.compare(0, native.size(), native) != 0 ||
      name.compare(name.size() - 4, 4, ".tmp") != 0) return false;
  for (size_t i = 0; i < 36; ++i) {
    const char c = name[native.size() + i];
    if (i == 8 || i == 13 || i == 18 || i == 23) { if (c != '-') return false; }
    else if (!hex(c)) return false;
  }
  return true;
}

// Must run only while Main holds the exclusive Bot directory lease, before a
// guest is granted a file host. Never follows symlinks or recursively walks.
// EAGAIN means a bounded batch was removed but more may remain; E2BIG means
// the scan budget was exhausted. Callers must fail closed on other errors.
inline int sweep(int directory, size_t entry_limit = 65536, size_t batch_limit = 1024) {
  if (directory < 3 || entry_limit == 0 || batch_limit == 0) return EINVAL;
  struct stat info{};
  if (fstat(directory, &info) != 0) return errno;
  if (!S_ISDIR(info.st_mode)) return ENOTDIR;
  if (info.st_uid != getuid() || (info.st_mode & 077) != 0) return EACCES;
  const int scan_fd = openat(directory, ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
  if (scan_fd < 0) return errno;
  DIR* stream = fdopendir(scan_fd);
  if (!stream) { const int error = errno; close(scan_fd); return error; }
  std::vector<std::string> names;
  bool more = false;
  size_t visited = 0;
  int error = 0;
  for (;;) {
    errno = 0;
    dirent* entry = readdir(stream);
    if (!entry) { error = errno; break; }
    if (++visited > entry_limit) { error = E2BIG; break; }
    const std::string name(entry->d_name);
    if (!transient(name)) continue;
    if (names.size() == batch_limit) { more = true; break; }
    names.push_back(name);
  }
  if (closedir(stream) != 0 && error == 0) error = errno;
  if (error) return error;
  // Validate the entire batch before touching any entry. A suspicious object
  // blocks startup; it is never followed, silently deleted or reported by name.
  for (const auto& name : names) {
    struct stat file{};
    if (fstatat(directory, name.c_str(), &file, AT_SYMLINK_NOFOLLOW) != 0) return errno;
    if (!S_ISREG(file.st_mode) || file.st_uid != getuid() || file.st_nlink != 1 || (file.st_mode & 077) != 0) return EACCES;
  }
  for (const auto& name : names) if (unlinkat(directory, name.c_str(), 0) != 0) return errno;
  if (!names.empty() && fsync(directory) != 0) return errno;
  return more ? EAGAIN : 0;
}
}
