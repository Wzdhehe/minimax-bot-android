// SPDX-License-Identifier: AGPL-3.0-only
// Verification-only SQLite VFS: explicit data FDs. No product launcher wiring.
// Reuses SQLite unix locking/SHM/inode bookkeeping, including deferred close.
#include <sqlite3.h>
#include <sys/stat.h>
#include <unistd.h>
#include <fcntl.h>
#include <cerrno>
#include <cstring>
#include <string>
#include <map>
#include <set>
#include <mutex>
#include <vector>

namespace {
constexpr const char* root = "/mbot-data";
struct Entry { int fd; bool sidecar; bool snapshot = false; };
std::map<std::string, Entry> files;
std::mutex gate;
sqlite3_vfs adapter{};
bool registered = false;
int snapshotAttempts = 0;
std::vector<int> refusedSnapshotAliases;
using OpenFunction = int(*)(const char*, int, int);
OpenFunction original_open = nullptr;

bool exists(const Entry& entry) {
  struct stat info{};
  return fstat(entry.fd, &info) == 0 && (!entry.sidecar || info.st_size > 0);
}

std::string key(const char* path) {
  if (!path) return {};
  std::string name(path);
  const std::string prefix = std::string(root) + "/";
  if (name.compare(0, prefix.size(), prefix) == 0) name.erase(0, prefix.size());
  if (name.empty() || name.size() > 128 || name == "." || name == "..") return {};
  for (unsigned char c : name) if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
      (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-')) return {};
  return name;
}
int denied(int error = EACCES) { errno = error; return -1; }
int open_file(const char* path, int flags, int) {
  std::lock_guard<std::mutex> lock(gate);
  // Preserve SQLite's entropy source, not a general pathname fallback.
  if (path && std::strcmp(path, "/dev/urandom") == 0 && (flags & O_ACCMODE) == O_RDONLY &&
      !(flags & (O_CREAT | O_EXCL | O_TRUNC | O_DIRECTORY))) return original_open(path, flags, 0);
  if (path && std::strcmp(path, root) == 0) {
    return denied(); // A virtual namespace, never a physical directory grant.
  }
  const auto found = files.find(key(path));
  if (found == files.end()) return denied();
  auto& entry = found->second;
  if (entry.snapshot && ((flags & O_ACCMODE) != O_RDONLY || (flags & (O_CREAT | O_EXCL | O_TRUNC)))) return denied();
  if (!exists(entry) && !(flags & O_CREAT)) return denied(ENOENT);
  if (exists(entry) && (flags & O_EXCL) && (flags & O_CREAT)) return denied(EEXIST);
  const int mode = fcntl(entry.fd, F_GETFL) & O_ACCMODE;
  if ((flags & O_ACCMODE) != O_RDONLY && mode == O_RDONLY) return denied();
  if ((flags & O_TRUNC) && ftruncate(entry.fd, 0) != 0) return -1;
  const int result = fcntl(entry.fd, F_DUPFD_CLOEXEC, 3);
  return result;
}
int stat_file(const char* path, struct stat* info) {
  std::lock_guard<std::mutex> lock(gate);
  if (path && std::strcmp(path, root) == 0) {
    std::memset(info, 0, sizeof(*info)); info->st_mode = S_IFDIR | 0700; info->st_nlink = 1;
    return 0;
  }
  const auto found = files.find(key(path));
  if (found == files.end() || !exists(found->second)) return denied(ENOENT);
  return fstat(found->second.fd, info);
}
int access_file(const char* path, int mode) {
  std::lock_guard<std::mutex> lock(gate);
  if (path && std::strcmp(path, root) == 0) return 0;
  const auto found = files.find(key(path));
  if (found == files.end() || !exists(found->second)) return denied(ENOENT);
  if ((mode & X_OK) || ((mode & W_OK) && (fcntl(found->second.fd, F_GETFL) & O_ACCMODE) == O_RDONLY)) return denied();
  return 0;
}
int unlink_file(const char* path) {
  std::lock_guard<std::mutex> lock(gate);
  const auto name = key(path);
  const auto found = files.find(name);
  if (found == files.end()) return denied();
  // Only journal/SHM/WAL lifecycle deletion is modeled by this prototype.
  // Main/backup replacement and arbitrary unlinks need owner-mediated leases.
  if (!(name.size() >= 4 && (name.compare(name.size()-4, 4, "-wal") == 0 || name.compare(name.size()-4, 4, "-shm") == 0)) &&
      !(name.size() >= 8 && name.compare(name.size()-8, 8, "-journal") == 0)) return denied();
  if (ftruncate(found->second.fd, 0) != 0) return -1;
  // A logical deletion is a file-size change, not an actual unlink. SQLite's
  // subsequent directory sync cannot persist this truncate: sync the data FD
  // itself before reporting successful removal of a journal/WAL/SHM sidecar.
  int result;
  do { result = fsync(found->second.fd); } while (result < 0 && errno == EINTR);
  return result;
}
int full_path(sqlite3_vfs*, const char* path, int length, char* output) {
  const auto name = key(path);
  std::lock_guard<std::mutex> lock(gate);
  if (files.find(name) == files.end()) return SQLITE_CANTOPEN;
  const auto result = std::string(root) + "/" + name;
  if (length <= static_cast<int>(result.size())) return SQLITE_CANTOPEN;
  std::memcpy(output, result.c_str(), result.size()+1);
  return SQLITE_OK;
}
int no_directory(const char*, int* fd) {
  // The owner durably creates all physical entries before FD handoff. During
  // this lease only fixed inodes/lengths change, never directory membership.
  // unixSync explicitly permits CANTOPEN here after it syncs the data file.
  *fd = -1; errno = EACCES; return SQLITE_CANTOPEN;
}
int delete_precreated(sqlite3_vfs*, const char* path, int) {
  // A logical delete is a truncate+file fsync, performed by unlink_file.
  // There is no physical directory entry to remove/sync inside the isolate.
  if (unlink_file(path) == 0) return SQLITE_OK;
  return errno == ENOENT ? SQLITE_IOERR_DELETE_NOENT : SQLITE_IOERR_DELETE;
}
}  // namespace

// Admit an already-granted immutable snapshot, never a caller-selected path.
// Main owns publication/immutability. This API verifies a private read-only
// regular inode owned by the same Main UID as state.db, distinct from every
// mutable family member. Registry duplicates live until process retirement.
// NEVER consume/close the input, even on refusal: an arbitrary alias could be
// a live primary SQLite file, and closing it would drop process POSIX locks.
extern "C" int mbot_register_readonly_snapshot_fd(int descriptor, char* output, int capacity) {
  if (descriptor < 3 || !output || capacity < 64) return SQLITE_MISUSE;
  std::lock_guard<std::mutex> lock(gate);
  const auto primary = files.find("state.db");
  struct stat info{}, source{};
  const int flags = fcntl(descriptor, F_GETFL);
#ifdef O_PATH
  if (flags >= 0 && (flags & O_PATH)) return SQLITE_MISUSE;
#endif
  if (!registered || primary == files.end() || flags < 0 || (flags & O_ACCMODE) != O_RDONLY ||
      fstat(primary->second.fd, &source) != 0 || fstat(descriptor, &info) != 0 ||
      !S_ISREG(info.st_mode) || (info.st_mode & 07777) != 0600 || info.st_nlink != 1 ||
      info.st_uid != source.st_uid || info.st_size <= 0 || info.st_size > 512LL * 1024 * 1024) return SQLITE_MISUSE;
  int snapshots = 0;
  for (const auto& [name, entry] : files) {
    struct stat held{};
    if (fstat(entry.fd, &held) != 0) return SQLITE_IOERR_FSTAT;
    if (held.st_dev == info.st_dev && held.st_ino == info.st_ino) {
      if (!entry.snapshot) return SQLITE_MISUSE;
      // The same confirmed immutable inode may be verified more than once.
      std::memcpy(output, name.c_str(), name.size() + 1);
      return SQLITE_OK;
    }
    if (entry.snapshot) ++snapshots;
  }
  if (snapshots >= 8 || snapshotAttempts >= 8) return SQLITE_FULL;
  const auto name = "snapshot-" + std::to_string(snapshots + 1) + ".db";
  if (files.count(name)) return SQLITE_MISUSE;
  ++snapshotAttempts;
  const int owned = fcntl(descriptor, F_DUPFD_CLOEXEC, 3);
  if (owned < 0) return SQLITE_CANTOPEN;
  // The supplied number may have been concurrently closed/reused. Validate
  // our own stable duplicate again. On mismatch retain it (bounded attempts)
  // until exit: closing an unexpected primary alias could release its locks.
  struct stat actual{};
  const int actualFlags = fcntl(owned, F_GETFL);
  if (fstat(owned, &actual) != 0 || actualFlags < 0 || actualFlags != flags ||
      actual.st_dev != info.st_dev || actual.st_ino != info.st_ino ||
      actual.st_mode != info.st_mode || actual.st_uid != info.st_uid ||
      actual.st_nlink != info.st_nlink || actual.st_size != info.st_size) {
    refusedSnapshotAliases.push_back(owned);
    return SQLITE_MISUSE;
  }
  files.emplace(name, Entry{owned, false, true});
  std::memcpy(output, name.c_str(), name.size() + 1);
  return SQLITE_OK;
}

// Inspect held files without opening/closing aliases: closing an alias could
// release this process's POSIX SQLite locks. Slot 4 is the virtual namespace,
// never a physical directory FD or a caller-selected path.
extern "C" int mbot_fd_vfs_metadata(int slot, struct stat* info, int* present) {
  if (!info || !present || slot < 0 || slot > 4) return SQLITE_MISUSE;
  std::lock_guard<std::mutex> lock(gate);
  if (!registered) return SQLITE_MISUSE;
  if (slot == 4) {
    std::memset(info, 0, sizeof(*info));
    info->st_mode = S_IFDIR | 0700;
    info->st_nlink = 1;
    *present = 1;
    return SQLITE_OK;
  }
  const char* names[] = {"state.db", "state.db-wal", "state.db-shm", "state.db-journal"};
  const auto found = files.find(names[slot]);
  if (found == files.end()) return SQLITE_NOTFOUND;
  if (fstat(found->second.fd, info) != 0 || !S_ISREG(info->st_mode) || info->st_size < 0) return SQLITE_IOERR_FSTAT;
  *present = !found->second.sidecar || info->st_size > 0;
  return SQLITE_OK;
}

// One immutable registry per process. Takes duplicates, never caller ownership.
// The trusted owner must grant only data files and terminate the isolated
// process on lease revocation: closing an owner copy cannot revoke a dup FD.
// Precondition: trusted owner has fsynced each precreated file and its containing
// directory; it must not rename/unlink/replace these inodes while leased.
extern "C" int mbot_register_precreated_fd_vfs(const char* const* names, const int* descriptors, int count) {
  std::lock_guard<std::mutex> lock(gate);
  if (registered || count < 4 || count > 16 || !names || !descriptors) return SQLITE_MISUSE;
  struct stat info{};
  std::map<std::string, Entry> staged;
  std::set<std::pair<dev_t, ino_t>> inodes;
  for (int i = 0; i < count; ++i) {
    const auto name = key(names[i]);
    if (name.empty() || name != names[i] || staged.count(name) || fstat(descriptors[i], &info) != 0 ||
        !S_ISREG(info.st_mode) || !inodes.emplace(info.st_dev, info.st_ino).second) {
      for (const auto& entry : staged) close(entry.second.fd);
      return SQLITE_MISUSE;
    }
    const int fd = fcntl(descriptors[i], F_DUPFD_CLOEXEC, 3);
    if (fd < 0) { for (const auto& entry : staged) close(entry.second.fd); return SQLITE_CANTOPEN; }
    const bool sidecar = (name.size() >= 4 && (name.compare(name.size()-4, 4, "-wal") == 0 || name.compare(name.size()-4, 4, "-shm") == 0)) ||
      (name.size() >= 8 && name.compare(name.size()-8, 8, "-journal") == 0);
    staged.emplace(name, Entry{fd, sidecar});
  }
  auto* original = sqlite3_vfs_find("unix");
  if (!original || original->iVersion < 3 || !original->xSetSystemCall || !original->xGetSystemCall) {
    for (const auto& entry : staged) close(entry.second.fd);
    return SQLITE_NOTFOUND;
  }
  original_open = reinterpret_cast<OpenFunction>(original->xGetSystemCall(original, "open"));
  if (!original_open) { for (const auto& entry : staged) close(entry.second.fd); return SQLITE_NOTFOUND; }
  // All pathname-based calls fail closed outside this explicit registry.
  struct Hook { const char* name; sqlite3_syscall_ptr function; sqlite3_syscall_ptr previous; };
  Hook hooks[] = {{"open", reinterpret_cast<sqlite3_syscall_ptr>(open_file), nullptr},
    {"stat", reinterpret_cast<sqlite3_syscall_ptr>(stat_file), nullptr}, {"lstat", reinterpret_cast<sqlite3_syscall_ptr>(stat_file), nullptr},
    {"access", reinterpret_cast<sqlite3_syscall_ptr>(access_file), nullptr}, {"unlink", reinterpret_cast<sqlite3_syscall_ptr>(unlink_file), nullptr},
    {"openDirectory", reinterpret_cast<sqlite3_syscall_ptr>(no_directory), nullptr}};
  for (auto& hook : hooks) {
    hook.previous = original->xGetSystemCall(original, hook.name);
    if (!hook.previous) { for (const auto& entry : staged) close(entry.second.fd); return SQLITE_NOTFOUND; }
  }
  files.swap(staged);
  auto rollback = [&]() {
    for (const auto& hook : hooks) original->xSetSystemCall(original, hook.name, hook.previous);
    for (const auto& entry : files) close(entry.second.fd);
    files.clear();
  };
  for (const auto& hook : hooks) {
    const int result = original->xSetSystemCall(original, hook.name, hook.function);
    if (result != SQLITE_OK) { rollback(); return result; }
  }
  adapter = *original;
  adapter.zName = "mbot-fd-prototype";
  adapter.pNext = nullptr;
  adapter.xFullPathname = full_path;
  adapter.xDelete = delete_precreated;
  const int result = sqlite3_vfs_register(&adapter, 1);
  if (result == SQLITE_OK) registered = true;
  else rollback();
  return result;
}
