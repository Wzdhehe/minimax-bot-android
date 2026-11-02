import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { test } from 'node:test';

import { createBotAgentPort } from './agent-port.mjs';
import { start } from '../vendor/minimax-bot/miniapp/node/server.mjs';

test('preserves the host identity and forwards cancellation, interaction and failures', async () => {
  const calls = [];
  const failure = new Error('runtime refused request');
  const gateway = Object.fromEntries(['run', 'models', 'reply', 'answer'].map((method) => [method, async (input) => {
    calls.push({ method, input });
    if (method === 'reply') throw failure;
    return { method };
  }]));
  const caller = { pluginId: 'builtin-bot', runId: 'generation-1' };
  const port = createBotAgentPort({ agent: gateway, caller });
  caller.runId = 'changed';
  const abort = new AbortController();
  const onEvent = () => {};
  await port.run({ text: 'hello', caller: { pluginId: 'spoofed' }, sessionId: 'session-1' }, { signal: abort.signal, onEvent });
  await port.models();
  await assert.rejects(port.reply({ sessionId: 'session-1', requestId: 'req', decision: 'deny' }), (e) => e === failure);
  await port.answer({ sessionId: 'session-1', requestId: 'q', answers: [] }, { signal: abort.signal, onEvent });
  assert.deepEqual(calls.map((call) => call.method), ['run', 'models', 'reply', 'answer']);
  for (const { input } of calls) assert.deepEqual(input.caller, { pluginId: 'builtin-bot', runId: 'generation-1' });
  assert.equal(calls[0].input.signal, abort.signal);
  assert.equal(calls[0].input.onEvent, onEvent);
  assert.equal(calls[3].input.signal, abort.signal);
  assert.throws(() => createBotAgentPort({ agent: gateway, caller: {} }), TypeError);
});

test('official Bot service can send two turns through the agent port', async (t) => {
  const dataDir = await mkdtemp(join(tmpdir(), 'builtin-bot-port-'));
  const probe = createServer();
  await new Promise((resolve, reject) => { probe.once('error', reject); probe.listen(0, '127.0.0.1', resolve); });
  const portNumber = probe.address().port;
  await new Promise((resolve, reject) => probe.close((error) => error ? reject(error) : resolve()));
  const calls = [];
  const gateway = {
    async models() { return { models: [], default: undefined }; },
    async reply() {},
    async answer() {},
    async run(input) {
      calls.push(input);
      const ids = { sessionId: 'owned-session', turnId: `turn-${calls.length}` };
      input.onEvent({ type: 'turn.started', ...ids });
      input.onEvent({ type: 'text.delta', ...ids, text: `Reply ${calls.length}` });
      return { ...ids, status: 'finished', text: `Reply ${calls.length}` };
    },
  };
  const app = await start({
    pluginRoot: resolve('runtime/vendor/minimax-bot'), dataDir,
    listen: { host: '127.0.0.1', port: portNumber }, signal: new AbortController().signal,
    logger: { info() {}, warn() {}, error() {} },
    agent: createBotAgentPort({ agent: gateway, caller: { pluginId: 'builtin-bot', runId: 'registered-run' } }),
  });
  t.after(async () => { await app.dispose(); await rm(dataDir, { recursive: true, force: true }); });
  const base = `http://127.0.0.1:${portNumber}`;
  const state = await (await fetch(`${base}/api/state`)).json();
  const chat = `bot:${state.bots[0].id}`;
  const url = `${base}/api/chats/${chat}/messages`;
  for (let turn = 1; turn <= 2; turn++) {
    const response = await fetch(url, {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ text: `Question ${turn}`, clientMessageId: `test-${turn}` }),
    });
    assert.equal(response.status, 200);
    let found = false;
    for (let attempt = 0; attempt < 200; attempt++) {
      const { messages } = await (await fetch(url)).json();
      if (messages.some((message) => message.role === 'bot' && message.text === `Reply ${turn}` && message.status === 'done')) {
        found = true;
        break;
      }
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    assert.ok(found, `turn ${turn} did not finish`);
  }
  assert.equal(calls.length, 2);
  assert.equal(calls[1].sessionId, 'owned-session');
  assert.deepEqual(calls[0].caller, { pluginId: 'builtin-bot', runId: 'registered-run' });
});
