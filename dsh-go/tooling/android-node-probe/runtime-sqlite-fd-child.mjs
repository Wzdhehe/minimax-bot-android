// SPDX-License-Identifier: AGPL-3.0-only
// Linux host acceptance only. Real runtime + prepared addon + four inherited
// SQLite FDs; ordinary runtime FS remains host FS, not Android isolation proof.
import assert from 'node:assert/strict';
import { closeSync, existsSync, readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const [library, wrapperPath, bundle, dataDir, pass] = process.argv.slice(2);
const withMetadata = pass !== '0';
const withSnapshotNamespace = pass === '2';
const require = createRequire(import.meta.url);
const wrapper = { exports: {} }, addon = { exports: {} };
new Function('require', 'module', 'exports', readFileSync(wrapperPath, 'utf8'))(require, wrapper, wrapper.exports);
process.dlopen(addon, library);
assert.equal(addon.exports.setFdVfs(3, 4, 5, 6), true);
for (const fd of [3, 4, 5, 6]) closeSync(fd);
const Database = wrapper.exports;
const opened = [];
const nativeSqlite = { open(options) {
  assert.equal(arguments.length, 1);
  assert.equal(Object.isFrozen(options), true);
  // The only admitted name in the native VFS. Runtime's logical pathname is
  // never passed into the addon, nor can a request select nativeBinding.
  const db = new Database('state.db', { ...options, nativeBinding: addon.exports });
  opened.push(db);
  return db;
} };
if (withMetadata) nativeSqlite.metadata = (slot) => addon.exports.fdVfsMetadata(slot);
let catalogs = 0;
if (withSnapshotNamespace) nativeSqlite.snapshots = {
  // Host policy fixture ONLY: an empty admitted namespace, not a snapshot FD
  // or a copying capability. The next negative case exercises the still-
  // mandatory actual copy independently of namespace admission.
  catalog() { catalogs++; return []; },
  remove() { throw Error('no_snapshot_was_granted'); },
};
const { createBuiltinBotRuntime } = await import(pathToFileURL(join(bundle, 'runtime-host.partial.mjs')).href);
// No metadata: refuse before opening SQLite. With real FD metadata: pass the
// original private-mode checks and reach mandatory migration backup. Without
// a snapshot namespace, reject directory admission; with that read-only view
// alone, copying MUST still fail. Neither case may apply v2 migrations.
await assert.rejects(createBuiltinBotRuntime({ dataDir, appVersion: 'host-real-runtime-fd-sqlite', nativeSqlite }),
  (error) => withMetadata
    ? error.code === 'LOCAL_RUNTIME_V2_DATABASE_BACKUP_FAILED' && (withSnapshotNamespace
      ? error.cause?.code === 'runtime_sqlite_backup_unavailable'
      : error.cause?.message === 'unsafe backup directory')
    // Trusted preflight diagnosis, not parsing an untrusted provider response.
    : error.message.startsWith('current_local_runtime_v2_migration_failed:sqlite:runtime-state:open:runtime_sqlite_metadata_unavailable'));
assert.equal(opened.length > 0, withMetadata);
assert.equal(catalogs > 0, withSnapshotNamespace);
assert.ok(opened.every((db) => !db.open), 'failed factory startup must close adopted handles');
assert.equal(existsSync(join(dataDir, 'v2/sqlite/runtime-state.sqlite')), false);
await assert.rejects(createBuiltinBotRuntime({ dataDir, appVersion: 'host-real-runtime-fd-sqlite', nativeSqlite }),
  (error) => error.code === 'runtime_sqlite_busy');
const verification = new Database('state.db', { nativeBinding: addon.exports, readonly: true, fileMustExist: true });
try {
  assert.equal(verification.pragma('integrity_check', { simple: true }), 'ok');
  assert.equal(verification.prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'local_runtime_v2_schema_migrations'").get(), undefined);
  assert.equal(Boolean(verification.prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'local_runtime_sessions'").get()), withMetadata);
} finally { verification.close(); }
console.log(withMetadata
  ? withSnapshotNamespace
    ? 'PASS: real FD runtime with an empty snapshot namespace still refuses missing copy capability; no v2 migration applied, host only'
    : 'PASS: real FD runtime passes private-mode checks and refuses an ungranted snapshot namespace; no v2 migration applied, host only'
  : 'PASS: real runtime four-FD startup refuses missing FS metadata before opening SQLite; host only');
