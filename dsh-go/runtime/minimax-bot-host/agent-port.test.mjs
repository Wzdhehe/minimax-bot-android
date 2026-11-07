import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { test } from 'node:test';

import { createBotAgentPort } from './agent-port.mjs';
import { NativeBindingError } from './native-model-binding.mjs';
import { start } from '../vendor/minimax-bot/miniapp/node/server.mjs';

// These tests exercise the upstream gateway/service contract with fake turns;
// the real scoped fetch and native binding are covered separately.
const stubRoute = {
  binding: { runForModelKey: (_key, operation) => operation() },
  resolveModelKey: async () => 'fixture/model',
};

test('preserves the host identity and forwards cancellation, interaction and failures', async () => {
  const calls = [];
  const failure = new Error('runtime refused request');
  const gateway = Object.fromEntries(['run', 'models', 'reply', 'answer'].map((method) => [method, async (input) => {
    calls.push({ method, input });
    if (method === 'reply') throw failure;
    return { method };
  }]));
  const caller = { pluginId: 'builtin-bot', runId: 'generation-1' };
  const port = createBotAgentPort({ agent: gateway, caller, ...stubRoute });
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
    agent: createBotAgentPort({ agent: gateway, caller: { pluginId: 'builtin-bot', runId: 'registered-run' }, ...stubRoute }),
  });
  t.after(async () => {
    await app.dispose();
    // The upstream engine may append a final persistence task after dispose's
    // writeChain snapshot. Retry only fixture deletion, not model mutations.
    // This does not establish that production shutdown drains every turn.
    await rm(dataDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 20 });
  });
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

test('runs the turn inside the bound native route', async () => {
  const order = [];
  const binding = {
    runForModelKey: (key, operation) => { order.push(['bind', key]); return operation(); },
    install() {},
  };
  const agent = {
    run: async () => { order.push(['run']); return 'ok'; },
    models: async () => [], reply: async () => {}, answer: async () => {},
  };
  const port = createBotAgentPort({ agent, caller: { pluginId: 'builtin:minimax-bot', runId: 'r1' }, binding });
  const result = await port.run({ text: 'hi', sessionId: 's1', model: 'custom_provider:minimax/m1' });
  assert.equal(result, 'ok');
  assert.deepEqual(order, [['bind', 'custom_provider:minimax/m1'], ['run']]);
});

test('resolves the session model when the caller does not name one', async () => {
  const binding = { runForModelKey: (key) => key };
  const agent = {
    run: async () => 'ok', models: async () => [], reply: async () => {}, answer: async () => {},
  };
  const port = createBotAgentPort({
    agent, caller: { pluginId: 'builtin:minimax-bot', runId: 'r1' }, binding,
    resolveModelKey: async () => 'custom_provider:minimax/m2',
  });
  assert.equal(await port.run({ text: 'hi', sessionId: 's1' }), 'custom_provider:minimax/m2');
});

test('accepts the upstream model selection object as the turn model', async () => {
  const order = [];
  const binding = {
    runForModelKey: (key, operation) => { order.push(key); return operation(); },
    install() {},
  };
  const agent = {
    run: async () => 'ok', models: async () => [], reply: async () => {}, answer: async () => {},
  };
  const port = createBotAgentPort({
    agent, caller: { pluginId: 'builtin:minimax-bot', runId: 'r1' }, binding,
  });
  await port.run({ text: 'hi', sessionId: 's1', model: { providerId: 'custom_provider:minimax', modelId: 'm3' } });
  assert.deepEqual(order, ['custom_provider:minimax/m3']);
});

test('refuses a turn whose model is not in the native catalog', async () => {
  const binding = {
    runForModelKey: () => { throw new NativeBindingError('model_not_available'); },
    install() {},
  };
  const agent = {
    run: async () => 'ok', models: async () => [], reply: async () => {}, answer: async () => {},
  };
  const port = createBotAgentPort({ agent, caller: { pluginId: 'builtin:minimax-bot', runId: 'r1' }, binding });
  await assert.rejects(() => port.run({ text: 'hi', sessionId: 's1', model: 'custom_provider:other/m1' }),
    (error) => error.code === 'model_not_available');
});

test('refuses a turn when no model can be resolved at all', async () => {
  const binding = { runForModelKey: (key, operation) => operation(), install() {} };
  const agent = {
    run: async () => 'ok', models: async () => [], reply: async () => {}, answer: async () => {},
  };
  const port = createBotAgentPort({
    agent, caller: { pluginId: 'builtin:minimax-bot', runId: 'r1' }, binding,
    resolveModelKey: async () => null,
  });
  await assert.rejects(() => port.run({ text: 'hi', sessionId: 's1' }),
    (error) => error instanceof NativeBindingError && error.code === 'model_not_available');
});

test('binds the answer turn as well', async () => {
  const order = [];
  const binding = {
    runForModelKey: (key, operation) => { order.push(key); return operation(); },
    install() {},
  };
  const agent = {
    run: async () => 'ok', models: async () => [], reply: async () => {}, answer: async () => {},
  };
  const port = createBotAgentPort({ agent, caller: { pluginId: 'builtin:minimax-bot', runId: 'r1' }, binding });
  await port.answer({ sessionId: 's1', requestId: 'q', answers: [], model: 'custom_provider:minimax/m1' });
  assert.deepEqual(order, ['custom_provider:minimax/m1']);
});

test('refuses model turns before calling the gateway when no binding is configured', async () => {
  const calls = [];
  const agent = {
    run: async (input) => { calls.push(['run', input.model]); return 'ok'; },
    models: async () => ({}), reply: async () => {},
    answer: async () => { calls.push(['answer']); },
  };
  const port = createBotAgentPort({ agent, caller: { pluginId: 'builtin:minimax-bot', runId: 'r1' } });
  await assert.rejects(port.run({ text: 'hi', model: 'custom_provider:minimax/m1' }),
    (error) => error.code === 'native_binding_unavailable');
  await assert.rejects(port.answer({ sessionId: 's1', requestId: 'q', answers: [] }),
    (error) => error.code === 'native_binding_unavailable');
  assert.deepEqual(calls, []);
});

test('the trusted Bot policy approves owned asks once without exposing an approval mode to HTTP', async () => {
  const replies = [], events = [];
  let late;
  const gateway = {
    async run(input) {
      late = input.onEvent;
      const ask = { type: 'permission.requested', sessionId: 'owned', requestId: 'request-1' };
      input.onEvent(ask); input.onEvent(ask);
      input.onEvent({ type: 'question.asked', sessionId: 'owned', requestId: 'question-1' });
      return { status: 'finished' };
    },
    async models() {}, async answer() {}, async reply(input) { replies.push(input); },
  };
  const port = createBotAgentPort({ agent: gateway, caller: { pluginId: 'builtin:minimax-bot', runId: 'r1' },
    autoApprove: true, ...stubRoute });
  await port.run({ text: 'work', autoApprove: false }, { onEvent: (event) => events.push(event.type) });
  assert.deepEqual(replies, [{ caller: { pluginId: 'builtin:minimax-bot', runId: 'r1' }, sessionId: 'owned', requestId: 'request-1', decision: 'allowOnce' }]);
  assert.deepEqual(events, ['question.asked']);
  late({ type: 'permission.requested', sessionId: 'owned', requestId: 'after-retirement' });
  await Promise.resolve(); assert.equal(replies.length, 1);
});

test('failed automatic approval aborts the run and never reports successful completion', async () => {
  let signal;
  const port = createBotAgentPort({ autoApprove: true, ...stubRoute, caller: { pluginId: 'builtin:minimax-bot', runId: 'r1' },
    agent: { async models() {}, async answer() {}, async reply() { throw Error('fixture'); },
      async run(input) { signal = input.signal; input.onEvent({ type: 'permission.requested', sessionId: 'owned', requestId: 'req' }); return {}; } } });
  await assert.rejects(port.run({ text: 'work' }), /bot_permission_reply_failed/);
  assert.equal(signal.aborted, true);
});

test('an empty upstream finish becomes a visible nonretryable failure, not a successful blank reply', async () => {
  const agent = { async run() { return { status: 'finished', text: '', sessionId: 'session-1' }; },
    async models() {}, async reply() {}, async answer() {} };
  const port = createBotAgentPort({ agent, caller: { pluginId: 'builtin-bot', runId: 'generation' }, ...stubRoute, autoApprove: true });
  const result = await port.run({ text: 'hello' });
  assert.equal(result.status, 'error');
  assert.equal(result.error.code, 'MODEL_OUTPUT_EMPTY');
  assert.equal(result.error.retryable, false);
  assert.equal(result.sessionId, 'session-1');
  agent.run = async () => ({ status: 'finished', text: '', pendingQuestion: { requestId: 'q' } });
  assert.equal((await port.run({ text: 'a question' })).status, 'finished');
  agent.run = async (input) => { input.onEvent({ type: 'text.delta', text: 'streamed reply' }); return { status: 'finished', text: '' }; };
  assert.equal((await port.run({ text: 'streamed' })).status, 'finished');
});

test('a native model failure aborts the owned gateway turn and reports error without replaying it', async () => {
  let calls = 0;
  const binding = { async runForModelKey(_key, operation, { onFailure }) {
    const running = operation();
    onFailure('broker_unavailable');
    return running;
  } };
  const agent = { async run(input) {
    calls++;
    input.onEvent({ type: 'turn.started', sessionId: 'owned-session', turnId: 'owned-turn' });
    await new Promise((done) => input.signal.addEventListener('abort', done, { once: true }));
    throw Object.assign(new Error('gateway cancellation details'), { code: 'REQUEST_CANCELLED' });
  }, async models() {}, async reply() {}, async answer() {} };
  const port = createBotAgentPort({ agent, caller: { pluginId: 'builtin-bot', runId: 'generation' },
    binding, resolveModelKey: async () => 'p/m', autoApprove: true });
  const result = await port.run({ text: 'hello' });
  assert.equal(calls, 1);
  assert.equal(result.status, 'error');
  assert.equal(result.sessionId, 'owned-session');
  assert.equal(result.turnId, 'owned-turn');
  assert.equal(result.error.code, 'NATIVE_MODEL_FAILED');
  assert.equal(result.error.retryable, false);
  assert.equal(JSON.stringify(result).includes('gateway cancellation details'), false);
});
