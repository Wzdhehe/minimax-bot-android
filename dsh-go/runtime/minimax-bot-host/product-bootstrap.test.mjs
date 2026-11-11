// SPDX-License-Identifier: AGPL-3.0-only
// Execute the actual signed-APK bootstrap control flow. Only its two module
// imports and native bindings are fixtures; this is not Android/Node-JNI proof.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { Script } from 'node:vm';
import test from 'node:test';

function deferred() {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
}

const original = readFileSync(new URL('../native-node/bootstrap.cjs', import.meta.url), 'utf8');
for (const url of ['filesystemUrl', 'runtimeUrl']) assert.equal(original.split(`import(${url})`).length, 2);
const source = original.replace('import(filesystemUrl)', '__mbotImport(filesystemUrl)')
  .replace('import(runtimeUrl)', '__mbotImport(runtimeUrl)');

function fixture({ start, close, importFailure = false, progressFailure = false, stopImmediately = false } = {}) {
  const stop = deferred(), started = deferred();
  const phases = [], failures = [], exits = [], assetReads = [];
  let ready = 0, closed = 0, filesystemClosed = 0, imports = 0;
  const product = {
    waitForStop: () => stopImmediately ? Promise.resolve() : stop.promise,
    progress(phase) { if (progressFailure) throw Error('diagnostic fixture'); phases.push(phase); },
    ready() { ready++; return true; },
    failed(phase) { failures.push(phase); },
    read(name) {
      assetReads.push(name);
      if (name === 'resources') return Buffer.from('[]');
      if (name === 'sqlite-wrapper') return Buffer.from('module.exports = function Database() {};');
      if (name === 'runtime' || name === 'filesystem') return Buffer.from('// signed code fixture');
      throw Error('unknown asset');
    },
  };
  const handle = { done: new Promise(() => {}), async close() { closed++; await close?.(); } };
  const process = {
    argv: ['mbot-node', '11', '10001', '/signed-apk/lib/x86_64', '12', '13', '14', '15'],
    _linkedBinding(name) { return name === 'mbot_product_bridge' ? product : {}; },
    dlopen(addon) { addon.exports = {}; },
    exit(code) { exits.push(code); },
  };
  const context = {
    process, Buffer, AbortController, Intl, Promise,
    require(name) {
      if (name === 'node:fs') return {};
      if (name === 'node:module') return { registerHooks() {} };
      throw Error('unknown builtin');
    },
    async __mbotImport(url) {
      imports++;
      if (url.endsWith('/filesystem.mjs')) return {
        installMobileFilesystem() { return { close() { filesystemClosed++; } }; },
      };
      if (importFailure) throw Error('private dependency response fixture');
      return { async startMobileBotRuntime(options) {
        started.resolve(options);
        if (start) return start(options, handle);
        options.onReady();
        return handle;
      } };
    },
  };
  const execution = new Script(source, { filename: 'signed-product-bootstrap.cjs' })
    .runInNewContext(context, { timeout: 1000 });
  return { stop, started, execution, phases, failures, exits, assetReads,
    get ready() { return ready; }, get closed() { return closed; },
    get filesystemClosed() { return filesystemClosed; }, get imports() { return imports; } };
}

test('the product bootstrap passes a live abort signal and fixed milestones to the runtime', async () => {
  const f = fixture();
  const options = await f.started.promise;
  assert.ok(options.signal instanceof AbortSignal);
  assert.equal(options.signal.aborted, false);
  assert.equal(typeof options.onStartupPhase, 'function');
  assert.deepEqual(f.phases, ['assets_loading', 'assets_loaded', 'filesystem_loading',
    'filesystem_installed', 'runtime_importing', 'runtime_imported']);
  f.stop.resolve();
  await f.execution;
  assert.equal(options.signal.aborted, true);
  assert.equal(f.ready, 1);
  assert.equal(f.closed, 1);
  assert.deepEqual(f.failures, []);
  assert.deepEqual(f.exits, [0]);
});

test('stop during a pending factory actually aborts that startup and never calls ready', async () => {
  let wasAborted = false;
  const f = fixture({ start: async ({ signal }) => {
    assert.ok(signal instanceof AbortSignal);
    await new Promise((resolve, reject) => signal.addEventListener('abort', () => {
      wasAborted = true;
      reject(Error('private cancellation fixture'));
    }, { once: true }));
  } });
  await f.started.promise;
  f.stop.resolve();
  await f.execution;
  assert.equal(wasAborted, true);
  assert.equal(f.ready, 0);
  assert.deepEqual(f.failures, ['runtime_start']);
});

test('an already requested stop never starts the factory or publishes ready', async () => {
  const f = fixture({ stopImmediately: true });
  await f.execution;
  assert.equal(f.ready, 0);
  assert.equal(f.closed, 0);
  assert.equal(f.filesystemClosed, 1);
  assert.deepEqual(f.failures, []);
  assert.deepEqual(f.exits, [0]);
});

test('a stopped loop waits for the actual close promise before exiting', async () => {
  const closing = deferred(), entered = deferred();
  const f = fixture({ close: async () => { entered.resolve(); await closing.promise; } });
  await f.started.promise;
  f.stop.resolve();
  await entered.promise;
  assert.deepEqual(f.exits, []);
  closing.resolve();
  await f.execution;
  assert.equal(f.closed, 1);
  assert.deepEqual(f.exits, [0]);
});

test('a diagnostic failure cannot prevent the product bootstrap from starting and stopping', async () => {
  const f = fixture({ progressFailure: true });
  await f.started.promise;
  f.stop.resolve();
  await f.execution;
  assert.equal(f.ready, 1);
  assert.deepEqual(f.failures, []);
  assert.deepEqual(f.exits, [0]);
});

test('dependency errors report only a fixed failed phase, never the original response', async () => {
  const f = fixture({ importFailure: true });
  await f.execution;
  f.stop.resolve();
  assert.deepEqual(f.failures, ['runtime_import']);
  assert.deepEqual(f.exits, [1]);
  assert.equal(f.ready, 0);
});
