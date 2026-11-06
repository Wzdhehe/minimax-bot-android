// SPDX-License-Identifier: AGPL-3.0-only
// Host-only regression of the exact APK SQLite block across fresh Node processes.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const [library, wrapper, mode] = process.argv.slice(2);

if (mode === 'metadata') {
  const wrapperModule = { exports: {} }, addon = { exports: {} };
  new Function('require', 'module', 'exports', fs.readFileSync(wrapper, 'utf8'))(require, wrapperModule, wrapperModule.exports);
  process.dlopen(addon, library);
  const metadata = addon.exports.fdVfsMetadata;
  assert.throws(() => metadata(0), { code: 'fd_vfs_metadata_unavailable' });
  addon.exports.setFdVfs(3, 4, 5, 6);
  for (const fd of [3, 4, 5, 6]) fs.closeSync(fd);
  for (const args of [[], [-1], [5], ['state.db'], [0.5], [0, 1]]) {
    assert.throws(() => metadata(...args), { code: 'fd_vfs_metadata_unavailable' });
  }
  assert.deepEqual(metadata(4), { present: true, mode: 0o40700, size: 0 });
  assert.equal(metadata(0).present, true);
  assert.equal(metadata(0).mode & 0o7777, 0o600);
  const Database = wrapperModule.exports;
  const db = new Database('state.db', { nativeBinding: addon.exports });
  try {
    db.pragma('journal_mode=WAL');
    db.exec('CREATE TABLE metadata_probe(text TEXT)');
    db.prepare('INSERT INTO metadata_probe VALUES (?)').run('fd metadata');
    assert.equal(metadata(1).present, true);
    assert.ok(metadata(1).size > 0);
    assert.equal(metadata(1).mode & 0o7777, 0o600);
    assert.equal(db.pragma('integrity_check', { simple: true }), 'ok');
  } finally { db.close(); }
  assert.equal(metadata(1).present, false);
  console.log('PASS: real addon fixed-slot FD metadata, live WAL size, private mode and invalid-input refusal; host only');
} else if (mode === 'child' || mode === 'missing-metadata') {
  const source = fs.readFileSync(path.join(__dirname, 'src/main/js/node-fd-probe.cjs'), 'utf8');
  const start = source.indexOf('  let sqlite = null;');
  const end = source.indexOf("  probePhase = 'model_streams';");
  assert.ok(start >= 0 && end > start);
  const run = new Function('require', 'fs', 'sqliteWrapper', 'nativeLibraryDir', 'dataFdArgs',
    "let probePhase = 'bootstrap';\n" + source.slice(start, end) + '\nreturn { sqlite, persistent, pid: process.pid };');
  // Descriptors 3..6 are the only granted data files. The parent
  // never opens SQLite, so its copies cannot release the child's POSIX locks.
  const originalDlopen = process.dlopen;
  try {
    if (mode === 'missing-metadata') process.dlopen = function(module, filename) {
      originalDlopen.call(process, module, filename);
      delete module.exports.fdVfsMetadata; // Simulate the previous addon API.
    };
    const execute = () => run(require, fs, fs.readFileSync(wrapper, 'utf8'), path.dirname(library), ['3', '4', '5', '6']);
    if (mode === 'missing-metadata') {
      assert.throws(execute, { code: 'fd_vfs_metadata_unavailable' });
      console.log('PASS: exact APK SQLite block refuses an addon without the new metadata API; host only');
    } else process.stdout.write(JSON.stringify(execute()));
  } finally { process.dlopen = originalDlopen; }
} else {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'mbot-fd-vfs-node-'));
  const files = ['state.db', 'state.db-wal', 'state.db-shm', 'state.db-journal'].map((name) => path.join(directory, name));
  const fds = [];
  try {
    files.forEach((file) => fds.push(fs.openSync(file, 'w+', 0o600)));
    fds.forEach((fd) => fs.fsyncSync(fd));
    const directoryFd = fs.openSync(directory, 'r');
    try { fs.fsyncSync(directoryFd); } finally { fs.closeSync(directoryFd); }
    let previousPid;
    for (let attempt = 0; attempt < 2; attempt++) {
      const report = JSON.parse(execFileSync(process.execPath, [__filename, library, wrapper, 'child'], {
        stdio: ['ignore', 'pipe', 'pipe', ...fds], env: {}, timeout: 15_000, maxBuffer: 16 * 1024,
      }));
      assert.notEqual(report.pid, previousPid);
      previousPid = report.pid;
      assert.deepEqual(report.persistent.initialRows, attempt === 0 ? [] : ['你好', 'second']);
      assert.equal(report.persistent.mode, 'wal');
      assert.equal(report.persistent.count, 2);
      assert.deepEqual(report.persistent.reopened, ['你好', 'second']);
      assert.equal(report.persistent.integrity, 'ok');
      assert.deepEqual(report.persistent.metadata, {
        directoryPrivate: true, filesPrivate: true, invalidRefused: 6, walLive: true, walRetired: true,
      });
      assert.deepEqual(report.sqlite.rows, ['你好', 'second']);
    }
    assert.ok(fs.statSync(files[0]).size > 0);
    execFileSync(process.execPath, [__filename, library, wrapper, 'missing-metadata'], {
      stdio: ['ignore', 'inherit', 'inherit', ...fds], env: {}, timeout: 15_000,
    });
    execFileSync(process.execPath, [__filename, library, wrapper, 'metadata'], {
      stdio: ['ignore', 'inherit', 'inherit', ...fds], env: {}, timeout: 15_000,
    });
    console.log('PASS: exact APK SQLite block, addon and FD VFS preserve data across independent host Node processes; not Android evidence');
  } finally {
    fds.forEach((fd) => fs.closeSync(fd));
    fs.rmSync(directory, { recursive: true, force: true });
  }
}
