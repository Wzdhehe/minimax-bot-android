// SPDX-License-Identifier: AGPL-3.0-only
// Virtual namespace policy tests; not Android filesystem/UID evidence.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, existsSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { bindRuntimeSqlite, createRuntimeSqliteRequire } from './runtime-sqlite-binding.mjs';
import { runtimeBuildWiring } from './runtime-build-plugin.mjs';
import { lstat, realpath, mkdir, readdir, rm } from './runtime-sqlite-snapshot-fs.mjs';
const fileName = 'runtime-state-before-v2-migration-1794000000000-01234567-89ab-4def-8123-456789abcdef.sqlite';
const record = () => ({ fileName, bytes: 8192, createdAtMs: 1794000000123, identity: 'a'.repeat(32) });
const code = (expected) => (error) => error.code === expected && error.message === expected;
function fixture(overrides = {}) {
  const root = mkdtempSync(join(tmpdir(), 'snapshot-fs-'));
  let rows = [record()];
  const calls = [];
  const snapshots = {
    catalog() { assert.equal(this, snapshots); calls.push('catalog'); return rows; },
    remove(request) { assert.equal(this, snapshots); calls.push(request); rows = []; return 'removed'; },
    ...overrides,
  };
  const lease = bindRuntimeSqlite({ dataDir: root, open() {}, snapshots });
  return { root, lease, calls, snapshots, file: join(root, 'v2/sqlite/backups', fileName),
    close() { lease.finish(true); rmSync(root, { recursive: true, force: true }); } };
}
test('Main-admitted directory chain and snapshots have stable virtual stats without physical host IO', async () => {
  const fixtureValue = fixture();
  const { root, file } = fixtureValue;
  try {
    assert.equal(await realpath(root), root);
    for (const path of [root, join(root, 'v2'), join(root, 'v2/sqlite'), join(root, 'v2/sqlite/backups')]) {
      const info = await lstat(path);
      assert.equal(info.isDirectory(), true); assert.equal(info.isSymbolicLink(), false);
      assert.equal(info.mode & 0o7777, 0o700);
    }
    const before = await lstat(file), after = await lstat(file);
    assert.equal(before.isFile(), true); assert.equal(before.isSymbolicLink(), false);
    assert.equal(before.mode & 0o7777, 0o600); assert.equal(before.size, 8192);
    assert.deepEqual([before.dev, before.ino, before.size, before.mtimeMs, before.ctimeMs],
      [after.dev, after.ino, after.size, after.mtimeMs, after.ctimeMs]);
    assert.equal(existsSync(join(root, 'v2')), false);
  } finally { fixtureValue.close(); }
});
test('mkdir only acknowledges existing private directories and follows EEXIST semantics', async () => {
  const value = fixture();
  try {
    const path = join(value.root, 'v2/sqlite/backups');
    await mkdir(path, { recursive: true, mode: 0o700 });
    await assert.rejects(mkdir(path, { mode: 0o700 }), code('EEXIST'));
    await assert.rejects(mkdir(path, { recursive: true, mode: 0o777 }), code('runtime_sqlite_snapshot_invalid'));
    assert.equal(existsSync(path), false);
  } finally { value.close(); }
});
test('only confirmed catalog entries are listed and removed by their opaque identity', async () => {
  const value = fixture();
  try {
    const entries = await readdir(join(value.root, 'v2/sqlite/backups'), { withFileTypes: true });
    assert.equal(entries.length, 1); assert.equal(entries[0].name, fileName);
    assert.equal(entries[0].isFile(), true); assert.equal(entries[0].isSymbolicLink(), false);
    await rm(value.file);
    assert.deepEqual(value.calls.find((call) => typeof call === 'object'), { fileName, identity: record().identity });
    assert.equal(Object.isFrozen(value.calls.find((call) => typeof call === 'object')), true);
    await assert.rejects(lstat(value.file), code('ENOENT'));
    await assert.rejects(lstat(value.file + '-wal'), code('ENOENT'));
    await assert.rejects(rm(join(value.root, 'v2/sqlite/backups'), { recursive: true }), code('runtime_sqlite_path_denied'));
  } finally { value.close(); }
});
test('missing or malformed native snapshot metadata cannot fall back to physical files', async () => {
  const root = mkdtempSync(join(tmpdir(), 'snapshot-missing-'));
  const lease = bindRuntimeSqlite({ dataDir: root, open() {} });
  try { await assert.rejects(realpath(root), code('runtime_sqlite_backup_unavailable')); }
  finally { lease.finish(true); rmSync(root, { recursive: true, force: true }); }
  const value = fixture({ catalog() { return [{ ...record(), baseUrl: 'private_endpoint' }]; } });
  try { await assert.rejects(lstat(value.file), code('runtime_snapshot_catalog_invalid')); }
  finally { value.close(); }
});
test('unknown removal poisons further snapshot operations without a retry', async () => {
  let removes = 0;
  const value = fixture({ remove() { removes++; return 'outcome_unknown'; } });
  try {
    await assert.rejects(rm(value.file), code('runtime_sqlite_snapshot_outcome_unknown'));
    await assert.rejects(lstat(value.file), code('runtime_sqlite_snapshot_outcome_unknown'));
    await assert.rejects(rm(value.file), code('runtime_sqlite_snapshot_outcome_unknown'));
    assert.equal(removes, 1);
  } finally { value.close(); }
});
test('late catalog replies cannot revive a closed scope and its root cannot be rebound', async () => {
  let resolve;
  const value = fixture({ catalog() { return new Promise((done) => { resolve = done; }); } });
  try {
    const pending = lstat(value.file);
    value.lease.deactivate(); resolve([record()]);
    await assert.rejects(pending, code('runtime_sqlite_closed'));
    value.lease.finish(true);
    assert.throws(() => bindRuntimeSqlite({ dataDir: value.root, open() {} }), code('runtime_sqlite_busy'));
  } finally { value.close(); }
});
test('ordinary host paths retain Node behavior and do not share the virtual namespace', async () => {
  const root = mkdtempSync(join(tmpdir(), 'snapshot-ordinary-'));
  try { const file = join(root, 'ordinary'); writeFileSync(file, 'abc'); assert.equal((await lstat(file)).size, 3); }
  finally { rmSync(root, { recursive: true, force: true }); }
});
test('the import seam affects ONLY the pinned migration backup helper', () => {
  const source = '/pinned-source';
  const resolvers = [];
  const wiring = runtimeBuildWiring(source);
  wiring.plugin.setup({ onResolve(options, callback) { resolvers.push({ ...options, callback }); } });
  const resolver = resolvers.find((item) => item.filter.test('node:fs/promises'));
  assert.match(resolver.callback({ importer: join(source, 'packages/local-runtime-v2/src/infra/db/backup.ts') }).path, /runtime-sqlite-snapshot-fs\.mjs$/u);
  assert.equal(resolver.callback({ importer: join(source, 'packages/other.ts') }), undefined);
});
test('a bare native status cannot silently claim a successful backup', async () => {
  const value = fixture();
  const dataDir = mkdtempSync(join(tmpdir(), 'snapshot-result-'));
  const raw = { open: true, exec() {}, prepare() {}, transaction() {}, backup() {}, close() { this.open = false; } };
  const lease = bindRuntimeSqlite({ dataDir, open() { return raw; }, backup() { return 'outcome_unknown'; }, openBackup() {}, snapshots: value.snapshots });
  try {
    const Database = createRuntimeSqliteRequire(import.meta.url)('better-sqlite3');
    const database = new Database(join(dataDir, 'v2/sqlite/runtime-state.sqlite'));
    await assert.rejects(database.backup(join(dataDir, 'v2/sqlite/backups', fileName)), code('runtime_sqlite_backup_unavailable'));
  } finally { lease.finish(true); value.close(); rmSync(dataDir, { recursive: true, force: true }); }
});
