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

namespace {
constexpr const char* root = "/mbot-data";
struct Entry { int fd; bool sidecar; };
std::map<std::string, Entry> files;
std::mutex gate;
sqlite3_vfs adapter{};
bool registered = false;
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

// One immutable registry per process. Takes duplicates, never caller ownership.
// The trusted owner must grant only data files and terminate the isolated
// process on lease revocation: closing an owner copy cannot revoke a dup FD.
// Precondition: trusted owner has fsynced each precreated file and its containing
// directory; it must not rename/unlink/replace these inodes while leased.
extern "C" int mbot_register_precreated_fd_vfs(const char* const* names, const int* descriptors, int count) {
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
