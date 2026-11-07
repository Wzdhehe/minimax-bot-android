// SPDX-License-Identifier: AGPL-3.0-only
// Host-only composition check of the exact bundled product entry. Uses real
// SQLite and owner-generated fixture FDs, never Android or real-model evidence.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire, registerHooks } from 'node:module';
import { join, resolve } from 'node:path';
import { randomBytes } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { execFileSync } from 'node:child_process';

const [bundleInput, addonInput, wrapperInput, directoryInput, scenario = 'empty', connectionFdInput, modelFdInput] = process.argv.slice(2);
if (!directoryInput) throw Error('Expected <mobile-bundle> <host-FD-addon> <sqlite-wrapper> <private-fixture-dir>');
if (!['empty', 'basic', 'advanced', 'disabled', 'no-default', 'anthropic', 'anthropic-fail'].includes(scenario)) throw Error('Unknown catalog smoke case');
if (process.versions.node !== '24.22.2' || typeof process.getuid !== 'function') throw Error('This host composition smoke requires POSIX Node 24.22.2 matching the Android addon headers');
const bundle = resolve(bundleInput), directory = resolve(directoryInput);
const require = createRequire(import.meta.url);
fs.mkdirSync(directory, { mode: 0o700, recursive: true });
const source = fs.readFileSync(join(bundle, 'runtime.mjs'));
const resources = JSON.parse(fs.readFileSync(join(bundle, 'resources.json'), 'utf8'));
const sqliteSource = fs.readFileSync(resolve(wrapperInput), 'utf8');
const addon = { exports: {} }, wrapper = { exports: {} };
process.dlopen(addon, resolve(addonInput));
new Function('require', 'module', 'exports', sqliteSource)(require, wrapper, wrapper.exports);
const fds = ['data', 'data-wal', 'data-shm', 'data-journal'].map((name) => {
  const fd = fs.openSync(join(directory, name), fs.constants.O_RDWR | fs.constants.O_CREAT, 0o600);
  fs.fchmodSync(fd, 0o600);
  return fd;
});
const descriptor = join(directory, 'descriptor.json');
const providerId = '4a931f3d-8b37-4c71-a000-111111111111';
const modelSettings = { modelId: 'fixture-model', enabled: true, effortOptions: [], modalities: { input: ['text'], output: ['text'] } };
if (['advanced', 'no-default'].includes(scenario)) Object.assign(modelSettings, { limit: { context: 1000000, output: 128000 },
  effortOptions: ['high', 'medium'], modalities: { input: ['text', 'image', 'pdf', 'video', 'audio'], output: ['text'] } });
const anthropic = scenario.startsWith('anthropic');
const modelFds = modelFdInput === undefined ? [] : modelFdInput.split(',').map(Number);
if (modelFds.some((fd) => !Number.isSafeInteger(fd) || fd < 3)) throw Error('Invalid inherited model fixture FD');
const modelRequests = [];
const catalog = { providers: scenario === 'empty' ? [] : [{ providerId, name: 'Fixture', apiFormat: anthropic ? 'anthropic-messages' : 'openai-completions',
  enabled: scenario !== 'disabled', models: ['fixture-model'], modelSettings: [modelSettings] }],
  defaultProviderId: ['basic', 'advanced', 'anthropic', 'anthropic-fail'].includes(scenario) ? providerId : null,
  defaultModelId: ['basic', 'advanced', 'anthropic', 'anthropic-fail'].includes(scenario) ? 'fixture-model' : null };
fs.writeFileSync(descriptor, JSON.stringify({ entryPath: '/mbot-code/runtime.mjs', pinCommit: '97818b2890b4acae1c6bd1200a226ff506680628',
  port: 32181, token: randomBytes(32).toString('hex'), catalog }), { mode: 0o600 });
const descriptorFd = fs.openSync(descriptor, 'r');
const rawRead = fs.readFileSync.bind(fs), rawWrite = fs.writeFileSync.bind(fs);
const statePath = join(directory, 'state.json');
const values = fs.existsSync(statePath) ? new Map(JSON.parse(rawRead(statePath, 'utf8'))) : new Map();
function save() { rawWrite(statePath, JSON.stringify([...values]), { mode: 0o600 }); }
const missing = () => Object.assign(Error('runtime_data_not_found'), { code: 'runtime_data_not_found' });
const files = {
  readSync(name) { if (!values.has(name)) throw missing(); return Buffer.from(values.get(name), 'base64'); },
  replaceSync(name, bytes) { values.set(name, Buffer.from(bytes).toString('base64')); save(); return 'published'; },
  removeSync(name) { const removed = values.delete(name); save(); return removed ? 'removed' : 'not_found'; },
  async read(name) { return this.readSync(name); },
  async size(name) { return this.readSync(name).length; },
  async replace(name, bytes) { return this.replaceSync(name, bytes); },
  async publish(name, bytes) { if (values.has(name)) return 'already_exists'; return this.replaceSync(name, bytes); },
  async append(name, bytes) { return this.replaceSync(name, Buffer.concat([values.has(name) ? this.readSync(name) : Buffer.alloc(0), Buffer.from(bytes)])); },
  async rename(from, to) { if (!values.has(from)) throw missing(); values.set(to, values.get(from)); values.delete(from); save(); return 'published'; },
  async remove(name) { if (!values.delete(name)) throw missing(); save(); return 'removed'; },
};
const snapshots = new Map();
const snapshotFixture = {
  catalog() { return [...snapshots.values()]; },
  copy(fileName) {
    if (snapshots.has(fileName)) return 'already_exists';
    // A separate owner-fixture process uses SQLite's real online backup,
    // including a WAL source, then normalizes its standalone read-only copy.
    const target = join(directory, fileName);
    execFileSync('python3', ['-c', 'import sqlite3,sys; s=sqlite3.connect(sys.argv[1]); d=sqlite3.connect(sys.argv[2]); s.backup(d); d.execute("PRAGMA journal_mode=DELETE"); assert d.execute("PRAGMA integrity_check").fetchone()[0]=="ok"; d.close(); s.close()', join(directory, 'data'), target], { timeout: 10_000 });
    fs.chmodSync(target, 0o600);
    snapshots.set(fileName, { fileName, identity: randomBytes(16).toString('hex'), bytes: fs.statSync(target).size, createdAtMs: Date.now() });
    return 'published';
  },
  openReadOnly(fileName, identity) {
    if (snapshots.get(fileName)?.identity !== identity) throw Error('snapshot_refused');
    return fs.openSync(join(directory, fileName), 'r');
  },
  remove(fileName, identity) {
    if (snapshots.get(fileName)?.identity !== identity) throw Error('snapshot_refused');
    fs.unlinkSync(join(directory, fileName)); snapshots.delete(fileName); return 'removed';
  },
};
const { installMobileFilesystem } = await import(pathToFileURL(join(bundle, 'filesystem.mjs')).href);
const lease = installMobileFilesystem({ files, assets: resources, code: source, metadata: (slot) => addon.exports.fdVfsMetadata(slot) });
const url = 'file:///mbot-code/runtime.mjs';
registerHooks({ resolve(s, c, next) { return s === url ? { url, shortCircuit: true } : next(s, c); },
  load(u, c, next) { return u === url ? { source, format: 'module', shortCircuit: true } : next(u, c); } });
const connectionFd = connectionFdInput === undefined ? null : Number(connectionFdInput);
if (connectionFd !== null && (!Number.isSafeInteger(connectionFd) || connectionFd < 3)) throw Error('Expected inherited fixture socket FD');
let offered = false;
let take;
const waiting = new Promise((resolve) => { take = resolve; });
const timeout = setTimeout(() => { console.error('FAIL: exact product startup timed out; host only'); process.exit(1); }, scenario === 'anthropic-fail' ? 120_000 : 45_000);
try {
  const { startMobileBotRuntime } = await import(url);
  let ready = false;
  const handle = await startMobileBotRuntime({ descriptorFd, ownerUid: process.getuid(), Database: wrapper.exports,
    addon: addon.exports, dataFds: fds, files, filesystem: lease,
    models: { open(id, payload) {
      if (!anthropic) throw Error('no_model_requested');
      modelRequests.push(JSON.parse(payload));
      rawWrite(join(directory, 'model-requests.json'), JSON.stringify(modelRequests), { mode: 0o600 });
      if (!modelFds.length) throw Error('fixture_native_admission_refused');
      const fd = modelFds.shift();
      if (scenario === 'anthropic-fail') { fs.closeSync(fd); throw Error('fixture_native_admission_refused'); }
      return fd;
    }, cancel() {} },
    snapshots: snapshotFixture,
    connections: { next() {
      if (!offered && connectionFd !== null) { offered = true; return Promise.resolve(connectionFd); }
      return waiting;
    }, cancel() { take(-1); } }, onReady() { ready = true; } });
  assert.equal(ready, true);
  assert.ok(values.has('runtime-fs-state.json'));
  if (['basic', 'advanced', 'no-default'].includes(scenario)) {
    // Read after startup: the socket fixture may concurrently query /api/models.
    const config = fs.readFileSync('/mbot-data/runtime/config.yaml', 'utf8');
    if (scenario !== 'no-default') assert.ok(config.includes(`custom_provider:${providerId}/fixture-model`));
    assert.ok(config.includes('native-broker-placeholder'));
    assert.ok(config.includes('https://models.mbot.invalid/v1'));
    if (['advanced', 'no-default'].includes(scenario)) {
      assert.match(config, /context:\s*1000000/);
      assert.match(config, /output:\s*128000/);
      assert.match(config, /effortOptions:/);
    }
  }
  if (connectionFd !== null) await new Promise((done) => process.stdin.once('data', done));
  await handle.close();
  console.log(`PASS: ${scenario} catalog; exact mobile product entry starts the real pinned factory and official Bot using the virtual filesystem, actual FD SQLite and Main-file fixture, then closes; host only, no Android execution or model turn`);
  process.exit(0); // Match the product's one-VM process retirement after close.
} catch (error) {
  console.error('FAIL: host product composition:', String(error.code ?? error.message).slice(0, 160));
  process.exitCode = 1;
} finally { clearTimeout(timeout); }
