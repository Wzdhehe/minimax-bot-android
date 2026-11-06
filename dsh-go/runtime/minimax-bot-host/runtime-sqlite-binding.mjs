// SPDX-License-Identifier: AGPL-3.0-only
import { createRequire as nodeCreateRequire } from 'node:module';
import { basename, dirname, isAbsolute, join, resolve } from 'node:path';

const entries = new Map();
let nativeOnly = false;
const failure = (code) => Object.assign(new Error(code), { code });
// Exact filename grammar of the pinned infra/db/backup.ts. This does not grant
// access to a directory: each native callback receives a validated basename.
const backupName = /^runtime-state-before-v2-migration-\d+-[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\.sqlite$/iu;
export const SQLITE_FS_UNHANDLED = Symbol('ordinary_host_filesystem');
const databaseFiles = ['runtime-state.sqlite', 'runtime-state.sqlite-wal', 'runtime-state.sqlite-shm', 'runtime-state.sqlite-journal'];

/** Native supplies an already-admitted SQLite constructor capability, not a
 * module path or a database path. This only adapts the pinned DB loader; the
 * launcher must separately assemble its admitted filesystem and FD VFS.
 */
export function bindRuntimeSqlite({ dataDir, open, backup, openBackup, metadata } = {}) {
  if (typeof dataDir !== 'string' || !isAbsolute(dataDir) || dataDir.includes('\0') || typeof open !== 'function') {
    throw failure('runtime_sqlite_invalid');
  }
  if ((backup !== undefined || openBackup !== undefined) &&
      (typeof backup !== 'function' || typeof openBackup !== 'function')) throw failure('runtime_sqlite_invalid');
  if (metadata !== undefined && typeof metadata !== 'function') throw failure('runtime_sqlite_invalid');
  const path = join(resolve(dataDir), 'v2/sqlite/runtime-state.sqlite');
  // FS imports are module-level. A native FS view is one execution-unit lease,
  // so its logical root cannot be rebound in this process after retirement.
  if (entries.has(path) && (!entries.get(path).drained || entries.get(path).hadMetadata || metadata !== undefined)) throw failure('runtime_sqlite_busy');
  const entry = { active: true, drained: false, open, backup, openBackup,
    metadata, hadMetadata: metadata !== undefined, directory: dirname(path),
    backupDir: join(resolve(dataDir), 'v2/sqlite/backups'), databases: new Set() };
  entries.set(path, entry);
  // Once this process has used a native capability it cannot silently revert
  // to npm/environment-selected SQLite. Android starts one Node per UID/run.
  nativeOnly = true;
  let finished = false;
  return Object.freeze({
    deactivate() { entry.active = false; },
    finish(verified) {
      if (finished) return;
      finished = true;
      entry.active = false;
      let closed = true;
      for (const db of entry.databases) {
        try { if (db.open !== false) db.close(); } catch { closed = false; }
      }
      entry.databases.clear();
      entry.drained = verified === true && closed;
      entry.open = null;
      entry.backup = null;
      entry.openBackup = null;
      entry.metadata = null;
      if (verified === true && !closed) throw failure('runtime_sqlite_close_failed');
    },
  });
}

/** Fixed SQLite-family view only, not a general filesystem or pathname broker.
 * Directory metadata describes the VFS namespace Main has already admitted.
 * chmod merely verifies an existing private mode; it never changes permissions.
 */
export function runtimeSqliteFilesystem(operation, input, value) {
  if (typeof input !== 'string' || !isAbsolute(input) || input.includes('\0')) return SQLITE_FS_UNHANDLED;
  const path = resolve(input);
  const entry = [...entries.values()].find((item) => path === item.directory || dirname(path) === item.directory);
  if (!entry) return SQLITE_FS_UNHANDLED;
  if (!entry.active) throw failure('runtime_sqlite_closed');
  if (typeof entry.metadata !== 'function') throw failure('runtime_sqlite_metadata_unavailable');
  const slot = path === entry.directory ? 4 : databaseFiles.indexOf(basename(path));
  if (slot < 0) throw failure('runtime_sqlite_path_denied');
  let info;
  try { info = entry.metadata(slot); } catch { throw failure('runtime_sqlite_metadata_unavailable'); }
  if (!entry.active) throw failure('runtime_sqlite_closed');
  if (!info || typeof info.present !== 'boolean' || !Number.isSafeInteger(info.size) || info.size < 0 ||
      !Number.isInteger(info.mode) || info.mode < 0 || info.mode > 0o177777 ||
      (info.mode & 0o170000) !== (slot === 4 ? 0o040000 : 0o100000)) {
    throw failure('runtime_sqlite_metadata_unavailable');
  }
  const privateMode = slot === 4 ? 0o700 : 0o600;
  if ((info.mode & 0o7777) !== privateMode) throw failure('runtime_sqlite_permissions');
  if (operation === 'existsSync') return info.present;
  if (!info.present) throw failure('ENOENT');
  if (operation === 'chmodSync') {
    if (value !== privateMode) throw failure('runtime_sqlite_permissions');
    return undefined;
  }
  if (operation === 'mkdirSync' && slot === 4 && value?.recursive === true) return undefined;
  throw failure('runtime_sqlite_metadata_invalid');
}

/** Used ONLY for node:module imports of the exact pinned DatabaseClient.
 * Ordinary Node builtins and the normal host path retain their existing API.
 */
export function createRuntimeSqliteRequire(origin) {
  const original = nodeCreateRequire(origin);
  function require(specifier) {
    if (specifier !== 'better-sqlite3') return original(specifier);
    // The legacy loader creates require at module evaluation, before trusted
    // runtime assembly. Capture the generation when loading its constructor.
    const captured = new Map(entries);
    return function Database(filename, options) {
      const path = typeof filename === 'string' && isAbsolute(filename) && !filename.includes('\0') ? resolve(filename) : null;
      let entry = path && captured.get(path);
      let snapshot = null;
      if (!entry && path && backupName.test(basename(path))) {
        entry = [...captured.values()].find((candidate) => dirname(path) === candidate.backupDir);
        if (entry) snapshot = basename(path);
      }
      if (!entry) {
        if (nativeOnly) throw failure('runtime_sqlite_path_denied');
        const loaded = original('better-sqlite3');
        const Constructor = typeof loaded === 'function' ? loaded : loaded.default;
        return new Constructor(filename, options);
      }
      if (!entry.active || typeof entry.open !== 'function') throw failure('runtime_sqlite_closed');
      if (options !== undefined && (!options || typeof options !== 'object' || Array.isArray(options) ||
          Object.keys(options).some((key) => !['readonly', 'fileMustExist', 'timeout'].includes(key)) ||
          ['readonly', 'fileMustExist'].some((key) => options[key] !== undefined && typeof options[key] !== 'boolean') ||
          (options.timeout !== undefined && (!Number.isInteger(options.timeout) || options.timeout < 0 || options.timeout > 5000)))) {
        throw failure('runtime_sqlite_invalid');
      }
      if (snapshot && (typeof entry.openBackup !== 'function' || options?.readonly !== true || options?.fileMustExist !== true)) {
        throw failure('runtime_sqlite_path_denied');
      }
      try {
        // No primary database pathname reaches the opener. Snapshots have a
        // separate basename-only port; legacy paths are not implicitly granted.
        const copiedOptions = Object.freeze({ ...(options ?? {}) });
        const database = snapshot
          ? entry.openBackup(Object.freeze({ fileName: snapshot, options: copiedOptions }))
          : entry.open(copiedOptions);
        if (!database || ['exec', 'prepare', 'transaction', 'backup', 'close'].some((key) => typeof database[key] !== 'function')) {
          try { database?.close?.(); } catch {}
          throw failure('runtime_sqlite_invalid');
        }
        // Preserve the actual SQLite instance/transactions/drizzle API. Only
        // online backup is routed through an explicitly admitted owner port.
        // No backup pathname reaches either native callback.
        try {
          Object.defineProperty(database, 'backup', { value: async (destination) => {
            if (!entry.active) throw failure('runtime_sqlite_closed');
            if (snapshot || typeof destination !== 'string' || !isAbsolute(destination) || destination.includes('\0') ||
                dirname(resolve(destination)) !== entry.backupDir || !backupName.test(basename(destination)) ||
                typeof entry.backup !== 'function') throw failure('runtime_sqlite_backup_unavailable');
            try { return await entry.backup(database, Object.freeze({ fileName: basename(destination) })); }
            catch { throw failure('runtime_sqlite_backup_unavailable'); }
          } });
        } catch (error) {
          try { database.close(); } catch {}
          throw error;
        }
        entry.databases.add(database);
        return database;
      } catch { throw failure('runtime_sqlite_unavailable'); }
    };
  }
  // Preserve the require helper's standard fields for the pinned loader.
  return Object.assign(require, original);
}
