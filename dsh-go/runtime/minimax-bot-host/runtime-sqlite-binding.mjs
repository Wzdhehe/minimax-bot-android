// SPDX-License-Identifier: AGPL-3.0-only
import { createRequire as nodeCreateRequire } from 'node:module';
import { basename, dirname, isAbsolute, join, resolve } from 'node:path';
import { validateSnapshotCatalog, validSnapshotName } from './runtime-snapshot-port.mjs';

const entries = new Map();
let nativeOnly = false;
const failure = (code) => Object.assign(new Error(code), { code });
// Exact filename grammar of the pinned infra/db/backup.ts. This does not grant
// access to a directory: each native callback receives a validated basename.
const backupName = /^runtime-state-before-v2-migration-\d+-[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\.sqlite$/iu;
export const SQLITE_FS_UNHANDLED = Symbol('ordinary_host_filesystem');
const databaseFiles = ['runtime-state.sqlite', 'runtime-state.sqlite-wal', 'runtime-state.sqlite-shm', 'runtime-state.sqlite-journal'];
let virtualNamespace = 0;

/** Native supplies an already-admitted SQLite constructor capability, not a
 * module path or a database path. This only adapts the pinned DB loader; the
 * launcher must separately assemble its admitted filesystem and FD VFS.
 */
export function bindRuntimeSqlite({ dataDir, open, backup, openBackup, metadata, snapshots } = {}) {
  if (typeof dataDir !== 'string' || !isAbsolute(dataDir) || dataDir.includes('\0') || typeof open !== 'function') {
    throw failure('runtime_sqlite_invalid');
  }
  if ((backup !== undefined || openBackup !== undefined) &&
      (typeof backup !== 'function' || typeof openBackup !== 'function')) throw failure('runtime_sqlite_invalid');
  if (metadata !== undefined && typeof metadata !== 'function') throw failure('runtime_sqlite_invalid');
  if (snapshots !== undefined && (!snapshots || typeof snapshots.catalog !== 'function' || typeof snapshots.remove !== 'function')) throw failure('runtime_sqlite_invalid');
  const path = join(resolve(dataDir), 'v2/sqlite/runtime-state.sqlite');
  // FS imports are module-level. A native FS view is one execution-unit lease,
  // so its logical root cannot be rebound in this process after retirement.
  if (entries.has(path) && (!entries.get(path).drained || entries.get(path).hadMetadata || entries.get(path).hadSnapshotView ||
      metadata !== undefined || snapshots !== undefined)) throw failure('runtime_sqlite_busy');
  const entry = { active: true, drained: false, open, backup, openBackup,
    metadata, hadMetadata: metadata !== undefined, directory: dirname(path), root: resolve(dataDir),
    snapshots: snapshots ? { catalog: snapshots.catalog.bind(snapshots), remove: snapshots.remove.bind(snapshots) } : null,
    hadSnapshotView: snapshots !== undefined, snapshotUncertain: false, namespace: ++virtualNamespace, snapshotInodes: new Map(),
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
      entry.snapshots = null;
      entry.snapshotInodes.clear();
      if (verified === true && !closed) throw failure('runtime_sqlite_close_failed');
    },
  });
}

/** Exact backup.ts promises seam. All paths under an admitted logical root
 * are handled/refused here; never fall back to the physical host there. The
 * directory chain is precreated by Main's snapshot owner. Stats identities
 * describe this virtual namespace, never Main's physical paths/inodes.
 */
export async function runtimeSqliteSnapshotFilesystem(operation, input, options) {
  if (typeof input !== 'string' || !isAbsolute(input) || input.includes('\0')) return SQLITE_FS_UNHANDLED;
  const path = resolve(input);
  const entry = [...entries.values()].filter((item) => path === item.root || path.startsWith(item.root.endsWith('/') ? item.root : item.root + '/'))
    .sort((left, right) => right.root.length - left.root.length)[0];
  if (!entry) return SQLITE_FS_UNHANDLED;
  entry.hadSnapshotView = true;
  const check = () => {
    if (!entry.active) throw failure('runtime_sqlite_closed');
    if (entry.snapshotUncertain) throw failure('runtime_sqlite_snapshot_outcome_unknown');
    if (!entry.snapshots) throw failure('runtime_sqlite_backup_unavailable');
  };
  check();
  const directories = [entry.root, join(entry.root, 'v2'), entry.directory, entry.backupDir];
  const directory = directories.includes(path);
  const fileName = !directory && dirname(path) === entry.backupDir ? basename(path) : null;
  if (!directory && (!fileName || !validSnapshotName(fileName))) throw failure('ENOENT');
  let rows;
  try { rows = await entry.snapshots.catalog(); } catch { check(); throw failure('runtime_sqlite_snapshot_unavailable'); }
  check();
  rows = validateSnapshotCatalog(rows);
  const row = fileName && rows.find((item) => item.fileName === fileName);
  if (!directory && !row) throw failure('ENOENT');
  if (operation === 'realpath') {
    if (options !== undefined) throw failure('runtime_sqlite_snapshot_invalid');
    return path;
  }
  if (operation === 'mkdir') {
    if (!directory || !options || typeof options !== 'object' ||
        Object.keys(options).some((key) => !['mode', 'recursive'].includes(key)) ||
        (options.mode !== undefined && options.mode !== 0o700) ||
        (options.recursive !== undefined && typeof options.recursive !== 'boolean')) throw failure('runtime_sqlite_snapshot_invalid');
    if (options.recursive !== true) throw failure('EEXIST');
    return undefined; // Already admitted, never chmod/mkdir a physical path.
  }
  const kindMethods = (kind) => ({ isDirectory: () => kind === 'directory', isFile: () => kind === 'file',
    isSymbolicLink: () => false, isBlockDevice: () => false, isCharacterDevice: () => false, isFIFO: () => false, isSocket: () => false });
  if (operation === 'lstat') {
    if (options !== undefined) throw failure('runtime_sqlite_snapshot_invalid');
    let ino;
    if (directory) ino = directories.indexOf(path) + 1;
    else {
      if (!entry.snapshotInodes.has(row.identity)) {
        if (entry.snapshotInodes.size >= 64) throw failure('runtime_sqlite_snapshot_invalid');
        entry.snapshotInodes.set(row.identity, entry.snapshotInodes.size + 5);
      }
      ino = entry.snapshotInodes.get(row.identity);
    }
    return Object.freeze({ ...kindMethods(directory ? 'directory' : 'file'), dev: entry.namespace, ino,
      mode: directory ? 0o040700 : 0o100600, size: directory ? 0 : row.bytes,
      mtimeMs: directory ? 0 : row.createdAtMs, ctimeMs: directory ? 0 : row.createdAtMs });
  }
  if (operation === 'readdir') {
    if (path !== entry.backupDir || !options || options.withFileTypes !== true || Object.keys(options).length !== 1) throw failure('runtime_sqlite_snapshot_invalid');
    return rows.map((item) => Object.freeze({ name: item.fileName, ...kindMethods('file') }));
  }
  if (operation === 'rm' && row && options === undefined) {
    let result;
    try { result = await entry.snapshots.remove(Object.freeze({ fileName: row.fileName, identity: row.identity })); }
    catch { entry.snapshotUncertain = true; check(); }
    if (result !== 'removed') entry.snapshotUncertain = true;
    check();
    return undefined;
  }
  throw failure('runtime_sqlite_path_denied');
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
      if (snapshot && entry.snapshotUncertain) throw failure('runtime_sqlite_snapshot_outcome_unknown');
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
            try {
              const result = await entry.backup(database, Object.freeze({ fileName: basename(destination) }));
              if (!entry.active) throw failure('runtime_sqlite_closed');
              if (entry.snapshots && result !== 'published') throw failure('runtime_sqlite_backup_unavailable');
              return result;
            }
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
