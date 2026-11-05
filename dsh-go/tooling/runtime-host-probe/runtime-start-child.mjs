// SPDX-License-Identifier: AGPL-3.0-only
// Host fixture: real pinned runtime/SQLite/Bot, fake native file/model/source
// capabilities. Inherited socket FDs have no duplicate Node owner in this child.
import assert from 'node:assert/strict';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createInterface } from 'node:readline';
import { closeSync, writeSync } from 'node:fs';

const [bundle, root] = process.argv.slice(2);
const { startBuiltinBotRuntimeFromFd } = await import(pathToFileURL(join(bundle, 'runtime-host.partial.mjs')).href);
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
    files, nativeBinding: { open() { nativeCalls++; throw Error('no_model_turn_expected'); }, cancel() {} },
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
  process.stdout.write('PASS: real pinned runtime FD startup and authenticated Bot, shared shutdown; no model turn or Android\n');
} finally {
  await handle?.close();
  closeSync(6);
  commands.close();
  process.stdin.destroy();
}
