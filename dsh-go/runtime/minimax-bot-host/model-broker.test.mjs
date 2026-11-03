// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { execFile } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { promisify } from 'node:util';
import { request as httpRequest } from 'node:http';
import { BrokerError, createModelBrokerClient, startModelProxy } from './model-broker.mjs';
import { buildRuntimeEnv, createRuntimeDescriptor, writeRuntimeDescriptor } from './runtime-descriptor.mjs';
import { startMockUpstream, startNativeBrokerDouble } from './native-broker-double.helper.mjs';

const run = promisify(execFile);
const KEY = 'sk-provider-key-value-9f2c';
const PIN = '97818b2890b4acae1c6bd1200a226ff506680628';

function clientBody(text, { framed = true, close = true } = {}) {
  let cancelled = false;
  const body = new ReadableStream({
    start(controller) { controller.enqueue(new TextEncoder().encode(text)); if (close) controller.close(); },
    cancel() { cancelled = true; },
  });
  return {
    client: createModelBrokerClient({ baseUrl: 'http://127.0.0.1:12345', token: 'test-session',
      fetchImpl: async () => new Response(body, { headers: {
        'content-type': 'text/event-stream', ...(framed ? { 'x-mbot-broker-protocol': '1' } : {}),
      } }),
    }),
    cancelled: () => cancelled,
  };
}

test('framed streams require DONE and release the reader even when the server stays open', async () => {
  const partial = clientBody('data: {"delta":"partial"}\n\n');
  await assert.rejects(partial.client.stream({ providerId: 'p', modelId: 'm', messages: [] }),
    (error) => error instanceof BrokerError && error.code === 'broker_stream_failed');
  const completed = clientBody('data: {"delta":"done"}\n\ndata: [DONE]\n\n', { close: false });
  await completed.client.stream({ providerId: 'p', modelId: 'm', messages: [] });
  assert.equal(completed.cancelled(), true);
  const legacy = clientBody('data: {"delta":"legacy"}\n\n', { framed: false });
  await legacy.client.stream({ providerId: 'p', modelId: 'm', messages: [] });
});

test('cancellation suppresses later events already buffered in the same packet', async () => {
  const s = clientBody('data: {"delta":"first"}\n\ndata: {"delta":"second"}\n\ndata: [DONE]\n\n', { close: false });
  const controller = new AbortController();
  const events = [];
  await assert.rejects(s.client.stream({ providerId: 'p', modelId: 'm', messages: [], signal: controller.signal,
    onEvent(event) { events.push(event); controller.abort(); },
  }), (error) => error instanceof BrokerError && error.code === 'broker_cancelled');
  assert.deepEqual(events, [{ delta: 'first' }]);
  assert.equal(s.cancelled(), true);
});

test('oversized unfinished frames and unrecognized error text are never forwarded', async () => {
  const oversized = clientBody('data: ' + 'x'.repeat(256 * 1024 + 1), { close: false });
  await assert.rejects(oversized.client.stream({ providerId: 'p', modelId: 'm', messages: [] }),
    (error) => error instanceof BrokerError && error.code === 'broker_invalid_response');
  assert.equal(oversized.cancelled(), true);
  const privateText = clientBody('data: {"error":"private_provider_value"}\n\n');
  await assert.rejects(privateText.client.stream({ providerId: 'p', modelId: 'm', messages: [] }),
    (error) => error instanceof BrokerError && error.code === 'broker_unavailable' && !error.message.includes('private_provider_value'));
});

test('proxy applies backpressure and its deadline releases a non-reading runtime connection', { timeout: 5000 }, async (t) => {
  let emitted = 0;
  let markAbort;
  const aborted = new Promise((resolve) => { markAbort = resolve; });
  const proxy = await startModelProxy({ token: 'proxy-test-session', timeoutMs: 100, broker: {
    async stream({ onEvent, signal }) {
      signal.addEventListener('abort', markAbort, { once: true });
      for (let index = 0; index < 10000; index++) {
        emitted++;
        await onEvent({ delta: 'x'.repeat(64 * 1024) });
      }
    },
  } });
  let incoming;
  let request;
  t.after(async () => { incoming?.destroy(); request?.destroy(); await proxy.dispose(); });
  incoming = await new Promise((resolve, reject) => {
    request = httpRequest(proxy.baseUrl + '/v1/model/stream', { method: 'POST', headers: {
      authorization: `Bearer ${proxy.token}`, 'content-type': 'application/json',
    } }, (response) => { response.on('error', () => {}); response.pause(); resolve(response); });
    request.on('error', reject);
    request.end(JSON.stringify({ providerId: 'p', modelId: 'm', messages: [] }));
  });
  await aborted;
  assert.ok(emitted > 0 && emitted < 10000, 'the proxy eagerly buffered the entire provider stream');
});

test('disposing the proxy cancels and drains active streams without waiting for the model deadline', { timeout: 3000 }, async () => {
  let cancelled = false;
  const proxy = await startModelProxy({ token: 'dispose-test-session', broker: {
    async stream({ onEvent, signal }) {
      await onEvent({ delta: 'started' });
      await new Promise((resolve, reject) => {
        signal.addEventListener('abort', () => { cancelled = true; reject(new BrokerError('broker_cancelled')); }, { once: true });
      });
    },
  } });
  const response = await fetch(proxy.baseUrl + '/v1/model/stream', { method: 'POST',
    headers: { authorization: `Bearer ${proxy.token}`, 'content-type': 'application/json' },
    body: JSON.stringify({ providerId: 'p', modelId: 'm', messages: [] }),
  });
  try {
    await proxy.dispose();
    await proxy.dispose();
    assert.equal(cancelled, true);
  } finally { await response.body.cancel().catch(() => {}); await proxy.dispose(); }
});

/** mock provider -> native broker double -> host proxy */
async function stack({ frames = [{ delta: 'a' }, { delta: 'b' }], status = 200 } = {}) {
  const upstream = await startMockUpstream({ frames, status });
  const native = await startNativeBrokerDouble({ key: KEY, upstream });
  const logs = [];
  const broker = createModelBrokerClient({ baseUrl: native.baseUrl, token: native.token });
  const proxy = await startModelProxy({ broker, token: 'd'.repeat(64), logger: (code) => logs.push(code) });
  return { upstream, native, broker, proxy, logs };
}

test('model events stream through the host proxy and never carry the provider key', async () => {
  const { upstream, native, proxy, logs } = await stack({ frames: [{ delta: 'hello' }, { delta: ' world' }] });
  try {
    const client = createModelBrokerClient({ baseUrl: proxy.baseUrl, token: proxy.token });
    const events = [];
    await client.stream({
      providerId: 'provider-1',
      modelId: 'model-1',
      messages: [{ role: 'user', content: 'hi' }],
      onEvent: (event) => events.push(event),
    });
    assert.deepEqual(events, [{ delta: 'hello' }, { delta: ' world' }]);
    // The key was injected natively, upstream.
    assert.equal(upstream.state.requests[0].headers.authorization, `Bearer ${KEY}`);
    assert.ok(!JSON.stringify(events).includes(KEY));
    assert.ok(!JSON.stringify(logs).includes(KEY));
    assert.ok(!JSON.stringify(upstream.state.requests[0].body).includes(KEY));
  } finally {
    await proxy.dispose();
    await native.dispose();
    await upstream.dispose();
  }
});

test('cancelling the stream closes the upstream provider connection', async () => {
  const { upstream, native, proxy } = await stack({ frames: [{ delta: 'a' }, { delta: 'b' }, { delta: 'c' }] });
  try {
    const client = createModelBrokerClient({ baseUrl: proxy.baseUrl, token: proxy.token });
    const controller = new AbortController();
    const events = [];
    await assert.rejects(() => client.stream({
      providerId: 'provider-1',
      modelId: 'model-1',
      messages: [{ role: 'user', content: 'hi' }],
      signal: controller.signal,
      onEvent: (event) => { events.push(event); if (events.length === 1) controller.abort(); },
    }), (error) => error instanceof BrokerError && error.code === 'broker_cancelled');
    // The provider connection is closed, not left dangling.
    await new Promise((done) => setTimeout(done, 100));
    assert.ok(upstream.state.closed + upstream.state.aborted > 0, 'the upstream provider connection was not closed');
    assert.equal(events.length, 1);
  } finally {
    await proxy.dispose();
    await native.dispose();
    await upstream.dispose();
  }
});

test('a rejected provider request surfaces a stable code without the key', async () => {
  const { upstream, native, proxy, logs } = await stack({ status: 401 });
  try {
    const client = createModelBrokerClient({ baseUrl: proxy.baseUrl, token: proxy.token });
    await assert.rejects(
      () => client.stream({ providerId: 'provider-1', modelId: 'model-1', messages: [] }),
      (error) => {
        assert.ok(error instanceof BrokerError);
        assert.ok(!error.message.includes(KEY));
        assert.ok(!JSON.stringify(logs).includes(KEY));
        return true;
      },
    );
  } finally {
    await proxy.dispose();
    await native.dispose();
    await upstream.dispose();
  }
});

test('the proxy refuses a foreign token and a browser origin', async () => {
  const { upstream, native, proxy } = await stack();
  try {
    const wrong = createModelBrokerClient({ baseUrl: proxy.baseUrl, token: 'e'.repeat(64) });
    await assert.rejects(
      () => wrong.stream({ providerId: 'p', modelId: 'm', messages: [] }),
      (error) => error instanceof BrokerError && error.code === 'broker_rejected',
    );
    const crossOrigin = await fetch(`${proxy.baseUrl}/v1/model/stream`, {
      method: 'POST',
      headers: { authorization: `Bearer ${proxy.token}`, origin: 'https://example.com', 'content-type': 'application/json' },
      body: JSON.stringify({ providerId: 'p', modelId: 'm', messages: [] }),
    });
    assert.equal(crossOrigin.status, 403);
    // Fetch overwrites Host; raw HTTP is needed to exercise a foreign Host.
    const foreignHost = await new Promise((resolve, reject) => {
      const call = httpRequest(`${proxy.baseUrl}/v1/model/stream`, {
        method: 'POST', headers: { authorization: `Bearer ${proxy.token}`, host: 'elsewhere.invalid', 'content-type': 'application/json' },
      }, (response) => { response.resume(); resolve(response.statusCode); });
      call.on('error', reject);
      call.end(JSON.stringify({ providerId: 'p', modelId: 'm', messages: [] }));
    });
    assert.equal(foreignHost, 403);
  } finally {
    await proxy.dispose();
    await native.dispose();
    await upstream.dispose();
  }
});

test('the runtime child process gets no key, no token and no descriptor on the command line', async () => {
  const { upstream, native, proxy } = await stack({ frames: [{ delta: 'ok' }] });
  const priv = mkdtempSync(join(tmpdir(), 'mbot-runtime-'));
  try {
    const descriptor = createRuntimeDescriptor({
      entryPath: join(priv, 'runtime', 'entry.mjs'),
      pinCommit: PIN,
      port: proxy.port,
      token: proxy.token,
    });
    const path = writeRuntimeDescriptor({ dir: priv, descriptor, allowedRoots: [priv] });
    const child = `
import { readFileSync } from 'node:fs';
const descriptor = JSON.parse(readFileSync(process.argv[1], 'utf8'));
const response = await fetch('http://127.0.0.1:' + descriptor.port + '/v1/model/stream', {
  method: 'POST',
  headers: { authorization: 'Bearer ' + descriptor.token, 'content-type': 'application/json' },
  body: JSON.stringify({ providerId: 'provider-1', modelId: 'model-1', messages: [{ role: 'user', content: 'hi' }] }),
});
const body = await response.text();
console.log(JSON.stringify({ body, env: process.env, argv: process.argv }));
`;
    const env = buildRuntimeEnv({ baseEnv: process.env, extra: { MINIMAX_API_KEY: KEY, NODE_EXTRA: 'ok' } });
    assert.ok(!JSON.stringify(env).includes(KEY), 'the host put the provider key into the runtime environment');
    const { stdout } = await run(process.execPath, ['--input-type=module', '-e', child, path], { env });
    const report = JSON.parse(stdout.trim().split('\n').pop());
    assert.ok(report.body.includes('ok'), 'the runtime did not receive the streamed answer');
    assert.ok(!stdout.includes(KEY), 'the provider key reached the runtime output');
    assert.ok(!JSON.stringify(report.env).includes(KEY), 'the provider key reached the runtime environment');
    assert.ok(!JSON.stringify(report.env).includes(proxy.token), 'the session token reached the runtime environment');
    assert.ok(!report.argv.includes(proxy.token), 'the session token reached the command line');
    assert.ok(!report.argv.includes(KEY), 'the provider key reached the command line');
    // The key is still injected natively, upstream.
    assert.equal(upstream.state.requests[0].headers.authorization, `Bearer ${KEY}`);
  } finally {
    await proxy.dispose();
    await native.dispose();
    await upstream.dispose();
  }
});
