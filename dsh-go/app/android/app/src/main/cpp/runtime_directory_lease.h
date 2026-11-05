// SPDX-License-Identifier: AGPL-3.0-only
#pragma once

#include <cerrno>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>

// The owner holds an independently opened private directory FD. flock is an
// advisory lock on its open file description: duplicates keep it alive until
// the last duplicate closes. No path, lock file or FD is exposed to a guest.
inline int try_runtime_directory_lease(int directory) {
  if (directory < 3) return EINVAL;
  struct stat info{};
  if (fstat(directory, &info) != 0) return errno;
  if (!S_ISDIR(info.st_mode)) return ENOTDIR;
  if (info.st_uid != getuid() || (info.st_mode & 077) != 0) return EACCES;
  return flock(directory, LOCK_EX | LOCK_NB) == 0 ? 0 : errno;
}
