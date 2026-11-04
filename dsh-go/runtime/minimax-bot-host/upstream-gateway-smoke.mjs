// Bundled against a separately checked-out, pinned WebUI source tree by
// run-upstream-gateway-smoke.sh. Session execution is intentionally stubbed.
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createMiniAppAgentGateway } from '@mcode-test/agent-gateway';
import { createProcessLocalMiniAppAgentApplication } from '@mcode-test/process-local';
import { createBotAgentPort } from './agent-port.mjs';

const dataDir = await mkdtemp(join(tmpdir(), 'bot-upstream-gateway-'));
const selection = { providerId: 'minimax', modelId: 'M3' };
const owners = new Map();
const turns = [];
const gateway = createMiniAppAgentGateway({ dataDir, logger: { info() {}, warn() {} }, nowMs: Date.now });
gateway.bind({
  subscribePermissionAsks: () => () => {},
  createSession: async ({ pluginId }) => {
    owners.set('session-1', pluginId);
    return { sessionId: 'session-1' };
  },
  getOwner: async (id) => owners.get(id),
  resolveMiniAppRoot: async (id) => owners.has(id) ? { rootSessionId: id, pluginId: owners.get(id) } : undefined,
  defaultSessionModel: async () => ({ selection }),
  resolveModel: async (requested) => requested ?? selection,
  listModels: async () => [{ ...selection, displayName: 'M3' }],
  readSessionModel: async () => selection,
  setSessionModel: async () => {},
  resolveHostSession: async () => undefined,
  runTurn: async (input) => {
    turns.push(input);
    input.onEvent?.({ type: 'text.delta', sessionId: input.sessionId, turnId: 'turn-1', text: 'ok' });
    return { turnId: 'turn-1', status: 'finished', text: 'ok' };
  },
  answerQuestion: async () => ({ status: 'finished', text: 'ok' }),
  replyPermission: async () => 'processed',
});
const agent = createProcessLocalMiniAppAgentApplication(gateway);
// Gateway contract only: session and model execution are deliberately stubbed.
const bot = createBotAgentPort({ agent, caller: { pluginId: 'builtin-bot', runId: 'generation-1' },
  binding: { runForModelKey: (_key, operation) => operation() },
  resolveModelKey: async () => 'fixture/model',
});
try {
  await assert.rejects(bot.run({ text: 'hello' }), (error) => error.code === 'NOT_PERMITTED');
  const release = gateway.registerArena({
    pluginId: 'builtin-bot', processGeneration: 'generation-1',
    policy: { kind: 'allowlist', capabilities: ['session.run'], maxConcurrent: 2 },
  });
  const events = [];
  const first = await bot.run({ text: 'hello' }, { onEvent: (event) => events.push(event) });
  assert.equal(first.sessionId, 'session-1');
  assert.equal(events.find((event) => event.type === 'text.delta')?.text, 'ok');
  const second = await bot.run({ text: 'again', sessionId: first.sessionId });
  assert.equal(second.text, 'ok');
  assert.equal(turns.length, 2);
  // Run the actual vendored Bot HTTP service through the actual upstream gateway.
  const pluginRoot = process.env.BOT_PLUGIN_ROOT;
  assert.ok(pluginRoot, 'BOT_PLUGIN_ROOT is required');
  const { start } = await import(pathToFileURL(join(pluginRoot, 'miniapp/node/server.mjs')).href);
  const probe = createServer();
  await new Promise((resolve, reject) => { probe.once('error', reject); probe.listen(0, '127.0.0.1', resolve); });
  const port = probe.address().port;
  await new Promise((resolve, reject) => probe.close((error) => error ? reject(error) : resolve()));
  const service = await start({
    pluginRoot, dataDir: join(dataDir, 'bot'), listen: { host: '127.0.0.1', port },
    signal: new AbortController().signal, logger: { info() {}, warn() {}, error() {} }, agent: bot,
  });
  try {
    const base = `http://127.0.0.1:${port}`;
    const state = await (await fetch(`${base}/api/state`)).json();
    const url = `${base}/api/chats/bot:${state.bots[0].id}/messages`;
    const sent = await fetch(url, {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ text: 'hello from Bot', clientMessageId: 'gateway-test' }),
    });
    assert.equal(sent.status, 200);
    let complete = false;
    for (let attempt = 0; attempt < 200; attempt++) {
      const { messages } = await (await fetch(url)).json();
      if (messages.some((message) => message.role === 'bot' && message.text === 'ok' && message.status === 'done')) {
        complete = true;
        break;
      }
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    assert.ok(complete, 'Bot did not finish through upstream gateway');
    assert.equal(turns.length, 3);
  } finally {
    await service.dispose();
  }
  release();
  await assert.rejects(bot.run({ text: 'later' }), (error) => error.code === 'NOT_PERMITTED');
  console.log('PASS: Bot service through upstream gateway with stubbed model/session ports');
} finally {
  await rm(dataDir, { recursive: true, force: true });
}
