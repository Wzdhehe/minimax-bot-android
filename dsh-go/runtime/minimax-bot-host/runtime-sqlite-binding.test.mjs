// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import { join } from 'node:path';
import { test } from 'node:test';
import { bindRuntimeSqlite, createRuntimeSqliteRequire } from './runtime-sqlite-binding.mjs';
import { runtimeBuildWiring } from './runtime-build-plugin.mjs';

let serial = 0;
const root = () => join(process.cwd(), `sqlite-capability-test-${++serial}`);
const filename = (dataDir) => join(dataDir, 'v2/sqlite/runtime-state.sqlite');
const database = () => ({ exec() {}, prepare() {}, transaction() {}, backup() {}, close() {} });
const constructor = () => createRuntimeSqliteRequire(import.meta.url)('better-sqlite3');
const code = (expected) => (error) => error.code === expected && error.message === expected;

test('the admitted SQLite capability receives only frozen open options, never a path', () => {
  const dataDir = root();
  const calls = [];
  const raw = database();
  const lease = bindRuntimeSqlite({ dataDir, open(...args) { calls.push(args); return raw; } });
  const Database = constructor();
  assert.equal(new Database(filename(dataDir), { readonly: true, fileMustExist: true, timeout: 5000 }), raw);
  assert.deepEqual(calls, [[{ readonly: true, fileMustExist: true, timeout: 5000 }]]);
  assert.equal(Object.isFrozen(calls[0][0]), true);
  lease.finish(true);
});

test('native mode refuses unrelated, backup, relative and module-selected database paths', () => {
  const dataDir = root();
  let calls = 0;
  const lease = bindRuntimeSqlite({ dataDir, open() { calls++; return database(); } });
  // The upstream Electron/environment override changes createRequire's origin;
  // it must not cause an npm constructor to be loaded in native mode.
  const Database = createRuntimeSqliteRequire(join(root(), 'uninstalled/package.json'))('better-sqlite3');
  for (const path of [filename(root()), `${filename(dataDir)}.backup`, 'runtime-state.sqlite', ':memory:', '/tmp/\0bad']) {
    assert.throws(() => new Database(path), code('runtime_sqlite_path_denied'));
  }
  assert.equal(calls, 0);
  assert.ok(new Database(filename(dataDir)));
  lease.finish(true);
});

test('native options cannot select a module, binding, path, logging callback or invalid timeout', () => {
  const dataDir = root();
  let calls = 0;
  const lease = bindRuntimeSqlite({ dataDir, open() { calls++; return database(); } });
  const Database = constructor();
  for (const options of [null, [], { nativeBinding: '/tmp/module' }, { filename: 'other' },
    { verbose() {} }, { timeout: -1 }, { timeout: 5001 }, { timeout: 1.1 }, { readonly: 'true' }, { fileMustExist: 1 }]) {
    assert.throws(() => new Database(filename(dataDir), options), code('runtime_sqlite_invalid'));
  }
  assert.equal(calls, 0);
  lease.finish(true);
});

test('native failures are stable and an invalid returned handle is closed', () => {
  const dataDir = root();
  let mode = 'throw', closed = 0;
  const lease = bindRuntimeSqlite({ dataDir, open() {
    if (mode === 'throw') throw Error('private native path and diagnostic');
    return { close() { closed++; } };
  } });
  const Database = constructor();
  assert.throws(() => new Database(filename(dataDir)), code('runtime_sqlite_unavailable'));
  mode = 'invalid';
  assert.throws(() => new Database(filename(dataDir)), code('runtime_sqlite_unavailable'));
  assert.equal(closed, 1);
  lease.finish(false);
});

test('migration backups use a basename capability and can only reopen read-only for integrity checking', async () => {
  const dataDir = root();
  const name = 'runtime-state-before-v2-migration-1790000000000-01234567-89ab-4cde-8fab-0123456789ab.sqlite';
  const path = join(dataDir, 'v2/sqlite/backups', name);
  const calls = [];
  const primary = database();
  const snapshot = database();
  const lease = bindRuntimeSqlite({ dataDir, open() { return primary; },
    async backup(db, request) { calls.push([db, request]); return { totalPages: 4, remainingPages: 0 }; },
    openBackup(request) { calls.push(request); return snapshot; },
  });
  const Database = constructor();
  const db = new Database(filename(dataDir));
  assert.deepEqual(await db.backup(path), { totalPages: 4, remainingPages: 0 });
  assert.equal(calls[0][0], primary);
  assert.deepEqual(calls[0][1], { fileName: name });
  assert.equal(Object.isFrozen(calls[0][1]), true);
  assert.equal(new Database(path, { readonly: true, fileMustExist: true }), snapshot);
  assert.deepEqual(calls[1], { fileName: name, options: { readonly: true, fileMustExist: true } });
  assert.equal(Object.isFrozen(calls[1]), true);
  for (const options of [undefined, {}, { readonly: true }, { readonly: false, fileMustExist: true }]) {
    assert.throws(() => new Database(path, options), code('runtime_sqlite_path_denied'));
  }
  for (const denied of [join(root(), name), join(dataDir, 'v2/sqlite/backups/arbitrary.sqlite'), 'relative.sqlite']) {
    await assert.rejects(db.backup(denied), code('runtime_sqlite_backup_unavailable'));
  }
  await assert.rejects(snapshot.backup(path), code('runtime_sqlite_backup_unavailable'));
  assert.equal(calls.length, 2);
  lease.deactivate();
  await assert.rejects(db.backup(path), code('runtime_sqlite_closed'));
  lease.finish(true);
});

test('a missing or failing native backup is never presented as a successful migration snapshot', async () => {
  const dataDir = root();
  const path = join(dataDir, 'v2/sqlite/backups/runtime-state-before-v2-migration-1-01234567-89ab-4cde-8fab-0123456789ab.sqlite');
  const lease = bindRuntimeSqlite({ dataDir, open: database });
  await assert.rejects(new (constructor())(filename(dataDir)).backup(path), code('runtime_sqlite_backup_unavailable'));
  lease.finish(true);
  const next = bindRuntimeSqlite({ dataDir, open: database,
    backup() { throw Error('private snapshot owner path'); }, openBackup: database });
  await assert.rejects(new (constructor())(filename(dataDir)).backup(path), code('runtime_sqlite_backup_unavailable'));
  next.finish(false);
});

test('revocation is immediate and a saved constructor never reaches a later generation', () => {
  const dataDir = root();
  let firstCalls = 0, secondCalls = 0;
  const first = bindRuntimeSqlite({ dataDir, open() { firstCalls++; return database(); } });
  const Saved = constructor();
  new Saved(filename(dataDir));
  first.deactivate();
  assert.throws(() => new Saved(filename(dataDir)), code('runtime_sqlite_closed'));
  assert.throws(() => bindRuntimeSqlite({ dataDir, open: database }), code('runtime_sqlite_busy'));
  first.finish(true);
  const second = bindRuntimeSqlite({ dataDir, open() { secondCalls++; return database(); } });
  assert.throws(() => new Saved(filename(dataDir)), code('runtime_sqlite_closed'));
  new (constructor())(filename(dataDir));
  assert.equal(firstCalls, 1);
  assert.equal(secondCalls, 1);
  first.finish(true);
  new (constructor())(filename(dataDir));
  assert.equal(secondCalls, 2);
  second.finish(true);
});

test('failed teardown cannot be relabelled successful or fall back to npm SQLite', () => {
  const dataDir = root();
  const lease = bindRuntimeSqlite({ dataDir, open: database });
  lease.finish(false);
  lease.finish(true);
  assert.throws(() => bindRuntimeSqlite({ dataDir, open: database }), code('runtime_sqlite_busy'));
  assert.throws(() => new (constructor())(filename(dataDir)), code('runtime_sqlite_closed'));
  assert.throws(() => new (constructor())(filename(root())), code('runtime_sqlite_path_denied'));
});

test('failed startup closes adopted handles and a failed final close prevents reuse', () => {
  const dataDir = root();
  let closed = 0;
  const first = bindRuntimeSqlite({ dataDir, open() { return { ...database(), close() { closed++; } }; } });
  new (constructor())(filename(dataDir));
  first.finish(false);
  assert.equal(closed, 1);
  assert.throws(() => bindRuntimeSqlite({ dataDir, open: database }), code('runtime_sqlite_busy'));
  const other = root();
  const second = bindRuntimeSqlite({ dataDir: other, open() { return { ...database(), close() { throw Error('busy native statement'); } }; } });
  new (constructor())(filename(other));
  assert.throws(() => second.finish(true), code('runtime_sqlite_close_failed'));
  assert.throws(() => bindRuntimeSqlite({ dataDir: other, open: database }), code('runtime_sqlite_busy'));
});

test('the adapted require preserves ordinary builtin loading and standard helpers', () => {
  const require = createRuntimeSqliteRequire(import.meta.url);
  assert.equal(require('node:path').join, join);
  assert.equal(require.resolve('node:fs'), 'node:fs');
  assert.ok(require.cache);
});

test('a legacy loader created before assembly captures the generation when it loads SQLite', () => {
  const require = createRuntimeSqliteRequire(import.meta.url);
  const dataDir = root();
  let calls = 0;
  const lease = bindRuntimeSqlite({ dataDir, open() { calls++; return database(); } });
  new (require('better-sqlite3'))(filename(dataDir));
  assert.equal(calls, 1);
  lease.finish(true);
});

test('the build intercepts node:module only at the two pinned SQLite loaders', () => {
  const source = root();
  const wiring = runtimeBuildWiring(source);
  const resolvers = [];
  wiring.plugin.setup({ onResolve(options, callback) { resolvers.push({ ...options, callback }); } });
  const resolver = resolvers.find((entry) => entry.filter.test('node:module'));
  const importer = join(source, 'packages/local-runtime-v2/src/infra/db/client.ts');
  assert.match(resolver.callback({ importer, path: 'node:module' }).path, /runtime-sqlite-module\.mjs$/);
  const legacy = join(source, 'packages/local-runtime/src/persistence/better-sqlite3-loader.ts');
  assert.match(resolver.callback({ importer: legacy, path: 'node:module' }).path, /runtime-sqlite-module\.mjs$/);
  assert.equal(resolver.callback({ importer: join(source, 'packages/other.ts'), path: 'node:module' }), undefined);
  assert.ok(wiring.adaptations.some((entry) => entry.importer.endsWith('infra/db/client.ts')));
});
