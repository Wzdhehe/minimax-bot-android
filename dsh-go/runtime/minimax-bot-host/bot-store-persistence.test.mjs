// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import { mkdtemp, rm, stat } from 'node:fs/promises';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { Store } from '../vendor/minimax-bot/miniapp/node/store.mjs';
import { createBotStorePersistence } from './bot-store-persistence.mjs';
import { startBotService } from './service.mjs';

const root = '/virtual/mbot-data';
const nativeError = (code) => Object.assign(new Error(`private diagnostic for ${code}`), { code });

function fakeCapability() {
  const values = new Map();
  let unknown = false, readError, mutations = 0;
  return {
    values,
    setUnknown() { unknown = true; },
    setReadError(code) { readError = code; },
    get mutations() { return mutations; },
    async read(name) {
      if (readError) throw nativeError(readError);
      if (!values.has(name)) throw nativeError('runtime_data_not_found');
      return Uint8Array.from(values.get(name));
    },
    async replace(name, bytes) {
      mutations++;
      values.set(name, Buffer.from(bytes));
      if (unknown) { unknown = false; return 'outcome_unknown'; }
      return 'published';
    },
    async append(name, bytes) {
      mutations++;
      const merged = Buffer.concat([values.get(name) ?? Buffer.alloc(0), Buffer.from(bytes)]);
      if (merged.length > 1024 * 1024) throw nativeError('runtime_data_too_large');
      values.set(name, merged);
      if (unknown) { unknown = false; return 'outcome_unknown'; }
      return 'published';
    },
  };
}

test('the real Bot Store restores state, chats, schedules and usage through a flat capability', async () => {
  const files = fakeCapability();
  const store = new Store(root, createBotStorePersistence({ dataDir: root, files }));
  await store.init();
  const chief = store.state.bots[0];
  const chat = `bot:${chief.id}`;
  await Promise.all([
    store.appendMessage(chat, { role: 'user', text: '你好，持久化' }),
    store.appendMessage(chat, { role: 'user', text: '第二条' }),
  ]);
  store.state.groups.push({ id: 'group_1', name: '协作群' });
  await store.appendMessage('group:group_1', { role: 'user', text: '群聊记录' });
  store.state.schedules.push({ id: 'schedule_1', botId: chief.id, prompt: '提醒我', enabled: true });
  await store.save();
  await store.recordUsage({ ts: 101, turnId: 'turn_1', botId: chief.id });

  const restored = new Store(root, createBotStorePersistence({ dataDir: root, files }));
  await restored.init();
  assert.equal(restored.state.bots[0].id, chief.id);
  assert.equal(restored.state.schedules[0].prompt, '提醒我');
  const messages = await restored.getMessages(chat);
  assert.deepEqual(messages.filter((item) => item.role === 'user').map((item) => item.text), ['你好，持久化', '第二条']);
  assert.deepEqual((await restored.getMessages('group:group_1')).map((item) => item.text), ['群聊记录']);
  assert.deepEqual((await restored.readUsage(0)).map((row) => row.turnId), ['turn_1']);
  assert.ok(files.values.has('bot-state.json'));
  assert.ok(files.values.has('bot-usage.jsonl'));
  assert.equal([...files.values.keys()].filter((name) => /^bot-chat-[a-f0-9]{64}\.json$/.test(name)).length, 2);
  assert.ok([...files.values.keys()].every((name) => !name.includes('/') && !name.includes(':')));
});

test('the real Bot HTTP service uses the injected Store capability and restores a created Bot', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'mbot-store-service-'));
  let service;
  t.after(async () => { if (service) await service.dispose(); await rm(directory, { recursive: true, force: true }); });
  const listener = createServer();
  await new Promise((resolve, reject) => { listener.once('error', reject); listener.listen(0, '127.0.0.1', resolve); });
  const port = listener.address().port;
  await new Promise((resolve, reject) => listener.close((error) => error ? reject(error) : resolve()));
  const files = fakeCapability();
  const options = { port, dataDir: directory, filesDir: directory, signal: new AbortController().signal, logger: { info() {}, warn() {}, error() {} } };
  const open = () => startBotService({ ...options, storePersistence: createBotStorePersistence({ dataDir: directory, files }) });
  service = await open();
  const token = service.descriptor.headers.Authorization;
  const response = await fetch(`${service.descriptor.baseUrl}/api/bots`, { method: 'POST',
    headers: { ...service.descriptor.headers, 'content-type': 'application/json' }, body: JSON.stringify({ name: 'Capability Bot' }) });
  assert.equal(response.status, 200);
  const created = (await response.json()).bot;
  assert.equal(created.name, 'Capability Bot');
  assert.ok([...files.values.values()].every((bytes) => !bytes.toString('utf8').includes(token)));
  await assert.rejects(stat(join(directory, 'state.json')), (error) => error.code === 'ENOENT');
  await service.dispose();
  service = await open();
  const restored = await fetch(`${service.descriptor.baseUrl}/api/state`, { headers: service.descriptor.headers });
  assert.equal(restored.status, 200);
  assert.ok((await restored.json()).bots.some((bot) => bot.id === created.id && bot.name === 'Capability Bot'));
});

test('an unknown write outcome poisons the lease until a fresh Store recovers durable state', async () => {
  const files = fakeCapability();
  const store = new Store(root, createBotStorePersistence({ dataDir: root, files }));
  await store.init();
  const chat = `bot:${store.state.bots[0].id}`;
  files.setUnknown();
  await assert.rejects(store.appendMessage(chat, { role: 'user', text: '可能已保存' }), (error) => error.code === 'runtime_data_outcome_unknown');
  const afterUnknown = files.mutations;
  await assert.rejects(store.appendMessage(chat, { role: 'user', text: '不得覆盖' }), (error) => error.code === 'runtime_data_outcome_unknown');
  assert.equal(files.mutations, afterUnknown);

  const restored = new Store(root, createBotStorePersistence({ dataDir: root, files }));
  await restored.init();
  assert.ok((await restored.getMessages(chat)).some((item) => item.text === '可能已保存'));
  assert.ok(!(await restored.getMessages(chat)).some((item) => item.text === '不得覆盖'));
});

test('an unknown usage append is not retried and a missing ledger alone means empty', async () => {
  const files = fakeCapability();
  const store = new Store(root, createBotStorePersistence({ dataDir: root, files }));
  await store.init();
  assert.deepEqual(await store.readUsage(0), []);
  files.setUnknown();
  await assert.rejects(store.recordUsage({ ts: 101, turnId: 'turn_1' }), (error) => error.code === 'runtime_data_outcome_unknown');
  const afterUnknown = files.mutations;
  await assert.rejects(store.recordUsage({ ts: 101, turnId: 'turn_1' }), (error) => error.code === 'runtime_data_outcome_unknown');
  assert.equal(files.mutations, afterUnknown);
  const restored = new Store(root, createBotStorePersistence({ dataDir: root, files }));
  await restored.init();
  assert.deepEqual((await restored.readUsage(0)).map((row) => row.turnId), ['turn_1']);
  files.setReadError('runtime_data_unavailable');
  await assert.rejects(restored.readUsage(0), (error) => error.code === 'runtime_data_unavailable' && !error.message.includes('private'));
});

test('a queued mutation cannot run after an earlier write returns an unknown outcome', async () => {
  const files = fakeCapability();
  let started, finish;
  const entered = new Promise((resolve) => { started = resolve; });
  let calls = 0;
  files.replace = async () => { calls++; started(); return new Promise((resolve) => { finish = resolve; }); };
  const persistence = createBotStorePersistence({ dataDir: root, files });
  const first = persistence.writeJsonAtomic(join(root, 'state.json'), { version: 1 });
  const second = persistence.writeJsonAtomic(join(root, 'state.json'), { version: 2 });
  const checked = Promise.all([
    assert.rejects(first, (error) => error.code === 'runtime_data_outcome_unknown'),
    assert.rejects(second, (error) => error.code === 'runtime_data_outcome_unknown'),
  ]);
  await entered;
  finish('outcome_unknown');
  await checked;
  assert.equal(calls, 1);
});

test('a full mutation queue refuses more work and invalidates later writes', async () => {
  const files = fakeCapability();
  let started, finish, calls = 0;
  const entered = new Promise((resolve) => { started = resolve; });
  files.replace = async () => { calls++; started(); return new Promise((resolve) => { finish = resolve; }); };
  const persistence = createBotStorePersistence({ dataDir: root, files });
  const pending = [persistence.writeJsonAtomic(join(root, 'state.json'), { n: 0 })];
  await entered;
  for (let n = 1; n < 32; n++) pending.push(persistence.writeJsonAtomic(join(root, 'state.json'), { n }));
  const results = Promise.allSettled(pending);
  await assert.rejects(persistence.writeJsonAtomic(join(root, 'state.json'), { n: 32 }), (error) => error.code === 'runtime_data_unavailable');
  finish('published');
  const settled = await results;
  assert.equal(calls, 1);
  assert.equal(settled.filter((item) => item.status === 'fulfilled').length, 0);
  assert.ok(settled.filter((item) => item.status === 'rejected').every((item) => item.reason.code === 'runtime_data_reload_required'));
});

test('corrupt persisted state is surfaced without echoing it or replacing it with an empty Bot', async () => {
  for (const bytes of [Buffer.from([0xff]), Buffer.from('{"private-content":')]) {
    const files = fakeCapability();
    files.values.set('bot-state.json', bytes);
    const store = new Store(root, createBotStorePersistence({ dataDir: root, files }));
    await assert.rejects(store.init(), (error) => error.code === 'runtime_data_invalid' && !error.message.includes('private-content'));
    assert.equal(files.mutations, 0);
    assert.deepEqual(files.values.get('bot-state.json'), bytes);
  }
});

test('path boundaries reject and an oversized save requires a clean reload', async () => {
  const files = fakeCapability();
  const persistence = createBotStorePersistence({ dataDir: root, files });
  await assert.rejects(persistence.readJson(join(root, 'messages', '..', 'private.json'), []), (error) => error.code === 'runtime_data_invalid');
  await assert.rejects(persistence.writeJsonAtomic(join(root, 'state.json'), { text: 'a'.repeat(1024 * 1024) }), (error) => error.code === 'runtime_data_too_large');
  assert.equal(files.mutations, 0);
  await assert.rejects(persistence.writeJsonAtomic(join(root, 'state.json'), { ok: true }), (error) => error.code === 'runtime_data_reload_required');
  const fresh = createBotStorePersistence({ dataDir: root, files });
  await fresh.writeJsonAtomic(join(root, 'state.json'), { ok: true });
  assert.deepEqual(await fresh.readJson(join(root, 'state.json'), {}), { ok: true });
  await assert.rejects(fresh.appendUsage(join(root, 'messages', 'bad.json'), 'x'), (error) => error.code === 'runtime_data_invalid');
  assert.throws(() => createBotStorePersistence({ dataDir: '/virtual/../escape', files }), TypeError);
});

test('a definite native size refusal stays explicit and prevents reuse until reload', async () => {
  const files = fakeCapability();
  const persistence = createBotStorePersistence({ dataDir: root, files });
  await persistence.appendUsage(join(root, 'usage.jsonl'), 'a'.repeat(1024 * 1024));
  await assert.rejects(persistence.appendUsage(join(root, 'usage.jsonl'), 'x'), (error) => error.code === 'runtime_data_too_large');
  await assert.rejects(persistence.readUsage(join(root, 'usage.jsonl')), (error) => error.code === 'runtime_data_reload_required');
  const fresh = createBotStorePersistence({ dataDir: root, files });
  assert.equal((await fresh.readUsage(join(root, 'usage.jsonl'))).length, 1024 * 1024);
});

test('an oversized Store save cannot later publish an in-memory failed change', async () => {
  const files = fakeCapability();
  const store = new Store(root, createBotStorePersistence({ dataDir: root, files }));
  await store.init();
  const saved = Buffer.from(files.values.get('bot-state.json'));
  store.state.settings.userName = 'a'.repeat(1024 * 1024);
  await assert.rejects(store.save(), (error) => error.code === 'runtime_data_too_large');
  store.state.settings.userName = 'changed after failure';
  await assert.rejects(store.save(), (error) => error.code === 'runtime_data_reload_required');
  assert.deepEqual(files.values.get('bot-state.json'), saved);
  const restored = new Store(root, createBotStorePersistence({ dataDir: root, files }));
  await restored.init();
  assert.equal(restored.state.settings.userName, '');
});
