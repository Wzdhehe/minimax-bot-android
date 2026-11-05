// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createBotFileLease } from './bot-file-lease.mjs';
import { createBotStorePersistence } from './bot-store-persistence.mjs';

const error = (code) => Object.assign(new Error('private native detail'), { code });
function fakeFiles() {
  const values = new Map();
  return {
    values,
    async read(name) { if (!values.has(name)) throw error('runtime_data_not_found'); return Uint8Array.from(values.get(name)); },
    async size(name) { if (!values.has(name)) throw error('runtime_data_not_found'); return values.get(name).length; },
    async replace(name, bytes) { values.set(name, Buffer.from(bytes)); return 'published'; },
    async append(name, bytes) { values.set(name, Buffer.concat([values.get(name) ?? Buffer.alloc(0), Buffer.from(bytes)])); return 'published'; },
    async publish(name, bytes) { if (values.has(name)) return 'already_exists'; values.set(name, Buffer.from(bytes)); return 'published'; },
    async rename(from, to) { if (!values.has(from)) throw error('runtime_data_not_found'); values.set(to, values.get(from)); values.delete(from); return 'published'; },
    async remove(name) { if (!values.delete(name)) throw error('runtime_data_not_found'); return 'removed'; },
  };
}

test('shared file lease preserves exclusive publication, move, remove and missing results', async () => {
  const files = fakeFiles(), lease = createBotFileLease(files);
  assert.equal(await lease.publish('memory-backup.bin', Uint8Array.of(1, 2)), 'published');
  assert.equal(await lease.publish('memory-backup.bin', Uint8Array.of(3)), 'already_exists');
  assert.equal(await lease.size('memory-backup.bin'), 2);
  assert.equal(await lease.rename('memory-backup.bin', 'memory-new.bin'), 'published');
  assert.deepEqual([...await lease.read('memory-new.bin')], [1, 2]);
  assert.equal(await lease.remove('memory-new.bin'), 'removed');
  await assert.rejects(lease.remove('memory-new.bin'), (e) => e.code === 'runtime_data_not_found' && !e.message.includes('private'));
  assert.equal(await lease.publish('memory-new.bin', Uint8Array.of(4)), 'published');
});

test('an unknown mutation invalidates all adapters sharing the same lease', async () => {
  const files = fakeFiles(), lease = createBotFileLease(files);
  const store = createBotStorePersistence({ dataDir: '/virtual/bot', lease });
  await store.writeJsonAtomic('/virtual/bot/state.json', { version: 1 });
  files.publish = async () => 'outcome_unknown';
  await assert.rejects(lease.publish('memory-backup.bin', Uint8Array.of(1)), (e) => e.code === 'runtime_data_outcome_unknown');
  await assert.rejects(store.readJson('/virtual/bot/state.json', {}), (e) => e.code === 'runtime_data_outcome_unknown');
  await assert.rejects(store.writeJsonAtomic('/virtual/bot/state.json', { version: 2 }), (e) => e.code === 'runtime_data_outcome_unknown');
});

test('queued writes retain their own byte snapshot', async () => {
  const files = fakeFiles(), lease = createBotFileLease(files);
  let started, finish;
  const entered = new Promise((resolve) => { started = resolve; });
  const replace = files.replace;
  files.replace = async (name, bytes) => {
    if (name === 'first.bin') { started(); await new Promise((resolve) => { finish = resolve; }); }
    return replace(name, bytes);
  };
  const first = lease.replace('first.bin', Uint8Array.of(1));
  await entered;
  const input = Uint8Array.of(2, 3);
  const second = lease.replace('second.bin', input);
  input.fill(9);
  finish();
  await Promise.all([first, second]);
  assert.deepEqual([...await lease.read('second.bin')], [2, 3]);
});

test('an in-flight read cannot return stale data after its shared lease is invalidated', async () => {
  const files = fakeFiles();
  let started, finish;
  const entered = new Promise((resolve) => { started = resolve; });
  files.read = async () => { started(); return new Promise((resolve) => { finish = resolve; }); };
  const lease = createBotFileLease(files);
  const read = lease.read('state.json');
  await entered;
  lease.invalidate('runtime_data_outcome_unknown');
  finish(Uint8Array.of(1));
  await assert.rejects(read, (e) => e.code === 'runtime_data_outcome_unknown');
});

test('file lease denies path escape and unsafe mutable byte storage', async () => {
  const files = fakeFiles(), lease = createBotFileLease(files);
  await assert.rejects(lease.read('../private'), (e) => e.code === 'runtime_data_invalid');
  assert.throws(() => lease.replace('.native-publish-unsafe.tmp', Uint8Array.of(1)), (e) => e.code === 'runtime_data_invalid');
  assert.throws(() => lease.replace('x', new Uint8Array(new SharedArrayBuffer(4))), (e) => e.code === 'runtime_data_invalid');
  await assert.rejects(lease.read('safe'), (e) => e.code === 'runtime_data_reload_required');
});
