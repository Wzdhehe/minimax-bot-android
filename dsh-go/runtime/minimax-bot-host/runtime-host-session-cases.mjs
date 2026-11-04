// SPDX-License-Identifier: AGPL-3.0-only
// Real pinned gateway/facade, explicitly stubbed model and session execution.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createProcessLocalMiniAppAgentApplication } from '@mcode-test/process-local';
import { createBotRuntimeSession, createMiniAppAgentGateway } from './runtime-host-session.mjs';

function deferred() {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
}
function fixture(dataDir, { runTurn, onClose = async () => {}, modelApplication = null } = {}) {
  const owners = new Map();
  const created = [];
  const turns = [];
  const selection = { providerId: 'fixture-provider', modelId: 'fixture-model' };
  const gateway = createMiniAppAgentGateway({ dataDir, logger: { info() {}, warn() {} }, nowMs: Date.now });
  gateway.bind({
    subscribePermissionAsks: () => () => {},
    createSession: async (input) => {
      created.push(input);
      const id = `session-${created.length}`;
      owners.set(id, input.pluginId);
      return { sessionId: id };
    },
    getOwner: async (id) => owners.get(id),
    resolveMiniAppRoot: async (id) => owners.has(id) ? { rootSessionId: id, pluginId: owners.get(id) } : undefined,
    defaultSessionModel: async () => ({ selection }),
    resolveModel: async (requested) => requested ?? selection,
    listModels: async () => [{ ...selection, displayName: 'Fixture' }],
    readSessionModel: async () => selection,
    setSessionModel: async () => {},
    resolveHostSession: async () => undefined,
    runTurn: async (input) => {
      turns.push(input);
      if (runTurn) return runTurn(input);
      input.onEvent?.({ type: 'text.delta', sessionId: input.sessionId, turnId: 'turn-1', text: 'fixture reply' });
      return { turnId: 'turn-1', status: 'finished', text: 'fixture reply' };
    },
    answerQuestion: async () => ({ status: 'finished', text: 'fixture answer' }),
    replyPermission: async () => 'processed',
  });
  const application = createProcessLocalMiniAppAgentApplication(gateway);
  const hostApplication = { miniAppAgent: application, ...(modelApplication ?? {}) };
  const assembled = { host: { apiHost: { close: onClose }, application: hostApplication } };
  return { assembled, application, gateway, created, owners, turns };
}

test('trusted registration uses the actual gateway and ignores request-supplied identity', async (t) => {
  let host;
  const session = await createBotRuntimeSession({ dataDir: '/fixture/first', startHost: async ({ dataDir }) => {
    host = fixture(dataDir);
    return host.assembled;
  } });
  t.after(() => session.close());
  assert.equal('registerArena' in host.application, false);
  assert.deepEqual(Object.keys(session).sort(), ['agent', 'close', 'providerBinding', 'setSessionModel']);
  assert.equal(Object.isFrozen(session.agent), true);
  const events = [];
  const first = await session.agent.run({ text: 'hello', caller: { pluginId: 'forged', runId: 'forged' } },
    { onEvent: (event) => events.push(event) });
  assert.equal(first.text, 'fixture reply');
  assert.equal(host.created[0].pluginId, 'builtin:minimax-bot');
  assert.equal(events.find((event) => event.type === 'text.delta').text, 'fixture reply');
  await session.agent.run({ text: 'again', sessionId: first.sessionId });
  assert.equal(host.created.length, 1);
  assert.equal(host.turns.length, 2);
  host.owners.set('foreign-session', 'market-plugin');
  await assert.rejects(session.agent.run({ text: 'steal', sessionId: 'foreign-session' }), (error) => error.code === 'SESSION_NOT_FOUND');
  await assert.rejects(host.application.models({ caller: { pluginId: 'builtin:minimax-bot', runId: 'forged' } }),
    (error) => error.code === 'AGENT_UNAVAILABLE');
});

test('the native catalog is projected through the upstream provider API and every turn is bound', async (t) => {
  const calls = { create: [], update: [], select: [], list: [], test: [] };
  const modelApplication = {
    modelProviders: {
      async listUser() { return []; },
      async create(input) {
        calls.create.push(input);
        return { providerId: `custom_provider:${input.name}`, name: input.name };
      },
      async update(input) {
        calls.update.push(input);
        return { providerId: input.providerId, name: input.name };
      },
      async testProvider(input) { calls.test.push(input); return { success: true }; },
    },
    models: {
      async list() {
        calls.list.push(true);
        return [{ providerId: 'custom_provider:minimax', modelId: 'm1',
          modelConfigId: 'custom_provider:minimax/m1', selected: true }];
      },
      async select(input) { calls.select.push(input); return true; },
    },
  };
  const nativeBinding = { open() { return Promise.resolve(null); }, cancel() {} };
  const session = await createBotRuntimeSession({
    dataDir: '/fixture/catalog',
    catalog: {
      providers: [{ providerId: 'minimax', apiFormat: 'openai-completions', enabled: true, models: ['m1'] }],
      defaultProviderId: 'minimax',
      defaultModelId: 'm1',
    },
    nativeBinding,
    startHost: async ({ dataDir }) => fixture(dataDir, { modelApplication }).assembled,
  });
  t.after(() => session.close());
  assert.deepEqual(calls.create, [{
    name: 'minimax',
    baseUrl: 'https://models.mbot.invalid/v1',
    apiFormat: 'openai-completions',
    models: [{ modelId: 'm1' }],
    apiKey: 'native-broker-placeholder',
  }]);
  assert.deepEqual(calls.select, [{ providerId: 'custom_provider:minimax', modelId: 'm1' }]);
  assert.deepEqual(session.providerBinding, [{
    forkProviderId: 'custom_provider:minimax',
    nativeProviderId: 'minimax',
    apiFormat: 'openai-completions',
    models: ['m1'],
    connectivity: 'not_run',
  }]);
  // The turn runs inside the native route of the model the session selected.
  const turn = await session.agent.run({ text: 'hello' });
  assert.equal(turn.text, 'fixture reply');
  assert.equal(calls.list.length, 1);
  // A switch outside the native catalog is refused before it reaches the fork.
  await assert.rejects(() => session.setSessionModel({ providerId: 'custom_provider:other', modelId: 'm1', sessionId: 'session-1' }),
    (error) => error.code === 'model_not_available');
  assert.equal(calls.select.length, 1);
  // A switch inside the catalog goes to the upstream session selection.
  assert.equal(await session.setSessionModel({ providerId: 'custom_provider:minimax', modelId: 'm1', sessionId: 'session-1' }), true);
  assert.deepEqual(calls.select[1], { providerId: 'custom_provider:minimax', modelId: 'm1', sessionId: 'session-1' });
});

test('concurrent host construction keeps gateway ownership within its own async scope', async (t) => {
  const a = deferred(), b = deferred();
  const hosts = [];
  const start = (dataDir, own, other) => createBotRuntimeSession({ dataDir, startHost: async ({ dataDir: root }) => {
    own.resolve(); await other.promise;
    const host = fixture(root); hosts.push(host);
    return host.assembled;
  } });
  const [first, second] = await Promise.all([start('/fixture/a', a, b), start('/fixture/b', b, a)]);
  t.after(() => Promise.all([first.close(), second.close()]));
  await first.agent.run({ text: 'a' });
  await second.agent.run({ text: 'b' });
  assert.deepEqual(hosts.map((host) => host.turns.length), [1, 1]);
  await first.close();
  await assert.rejects(first.agent.models(), (error) => error.code === 'AGENT_UNAVAILABLE');
  assert.equal((await second.agent.models()).models.length, 1);
});

test('closing revokes queued and active turns, joins teardown and blocks retained ports', async () => {
  const bothStarted = deferred(), closeStarted = deferred(), finishClose = deferred();
  const signals = [];
  let host, closeCount = 0;
  const session = await createBotRuntimeSession({ dataDir: '/fixture/cancel', startHost: async ({ dataDir }) => {
    host = fixture(dataDir, {
      runTurn: async (input) => {
        signals.push(input.signal);
        if (signals.length === 2) bothStarted.resolve();
        await new Promise((resolve) => input.signal.addEventListener('abort', resolve, { once: true }));
        return { turnId: 'turn-aborted', status: 'aborted', text: '' };
      },
      onClose: async () => { closeCount++; closeStarted.resolve(); await finishClose.promise; },
    });
    return host.assembled;
  } });
  const settled = Promise.allSettled([1, 2, 3].map((id) => session.agent.run({ text: `queued-${id}` })));
  await bothStarted.promise;
  await new Promise(setImmediate);
  const closing = session.close();
  assert.equal(host.assembled.host.apiHost.close(), closing);
  await closeStarted.promise;
  assert.ok(signals.every((signal) => signal.aborted));
  let complete = false;
  closing.then(() => { complete = true; });
  await new Promise(setImmediate);
  assert.equal(complete, false);
  finishClose.resolve();
  await closing;
  const results = await settled;
  assert.equal(host.turns.length, 2, 'the queued third turn must not dispatch');
  assert.ok(results.every((result) => result.status === 'fulfilled' ? result.value.status === 'aborted' : result.reason.code === 'REQUEST_CANCELLED'));
  assert.equal(closeCount, 1);
  await assert.rejects(session.agent.models(), (error) => error.code === 'AGENT_UNAVAILABLE');
});

test('an abort before startup never constructs a runtime; an abort during startup closes the acquired runtime', async () => {
  const aborted = new AbortController(); aborted.abort();
  await assert.rejects(createBotRuntimeSession({ dataDir: '/fixture/aborted', signal: aborted.signal,
    startHost: () => assert.fail('must not construct after abort') }), (error) => error.code === 'REQUEST_CANCELLED');
  const started = deferred(), ready = deferred(), controller = new AbortController();
  let closes = 0;
  const pending = createBotRuntimeSession({ dataDir: '/fixture/abort-during', signal: controller.signal,
    startHost: async ({ dataDir }) => {
      const host = fixture(dataDir, { onClose: async () => { closes++; } });
      started.resolve(); await ready.promise; return host.assembled;
    } });
  await started.promise;
  controller.abort(); ready.resolve();
  await assert.rejects(pending, (error) => error.code === 'REQUEST_CANCELLED');
  assert.equal(closes, 1);
});

test('an unavailable gateway or incomplete application closes the acquired runtime instead of fabricating identity', async () => {
  for (const missing of ['gateway', 'application']) {
    let closes = 0;
    await assert.rejects(createBotRuntimeSession({ dataDir: `/fixture/missing-${missing}`, startHost: async ({ dataDir }) => {
      if (missing === 'gateway') return { host: { apiHost: { close: async () => { closes++; } }, application: { miniAppAgent: {} } } };
      const host = fixture(dataDir, { onClose: async () => { closes++; } });
      delete host.assembled.host.application.miniAppAgent;
      return host.assembled;
    } }), (error) => error.code === 'runtime_agent_registration_unavailable');
    assert.equal(closes, 1);
  }
});

test('duplicate or mismatched gateway construction fails closed', async () => {
  for (const mode of ['duplicate', 'mismatched']) {
    await assert.rejects(createBotRuntimeSession({ dataDir: '/fixture/invalid', startHost: async ({ dataDir }) => {
      if (mode === 'mismatched') return fixture('/fixture/wrong-root').assembled;
      fixture(dataDir); return fixture(dataDir).assembled;
    } }), (error) => error.code === (mode === 'duplicate' ? 'runtime_gateway_ambiguous' : 'runtime_gateway_scope_mismatch'));
  }
});

test('shutdown failures remain observable and cannot leave the Bot arena enabled', async () => {
  let closes = 0;
  const expected = new Error('fixture shutdown failure');
  const session = await createBotRuntimeSession({ dataDir: '/fixture/close-failed', startHost: async ({ dataDir }) =>
    fixture(dataDir, { onClose: async () => { closes++; throw expected; } }).assembled });
  const closing = session.close();
  assert.equal(session.close(), closing);
  await assert.rejects(closing, (error) => error === expected);
  await assert.rejects(session.agent.run({ text: 'closed' }), (error) => error.code === 'AGENT_UNAVAILABLE');
  assert.equal(closes, 1);
});

test('aborting an acquired session revokes it and a later session cannot revive the retained port', async () => {
  const controller = new AbortController();
  let closes = 0;
  const startHost = async ({ dataDir }) => fixture(dataDir, { onClose: async () => { closes++; } }).assembled;
  const old = await createBotRuntimeSession({ dataDir: '/fixture/restart', signal: controller.signal, startHost });
  await old.agent.models();
  controller.abort(); await old.close();
  const fresh = await createBotRuntimeSession({ dataDir: '/fixture/restart', startHost });
  try {
    assert.equal((await fresh.agent.models()).models.length, 1);
    await assert.rejects(old.agent.models(), (error) => error.code === 'AGENT_UNAVAILABLE');
  } finally { await fresh.close(); }
  assert.equal(closes, 2);
});
