// SPDX-License-Identifier: AGPL-3.0-only
// Policy fixture only. Native FD consumption/SQLite integrity need native tests.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createNativeSnapshotSqlitePort, validSnapshotName, validateSnapshotCatalog } from './runtime-snapshot-port.mjs';
const fileName = 'runtime-state-before-v2-migration-1794000000000-01234567-89ab-4def-8123-456789abcdef.sqlite';
const row = () => ({ fileName, bytes: 8192, createdAtMs: 1794000000123, identity: 'a'.repeat(32) });
const code = (expected) => (error) => error.code === expected && error.message === expected;
function fixture(overrides = {}) {
  const calls = [], database = { close() { calls.push(['close']); } };
  const native = {
    copy(name) { assert.equal(this, native); calls.push(['copy', name]); return 'published'; },
    catalog() { assert.equal(this, native); calls.push(['catalog']); return JSON.stringify([row()]); },
    openReadOnly(name, identity) { assert.equal(this, native); calls.push(['open', name, identity]); return 42; },
    remove(name, identity) { assert.equal(this, native); calls.push(['remove', name, identity]); return 'removed'; },
    openDatabase(fd, options) { assert.equal(this, native); calls.push(['consume', fd, options]); return database; },
    ...overrides,
  };
  return { native, calls, database, port: createNativeSnapshotSqlitePort(native) };
}
test('native calls retain their receiver and carry a basename/identity, never a source or directory', async () => {
  const { port, calls, database } = fixture();
  assert.equal(await port.backup({ privateDatabase: true }, { fileName }), 'published');
  assert.equal(port.openBackup({ fileName, options: { readonly: true, fileMustExist: true } }), database);
  assert.equal(await port.snapshots.remove({ fileName, identity: row().identity }), 'removed');
  assert.deepEqual(calls.filter((call) => call[0] === 'copy'), [['copy', fileName]]);
  assert.deepEqual(calls.find((call) => call[0] === 'open'), ['open', fileName, row().identity]);
  assert.equal(Object.isFrozen(calls.find((call) => call[0] === 'consume')[2]), true);
});
test('catalogs are copied/frozen and reject malformed, duplicate, extra-field or sparse input', () => {
  const original = [row()];
  const safe = validateSnapshotCatalog(original);
  original[0].bytes = 7;
  assert.equal(safe[0].bytes, 8192);
  assert.ok(Object.isFrozen(safe) && Object.isFrozen(safe[0]));
  const cases = [null, {}, Array(1), [row(), row()], [{ ...row(), bytes: '8192' }],
    [{ ...row(), bytes: 0 }], [{ ...row(), bytes: 512 * 1024 * 1024 + 1 }],
    [{ ...row(), identity: 'invalid' }], [{ ...row(), createdAtMs: NaN }], [{ ...row(), apiKey: 'never_echo_this' }]];
  for (const input of cases) assert.throws(() => validateSnapshotCatalog(input), code('runtime_snapshot_catalog_invalid'));
  let getters = 0;
  const accessor = row(); Object.defineProperty(accessor, 'bytes', { get() { getters++; return 8192; } });
  assert.throws(() => validateSnapshotCatalog([accessor]), code('runtime_snapshot_catalog_invalid'));
  assert.equal(getters, 0);
});
test('names cannot select paths, another database, an unsafe timestamp or a noncanonical UUID', () => {
  assert.equal(validSnapshotName(fileName), true);
  for (const bad of ['../' + fileName, '/tmp/' + fileName, 'state.db', fileName.replace('1794000000000', '9999999999999999'),
    fileName.replace('4def', '0def'), fileName + '-wal']) assert.equal(validSnapshotName(bad), false);
});
test('only a definitive published status succeeds; known refusal never becomes success', async () => {
  for (const [status, error] of [['already_exists', 'runtime_snapshot_exists'], ['not_published', 'runtime_snapshot_refused']]) {
    const { port, calls } = fixture({ copy() { calls.push(['copy']); return status; } });
    await assert.rejects(port.backup(null, { fileName }), code(error));
    assert.equal(calls.filter((call) => call[0] === 'copy').length, 1);
    assert.equal(port.snapshots.catalog().length, 1);
  }
});
test('unknown status or a lost mutation reply poisons the port without retry or raw error leakage', async () => {
  for (const copy of [() => 'outcome_unknown', () => undefined, () => { throw Error('private key endpoint'); }]) {
    const { port } = fixture({ copy });
    await assert.rejects(port.backup(null, { fileName }), code('runtime_snapshot_outcome_unknown'));
    assert.throws(() => port.snapshots.catalog(), code('runtime_snapshot_outcome_unknown'));
    await assert.rejects(port.backup(null, { fileName }), code('runtime_snapshot_outcome_unknown'));
  }
});
test('read-only snapshots require existence and select the exact catalog identity before FD receipt', () => {
  const { port, calls } = fixture();
  for (const options of [{}, { readonly: true }, { readonly: false, fileMustExist: true },
    { readonly: true, fileMustExist: true, timeout: -1 }, { readonly: true, fileMustExist: true, nativeBinding: '/private' }]) {
    assert.throws(() => port.openBackup({ fileName, options }), code('runtime_snapshot_invalid'));
  }
  assert.equal(calls.length, 0);
  const missing = fileName.replace('1794000000000', '1794000000001');
  assert.throws(() => port.openBackup({ fileName: missing, options: { readonly: true, fileMustExist: true } }), code('ENOENT'));
  assert.equal(calls.some((call) => call[0] === 'open'), false);
});
test('invalid descriptors are refused and native constructor errors never leak their text', () => {
  for (const fd of [-1, 2, 3.5, 0x100000000, NaN]) {
    const { port, calls } = fixture({ openReadOnly() { return fd; } });
    assert.throws(() => port.openBackup({ fileName, options: { readonly: true, fileMustExist: true } }), code('runtime_snapshot_unavailable'));
    assert.equal(calls.some((call) => call[0] === 'consume'), false);
  }
  let consumed = 0;
  const { port } = fixture({ openDatabase(fd) { assert.equal(fd, 42); consumed++; throw Error('private path or key'); } });
  assert.throws(() => port.openBackup({ fileName, options: { readonly: true, fileMustExist: true } }), code('runtime_snapshot_unavailable'));
  assert.equal(consumed, 1);
});
test('closing during FD receipt still consumes the FD and closes the constructed handle', () => {
  let port;
  const fixtureValue = fixture({ openReadOnly() { port.deactivate(); return 42; } });
  port = fixtureValue.port;
  assert.throws(() => port.openBackup({ fileName, options: { readonly: true, fileMustExist: true } }), code('runtime_snapshot_closed'));
  assert.equal(fixtureValue.calls.filter((call) => call[0] === 'consume').length, 1);
  assert.equal(fixtureValue.calls.filter((call) => call[0] === 'close').length, 1);
});
test('wrong deletion identity is rejected and an unknown deletion blocks further operations', async () => {
  const { port, calls } = fixture({ remove() { calls.push(['remove']); return 'outcome_unknown'; } });
  await assert.rejects(port.snapshots.remove({ fileName, identity: 'b'.repeat(32) }), code('runtime_snapshot_identity_mismatch'));
  assert.equal(calls.some((call) => call[0] === 'remove'), false);
  await assert.rejects(port.snapshots.remove({ fileName, identity: row().identity }), code('runtime_snapshot_outcome_unknown'));
  assert.throws(() => port.snapshots.catalog(), code('runtime_snapshot_outcome_unknown'));
});
test('closure while awaiting copy cannot restore a success', async () => {
  let resolve;
  const { port } = fixture({ copy() { return new Promise((done) => { resolve = done; }); } });
  const pending = port.backup(null, { fileName });
  port.deactivate(); resolve('published');
  await assert.rejects(pending, code('runtime_snapshot_closed'));
});
