// SPDX-License-Identifier: AGPL-3.0-only
#include "backup.h"
#include <sqlite3.h>
#include <algorithm>
#include <chrono>
#include <cstring>
#include <condition_variable>
#include <mutex>
#include <thread>

namespace mbot {
namespace {
using Clock = std::chrono::steady_clock;
struct Budget {
  Clock::time_point deadline;
  const std::atomic_bool* cancellation;
  bool expired() const { return Clock::now() >= deadline; }
  bool cancelled() const { return cancellation && cancellation->load(std::memory_order_relaxed); }
  bool stopped() const { return cancelled() || expired(); }
  BackupResult stopResult() const { return cancelled() ? BackupResult::cancelled : BackupResult::timed_out; }
};

// The pinned better-sqlite3 definitions omit progress callbacks. SQLite's
// cross-thread interrupt remains available; keep the original compile flags.
// Join before caller-owned handles may close, including every error path.
class Interrupt {
  std::mutex mutex;
  std::condition_variable condition;
  bool done = false;
  std::thread worker;
 public:
  Interrupt(sqlite3* source, sqlite3* destination, const Budget& budget) : worker([&, source, destination] {
    std::unique_lock<std::mutex> lock(mutex);
    while (!done && !budget.stopped()) {
      condition.wait_until(lock, std::min(budget.deadline, Clock::now() + std::chrono::milliseconds(5)));
    }
    if (!done) { sqlite3_interrupt(source); sqlite3_interrupt(destination); }
  }) {}
  ~Interrupt() {
    { std::lock_guard<std::mutex> lock(mutex); done = true; }
    condition.notify_all();
    worker.join();
  }
};

struct Scope {
  sqlite3* source;
  sqlite3* destination;
  bool began = false;
  ~Scope() {
    // Interrupt has already joined. Rollback of the read transaction releases
    // its snapshot, without ever committing/changing the source.
    sqlite3_busy_timeout(source, 0);
    sqlite3_busy_timeout(destination, 0);
    if (began) sqlite3_exec(source, "ROLLBACK", nullptr, nullptr, nullptr);
  }
};

bool integer(sqlite3* db, const char* sql, sqlite3_int64* result) {
  sqlite3_stmt* statement = nullptr;
  if (sqlite3_prepare_v2(db, sql, -1, &statement, nullptr) != SQLITE_OK) return false;
  bool ok = sqlite3_step(statement) == SQLITE_ROW && sqlite3_column_type(statement, 0) == SQLITE_INTEGER;
  if (ok) *result = sqlite3_column_int64(statement, 0);
  if (ok) ok = sqlite3_step(statement) == SQLITE_DONE;
  if (sqlite3_finalize(statement) != SQLITE_OK) ok = false;
  return ok;
}

BackupResult failed(sqlite3* db, const Budget& budget) {
  if (budget.stopped()) return budget.stopResult();
  const int code = sqlite3_errcode(db) & 0xff;
  return code == SQLITE_BUSY || code == SQLITE_LOCKED ? BackupResult::busy : BackupResult::copy_failed;
}

bool integrity(sqlite3* db) {
  sqlite3_stmt* statement = nullptr;
  if (sqlite3_prepare_v2(db, "PRAGMA integrity_check", -1, &statement, nullptr) != SQLITE_OK) return false;
  bool ok = sqlite3_step(statement) == SQLITE_ROW && sqlite3_column_type(statement, 0) == SQLITE_TEXT &&
    sqlite3_column_bytes(statement, 0) == 2;
  if (ok) {
    const auto* text = sqlite3_column_text(statement, 0);
    ok = text && std::memcmp(text, "ok", 2) == 0;
  }
  if (ok) ok = sqlite3_step(statement) == SQLITE_DONE;
  if (sqlite3_finalize(statement) != SQLITE_OK) ok = false;
  return ok;
}

bool singleFileJournal(sqlite3* db) {
  sqlite3_stmt* statement = nullptr;
  if (sqlite3_prepare_v2(db, "PRAGMA journal_mode=DELETE", -1, &statement, nullptr) != SQLITE_OK) return false;
  bool ok = sqlite3_step(statement) == SQLITE_ROW && sqlite3_column_type(statement, 0) == SQLITE_TEXT;
  if (ok) {
    const auto* mode = reinterpret_cast<const char*>(sqlite3_column_text(statement, 0));
    const char* file = sqlite3_db_filename(db, "main");
    // Disposable in-memory destinations are used by kernel fault tests. The
    // granted-file worker always has a nonempty, fixed snapshot filename.
    ok = mode && (!std::strcmp(mode, "delete") ||
      ((!file || !*file) && !std::strcmp(mode, "memory")));
  }
  if (ok) ok = sqlite3_step(statement) == SQLITE_DONE;
  if (sqlite3_finalize(statement) != SQLITE_OK) ok = false;
  return ok;
}
}

static BackupResult copy(sqlite3* source, sqlite3* destination, std::int64_t maxBytes, int timeoutMs,
                         const std::atomic_bool* cancelled) {
  if (!source || !destination || source == destination || maxBytes < 4096 ||
      maxBytes > 512LL * 1024 * 1024 || timeoutMs < 1 || timeoutMs > 30000 ||
      !sqlite3_get_autocommit(source) || !sqlite3_get_autocommit(destination) ||
      sqlite3_db_readonly(destination, "main") != 0) return BackupResult::invalid;
  if (sqlite3_db_readonly(source, "main") != 1) return BackupResult::source_not_readonly;
  Budget budget{Clock::now() + std::chrono::milliseconds(timeoutMs), cancelled};
  if (budget.cancelled()) return BackupResult::cancelled;
  Scope scope{source, destination};
  Interrupt interrupt(source, destination, budget);
  const int wait = std::min(timeoutMs, 25);
  sqlite3_busy_timeout(source, wait);
  sqlite3_busy_timeout(destination, wait);

  sqlite3_int64 destinationPages = 0;
  if (!integer(destination, "PRAGMA page_count", &destinationPages)) return failed(destination, budget);
  if (destinationPages != 0) return BackupResult::destination_not_empty;
  if (sqlite3_exec(source, "BEGIN", nullptr, nullptr, nullptr) != SQLITE_OK) return failed(source, budget);
  scope.began = true;
  // Force a real read snapshot before measuring size. Hold it through backup;
  // a concurrent WAL writer cannot enlarge/change the chosen snapshot later.
  sqlite3_int64 tables = 0, pageSize = 0, pages = 0;
  if (!integer(source, "SELECT count(*) FROM sqlite_schema", &tables) ||
      sqlite3_txn_state(source, "main") != SQLITE_TXN_READ ||
      !integer(source, "PRAGMA page_size", &pageSize) || !integer(source, "PRAGMA page_count", &pages)) {
    return failed(source, budget);
  }
  if (pageSize < 512 || pageSize > 65536 || (pageSize & (pageSize - 1)) != 0 || pages < 0) return BackupResult::copy_failed;
  if (pages > maxBytes / pageSize) return BackupResult::too_large;
  if (budget.stopped()) return budget.stopResult();
  // The published snapshot is a single database file; no live WAL may be
  // required to read it after this dedicated writer has exited.
  if (sqlite3_exec(destination, "PRAGMA journal_mode=DELETE; PRAGMA synchronous=FULL; PRAGMA temp_store=MEMORY;",
                   nullptr, nullptr, nullptr) != SQLITE_OK) return failed(destination, budget);
  sqlite3_backup* backup = sqlite3_backup_init(destination, "main", source, "main");
  if (!backup) return failed(destination, budget);
  int step = SQLITE_OK;
  while (!budget.stopped()) {
    step = sqlite3_backup_step(backup, 64);
    if (step == SQLITE_DONE) break;
    if (step != SQLITE_OK && step != SQLITE_BUSY && step != SQLITE_LOCKED) break;
    // This continues the SAME backup operation; no request or copy is retried
    // from scratch after an unknown result.
    if (step == SQLITE_BUSY || step == SQLITE_LOCKED) sqlite3_sleep(1);
  }
  const int finished = sqlite3_backup_finish(backup);
  if (budget.stopped()) return budget.stopResult();
  if (step != SQLITE_DONE || finished != SQLITE_OK) return failed(destination, budget);
  // backup copies page 1's WAL format bits too. Setting DELETE only before
  // copying is insufficient: restore rollback mode through SQLite AFTER the
  // copy, so a fresh reader needs just the published read-only database FD.
  if (!singleFileJournal(destination)) return failed(destination, budget);
  if (budget.stopped()) return budget.stopResult();
  if (!integrity(destination)) return budget.stopped() ? budget.stopResult() : BackupResult::integrity_failed;
  return budget.stopped() ? budget.stopResult() : BackupResult::ok;
}

BackupResult copySqliteBackup(sqlite3* source, sqlite3* destination, std::int64_t maxBytes, int timeoutMs,
                              const std::atomic_bool* cancelled) {
  // A worker/thread allocation failure must not escape a future JNI boundary
  // as an exception or a platform diagnostic. RAII retires adopted work first.
  try { return copy(source, destination, maxBytes, timeoutMs, cancelled); }
  catch (...) { return BackupResult::copy_failed; }
}

BackupResult verifySqliteBackup(sqlite3* snapshot, int timeoutMs, const std::atomic_bool* cancelled) {
  if (!snapshot || timeoutMs < 1 || timeoutMs > 30000 || !sqlite3_get_autocommit(snapshot)) return BackupResult::invalid;
  if (sqlite3_db_readonly(snapshot, "main") != 1) return BackupResult::source_not_readonly;
  try {
    Budget budget{Clock::now() + std::chrono::milliseconds(timeoutMs), cancelled};
    if (budget.stopped()) return budget.stopResult();
    Scope cleanup{snapshot, snapshot};
    Interrupt interrupt(snapshot, snapshot, budget);
    sqlite3_busy_timeout(snapshot, std::min(timeoutMs, 25));
    if (!integrity(snapshot)) return budget.stopped() ? budget.stopResult() : BackupResult::integrity_failed;
    return budget.stopped() ? budget.stopResult() : BackupResult::ok;
  } catch (...) { return BackupResult::copy_failed; }
}
}
