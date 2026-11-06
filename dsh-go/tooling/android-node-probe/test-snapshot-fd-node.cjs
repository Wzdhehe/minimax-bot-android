// SPDX-License-Identifier: AGPL-3.0-only
// Exact addon + actual snapshot constructor, Linux host only. No Android/IPC claim.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { pathToFileURL } = require('node:url');
const { execFileSync } = require('node:child_process');
const [library, wrapper, mode] = process.argv.slice(2);

async function child() {
  const addon = { exports: {} }, wrapperModule = { exports: {} };
  process.dlopen(addon, library);
  new Function('require', 'module', 'exports', fs.readFileSync(wrapper, 'utf8'))(require, wrapperModule, wrapperModule.exports);
  const Database = wrapperModule.exports, nativeBinding = addon.exports;
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'snapshot-fd-node-'));
  const physical = path.join(root, 'published.sqlite'), corrupt = path.join(root, 'corrupt.sqlite');
  try {
    // Main fixture publishes a checkpointed immutable snapshot before the
    // guest VFS takes over. It is a real SQLite file, not canned pragma output.
    const seed = new Database(physical, { nativeBinding });
    try { seed.exec("PRAGMA journal_mode=DELETE; CREATE TABLE notes(text TEXT); INSERT INTO notes VALUES ('你好·真实快照')"); }
    finally { seed.close(); }
    fs.chmodSync(physical, 0o600);
    fs.writeFileSync(corrupt, 'corrupt snapshot bytes', { mode: 0o600 });
    nativeBinding.setFdVfs(3, 4, 5, 6);
    for (const fd of [3, 4, 5, 6]) fs.closeSync(fd);
    const moduleRoot = path.resolve(__dirname, '../../runtime/minimax-bot-host');
    const { createSnapshotFdDatabase } = await import(pathToFileURL(path.join(moduleRoot, 'runtime-snapshot-database.mjs')).href);
    const { createNativeSnapshotSqlitePort } = await import(pathToFileURL(path.join(moduleRoot, 'runtime-snapshot-port.mjs')).href);
    let closes = 0, offered;
    const openDatabase = createSnapshotFdDatabase({ Database, nativeBinding,
      closeDescriptor(fd) { closes++; fs.closeSync(fd); } });
    const row = { fileName: 'runtime-state-before-v2-migration-1794000000000-01234567-89ab-4def-8123-456789abcdef.sqlite',
      bytes: fs.statSync(physical).size, createdAtMs: 1794000000000, identity: 'a'.repeat(32) };
    const port = createNativeSnapshotSqlitePort({
      catalog() { return [row]; }, copy() { throw Error('copy not part of this fixture'); },
      openReadOnly(name, identity) { assert.equal(name, row.fileName); assert.equal(identity, row.identity); return offered = fs.openSync(physical, 'r'); },
      remove() { throw Error('remove not part of this fixture'); }, openDatabase,
    });
    const database = port.openBackup({ fileName: row.fileName, options: { readonly: true, fileMustExist: true } });
    try {
      assert.equal(database.readonly, true);
      assert.deepEqual(database.prepare('SELECT text FROM notes').all(), [{ text: '你好·真实快照' }]);
      assert.equal(database.pragma('integrity_check', { simple: true }), 'ok');
      assert.throws(() => database.exec("INSERT INTO notes VALUES ('no')"), { code: 'SQLITE_READONLY' });
      assert.throws(() => fs.fstatSync(offered), { code: 'EBADF' });
    } finally { database.close(); }
    assert.equal(closes, 1);
    const damagedFd = fs.openSync(corrupt, 'r');
    const damaged = openDatabase(damagedFd, { readonly: true, fileMustExist: true });
    try { assert.throws(() => damaged.pragma('integrity_check'), { code: 'SQLITE_NOTADB' }); }
    finally { damaged.close(); }
    assert.throws(() => fs.fstatSync(damagedFd), { code: 'EBADF' });
    const invalidOptionsFd = fs.openSync(physical, 'r');
    assert.throws(() => openDatabase(invalidOptionsFd, { readonly: false, fileMustExist: true }), { code: 'runtime_snapshot_database_unavailable' });
    assert.throws(() => fs.fstatSync(invalidOptionsFd), { code: 'EBADF' });
    assert.equal(closes, 3);
    for (const args of [[], [-1], ['../state.db'], [3.5], [3, 4]]) {
      assert.throws(() => nativeBinding.fdVfsReadOnlySnapshot(...args), { code: 'fd_vfs_snapshot_unavailable' });
    }
    // Missing API and a malformed native logical name fail without leaking
    // native exception text; every genuinely received snapshot FD is closed.
    assert.throws(() => createSnapshotFdDatabase({ Database, nativeBinding: {}, closeDescriptor: fs.closeSync }), { code: 'runtime_snapshot_database_unavailable' });
    for (const register of [() => '../private.sqlite', () => { throw Error('private path and credential'); }]) {
      const fd = fs.openSync(physical, 'r');
      const open = createSnapshotFdDatabase({ Database, nativeBinding: { fdVfsReadOnlySnapshot: register }, closeDescriptor: fs.closeSync });
      assert.throws(() => open(fd, { readonly: true, fileMustExist: true }), { code: 'runtime_snapshot_database_unavailable', message: 'runtime_snapshot_database_unavailable' });
      assert.throws(() => fs.fstatSync(fd), { code: 'EBADF' });
    }
    console.log('PASS: actual Node addon opens the Main-fixture read-only snapshot, consumes granted FDs, reads Chinese rows, checks real integrity, refuses writes/corruption/invalid native responses; host only');
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
}
if (mode === 'child') child().catch((error) => { console.error(error); process.exitCode = 1; });
else {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'snapshot-fd-family-'));
  const fds = [];
  try {
    for (const name of ['state.db', 'state.db-wal', 'state.db-shm', 'state.db-journal']) fds.push(fs.openSync(path.join(root, name), 'wx+', 0o600));
    execFileSync(process.execPath, [__filename, library, wrapper, 'child'], {
      stdio: ['ignore', 'inherit', 'inherit', ...fds], env: {}, timeout: 15_000,
    });
  } finally { for (const fd of fds) fs.closeSync(fd); fs.rmSync(root, { recursive: true, force: true }); }
}
