// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, rm, stat } from 'node:fs/promises';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { MemoryStore } from '../vendor/minimax-bot/miniapp/node/memory.mjs';
import { createBotFileLease } from './bot-file-lease.mjs';
import { createBotMemoryFilesystem } from './bot-memory-filesystem.mjs';
import { createBotPersistence } from './bot-persistence.mjs';
import { startBotService } from './service.mjs';

const root = '/virtual/mbot';
const nativeError = (code) => Object.assign(new Error('private native detail'), { code });
function fakeFiles() {
  const values = new Map();
  let renames = 0, unknownRename = -1, replaces = 0, unknownReplace = -1, unknownAppend = false, unknownPublish = false;
  return {
    values,
    failRenameAfterApplying(number) { unknownRename = number; },
    failReplaceAfterApplying(number) { unknownReplace = number; },
    failAppendAfterApplying() { unknownAppend = true; },
    failPublishAfterApplying() { unknownPublish = true; },
    async read(name) { if (!values.has(name)) throw nativeError('runtime_data_not_found'); return Uint8Array.from(values.get(name)); },
    async size(name) { if (!values.has(name)) throw nativeError('runtime_data_not_found'); return values.get(name).length; },
    async replace(name, bytes) {
      values.set(name, Buffer.from(bytes));
      if (++replaces === unknownReplace) return 'outcome_unknown';
      return 'published';
    },
    async append(name, bytes) {
      const merged = Buffer.concat([values.get(name) ?? Buffer.alloc(0), Buffer.from(bytes)]);
      if (merged.length > 1024 * 1024) throw nativeError('runtime_data_too_large');
      values.set(name, merged);
      if (unknownAppend) { unknownAppend = false; return 'outcome_unknown'; }
      return 'published';
    },
    async publish(name, bytes) {
      if (values.has(name)) return 'already_exists';
      values.set(name, Buffer.from(bytes));
      if (unknownPublish) { unknownPublish = false; return 'outcome_unknown'; }
      return 'published';
    },
    async rename(from, to) {
      if (!values.has(from)) throw nativeError('runtime_data_not_found');
      values.set(to, values.get(from)); values.delete(from);
      if (++renames === unknownRename) return 'outcome_unknown';
      return 'published';
    },
    async remove(name) { if (!values.delete(name)) throw nativeError('runtime_data_not_found'); return 'removed'; },
  };
}
const filesystem = (files, dataDir = root) => createBotMemoryFilesystem({ dataDir, lease: createBotFileLease(files) });

test('the real MemoryStore preserves team and Bot memories, backups and journal across reopen', async () => {
  const files = fakeFiles(), io = filesystem(files);
  const memory = new MemoryStore(root, io);
  await memory.init();
  const original = await memory.remember('team', { text: '喜欢简短回答', key: 'style' });
  await assert.rejects(memory.remember('team', { text: 'password: test-value', key: 'private' }));
  const updated = await memory.remember('team', { text: '喜欢详细回答', key: 'style' });
  assert.equal(updated.entry.id, original.entry.id);
  await memory.remember('team', { text: '喜欢有例子的回答', key: 'style' });
  await memory.remember('bot_alpha', { text: '机器人专属记忆', key: 'role' });
  const backup = await io.readFile(join(root, 'memory', 'backups', 'team.pre-storage-v2.md'), 'utf8');
  assert.ok(backup.includes('喜欢简短回答'));
  assert.ok(!backup.includes('喜欢详细回答'));

  const restored = new MemoryStore(root, filesystem(files));
  await restored.init();
  assert.equal((await restored.list('team'))[0].text, '喜欢有例子的回答');
  assert.equal((await restored.list('bot_alpha'))[0].text, '机器人专属记忆');
  assert.equal(restored.journalEntries(await restored.journalText()).length, 4);
  await restored.forget('team', original.entry.id, 'test');
  assert.deepEqual(await restored.list('team'), []);
  await restored.archive('bot_alpha');
  assert.deepEqual(await restored.list('bot_alpha'), []);
  assert.ok([...files.values.keys()].every((name) => /^bot-memory-[a-f0-9]{64}\.bin$/.test(name)));
});

test('legacy memory is backed up byte for byte before its first change', async () => {
  const files = fakeFiles(), io = filesystem(files);
  const path = join(root, 'memory', 'team.md');
  const legacy = Buffer.from('\ufeff# Team memory\r\n\r\n- 原始内容 [source: chat; added: 2024-01-01; by: User]\r\n');
  await io.writeFile(path, legacy);
  const memory = new MemoryStore(root, io);
  await memory.init();
  await memory.remember('team', { text: '新增记忆', key: 'new' });
  assert.deepEqual(await io.readFile(join(root, 'memory', 'backups', 'team.pre-storage-v2.md')), legacy);
});

test('an unknown journal append recovers without duplicating its operation', async () => {
  const files = fakeFiles(), memory = new MemoryStore(root, filesystem(files));
  await memory.init();
  files.failAppendAfterApplying();
  await assert.rejects(memory.remember('team', { text: '日志恢复', key: 'journal' }),
    (error) => error.code === 'memory_pending' && error.details?.causeCode === 'runtime_data_outcome_unknown');
  // The durable pending record, not the prepared document, is the source of
  // recovery. Simulate cleanup after the former writer has fully retired.
  const stages = [...files.values.keys()].filter((name) => /^bot-memory-stage-[a-f0-9]{64}\.bin$/.test(name));
  assert.equal(stages.length, 1);
  for (const stage of stages) files.values.delete(stage);
  const restored = new MemoryStore(root, filesystem(files));
  await restored.init();
  assert.equal((await restored.list('team'))[0].text, '日志恢复');
  assert.equal(restored.journalEntries(await restored.journalText()).length, 1);
});

test('an unknown preparation write before the pending record does not invent a committed memory', async () => {
  const files = fakeFiles(), memory = new MemoryStore(root, filesystem(files));
  await memory.init();
  files.failReplaceAfterApplying(1);
  await assert.rejects(memory.remember('team', { text: '尚未确认', key: 'prepare' }),
    (error) => error.code === 'runtime_data_outcome_unknown');
  const restored = new MemoryStore(root, filesystem(files));
  await restored.init();
  assert.deepEqual(await restored.list('team'), []);
  assert.equal(restored.journalEntries(await restored.journalText()).length, 0);
  // The rejected lease cannot clean an already-created temporary record.
  // Durable orphan cleanup is a separate native lifecycle responsibility.
  assert.equal(files.values.size, 1);
  assert.match([...files.values.keys()][0], /^bot-memory-stage-[a-f0-9]{64}\.bin$/);
});

test('a pending transaction from the old stage naming scheme still recovers', async () => {
  const files = fakeFiles(), memory = new MemoryStore(root, filesystem(files));
  await memory.init();
  files.failAppendAfterApplying();
  await assert.rejects(memory.remember('team', { text: '旧事务恢复', key: 'legacy' }),
    (error) => error.code === 'memory_pending');
  const pendingName = `bot-memory-${createHash('sha256').update('.pending.json').digest('hex')}.bin`;
  const transaction = JSON.parse(files.values.get(pendingName).toString('utf8'));
  const oldStage = `bot-memory-${createHash('sha256').update(`team.md.${transaction.operationId}.pending`).digest('hex')}.bin`;
  const newStage = [...files.values.keys()].find((name) => /^bot-memory-stage-[a-f0-9]{64}\.bin$/.test(name));
  assert.ok(newStage);
  files.values.set(oldStage, files.values.get(newStage));
  files.values.delete(newStage);
  const restored = new MemoryStore(root, filesystem(files));
  await restored.init();
  assert.equal((await restored.list('team'))[0].text, '旧事务恢复');
  assert.equal(restored.journalEntries(await restored.journalText()).length, 1);
  // Old hashed orphans cannot be safely distinguished from durable files.
  assert.ok(files.values.has(oldStage));
});

test('an unknown exclusive backup does not replace the original memory on retry', async () => {
  const files = fakeFiles(), memory = new MemoryStore(root, filesystem(files));
  await memory.init();
  await memory.remember('team', { text: '原始内容', key: 'topic' });
  files.failPublishAfterApplying();
  await assert.rejects(memory.remember('team', { text: '更新内容', key: 'topic' }),
    (error) => error.code === 'memory_backup_failed' && error.details?.causeCode === 'runtime_data_outcome_unknown');
  const io = filesystem(files), restored = new MemoryStore(root, io);
  await restored.init();
  assert.equal((await restored.list('team'))[0].text, '原始内容');
  await restored.remember('team', { text: '更新内容', key: 'topic' });
  assert.ok((await io.readFile(join(root, 'memory', 'backups', 'team.pre-storage-v2.md'), 'utf8')).includes('原始内容'));
});

test('a rename with unknown outcome invalidates the shared lease and recovery does not duplicate the journal', async () => {
  const files = fakeFiles();
  const { storePersistence: store, memoryFilesystem } = createBotPersistence({ dataDir: root, files });
  await store.writeJsonAtomic(join(root, 'state.json'), { version: 1 });
  const memory = new MemoryStore(root, memoryFilesystem);
  await memory.init();
  files.failRenameAfterApplying(2);
  await assert.rejects(memory.remember('team', { text: '恢复事务', key: 'recovery' }),
    (error) => error.code === 'memory_pending' && error.details?.causeCode === 'runtime_data_outcome_unknown');
  await assert.rejects(store.readJson(join(root, 'state.json'), {}), (error) => error.code === 'runtime_data_outcome_unknown');

  const restored = new MemoryStore(root, filesystem(files));
  await restored.init();
  assert.equal((await restored.list('team'))[0].text, '恢复事务');
  const journal = restored.journalEntries(await restored.journalText());
  assert.equal(journal.length, 1);
  assert.equal(new Set(journal.map((row) => row.operationId)).size, 1);
});

test('memory paths outside the permitted virtual namespace are rejected', async () => {
  const files = fakeFiles(), io = filesystem(files);
  await assert.rejects(io.readFile('/virtual/private', 'utf8'), (error) => error.code === 'runtime_data_invalid');
  await assert.rejects(io.writeFile('/virtual/mbot/memory/bots/../../secret', 'x', 'utf8'), (error) => error.code === 'runtime_data_invalid');
  await assert.rejects(io.mkdir('/virtual/mbot/memory/elsewhere', { recursive: true }), (error) => error.code === 'runtime_data_invalid');
  assert.equal(files.values.size, 0);
  const incomplete = createBotFileLease({ read() {}, replace() {}, append() {} });
  assert.throws(() => createBotMemoryFilesystem({ dataDir: root, lease: incomplete }), TypeError);
});

test('the real Bot JSON service can restore state and memory without a physical Bot data directory', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'mbot-memory-service-'));
  const dataDir = join(directory, 'virtual-bot');
  let service;
  t.after(async () => { if (service) await service.dispose(); await rm(directory, { recursive: true, force: true }); });
  const listener = createServer();
  await new Promise((resolve, reject) => { listener.once('error', reject); listener.listen(0, '127.0.0.1', resolve); });
  const port = listener.address().port;
  await new Promise((resolve, reject) => listener.close((error) => error ? reject(error) : resolve()));
  const files = fakeFiles();
  const open = () => {
    const persistence = createBotPersistence({ dataDir, files });
    return startBotService({ port, dataDir, filesDir: directory, signal: new AbortController().signal,
      logger: { info() {}, warn() {}, error() {} }, ...persistence });
  };
  service = await open();
  const token = service.descriptor.headers.Authorization;
  const response = await fetch(`${service.descriptor.baseUrl}/api/memory/team`, { method: 'POST',
    headers: { ...service.descriptor.headers, 'content-type': 'application/json' }, body: JSON.stringify({ text: '持久记忆', key: 'note' }) });
  assert.equal(response.status, 200);
  await response.text();
  assert.ok([...files.values.values()].every((bytes) => !bytes.toString('utf8').includes(token)));
  await assert.rejects(stat(dataDir), (error) => error.code === 'ENOENT');
  await service.dispose();
  service = await open();
  const restored = await fetch(`${service.descriptor.baseUrl}/api/memory/team`, { headers: service.descriptor.headers });
  assert.equal(restored.status, 200);
  assert.ok(JSON.stringify(await restored.json()).includes('持久记忆'));
  await assert.rejects(stat(dataDir), (error) => error.code === 'ENOENT');
});

test('restricted Bot service uses broker persistence and explicitly refuses unsupported file routes', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'mbot-restricted-service-'));
  const dataDir = join(directory, 'virtual-bot');
  let service;
  t.after(async () => { if (service) await service.dispose(); await rm(directory, { recursive: true, force: true }); });
  const listener = createServer();
  await new Promise((resolve, reject) => { listener.once('error', reject); listener.listen(0, '127.0.0.1', resolve); });
  const port = listener.address().port;
  await new Promise((resolve, reject) => listener.close((error) => error ? reject(error) : resolve()));
  const files = fakeFiles();
  const open = () => startBotService({ port, dataDir, fileMode: 'unavailable', signal: new AbortController().signal,
    logger: { info() {}, warn() {}, error() {} }, ...createBotPersistence({ dataDir, files }) });
  await assert.rejects(startBotService({ port, dataDir, fileMode: 'unavailable', signal: new AbortController().signal,
    logger: { info() {}, warn() {}, error() {} } }), TypeError);
  await assert.rejects(startBotService({ port, dataDir, fileMode: 'typo' }), TypeError);
  service = await open();
  assert.equal(service.workspace, null);
  const request = (path, options = {}) => fetch(`${service.descriptor.baseUrl}${path}`, {
    ...options, headers: { connection: 'close', ...service.descriptor.headers, ...options.headers },
  });
  const anonymous = await fetch(`${service.descriptor.baseUrl}/api/uploads`, { method: 'POST', headers: { connection: 'close' }, body: 'x' });
  assert.equal(anonymous.status, 403);
  await anonymous.text();
  const create = await request('/api/bots', { method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ name: 'Restricted Bot' }) });
  assert.equal(create.status, 200);
  const { bot } = await create.json();
  const blocked = [
    ['/api/media?chat=bot:x&src=x', {}],
    ['/api/uploads?name=test.txt', { method: 'POST', body: 'data' }],
    ['/api/uploads/abcdef012345-test.txt', {}],
    ['/api/avatars', {}],
    ['/api/avatars', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ avatar: 'data:image/png;base64,AA==' }) }],
    ['/api/avatars/avatar.png', {}],
    ['/api/bots', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ name: 'No avatar', avatar: 'data:image/png;base64,AA==' }) }],
    [`/api/chats/bot:${bot.id}/messages`, { method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ text: 'hello', attachments: ['abcdef012345-test.txt'] }) }],
  ];
  for (const [path, options] of blocked) {
    const response = await request(path, options);
    assert.equal(response.status, 503, path);
    assert.equal((await response.json()).error, 'files_unavailable');
  }
  const client = await request('/');
  assert.equal(client.status, 404);
  await client.text();
  await assert.rejects(stat(dataDir), (error) => error.code === 'ENOENT');
  await service.dispose();
  service = await open();
  const state = await request('/api/state');
  assert.equal(state.status, 200);
  assert.ok((await state.json()).bots.some((entry) => entry.id === bot.id));
  await assert.rejects(stat(dataDir), (error) => error.code === 'ENOENT');
});
