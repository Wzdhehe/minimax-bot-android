// SPDX-License-Identifier: AGPL-3.0-only
// Linux host acceptance only. Real runtime + prepared addon + four inherited
// SQLite FDs; ordinary runtime FS remains host FS, not Android isolation proof.
import assert from 'node:assert/strict';
import { closeSync, existsSync, readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const [library, wrapperPath, bundle, dataDir] = process.argv.slice(2);
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
const { createBuiltinBotRuntime } = await import(pathToFileURL(join(bundle, 'runtime-host.partial.mjs')).href);
// The legacy DB setup performs chmod on the logical database pathname after
// opening SQLite. Four FD VFS is not a node:fs metadata adapter: without that
// capability the real factory must stop, not create a path-based DB fallback.
await assert.rejects(createBuiltinBotRuntime({ dataDir, appVersion: 'host-real-runtime-fd-sqlite', nativeSqlite }),
  // The pinned migration preflight wraps the host errno in its own Error.
  // This is trusted fixture diagnosis, never parsing a provider response.
  (error) => error.message.startsWith('current_local_runtime_v2_migration_failed:sqlite:runtime-state:open:ENOENT:'));
assert.ok(opened.length > 0, 'the real factory must have opened the FD-backed database');
assert.ok(opened.every((db) => !db.open), 'failed factory startup must close adopted handles');
assert.equal(existsSync(join(dataDir, 'v2/sqlite/runtime-state.sqlite')), false);
await assert.rejects(createBuiltinBotRuntime({ dataDir, appVersion: 'host-real-runtime-fd-sqlite', nativeSqlite }),
  (error) => error.code === 'runtime_sqlite_busy');
const verification = new Database('state.db', { nativeBinding: addon.exports, readonly: true, fileMustExist: true });
try {
  assert.equal(verification.pragma('integrity_check', { simple: true }), 'ok');
  assert.equal(verification.prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'local_runtime_v2_schema_migrations'").get(), undefined);
} finally { verification.close(); }
console.log('PASS: real runtime four-FD startup refuses missing FS metadata, closes handles and blocks reuse; no migrations applied, host only');
