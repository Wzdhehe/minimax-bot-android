// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import { chmodSync, closeSync, constants, fstatSync, openSync } from 'node:fs';
import { mkdtemp, rm } from 'node:fs/promises';
import { createServer } from 'node:net';
import { once } from 'node:events';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { startBotRuntimeFromFd } from './bot-runtime-start.mjs';
import { writeRuntimeDescriptor } from './runtime-descriptor.mjs';
import { UPSTREAM_RUNTIME_COMMIT } from './upstream-runtime-pin.mjs';

const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const agent = Object.freeze({ async models() { return []; }, async run() {}, async reply() {}, async answer() {} });

async function fixture(t, descriptorPatch = {}) {
  const root = await mkdtemp(join(tmpdir(), 'mbot-start-'));
  const blocker = createServer();
  blocker.listen(0, '127.0.0.1');
  await once(blocker, 'listening');
  const port = blocker.address().port;
  const path = writeRuntimeDescriptor({ dir: root, allowedRoots: [root], descriptor: {
    entryPath: join(root, 'runtime.mjs'), pinCommit: UPSTREAM_RUNTIME_COMMIT, port,
    token: 'a'.repeat(64), ...descriptorPatch,
  } });
  const descriptorFd = openSync(path, constants.O_RDONLY | constants.O_NOFOLLOW);
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
  const incoming = deferred();
  // This fixture allocates its queue future before next is invoked. Native
  // cancellation can close an empty queue too; do not create an unobserved
  // fixture rejection when startup aborts before the first wait is requested.
  void incoming.promise.catch(() => {});
  const counts = { ready: 0, factory: 0, close: 0, cancel: 0, next: 0 };
  const controller = new AbortController();
  const options = {
    descriptorFd, expectedOwnerUid: process.getuid(), runtimeDataDir: join(root, 'runtime-data'),
    botDataDir: join(root, 'virtual-bot-data'), appVersion: 'host-only-test',
    nativeBinding: { open() { throw Error('unused'); }, cancel() {} }, files,
    connections: {
      next(...args) { assert.equal(args.length, 0); counts.next++; return incoming.promise; },
      cancel() { counts.cancel++; incoming.reject(Error('private-native-detail')); },
    },
    signal: controller.signal,
    logger: { info() {}, warn() {}, error() {} },
    onReady(...args) { assert.equal(args.length, 0); counts.ready++; },
    async startRuntime(input) {
      counts.factory++;
      assert.equal(input.descriptor.port, port);
      assert.equal(input.dataDir, options.runtimeDataDir);
      assert.equal(input.nativeBinding, options.nativeBinding);
      return { agent, async close() { counts.close++; } };
    },
  };
  let owned = true, handle;
  t.after(async () => {
    if (owned) closeSync(descriptorFd);
    await handle?.close().catch(() => {});
    if (blocker.listening) await new Promise((resolve) => blocker.close(resolve));
    await rm(root, { recursive: true, force: true });
  });
  return { options, counts, controller, incoming, path, descriptorFd,
    async start(patch = {}) { owned = false; handle = await startBotRuntimeFromFd({ ...options, ...patch }); return handle; } };
}

test('native capabilities and the real Bot service start before the private ready receipt, without listening', async (t) => {
  const f = await fixture(t);
  const phases = [];
  const handle = await f.start({ onStartupPhase: (phase) => phases.push(phase) });
  // The parent still holds the advertised TCP port; success proves the Bot
  // attached its HTTP server to FD transport rather than calling listen.
  assert.equal(f.counts.ready, 1);
  assert.equal(f.counts.factory, 1);
  assert.deepEqual(phases, ['bot_service_starting', 'bot_service_started', 'ingress_started']);
  assert.throws(() => fstatSync(f.descriptorFd), { code: 'EBADF' });
  const closing = handle.close();
  assert.equal(handle.close(), closing);
  await closing;
  await handle.done;
  assert.equal(f.counts.close, 1);
  assert.equal(f.counts.cancel, 1);
});

test('a failing phase observer cannot prevent the real Bot service from starting or draining', async (t) => {
  const f = await fixture(t);
  const handle = await f.start({ onStartupPhase() { throw Error('private diagnostic fixture'); } });
  assert.equal(f.counts.ready, 1);
  await handle.close();
  assert.equal(f.counts.close, 1);
});

test('missing native model capability refuses startup and still consumes the descriptor', async (t) => {
  const f = await fixture(t);
  await assert.rejects(f.start({ nativeBinding: null }), { code: 'runtime_start_invalid' });
  assert.throws(() => fstatSync(f.descriptorFd), { code: 'EBADF' });
  assert.equal(f.counts.factory, 0);
  assert.equal(f.counts.ready, 0);
});

test('a legacy loopback model broker cannot enter the isolated starter', async (t) => {
  const f = await fixture(t, { broker: { baseUrl: 'http://127.0.0.1:43119', token: 'b'.repeat(64) } });
  await assert.rejects(f.start(), { code: 'runtime_start_invalid' });
  assert.throws(() => fstatSync(f.descriptorFd), { code: 'EBADF' });
  assert.equal(f.counts.factory, 0);
});

test('nonprivate descriptor permissions fail before any runtime or ready side effect', async (t) => {
  const f = await fixture(t);
  chmodSync(f.path, 0o644);
  await assert.rejects(f.start(), { code: 'descriptor_permissions' });
  assert.throws(() => fstatSync(f.descriptorFd), { code: 'EBADF' });
  assert.equal(f.counts.factory, 0);
  assert.equal(f.counts.ready, 0);
});

test('an aborted startup closes a factory result arriving late and never reports ready', async (t) => {
  const f = await fixture(t);
  const late = deferred();
  const starting = f.start({ startRuntime: () => late.promise });
  f.controller.abort();
  late.resolve({ agent, async close() { f.counts.close++; } });
  await assert.rejects(starting, { code: 'REQUEST_CANCELLED' });
  assert.equal(f.counts.close, 1);
  assert.equal(f.counts.cancel, 1);
  assert.equal(f.counts.ready, 0);
});

test('a failed private ready notification tears down the runtime and does not leak its error', async (t) => {
  const f = await fixture(t);
  await assert.rejects(f.start({ onReady() { throw Error('private-control-channel-detail'); } }), (error) => {
    assert.equal(error.code, 'runtime_start_unavailable');
    assert.equal(error.message, 'runtime_start_unavailable');
    assert.equal(error.cause, undefined);
    return true;
  });
  assert.equal(f.counts.close, 1);
  assert.equal(f.counts.cancel, 1);
});

test('abort after ready performs the shared shutdown even without an explicit close call', async (t) => {
  const f = await fixture(t);
  const handle = await f.start();
  f.controller.abort();
  await handle.done;
  assert.equal(f.counts.close, 1);
  assert.equal(f.counts.cancel, 1);
});

test('an unexpected FD source failure retires the service and leaves an unverified close result', async (t) => {
  const f = await fixture(t);
  const handle = await f.start();
  f.incoming.reject(Error('private-source-detail'));
  await assert.rejects(handle.done, { code: 'runtime_close_unverified' });
  await assert.rejects(handle.close(), { code: 'runtime_close_unverified' });
  assert.equal(f.counts.close, 1);
  assert.equal(f.counts.cancel, 1);
});

test('a failed runtime cleanup still cancels ingress and exposes only a stable failure', async (t) => {
  const f = await fixture(t);
  const handle = await f.start({ startRuntime: async () => ({ agent, close() { throw Error('private-cleanup-detail'); } }) });
  await assert.rejects(handle.close(), (error) => {
    assert.equal(error.code, 'runtime_close_unverified');
    assert.equal(error.message, 'runtime_close_unverified');
    assert.equal(error.cause, undefined);
    return true;
  });
  await assert.rejects(handle.done, { code: 'runtime_close_unverified' });
  assert.equal(f.counts.cancel, 1);
});
