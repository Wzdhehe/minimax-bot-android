// SPDX-License-Identifier: AGPL-3.0-only
// Host fixture: real pinned runtime/SQLite/Bot, fake native file/model/source
// capabilities. Inherited socket FDs have no duplicate Node owner in this child.
import assert from 'node:assert/strict';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createInterface } from 'node:readline';
import { closeSync, existsSync, writeSync } from 'node:fs';
import { createRequire } from 'node:module';

const [bundle, root] = process.argv.slice(2);
const { startBuiltinBotRuntimeFromFd } = await import(pathToFileURL(join(bundle, 'runtime-host.partial.mjs')).href);
// This host constructor stands in for the native-owned FD mapping. Ordinary
// host FS preserves upstream chmod/stat/backup identity behavior. It is not
// Android, virtual filesystem or FD VFS execution evidence.
const Database = createRequire(import.meta.url)('better-sqlite3');
const physicalDatabase = join(root, 'v2/sqlite/runtime-state.sqlite');
const databases = [];
const backupMethods = new WeakMap();
const nativeSqlite = { open(options) {
  assert.equal(this, nativeSqlite);
  assert.equal(arguments.length, 1);
  assert.equal(Object.isFrozen(options), true);
  const database = new Database(physicalDatabase, options);
  databases.push(database);
  backupMethods.set(database, database.backup.bind(database));
  return database;
}, backup(database, { fileName }) {
  return backupMethods.get(database)(join(root, 'v2/sqlite/backups', fileName));
}, openBackup({ fileName, options }) {
  const database = new Database(join(root, 'v2/sqlite/backups', fileName), options);
  databases.push(database);
  return database;
} };
const values = new Map();
const missing = () => Object.assign(Error('runtime_data_not_found'), { code: 'runtime_data_not_found' });
const files = {
  async read(name) { if (!values.has(name)) throw missing(); return Uint8Array.from(values.get(name)); },
  async size(name) { if (!values.has(name)) throw missing(); return values.get(name).length; },
  async replace(name, bytes) { values.set(name, Buffer.from(bytes)); return 'published'; },
  async append(name, bytes) { values.set(name, Buffer.concat([values.get(name) ?? Buffer.alloc(0), Buffer.from(bytes)])); return 'published'; },
  async publish(name, bytes) { if (values.has(name)) return 'already_exists'; values.set(name, Buffer.from(bytes)); return 'published'; },
  async rename(from, to) { if (!values.has(from)) throw missing(); values.set(to, values.get(from)); values.delete(from); return 'published'; },
  async remove(name) { if (!values.delete(name)) throw missing(); return 'removed'; },
};
let requested = 0, cancelCalls = 0, nativeCalls = 0, readyCalls = 0, pending;
const commands = createInterface({ input: process.stdin });
let stop;
const stopped = new Promise((resolve) => { stop = resolve; });
commands.on('line', (line) => { if (line === 'close') stop(); });
let handle;
try {
  handle = await startBuiltinBotRuntimeFromFd({ descriptorFd: 3, expectedOwnerUid: process.getuid(),
    runtimeDataDir: root, botDataDir: join(root, 'virtual-bot'), appVersion: 'host-fd-start-probe',
    files, nativeSqlite, nativeBinding: { open() { nativeCalls++; throw Error('no_model_turn_expected'); }, cancel() {} },
    connections: {
      next() { return requested++ < 2 ? requested + 3 : new Promise((_, reject) => { pending = reject; }); },
      cancel() { cancelCalls++; pending?.(Error('source_closed')); },
    },
    logger: { info() {}, warn() {}, error() {} },
    onReady() { readyCalls++; writeSync(6, 'R'); },
    // The bound entry must override this; it cannot be used to replace the
    // real factory through the caller's options object.
    startRuntime() { throw Error('caller_factory_must_not_be_used'); },
  });
  await stopped;
  const closing = handle.close();
  assert.equal(handle.close(), closing);
  await closing;
  await handle.done;
  assert.equal(readyCalls, 1);
  assert.equal(cancelCalls, 1);
  assert.equal(nativeCalls, 0);
  assert.ok(databases.length > 0, 'the real migrations must open the native capability');
  assert.ok(databases.every((database) => !database.open), 'shutdown must close every real handle');
  assert.equal(existsSync(physicalDatabase), true);
  assert.ok(databases.length >= 3, 'both legacy and v2 connections plus snapshot integrity must use the capability');
  process.stdout.write('PASS: real runtime migrations through explicit SQLite capability with native backup and integrity ports; host FS only\n');
  process.stdout.write('PASS: real pinned runtime FD startup and authenticated Bot, shared shutdown; no model turn or Android\n');
} finally {
  await handle?.close();
  closeSync(6);
  commands.close();
  process.stdin.destroy();
}
