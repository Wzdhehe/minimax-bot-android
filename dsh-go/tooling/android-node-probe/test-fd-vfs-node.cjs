// SPDX-License-Identifier: AGPL-3.0-only
// Host-only regression of the exact APK SQLite block across fresh Node processes.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const [library, wrapper, mode] = process.argv.slice(2);

if (mode === 'child') {
  const source = fs.readFileSync(path.join(__dirname, 'src/main/js/node-fd-probe.cjs'), 'utf8');
  const start = source.indexOf('  let sqlite = null;');
  const end = source.indexOf("  probePhase = 'model_streams';");
  assert.ok(start >= 0 && end > start);
  const run = new Function('require', 'fs', 'sqliteWrapper', 'nativeLibraryDir', 'dataFdArgs',
    "let probePhase = 'bootstrap';\n" + source.slice(start, end) + '\nreturn { sqlite, persistent, pid: process.pid };');
  // Descriptors 3..7 are the only granted data files/directory. The parent
  // never opens SQLite, so its copies cannot release the child's POSIX locks.
  const report = run(require, fs, fs.readFileSync(wrapper, 'utf8'), path.dirname(library), ['3', '4', '5', '6', '7']);
  process.stdout.write(JSON.stringify(report));
} else {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'mbot-fd-vfs-node-'));
  const files = ['state.db', 'state.db-wal', 'state.db-shm', 'state.db-journal'].map((name) => path.join(directory, name));
  const fds = [];
  try {
    files.forEach((file) => fds.push(fs.openSync(file, 'w+')));
    fds.push(fs.openSync(directory, 'r'));
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
      assert.deepEqual(report.sqlite.rows, ['你好', 'second']);
    }
    assert.ok(fs.statSync(files[0]).size > 0);
    console.log('PASS: exact APK SQLite block, addon and FD VFS preserve data across independent host Node processes; not Android evidence');
  } finally {
    fds.forEach((fd) => fs.closeSync(fd));
    fs.rmSync(directory, { recursive: true, force: true });
  }
}
