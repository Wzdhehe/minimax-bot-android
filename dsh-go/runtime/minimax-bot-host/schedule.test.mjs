// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtemp, rm } from 'node:fs/promises';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Store } from '../vendor/minimax-bot/miniapp/node/store.mjs';
import { startBotService } from './service.mjs';

test('due execution and Bot-owned MCP task management share the real service store', async (t) => {
  const filesDir = await mkdtemp(join(tmpdir(), 'bot-schedule-'));
  const dataDir = join(filesDir, 'bot-data');
  const store = new Store(dataDir);
  await store.init();
  const botId = store.state.bots[0].id;
  store.state.schedules.push({ id: 'sch_due', botId, name: 'Due', prompt: 'scheduled-probe', kind: 'once', enabled: true, runAt: Date.now() - 1000, nextRunAt: Date.now() - 1000 });
  store.state.schedules.push({ id: 'sch_disabled', botId, name: 'Off', prompt: 'must-not-run', kind: 'once', enabled: false, runAt: Date.now() - 1000, nextRunAt: Date.now() - 1000 });
  await store.save();
  const probe = createServer();
  await new Promise((resolve, reject) => { probe.once('error', reject); probe.listen(0, '127.0.0.1', resolve); });
  const port = probe.address().port;
  await new Promise((resolve, reject) => probe.close((error) => error ? reject(error) : resolve()));
  let runs = 0;
  const agent = { async run(input, options) {
    runs++;
    assert.match(input.text, /scheduled-probe/);
    const ids = { sessionId: 'schedule-session', turnId: 'schedule-turn' };
    options.onEvent({ type: 'turn.started', ...ids });
    return { ...ids, status: 'finished', text: 'scheduled result' };
  } };
  const service = await startBotService({ port, filesDir, dataDir, agent, signal: new AbortController().signal, logger: { info() {}, warn() {}, error() {} } });
  t.after(async () => { await service.dispose(); await rm(filesDir, { recursive: true, force: true }); });
  let messages = [];
  for (let attempt = 0; attempt < 200; attempt++) {
    const response = await fetch(`${service.descriptor.baseUrl}/api/chats/bot:${botId}/messages`, { headers: service.descriptor.headers });
    messages = (await response.json()).messages;
    if (messages.some((message) => message.role === 'bot' && message.text === 'scheduled result' && message.status === 'done')) break;
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  assert.equal(runs, 1);
  assert.equal(messages.filter((message) => message.role === 'schedule').length, 1);
  assert.ok(messages.some((message) => message.text === 'scheduled result'));
  const state = await (await fetch(`${service.descriptor.baseUrl}/api/state`, { headers: service.descriptor.headers })).json();
  assert.deepEqual(state.schedules.map((schedule) => schedule.id), ['sch_disabled']);
  const post = async (path, body) => {
    const response = await fetch(service.descriptor.baseUrl + path, { method: 'POST', headers: { ...service.descriptor.headers, 'content-type': 'application/json' }, body: JSON.stringify(body) });
    assert.equal(response.status, 200);
    return response.json();
  };
  const own = (await post('/api/bots', { name: 'Scheduler', role: 'Manage my tasks' })).bot;
  const other = (await post('/api/bots', { name: 'Other', role: 'Other tasks' })).bot;
  let rpcId = 0;
  const tool = async (name, arguments_) => (await post('/mcp/minimax-bot', { jsonrpc: '2.0', id: ++rpcId, method: 'tools/call', params: { name, arguments: { caller_bot_id: own.id, ...arguments_ } } })).result;
  const created = await tool('create_schedule', { kind: 'daily', time: '09:00', prompt: 'self-created', name: 'Own task' });
  assert.notEqual(created.isError, true);
  const schedule = JSON.parse(created.content[0].text);
  assert.equal(schedule.bot_id, own.id);
  const list = JSON.parse((await tool('list_schedules', {})).content[0].text);
  assert.deepEqual(list.map((row) => row.schedule_id), [schedule.schedule_id]);
  const denied = await tool('create_schedule', { kind: 'daily', time: '09:00', prompt: 'not allowed', target_bot: other.id });
  assert.equal(denied.isError, true);
  const snapshot = await (await fetch(`${service.descriptor.baseUrl}/api/state`, { headers: service.descriptor.headers })).json();
  assert.ok(snapshot.schedules.some((row) => row.id === schedule.schedule_id && row.botId === own.id));
  await tool('delete_schedule', { schedule_id: schedule.schedule_id });
  assert.deepEqual(JSON.parse((await tool('list_schedules', {})).content[0].text), []);
});
