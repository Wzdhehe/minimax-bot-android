// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import { existsSync as physicalExists, mkdirSync as physicalMkdir, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { bindRuntimeSqlite } from './runtime-sqlite-binding.mjs';
import fs, { chmodSync, existsSync, mkdirSync } from './runtime-sqlite-fs.mjs';

const code = (expected) => (error) => error.code === expected && error.message === expected;
const info = (slot) => ({ present: slot === 0 || slot === 4, mode: slot === 4 ? 0o40700 : 0o100600, size: 0 });
function fixture(t) {
  const parent = mkdtempSync(join(tmpdir(), 'mbot-sqlite-fs-'));
  t.after(() => rmSync(parent, { recursive: true, force: true }));
  const root = join(parent, 'not-a-physical-runtime');
  return { root, directory: join(root, 'v2/sqlite'), main: join(root, 'v2/sqlite/runtime-state.sqlite') };
}

test('ordinary host filesystem calls retain their existing behavior', (t) => {
  const { directory, main } = fixture(t);
  mkdirSync(directory, { recursive: true });
  writeFileSync(main, 'host fixture');
  chmodSync(main, 0o600);
  assert.equal(existsSync(main), true);
  assert.equal(fs.existsSync(main), true);
  assert.equal(existsSync(`${main}-wal`), false);
});

test('native SQLite metadata uses fixed numeric slots and creates no physical path', (t) => {
  const { root, directory, main } = fixture(t);
  const calls = [];
  const lease = bindRuntimeSqlite({ dataDir: root, open() {}, metadata(slot) { calls.push(slot); return info(slot); } });
  t.after(() => lease.finish(true));
  assert.equal(mkdirSync(directory, { recursive: true, mode: 0o700 }), undefined);
  chmodSync(directory, 0o700);
  chmodSync(main, 0o600);
  assert.equal(existsSync(main), true);
  assert.equal(existsSync(`${main}-wal`), false);
  assert.equal(physicalExists(directory), false);
  assert.ok(calls.every((slot) => Number.isInteger(slot) && slot >= 0 && slot <= 4));
  assert.deepEqual(calls, [4, 4, 0, 0, 1]);
  assert.throws(() => chmodSync(main, 0o777), code('runtime_sqlite_permissions'));
  assert.throws(() => mkdirSync(main, { recursive: true }), code('runtime_sqlite_metadata_invalid'));
  assert.throws(() => existsSync(join(directory, 'other.db')), code('runtime_sqlite_path_denied'));
});

test('a native lease without metadata never falls back to a real path', (t) => {
  const { root, directory, main } = fixture(t);
  physicalMkdir(directory, { recursive: true, mode: 0o700 });
  writeFileSync(main, 'must not be interpreted as a native database');
  const lease = bindRuntimeSqlite({ dataDir: root, open() {} });
  t.after(() => lease.finish(false));
  assert.equal(physicalExists(main), true);
  assert.throws(() => existsSync(main), code('runtime_sqlite_metadata_unavailable'));
});

test('bad metadata or a failed callback is a failure, never a false missing file', (t) => {
  const { root, main } = fixture(t);
  let next;
  const lease = bindRuntimeSqlite({ dataDir: root, open() {}, metadata() {
    if (next instanceof Error) throw next;
    return next;
  } });
  t.after(() => lease.finish(false));
  for (next of [null, {}, { ...info(0), present: 'true' }, { ...info(0), size: NaN },
    { ...info(0), size: Number.MAX_SAFE_INTEGER + 1 }, info(4), Error('private owner diagnostic')]) {
    assert.throws(() => existsSync(main), code('runtime_sqlite_metadata_unavailable'));
  }
  for (next of [{ ...info(0), mode: 0o100644 }, { ...info(0), mode: 0o104600 }]) {
    assert.throws(() => existsSync(main), code('runtime_sqlite_permissions'));
  }
});

test('a retired filesystem root stays revoked for the life of the execution unit', (t) => {
  const { root, main } = fixture(t);
  let calls = 0;
  const metadata = (slot) => { calls++; return info(slot); };
  const lease = bindRuntimeSqlite({ dataDir: root, open() {}, metadata });
  assert.equal(existsSync(main), true);
  lease.deactivate();
  assert.throws(() => existsSync(main), code('runtime_sqlite_closed'));
  lease.finish(true);
  assert.throws(() => bindRuntimeSqlite({ dataDir: root, open() {}, metadata }), code('runtime_sqlite_busy'));
  assert.equal(calls, 1);
});

test('an old constructor-only root cannot later acquire a fresh filesystem view', (t) => {
  const { root } = fixture(t);
  const lease = bindRuntimeSqlite({ dataDir: root, open() {} });
  lease.finish(true);
  assert.throws(() => bindRuntimeSqlite({ dataDir: root, open() {}, metadata: info }), code('runtime_sqlite_busy'));
});

test('metadata cannot return an accepted answer after its lease closed during the callback', (t) => {
  const { root, main } = fixture(t);
  const lease = bindRuntimeSqlite({ dataDir: root, open() {}, metadata(slot) { lease.deactivate(); return info(slot); } });
  t.after(() => lease.finish(false));
  assert.throws(() => existsSync(main), code('runtime_sqlite_closed'));
});

test('zero-length sidecars and present private files keep their distinct metadata semantics', (t) => {
  const { root, main } = fixture(t);
  let present = false;
  const lease = bindRuntimeSqlite({ dataDir: root, open() {}, metadata(slot) { return { ...info(slot), present }; } });
  t.after(() => lease.finish(true));
  assert.equal(existsSync(`${main}-wal`), false);
  assert.throws(() => chmodSync(`${main}-wal`, 0o600), code('ENOENT'));
  present = true;
  assert.equal(existsSync(`${main}-wal`), true);
  assert.equal(chmodSync(`${main}-wal`, 0o600), undefined);
});
